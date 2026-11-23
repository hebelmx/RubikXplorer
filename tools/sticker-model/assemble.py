"""Seeded, reproducible assembly of the Story 10.23 sticker-reader dataset into `tools/sticker-model/artifacts/`.

Usage:
    uv run --project tools/sticker-model python tools/sticker-model/assemble.py [--out DIR] [--no-evidence]

Reads every usable labelled source (Fixtures/live A2 frames, Fixtures/corner holds, the S4 photos of
Fixtures/labeled/photo-evaluation.json), writes one upright 96x96 RGB PNG per face in Kotlin cell order, checks each
face's alignment against the JSON's measured stickers, and writes a deterministic `manifest.json` plus the inventory
(`inventory.json`, and `docs/evidence/10-23/inventory.md` unless --no-evidence). It then builds `split.json` from
`holdout.json` and refuses (exit 1) when any split check fails. Nothing random is involved; the seed is recorded so
the synthetic side (generated at training time) is tied to the same manifest.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import numpy as np
from PIL import Image

import split as split_tool
from sticker_common import (
    ARTIFACTS,
    CORNER_CELL_ROI,
    CORNER_CROP_RADIUS_MULTIPLIER,
    CORNER_MODEL,
    CORNER_MODEL_SIZE,
    CORNER_PRESENCE,
    DEFAULT_SEED,
    EVIDENCE,
    GRID_FRACTION,
    GUIDED_CELL_ROI,
    HOLDOUT_FILE,
    INPUT_SIZE,
    REPO_ROOT,
    SPLIT_FILE,
    cell_means,
    delta_e,
    dump_json,
    guide_quads,
    load_rgb,
    rect_quad,
    rgb_to_lab,
    sha256_bytes,
    sha256_file,
    to_uint8,
    unwarp,
    valid_cube_geometry,
)

LIVE = REPO_ROOT / "Fixtures/live"
CORNER = REPO_ROOT / "Fixtures/corner"
PHOTOS = REPO_ROOT / "Fixtures/labeled/photo-evaluation.json"
FACES = ("U", "R", "F", "D", "L", "B")

# Alignment gate (per face): the crop's cell means against the JSON's measured stickers, CIELAB dE76. The pixels differ
# (JPEG re-encode, a Python unwarp of a downscaled crop against the device's analysis frame), so the gate catches a
# misplaced grid or quad, not small colour shifts: a face is excluded when its median cell dE exceeds MEDIAN_LIMIT or any
# non-centre cell exceeds MAX_LIMIT (a logo centre is measured around the logo, so it is not held to the max).
MEDIAN_LIMIT = 15.0
MAX_LIMIT = 30.0

# Truth that is known bad (docs/scan-accuracy.md): sm-s721u1_stkb_studio-a's truth is not a valid cube (10 WHITE, 8 RED).
EXCLUDED_GROUPS = {"live/sm-s721u1_stkb_studio-a": "truth is not a valid cube (docs/scan-accuracy.md)"}

DEVICES = {
    "lenovo-tb128xu": "Lenovo TB128XU",
    "sm-s721u1": "Galaxy S24 FE",
    "GalaxyS24FE": "Galaxy S24 FE",
    "GalaxyTab": "Galaxy Tab SM-T738U",
}
LIGHTS = {
    "studio": "studio (front)",
    "studio-a": "studio (front)",
    "warm": "warm (front)",
    "cool-front": "cool (front)",
    "cool": "cool (backlit)",
}
CUBES = {"RubikStickered": "rubik-stickered", "GANStickerless": "gan-stickerless"}


def stickered(cube: str) -> bool:
    return cube.startswith("stk") or cube == "rubik-stickered"


def _alignment(crop: np.ndarray, stickers: list[dict] | None, roi: float) -> dict | None:
    """Median dE over all nine cells; max dE over the eight non-centre cells (a logo centre is measured around the logo)."""
    if stickers is None:
        return None
    measured = rgb_to_lab(np.stack(cell_means(crop, roi)))
    errors = [delta_e(measured[i], (s["l"], s["a"], s["b"])) for i, s in enumerate(stickers)]
    edge = [e for i, e in enumerate(errors) if i != 4]
    return {"medianDeltaE": round(float(np.median(errors)), 2), "maxDeltaE": round(float(max(edge)), 2)}


def _aligned(alignment: dict | None) -> str | None:
    """Exclusion reason, or None when aligned (or when the source has no measured stickers to check against)."""
    if alignment is None:
        return None
    if alignment["medianDeltaE"] > MEDIAN_LIMIT:
        return f"misaligned: median cell dE {alignment['medianDeltaE']} > {MEDIAN_LIMIT}"
    if alignment["maxDeltaE"] > MAX_LIMIT:
        return f"misaligned: max cell dE {alignment['maxDeltaE']} > {MAX_LIMIT}"
    return None


class CornerModel:
    """`corner_keypoints.tflite` on the saved 200x200 corner crop, sampled like `CornerModelTransform.fillRgbInput`."""

    def __init__(self, path: Path = CORNER_MODEL) -> None:
        import tensorflow as tf

        self.interpreter = tf.lite.Interpreter(model_path=str(path), num_threads=1)
        self.interpreter.allocate_tensors()
        self.input = self.interpreter.get_input_details()[0]["index"]
        self.output = self.interpreter.get_output_details()[0]["index"]

    def locate(self, image: np.ndarray) -> tuple[list[np.ndarray] | None, str]:
        height, width = image.shape[:2]
        # The recorder saves the upright centred square of the analysis crop; the guide is the centred 80% square.
        side = min(width, height)
        center = np.array([width / 2.0, height / 2.0])
        radius = side * GRID_FRACTION / 2.0
        half = radius * CORNER_CROP_RADIUS_MULTIPLIER
        steps = (np.arange(CORNER_MODEL_SIZE) + 0.5) / CORNER_MODEL_SIZE
        xs = np.clip(np.rint(center[0] + (steps - 0.5) * 2 * half), 0, width - 1).astype(np.int64)
        ys = np.clip(np.rint(center[1] + (steps - 0.5) * 2 * half), 0, height - 1).astype(np.int64)
        crop = image[ys][:, xs] / 255.0
        self.interpreter.set_tensor(self.input, crop[None].astype(np.float32))
        self.interpreter.invoke()
        out = self.interpreter.get_tensor(self.output)[0].astype(np.float64)
        if not np.all(np.isfinite(out)) or out[0] < CORNER_PRESENCE:
            return None, f"corner model: presence {out[0]:.3f} < {CORNER_PRESENCE}"
        uv = out[1:].reshape(7, 2)
        points = center + (uv - 0.5) * 2 * half
        if not valid_cube_geometry(points, center, radius):
            return None, "corner model: keypoints fail the cube geometry guard"
        return guide_quads(points), ""


def _write_crop(out: Path, item: str, crop: np.ndarray) -> tuple[str, str]:
    rel = Path("crops") / (item.replace("/", "__") + ".png")
    path = out / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(to_uint8(crop)).save(path, format="PNG", optimize=False)
    return rel.as_posix(), sha256_file(path)


def _record(item, group, source, labels, meta, crop_rel, crop_sha, alignment, extra=None) -> dict:
    record = {
        "item": item,
        "group": group,
        "source": source.relative_to(REPO_ROOT).as_posix(),
        "sha256": sha256_file(source),
        "labels": labels,
        "crop": crop_rel,
        "cropSha256": crop_sha,
        "alignment": alignment,
        **meta,
    }
    if extra:
        record.update(extra)
    return record


def assemble_live(out: Path, items: list, excluded: list, sources: list) -> None:
    for directory in sorted(p for p in LIVE.iterdir() if p.is_dir()):
        group = f"live/{directory.name}"
        device_id, cube, light = directory.name.split("_", 2)
        meta = {
            "device": DEVICES[device_id],
            "cube": cube,
            "light": LIGHTS.get(light, light),
            "stickered": stickered(cube),
            "kind": "real",
            "pipeline": "live-a2" if light.endswith("-a") else "live-a",
        }
        records = {face: json.loads((directory / f"{face}.json").read_text()) for face in FACES if (directory / f"{face}.json").is_file()}
        framed = [face for face, r in records.items() if r.get("frame")]
        sources.append({"group": group, **meta, "faces": len(records), "imageFaces": len(framed), "schema": sorted({r["schemaVersion"] for r in records.values()})})
        for face in FACES:
            record = records.get(face)
            if record is None or not record.get("frame"):
                continue
            item = f"{group}/{face}"
            source = directory / record["frame"]
            if group in EXCLUDED_GROUPS:
                excluded.append({"item": item, "source": source.relative_to(REPO_ROOT).as_posix(), "reason": EXCLUDED_GROUPS[group]})
                continue
            sample = record["labeledSample"]["sample"]
            if sample.get("orientationDegrees", 0) != 0:
                excluded.append({"item": item, "source": source.relative_to(REPO_ROOT).as_posix(), "reason": "non-zero orientation"})
                continue
            image = load_rgb(source)
            height, width = image.shape[:2]
            side = min(width, height) * GRID_FRACTION
            left, top = (width - side) / 2.0, (height - side) / 2.0
            crop = unwarp(image, rect_quad(left, top, left + side, top + side))
            alignment = _alignment(crop, sample["stickers"], GUIDED_CELL_ROI)
            reason = _aligned(alignment)
            if reason:
                excluded.append({"item": item, "source": source.relative_to(REPO_ROOT).as_posix(), "reason": reason, "alignment": alignment})
                continue
            crop_rel, crop_sha = _write_crop(out, item, crop)
            items.append(_record(item, group, source, record["labeledSample"]["truth"], meta, crop_rel, crop_sha, alignment, {"face": face}))


def assemble_corner(out: Path, items: list, excluded: list, sources: list, detector: CornerModel) -> None:
    for directory in sorted(p for p in CORNER.iterdir() if p.is_dir()):
        group = f"corner/{directory.name}"
        device_id, cube, light = directory.name.split("_", 2)
        meta = {
            "device": DEVICES[device_id],
            "cube": cube,
            "light": LIGHTS.get(light, light),
            "stickered": stickered(cube),
            "kind": "real",
            "pipeline": "corner-pre-10.19",
        }
        holds = sorted(directory.glob("corner-*.json"))
        faces = 0
        for path in holds:
            record = json.loads(path.read_text())
            faces += len(record["labeledSamples"])
            source = directory / record["frame"]
            image = load_rgb(source)
            quads, why = detector.locate(image)
            for index, labeled in enumerate(record["labeledSamples"]):
                face = labeled["sample"]["face"]
                item = f"{group}/{path.stem}/{face}"
                if quads is None:
                    excluded.append({"item": item, "source": source.relative_to(REPO_ROOT).as_posix(), "reason": why})
                    continue
                crop = unwarp(image, quads[index])
                if crop is None:
                    excluded.append({"item": item, "source": source.relative_to(REPO_ROOT).as_posix(), "reason": "degenerate quad"})
                    continue
                alignment = _alignment(crop, labeled["sample"]["stickers"], CORNER_CELL_ROI)
                reason = _aligned(alignment)
                if reason:
                    excluded.append({"item": item, "source": source.relative_to(REPO_ROOT).as_posix(), "reason": reason, "alignment": alignment})
                    continue
                crop_rel, crop_sha = _write_crop(out, item, crop)
                extra = {"face": face, "hold": record["hold"], "faceIndex": index}
                items.append(_record(item, group, source, labeled["truth"], meta, crop_rel, crop_sha, alignment, extra))
        sources.append({"group": group, **meta, "faces": faces, "imageFaces": faces, "schema": [3]})


def assemble_photos(out: Path, items: list, excluded: list, sources: list) -> None:
    manifest = json.loads(PHOTOS.read_text())
    for entry in manifest["groups"]:
        cube_key, device_key = entry["cubeDevice"].split("_", 1)
        group = f"photo/{entry['cubeDevice']}_{entry['scoredState']}"
        cube = CUBES[cube_key]
        meta = {
            "device": DEVICES[device_key],
            "cube": cube,
            "light": "room (unmetered)",
            "stickered": stickered(cube),
            "kind": "real",
            "pipeline": "photo-s4",
        }
        sources.append({"group": group, **meta, "faces": len(entry["samples"]), "imageFaces": len(entry["samples"]), "schema": [manifest["schemaVersion"]]})
        for sample in entry["samples"]:
            source = REPO_ROOT / sample["image"]
            item = f"{group}/{source.stem}"
            image = load_rgb(source)
            height, width = image.shape[:2]
            left, top, right, bottom = sample["rect"]
            crop = unwarp(image, rect_quad(left * width, top * height, right * width, bottom * height))
            # No measured stickers in the photo manifest: the alignment check does not apply (recorded as null).
            crop_rel, crop_sha = _write_crop(out, item, crop)
            items.append(_record(item, group, source, sample["truth"], meta, crop_rel, crop_sha, None))


def lab_only_sources() -> list[dict]:
    """Sources that hold labels or measurements but no usable face image (inventory only)."""
    rows = []
    for directory in sorted(p for p in LIVE.iterdir() if p.is_dir()):
        records = [json.loads(p.read_text()) for p in sorted(directory.glob("*.json"))]
        if records and not any(r.get("frame") for r in records):
            rows.append({"source": f"Fixtures/live/{directory.name}", "holds": "Lab samples + 9-sticker truth (schema 1, session A)", "faces": len(records), "why": "no frame image"})
    rows += [
        {"source": "Fixtures/labeled/face-samples.json", "holds": "one synthetic CIELAB face + truth", "faces": 1, "why": "synthetic contract fixture, no image"},
        {"source": "docs/evidence/quick-scan-2026-11-22/bursts/*.jsonl", "holds": "per-frame corner cell Lab, no truth", "faces": 0, "why": "no labels"},
        {"source": "Fixtures/matrix/*.log", "holds": "diagnostic log lines (test matrix runs)", "faces": 0, "why": "no images"},
        {"source": "Fixtures/*_S1, *_S2 photos", "holds": "unlabelled photos of two cubes", "faces": 0, "why": "no labels"},
        {"source": "Fixtures/*_S3 photos", "holds": "monochrome calibration faces (a centre point, no rect)", "faces": 0, "why": "no face rectangle"},
        {"source": "git 4ced73f6 (deleted)", "holds": "WhatsApp copies of the S1/S2 originals", "faces": 0, "why": "recompressed duplicates, no labels"},
        {"source": "captures/ (local, uncommitted)", "holds": "local recorder pulls", "faces": 0, "why": "not committed"},
    ]
    return rows


def assemble(out: Path, seed: int = DEFAULT_SEED) -> dict:
    out.mkdir(parents=True, exist_ok=True)
    crops = out / "crops"
    if crops.is_dir():
        for stale in crops.glob("*.png"):
            stale.unlink()
    items: list[dict] = []
    excluded: list[dict] = []
    sources: list[dict] = []
    assemble_live(out, items, excluded, sources)
    assemble_corner(out, items, excluded, sources, CornerModel())
    assemble_photos(out, items, excluded, sources)
    items.sort(key=lambda r: r["item"])
    excluded.sort(key=lambda r: r["item"])
    sources.sort(key=lambda r: r["group"])
    holdout = json.loads(HOLDOUT_FILE.read_text())
    sides = split_tool.sides_for(holdout, [s["group"] for s in sources])
    for record in items:
        record["side"] = sides[record["group"]]
    for source in sources:
        source["side"] = sides[source["group"]]
    manifest = {
        "schemaVersion": 1,
        "seed": seed,
        "inputSize": INPUT_SIZE,
        "cellOrder": "row-major (Kotlin FrameReader / CornerReader cell order)",
        "alignment": {"medianLimit": MEDIAN_LIMIT, "maxLimit": MAX_LIMIT},
        "items": items,
        "excluded": excluded,
        "synthetic": {"group": split_tool.SYNTHETIC_GROUP, "side": "train", "generator": "synth_faces.py", "seed": seed},
    }
    (out / "manifest.json").write_text(dump_json(manifest), encoding="utf-8")
    inventory = {"sources": sources, "labOnly": lab_only_sources(), "excluded": excluded}
    (out / "inventory.json").write_text(dump_json(inventory), encoding="utf-8")
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, default=ARTIFACTS)
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument("--no-evidence", action="store_true", help="do not write split.json or docs/evidence/10-23/inventory.md")
    args = parser.parse_args()
    manifest = assemble(args.out, args.seed)
    split = split_tool.build(manifest, json.loads(HOLDOUT_FILE.read_text()))
    failures = split_tool.verify(split, json.loads(HOLDOUT_FILE.read_text()), REPO_ROOT)
    if failures:
        # Nothing committed is overwritten by a failing split.
        print("Split checks failed; refusing to train (split.json and inventory.md left unchanged):\n  " + "\n  ".join(failures), file=sys.stderr)
        return 1
    if not args.no_evidence:
        SPLIT_FILE.write_text(dump_json(split), encoding="utf-8")
        import inventory_report

        EVIDENCE.mkdir(parents=True, exist_ok=True)
        (EVIDENCE / "inventory.md").write_text(inventory_report.render(manifest, json.loads((args.out / "inventory.json").read_text())), encoding="utf-8")
    counts = {side: sum(1 for r in manifest["items"] if r["side"] == side) for side in ("train", "tune", "heldout")}
    print(f"Assembled {len(manifest['items'])} real faces {counts}, excluded {len(manifest['excluded'])}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
