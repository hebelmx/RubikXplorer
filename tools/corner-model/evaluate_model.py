"""Evaluate the quantized LiteRT 7-keypoint corner model on held-out synthetic 3D,
real-palette warped Fixtures/live/ frames, and adversarial dark/glossy & empty negatives (#196).

Usage:
    uv run --project tools/corner-model python tools/corner-model/evaluate_model.py
    # Story 10.18 AC2 acceptance on real labelled Lenovo / S24 FE captures (README "Real corner fixtures"):
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


def evaluate_real_corner_fixtures(interpreter: tf.lite.Interpreter, corner_dir: Path, threshold: float) -> dict[str, float]:
    """Evaluates labelled real captures under `corner_dir` (README contract, same loader as training).

    Exits nonzero with a clear message when the directory is missing or holds no labelled capture; a malformed
    capture stops the run with its path (the loader never skips one).
    """
    from train_corner_model import load_optional_corner_fixtures

    if not corner_dir.is_dir():
        sys.exit(f"--fixtures-corner {corner_dir} does not exist; record and label real captures first (README).")
    loaded = load_optional_corner_fixtures(corner_dir)
    if loaded is None:
        sys.exit(f"--fixtures-corner {corner_dir} holds no labelled *.json capture; nothing to evaluate (README).")
    xs, ys = loaded
    return evaluate_slice(interpreter, list(zip(xs, ys)), threshold)


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
        m = evaluate_real_corner_fixtures(interpreter, args.fixtures_corner, args.threshold)
        positives = int(m["tp"] + m["fn"])
        negatives = int(m["tn"] + m["fp"])
        print(f"Model: {args.model} ({size_bytes:,} bytes); real captures: {args.fixtures_corner}")
        print(f"Presence threshold: {args.threshold:.2f} + CornerLocator.isValidCubeGeometry guard")
        failures = []
        if positives:
            print(
                f"positives={positives} | recall={m['recall']*100:5.1f}% (raw={m['raw_presence_recall']*100:5.1f}%) "
                f"| mean={m['mean_px_err']:4.2f}px med={m['median_px_err']:4.2f}px p90={m['p90_px_err']:4.2f}px (128 px crop)"
            )
            if m["recall"] < MIN_RECALL:
                failures.append(f"recall {m['recall']:.4f} < {MIN_RECALL}")
        else:
            failures.append("no positive capture; recall and pixel error cannot be measured")
        if negatives:
            print(f"negatives={negatives} | rejection={m['rejection_rate']*100:5.1f}% | FP={int(m['fp'])}")
            if m["rejection_rate"] < MIN_NEG_REJECTION:
                failures.append(f"negative rejection {m['rejection_rate']:.4f} < {MIN_NEG_REJECTION}")
        else:
            failures.append("no negative capture; rejection cannot be measured")
        if args.json_out is not None:
            args.json_out.parent.mkdir(parents=True, exist_ok=True)
            report = {"model_path": str(args.model), "fixtures_corner": str(args.fixtures_corner), "threshold": args.threshold, "real": m}
            args.json_out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
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
        args.json_out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    assert size_bytes < MAX_MODEL_BYTES, f"Model size {size_bytes} exceeds {MAX_MODEL_BYTES} bytes"
    assert precision >= MIN_PRECISION, f"Precision too low: {precision:.4f}"
    assert recall >= MIN_RECALL, f"Recall too low: {recall:.4f}"
    assert neg_reject >= MIN_NEG_REJECTION, f"Negative rejection too low: {neg_reject:.4f}"


if __name__ == "__main__":
    main()
