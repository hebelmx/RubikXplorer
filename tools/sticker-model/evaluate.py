"""Held-out evaluation of the shadow sticker reader against the Kotlin reads (Story 10.23). No bar; routed to the PO.

Usage:
    uv run --project tools/sticker-model python tools/sticker-model/evaluate.py [--groups FILE] [--name NAME] [--skip-curve]

For every held-out face (the groups in `holdout.json`, or in --groups: a JSON list of group names or an object with a
`heldout` list), per device x cube x light: stickers correct, uncertain and confidently wrong for the model and for the
Kotlin `self`, `second` and gated reads (`docs/evidence/10-23/kotlin-reads.json`, from
`./gradlew :core:domain:exportKotlinStickerReads`); model-versus-Kotlin agreement; the Red/Orange and White/Yellow
confusions; and a learning curve (retrained on 25/50/75% of the real training groups, seeded subsets, synthetic held
constant; 100% is the exported model). Writes `docs/evidence/10-23/<name>.{md,json}` (default `evaluation`).
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
from collections import defaultdict
from pathlib import Path

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import numpy as np

import train_sticker_model as trainer
from sticker_common import (
    ARTIFACTS,
    COLOR_INDEX,
    COLOR_ORDER,
    EVIDENCE,
    HOLDOUT_FILE,
    MODEL_CARD,
    MODEL_OUTPUT,
    dump_json,
)

READERS = ("model", "self", "second", "gated")
CURVE = (0.25, 0.5, 0.75, 1.0)
PAIRS = {"redOrange": ("RED", "ORANGE"), "whiteYellow": ("WHITE", "YELLOW")}


def load_groups(path: Path) -> list[str]:
    data = json.loads(path.read_text())
    groups = data["heldout"] if isinstance(data, dict) else data
    return sorted(groups)


def model_readings(probs: np.ndarray, tau: float) -> list[list[dict]]:
    out = []
    for face in probs:
        out.append([{"color": COLOR_ORDER[int(cell.argmax())], "confidence": "CONFIDENT" if float(cell.max()) >= tau else "UNCERTAIN"} for cell in face])
    return out


def empty_tally() -> dict:
    return {"stickers": 0, "correct": 0, "uncertain": 0, "confidentlyWrong": 0, "redOrange": {"confident": 0, "all": 0}, "whiteYellow": {"confident": 0, "all": 0}}


def tally(target: dict, truth: list[str], readings: list[dict | None]) -> None:
    for expected, reading in zip(truth, readings):
        target["stickers"] += 1
        confident = reading is not None and reading["confidence"] == "CONFIDENT"
        if not confident:
            target["uncertain"] += 1
        elif reading["color"] == expected:
            target["correct"] += 1
        else:
            target["confidentlyWrong"] += 1
        if reading is not None:
            for key, (a, b) in PAIRS.items():
                if {expected, reading["color"]} == {a, b}:
                    target[key]["all"] += 1
                    if confident:
                        target[key]["confident"] += 1


def agreement(model: list[dict], kotlin: list[dict | None], truth: list[str], target: dict) -> None:
    for m, k, expected in zip(model, kotlin, truth):
        mc = m["confidence"] == "CONFIDENT"
        kc = k is not None and k["confidence"] == "CONFIDENT"
        if mc and kc:
            key = "bothConfidentSame" if m["color"] == k["color"] else "bothConfidentDiffer"
        elif mc:
            key = "onlyModelConfident"
            if m["color"] == expected:
                target["modelRightWhereKotlinUncertain"] += 1
        elif kc:
            key = "onlyKotlinConfident"
            if k["color"] == expected:
                target["kotlinRightWhereModelUncertain"] += 1
        else:
            key = "neitherConfident"
        target[key] += 1


def empty_agreement() -> dict:
    return {
        "bothConfidentSame": 0,
        "bothConfidentDiffer": 0,
        "onlyModelConfident": 0,
        "onlyKotlinConfident": 0,
        "neitherConfident": 0,
        "modelRightWhereKotlinUncertain": 0,
        "kotlinRightWhereModelUncertain": 0,
    }


def score(records: list[dict], model: dict[str, list[dict]], kotlin: dict[str, dict]) -> dict:
    cells: dict[str, dict] = defaultdict(lambda: {"faces": 0, "groups": set(), **{r: empty_tally() for r in READERS}, "agreementGated": empty_agreement(), "agreementSelf": empty_agreement(), "agreementSecond": empty_agreement(), "kotlinFaces": 0})
    for record in records:
        key = f"{record['device']} / {record['cube']} / {record['light']}"
        for name in (key, "ALL held-out"):
            cell = cells[name]
            cell["faces"] += 1
            cell["groups"].add(record["group"])
            tally(cell["model"], record["labels"], model[record["item"]])
            reads = kotlin.get(record["item"])
            if reads is not None:
                cell["kotlinFaces"] += 1
                for reader in ("self", "second", "gated"):
                    tally(cell[reader], record["labels"], reads[reader])
                agreement(model[record["item"]], reads["gated"], record["labels"], cell["agreementGated"])
                agreement(model[record["item"]], reads["self"], record["labels"], cell["agreementSelf"])
                agreement(model[record["item"]], reads["second"], record["labels"], cell["agreementSecond"])
    return {name: {**cell, "groups": sorted(cell["groups"])} for name, cell in sorted(cells.items(), key=lambda kv: (kv[0] == "ALL held-out", kv[0]))}


def learning_curve(manifest: dict, card: dict, records: list[dict], images: np.ndarray, exported_probs: np.ndarray, epochs: int | None) -> list[dict]:
    recipe = card["recipe"]
    train_groups = sorted({r["group"] for r in manifest["items"] if r["side"] == "train"})
    order = [train_groups[i] for i in np.random.default_rng(card["seed"] + 10).permutation(len(train_groups))]
    tune_x, tune_y, _ = trainer.real_faces(manifest, "tune")
    labels = np.array([[COLOR_INDEX[c] for c in r["labels"]] for r in records])
    points = []
    for fraction in CURVE:
        count = max(1, math.ceil(fraction * len(order)))
        subset = order[:count]
        if fraction == 1.0:
            probs, tau, source = exported_probs, card["tau"], "exported model"
        else:
            x, y, info = trainer.training_set(manifest, card["seed"], recipe["synthetic"], recipe["realCopies"], set(subset))
            model = trainer.fit(x, y, card["seed"], epochs or recipe["epochs"], recipe["batchSize"], recipe["learningRate"], verbose=0)
            model_bytes = trainer.export_bytes(model, card["quant"])
            tau, _ = trainer.choose_tau(trainer.tflite_probs(model_bytes, tune_x), tune_y)
            probs, source = trainer.tflite_probs(model_bytes, images), "retrained"
        top = probs.argmax(axis=-1)
        confident = probs.max(axis=-1) >= tau
        realFaces = sum(1 for r in manifest["items"] if r["side"] == "train" and r["group"] in subset)
        points.append(
            {
                "fraction": fraction,
                "groups": count,
                "realFaces": realFaces,
                "tau": tau,
                "source": source,
                "stickers": int(labels.size),
                "correct": int((confident & (top == labels)).sum()),
                "uncertain": int((~confident).sum()),
                "confidentlyWrong": int((confident & (top != labels)).sum()),
                "top1Correct": int((top == labels).sum()),
            }
        )
        print(f"curve {fraction:.0%}: {points[-1]}")
    return points


def pct(n: int, d: int) -> str:
    return f"{n} ({100.0 * n / d:.1f}%)" if d else "-"


def headline(result: dict) -> list[str]:
    overall = result["cells"].get("ALL held-out")
    if not overall:
        return ["No held-out faces."]
    rows = []
    for reader in READERS:
        t = overall[reader]
        if t["stickers"]:
            rows.append(
                f"- `{reader}`: {pct(t['correct'], t['stickers'])} correct, {pct(t['uncertain'], t['stickers'])} uncertain, "
                f"{pct(t['confidentlyWrong'], t['stickers'])} confidently wrong."
            )
    agreement_gated = overall["agreementGated"]
    if overall["kotlinFaces"]:
        rows.append(
            f"- Where the gated Kotlin read left a sticker uncertain, the model was confident and right on "
            f"{agreement_gated['modelRightWhereKotlinUncertain']}; both were confident and differed on "
            f"{agreement_gated['bothConfidentDiffer']}."
        )
    return rows


def render(result: dict) -> str:
    card = result["model"]
    lines = [
        f"# Story 10.23: shadow sticker reader evaluation ({result['name']})",
        "",
        "**No bar; routed to the PO.** Nothing here gates a build or a release: the report is evidence for the PO's later",
        "decision on a further mode (second read or rescue), which needs a further SCP. Generated by",
        "`tools/sticker-model/evaluate.py` (re-run it; do not edit by hand).",
        "",
        f"- **Model:** `sticker_reader.tflite` SHA-256 `{card['sha256']}` ({card['bytes']:,} bytes), tau = {card['tau']} (CONFIDENT when the top-1 probability >= tau).",
        f"- **Groups:** {', '.join(f'`{g}`' for g in result['groups'])}.",
        f"- **Faces:** {result['faces']} held-out faces ({result['faces'] * 9} stickers); Kotlin reads for {result['kotlinFaces']} of them (`kotlin-reads.json`).",
        "- **Readers:** `model` = the shadow reader on the face image only; `self` = Kotlin self-calibration read; `second` =",
        "  Kotlin per-face correction against the record's calibration (`cal`); `gated` = the agreement gate of the two, what",
        "  Quick Scan decides with. *Correct* and *confidently wrong* count CONFIDENT stickers; *uncertain* counts UNCERTAIN or unread ones.",
        "- **Caveats:** held-out cells hold about 6 faces (54 stickers) each, so every number is indicative. Corner crops are",
        "  unwarped in Python from the saved 200x200 crop, while the Kotlin Lab values were measured on the device's analysis",
        "  frame: the pixels the two readers saw differ. The backlit held-out groups are Lenovo-only; the S24 FE held-out groups",
        "  are studio front light only. No S24 FE corner capture, shaded or backlit S24 FE face or torch-on capture exists (H1). No",
        "  group is labelled glossy, so the report cannot speak to glossy faces.",
        "",
        "## Headline (all held-out stickers)",
        "",
        *headline(result),
        "",
        "## Per device x cube x light",
        "",
        "| Cell | Faces | Reader | Correct | Uncertain | Confidently wrong | R<->O (confident / all) | W<->Y (confident / all) |",
        "| --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for name, cell in result["cells"].items():
        for reader in READERS:
            t = cell[reader]
            if t["stickers"] == 0:
                lines.append(f"| {name} | {cell['faces']} | {reader} | n/a | n/a | n/a | n/a | n/a |")
                continue
            lines.append(
                f"| {name} | {cell['faces']} | {reader} | {pct(t['correct'], t['stickers'])} | {pct(t['uncertain'], t['stickers'])} | "
                f"{pct(t['confidentlyWrong'], t['stickers'])} | {t['redOrange']['confident']} / {t['redOrange']['all']} | "
                f"{t['whiteYellow']['confident']} / {t['whiteYellow']['all']} |"
            )
    lines += [
        "",
        "## Model versus Kotlin (stickers)",
        "",
        "| Cell | Against | Both confident, same | Both confident, differ | Only model confident | Only Kotlin confident | Neither | Model right where Kotlin uncertain | Kotlin right where model uncertain |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for name, cell in result["cells"].items():
        for label, key in (("gated", "agreementGated"), ("self", "agreementSelf"), ("second", "agreementSecond")):
            a = cell[key]
            if cell["kotlinFaces"] == 0:
                continue
            lines.append(
                f"| {name} | {label} | {a['bothConfidentSame']} | {a['bothConfidentDiffer']} | {a['onlyModelConfident']} | "
                f"{a['onlyKotlinConfident']} | {a['neitherConfident']} | {a['modelRightWhereKotlinUncertain']} | {a['kotlinRightWhereModelUncertain']} |"
            )
    lines += ["", "## Learning curve (all held-out faces; synthetic training faces held constant)", ""]
    if result["learningCurve"]:
        lines += [
            "| Real training groups | Real faces | tau | Correct | Uncertain | Confidently wrong | Top-1 right | Model |",
            "| --- | --- | --- | --- | --- | --- | --- | --- |",
        ]
        for p in result["learningCurve"]:
            lines.append(
                f"| {p['fraction']:.0%} ({p['groups']}) | {p['realFaces']} | {p['tau']} | {pct(p['correct'], p['stickers'])} | "
                f"{pct(p['uncertain'], p['stickers'])} | {pct(p['confidentlyWrong'], p['stickers'])} | {pct(p['top1Correct'], p['stickers'])} | {p['source']} |"
            )
        lines += [
            "",
            "Subsets are seeded (`seed + 10` permutation of the training groups, the first 25/50/75% taken), each retrained and",
            "picking its own tau on the tune split. The 100% point is not a retrain: it is the exported, shipped model. With this",
            "little real data the curve shows the trend only.",
        ]
    else:
        lines.append("Not run (--skip-curve).")
    lines += [
        "",
        "## Reading this report",
        "",
        "- *Confidently wrong* is the safety number: a kept face must never carry one."
        + (
            " tau already sits at the 0.99 ceiling of its grid (no grid value reached 0 confidently wrong on the tune split, so the"
            " spec's fallback applies); the rule cannot raise it further, so any non-zero model count here means the model is not"
            " ready for any mode beyond shadow."
            if card.get("tauCeiling")
            else " A non-zero model count means tau would need to rise, or the model is not ready for any mode beyond shadow."
        ),
        "- *Model right where Kotlin uncertain* is the potential of a later rescue mode; *both confident, differ* is where a",
        "  second-read mode would turn a CONFIDENT Kotlin sticker UNCERTAIN (or catch a Kotlin error).",
        "- The on-device comparison (H2) and Story 10.20's held-out groups (H3) are in `human-gate.md`.",
        "",
    ]
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--groups", type=Path, default=HOLDOUT_FILE)
    parser.add_argument("--name", default="evaluation")
    parser.add_argument("--kotlin-reads", type=Path, default=EVIDENCE / "kotlin-reads.json")
    parser.add_argument("--model", type=Path, default=MODEL_OUTPUT)
    parser.add_argument("--card", type=Path, default=MODEL_CARD)
    parser.add_argument("--skip-curve", action="store_true")
    parser.add_argument("--curve-epochs", type=int)
    parser.add_argument("--reuse-curve", action="store_true", help="keep the learning curve of an earlier <name>.json for the same model")
    args = parser.parse_args()

    manifest = trainer.load_manifest()
    card = json.loads(args.card.read_text())
    groups = load_groups(args.groups)
    holdout = json.loads(HOLDOUT_FILE.read_text())
    seen = set(card["recipe"]["realGroups"]) | set(holdout["tune"]) | {r["group"] for r in manifest["items"] if r["side"] in ("train", "tune")}
    used = sorted(set(groups) & seen)
    if used:
        raise SystemExit(f"Refusing to evaluate on groups the model trained on or tau was tuned on: {used}")
    records = [r for r in manifest["items"] if r["group"] in groups]
    missing = sorted(set(groups) - {r["group"] for r in records})
    if missing:
        raise SystemExit(f"No assembled faces for {missing}: run assemble.py (and add the groups to holdout.json first)")
    model_bytes = args.model.read_bytes()
    if trainer.sha256_bytes(model_bytes) != card["sha256"]:
        raise SystemExit(f"{args.model} does not match model-card.json")
    images = np.stack([np.asarray(trainer.Image.open(ARTIFACTS / r["crop"]).convert("RGB"), dtype=np.uint8) for r in records])
    probs = trainer.tflite_probs(model_bytes, images)
    model = dict(zip((r["item"] for r in records), model_readings(probs, card["tau"])))
    if not args.kotlin_reads.is_file():
        raise SystemExit(f"{args.kotlin_reads} is missing: run ./gradlew :core:domain:exportKotlinStickerReads")
    kotlin = {item["item"]: item for item in json.loads(args.kotlin_reads.read_text())["items"]}
    without = sorted(g for g in groups if not any(item.startswith(g + "/") for item in kotlin))
    if without:
        raise SystemExit(f"No Kotlin reads for {without} in {args.kotlin_reads}: add them to holdout.json and re-run exportKotlinStickerReads")
    previous = EVIDENCE / f"{args.name}.json"
    if args.reuse_curve and previous.is_file() and json.loads(previous.read_text())["model"]["sha256"] == card["sha256"]:
        curve = json.loads(previous.read_text())["learningCurve"]
    else:
        curve = [] if args.skip_curve else learning_curve(manifest, card, records, images, probs, args.curve_epochs)
    result = {
        "schemaVersion": 1,
        "name": args.name,
        "bar": "none; routed to the PO",
        "groups": groups,
        "faces": len(records),
        "kotlinFaces": sum(1 for r in records if r["item"] in kotlin),
        "model": {**{k: card[k] for k in ("sha256", "bytes", "tau", "seed", "quant")}, "tauCeiling": not card["tauRule"]["zeroConfidentlyWrongReached"]},
        "cells": score(records, model, kotlin),
        "learningCurve": curve,
        "faceReads": [
            {"item": r["item"], "truth": r["labels"], "model": model[r["item"]], **({k: kotlin[r["item"]][k] for k in ("self", "second", "gated")} if r["item"] in kotlin else {})}
            for r in records
        ],
    }
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    (EVIDENCE / f"{args.name}.json").write_text(dump_json(result), encoding="utf-8")
    (EVIDENCE / f"{args.name}.md").write_text(render(result), encoding="utf-8")
    overall = result["cells"]["ALL held-out"]
    print({reader: {k: overall[reader][k] for k in ("correct", "uncertain", "confidentlyWrong")} for reader in READERS})
    return 0


if __name__ == "__main__":
    sys.exit(main())
