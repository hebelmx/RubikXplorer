"""Evaluate the quantized LiteRT 7-keypoint corner model on held-out synthetic 3D,
real-palette warped Fixtures/live/ frames, and adversarial dark/glossy & empty negatives (#196).

Usage:
    uv run --project tools/corner-model python tools/corner-model/evaluate_model.py
    # Story 10.19 D5 acceptance on real labelled Lenovo / S24 FE keypoint fixtures (README "Real corner fixtures"):
    uv run --project tools/corner-model python tools/corner-model/evaluate_model.py --fixtures-corner Fixtures/corner
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import numpy as np
import tensorflow as tf

from render_cube import (
    FixtureAssets,
    is_valid_geometry_normalized,
    load_live_fixtures,
    render_negative_sample,
    render_positive_sample,
)

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MODEL = REPO_ROOT / "app/src/camera/corner/assets/corner_keypoints.tflite"
DEFAULT_FIXTURES_LIVE = REPO_ROOT / "Fixtures/live"
DEFAULT_THRESHOLD = 0.55
MAX_MODEL_BYTES = 450 * 1024
# Gates shared by the synthetic run and the real-capture slice.
MIN_PRECISION = 0.96
MIN_RECALL = 0.92
MIN_NEG_REJECTION = 0.96
# Story 10.19 D5, per device on real held-out keypoint fixtures.
MIN_DEVICE_POSITIVES = 20
MIN_DEVICE_NEGATIVES = 10
MAX_CORNER_ERROR_FRACTION = 0.03
REQUIRED_DEVICES = ("TB128XU", "SM-S721")


def run_tflite(interpreter: tf.lite.Interpreter, batch: np.ndarray) -> np.ndarray:
    inp_idx = interpreter.get_input_details()[0]["index"]
    out_idx = interpreter.get_output_details()[0]["index"]
    preds = np.empty((len(batch), 15), dtype=np.float32)
    for i in range(len(batch)):
        interpreter.set_tensor(inp_idx, batch[i : i + 1])
        interpreter.invoke()
        preds[i] = interpreter.get_tensor(out_idx)[0]
    return preds


def evaluate_slice(
    interpreter: tf.lite.Interpreter,
    samples: list[tuple[np.ndarray, np.ndarray]],
    threshold: float,
) -> dict[str, float]:
    xs = np.stack([s[0] for s in samples], axis=0)
    ys = np.stack([s[1] for s in samples], axis=0)
    preds = run_tflite(interpreter, xs)
    pres_true = ys[:, 0] > 0.5
    pres_pred = preds[:, 0] >= threshold

    geom_valid = np.array([is_valid_geometry_normalized(preds[i, 1:].reshape(7, 2)) for i in range(len(preds))])
    accepted = pres_pred & geom_valid

    tp = int(np.sum(pres_true & accepted))
    fn = int(np.sum(pres_true & ~accepted))
    fp = int(np.sum(~pres_true & accepted))
    tn = int(np.sum(~pres_true & ~accepted))

    metrics: dict[str, float] = {
        "count": float(len(samples)),
        "tp": float(tp),
        "fn": float(fn),
        "fp": float(fp),
        "tn": float(tn),
    }
    if np.any(pres_true):
        pos_mask = pres_true & accepted
        metrics["recall"] = tp / max(1, tp + fn)
        metrics["raw_presence_recall"] = float(np.sum(pres_true & pres_pred)) / max(1, int(np.sum(pres_true)))
        if np.any(pos_mask):
            true_xy = ys[pos_mask, 1:].reshape(-1, 7, 2) * 128.0
            pred_xy = preds[pos_mask, 1:].reshape(-1, 7, 2) * 128.0
            dists = np.sqrt(np.sum((true_xy - pred_xy) ** 2, axis=-1))
            metrics["mean_px_err"] = float(np.mean(dists))
            metrics["median_px_err"] = float(np.median(dists))
            metrics["p90_px_err"] = float(np.percentile(dists, 90))
        else:
            metrics["mean_px_err"] = float("inf")
            metrics["median_px_err"] = float("inf")
            metrics["p90_px_err"] = float("inf")
    if np.any(~pres_true):
        metrics["rejection_rate"] = tn / max(1, tn + fp)
        metrics["false_positive_rate"] = fp / max(1, tn + fp)
    return metrics


def json_safe(value: object) -> object:
    """`value` with every non-finite float (an empty slice's inf error) as null, so `--json-out` writes strict JSON."""
    if isinstance(value, float):
        return value if np.isfinite(value) else None
    if isinstance(value, dict):
        return {key: json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [json_safe(item) for item in value]
    return value


def corner_error_fraction(true_kps: np.ndarray, pred_kps: np.ndarray) -> float:
    """Mean distance of the 7 predicted corners to the labeled ones, as a fraction of the labeled cube outline's diagonal
    (the bounding box of the 7 labeled corners), so the bar does not depend on how large the cube is in the crop."""
    span = true_kps.max(axis=0) - true_kps.min(axis=0)
    diagonal = float(np.hypot(span[0], span[1]))
    if diagonal <= 0.0:
        return float("inf")
    return float(np.mean(np.sqrt(np.sum((true_kps - pred_kps) ** 2, axis=-1)))) / diagonal


def evaluate_real_corner_fixtures(interpreter: tf.lite.Interpreter, corner_dir: Path, threshold: float) -> dict[str, dict]:
    """Evaluates the labeled real keypoint fixtures under `corner_dir` per device (Story 10.19 D5).

    Every real capture is held out (the model is not retrained in Story 10.19). Color records and manifests are skipped.
    Exits nonzero with a clear message when the directory is missing or holds no keypoint fixture; a malformed one stops
    the run with its path (the loader never skips one). Returns `{device: metrics}`.
    """
    from train_corner_model import load_corner_keypoint_fixtures

    if not corner_dir.is_dir():
        sys.exit(f"--fixtures-corner {corner_dir} does not exist; record and label real captures first (README).")
    loaded = load_corner_keypoint_fixtures(corner_dir)
    if not loaded:
        sys.exit(f"--fixtures-corner {corner_dir} holds no keypoint fixture; nothing to evaluate (README).")
    by_device: dict[str, list[dict]] = {}
    for fixture in loaded:
        by_device.setdefault(fixture["device"], []).append(fixture)
    report: dict[str, dict] = {}
    for device, fixtures in sorted(by_device.items()):
        samples = [(f["x"], f["y"]) for f in fixtures]
        m = evaluate_slice(interpreter, samples, threshold)
        xs = np.stack([s[0] for s in samples], axis=0)
        ys = np.stack([s[1] for s in samples], axis=0)
        preds = run_tflite(interpreter, xs)
        errors = []
        for i in range(len(samples)):
            accepted = preds[i, 0] >= threshold and is_valid_geometry_normalized(preds[i, 1:].reshape(7, 2))
            if ys[i, 0] > 0.5 and accepted:
                errors.append(corner_error_fraction(ys[i, 1:].reshape(7, 2), preds[i, 1:].reshape(7, 2)))
        m["positives"] = float(np.sum(ys[:, 0] > 0.5))
        m["negatives"] = float(np.sum(ys[:, 0] <= 0.5))
        m["mean_corner_error_fraction"] = float(np.mean(errors)) if errors else float("inf")
        report[device] = m
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate quantized corner_keypoints.tflite.")
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--fixtures-live", type=Path, default=DEFAULT_FIXTURES_LIVE)
    parser.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD)
    parser.add_argument("--per-slice", type=int, default=160)
    parser.add_argument("--seed", type=int, default=991018)
    parser.add_argument("--json-out", type=Path, default=None)
    parser.add_argument(
        "--fixtures-corner",
        type=Path,
        default=None,
        help="Evaluate only labelled real captures in this directory (Story 10.18 AC2 acceptance), not the synthetic slices.",
    )
    args = parser.parse_args()

    if not args.model.is_file():
        raise SystemExit(f"Model not found: {args.model}")

    size_bytes = args.model.stat().st_size
    interpreter = tf.lite.Interpreter(model_path=str(args.model), num_threads=4)
    interpreter.allocate_tensors()

    if args.fixtures_corner is not None:
        devices = evaluate_real_corner_fixtures(interpreter, args.fixtures_corner, args.threshold)
        print(f"Model: {args.model} ({size_bytes:,} bytes); real captures (all held out): {args.fixtures_corner}")
        print(f"Presence threshold: {args.threshold:.2f} + CornerLocator.isValidCubeGeometry guard")
        failures = []
        missing = [name for name in REQUIRED_DEVICES if not any(name.lower() in d.lower() for d in devices)]
        for name in missing:
            failures.append(f"no keypoint fixture from {name}")
        for device, m in devices.items():
            positives = int(m["positives"])
            negatives = int(m["negatives"])
            line = f"{device}: positives={positives} negatives={negatives}"
            if positives:
                line += (
                    f" | recall={m['recall']*100:5.1f}% (raw={m['raw_presence_recall']*100:5.1f}%)"
                    f" | corner error={m['mean_corner_error_fraction']*100:4.2f}% of the outline diagonal"
                    f" (mean={m['mean_px_err']:4.2f}px p90={m['p90_px_err']:4.2f}px, 128 px crop)"
                )
            if negatives:
                line += f" | rejection={m['rejection_rate']*100:5.1f}% FP={int(m['fp'])}"
            print(line)
            if positives < MIN_DEVICE_POSITIVES:
                failures.append(f"{device}: {positives} positives (need >= {MIN_DEVICE_POSITIVES})")
            if negatives < MIN_DEVICE_NEGATIVES:
                failures.append(f"{device}: {negatives} negatives (need >= {MIN_DEVICE_NEGATIVES})")
            if positives and m["recall"] < MIN_RECALL:
                failures.append(f"{device}: recall {m['recall']:.4f} < {MIN_RECALL}")
            if negatives and m["rejection_rate"] < MIN_NEG_REJECTION:
                failures.append(f"{device}: negative rejection {m['rejection_rate']:.4f} < {MIN_NEG_REJECTION}")
            if positives and m["mean_corner_error_fraction"] > MAX_CORNER_ERROR_FRACTION:
                failures.append(
                    f"{device}: corner error {m['mean_corner_error_fraction']:.4f} > {MAX_CORNER_ERROR_FRACTION} of the outline diagonal"
                )
        if args.json_out is not None:
            args.json_out.parent.mkdir(parents=True, exist_ok=True)
            report = {"model_path": str(args.model), "fixtures_corner": str(args.fixtures_corner), "threshold": args.threshold, "devices": devices}
            args.json_out.write_text(json.dumps(json_safe(report), indent=2, allow_nan=False) + "\n", encoding="utf-8")
        if failures:
            sys.exit("Real-capture acceptance FAILED: " + "; ".join(failures))
        print("Real-capture acceptance PASSED")
        return

    # Load ALL live fixtures (including held-out lenovo stkb and s24fe stlb!)
    all_assets = load_live_fixtures(args.fixtures_live)
    heldout_groups = ("lenovo-tb128xu_stkb_studio-a", "sm-s721u1_stlb_studio-a")
    heldout_crops = [c for c in all_assets.face_crops if c[0] in heldout_groups]
    missing = [g for g in heldout_groups if not any(c[0] == g for c in heldout_crops)]
    if missing:
        raise SystemExit(f"Held-out fixture groups not found under {args.fixtures_live}: {missing}")
    heldout_assets = FixtureAssets(all_assets.palettes, heldout_crops)

    rng = np.random.default_rng(args.seed)
    slices = {
        "stickered_3d": [
            render_positive_sample(rng, all_assets, force_style="stickered") for _ in range(args.per_slice)
        ],
        "stickerless_3d": [
            render_positive_sample(rng, all_assets, force_style="stickerless") for _ in range(args.per_slice)
        ],
        "heldout_real_crop_3d": [
            render_positive_sample(rng, heldout_assets, force_style="real_crop") for _ in range(args.per_slice)
        ],
        "dark_glossy_cube_196": [
            render_positive_sample(rng, all_assets, force_dark_glossy=True) for _ in range(args.per_slice)
        ],
        "neg_dark_glossy_reflection_196": [
            render_negative_sample(rng, all_assets, neg_kind="dark_glossy_reflection") for _ in range(args.per_slice)
        ],
        "neg_empty_and_hand": [
            render_negative_sample(rng, all_assets, neg_kind=("empty_surface" if i % 2 == 0 else "hand_only"))
            for i in range(args.per_slice)
        ],
        "neg_flat_2d_face": [
            render_negative_sample(rng, all_assets, neg_kind="flat_2d_face") for _ in range(args.per_slice)
        ],
    }

    report: dict[str, object] = {
        "model_path": str(args.model),
        "size_bytes": size_bytes,
        "size_kb": round(size_bytes / 1024.0, 2),
        "threshold": args.threshold,
        "slices": {},
    }

    total_tp = total_fp = total_fn = total_tn = 0
    print(f"Model: {args.model} ({size_bytes:,} bytes = {size_bytes / 1024.0:.1f} KB, limit {MAX_MODEL_BYTES // 1024} KB)")
    print(f"Presence threshold: {args.threshold:.2f} + CornerLocator.isValidCubeGeometry guard")
    print("-" * 86)
    for name, data in slices.items():
        m = evaluate_slice(interpreter, data, args.threshold)
        report["slices"][name] = m  # type: ignore[index]
        total_tp += int(m["tp"])
        total_fp += int(m["fp"])
        total_fn += int(m["fn"])
        total_tn += int(m["tn"])
        if "recall" in m:
            print(
                f"{name:32s} | recall={m['recall']*100:5.1f}% (raw={m['raw_presence_recall']*100:5.1f}%) "
                f"| mean={m['mean_px_err']:4.2f}px med={m['median_px_err']:4.2f}px p90={m['p90_px_err']:4.2f}px"
            )
        else:
            print(
                f"{name:32s} | rejection={m['rejection_rate']*100:5.1f}% | FP={int(m['fp'])}/{int(m['count'])}"
            )

    precision = total_tp / max(1, total_tp + total_fp)
    recall = total_tp / max(1, total_tp + total_fn)
    neg_reject = total_tn / max(1, total_tn + total_fp)
    report["overall"] = {
        "precision": precision,
        "recall": recall,
        "negative_rejection": neg_reject,
        "tp": total_tp,
        "fp": total_fp,
        "fn": total_fn,
        "tn": total_tn,
    }
    print("-" * 86)
    print(
        f"OVERALL | precision={precision*100:.2f}% | recall={recall*100:.2f}% "
        f"| negative_rejection={neg_reject*100:.2f}% (TP={total_tp}, FP={total_fp}, FN={total_fn}, TN={total_tn})"
    )

    if args.json_out is not None:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(json.dumps(json_safe(report), indent=2, allow_nan=False) + "\n", encoding="utf-8")

    assert size_bytes < MAX_MODEL_BYTES, f"Model size {size_bytes} exceeds {MAX_MODEL_BYTES} bytes"
    assert precision >= MIN_PRECISION, f"Precision too low: {precision:.4f}"
    assert recall >= MIN_RECALL, f"Recall too low: {recall:.4f}"
    assert neg_reject >= MIN_NEG_REJECTION, f"Negative rejection too low: {neg_reject:.4f}"


if __name__ == "__main__":
    main()
