"""Evaluate Cubentia's clean-room LiteRT v2 INT8 sticker-center heatmap model (`sticker_centers_v2_int8.tflite`)
on real Cubentia fixtures (tuning split by default; canonical holdout union strictly respected) and negative crops.

Self-contained inside `tools/center-model/`.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import time
from collections import Counter, defaultdict
from pathlib import Path
from typing import List, Tuple

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import numpy as np
from PIL import Image, ImageDraw

try:
    from ai_edge_litert.interpreter import Interpreter as _LiteRtInterpreter
except ImportError:
    import tensorflow as tf

    _LiteRtInterpreter = tf.lite.Interpreter

import cube_geometry as cg
from holdout_policy import EXCLUDED_LIVE, REPO_ROOT, load_heldout_union


class LiteRtCenterDetector:
    """Zero-OpenCV LiteRT sticker-center heatmap detector with 3x3 local-max peak extraction."""

    def __init__(self, model_path: Path, num_threads: int = 2) -> None:
        self.model_name = model_path.name
        self.interpreter = _LiteRtInterpreter(model_path=str(model_path), num_threads=num_threads)
        self.interpreter.allocate_tensors()
        in_det = self.interpreter.get_input_details()[0]
        out_det = self.interpreter.get_output_details()[0]
        self.inp_idx = in_det["index"]
        self.out_idx = out_det["index"]
        self.inp_dtype = in_det["dtype"]
        self.inp_quant = in_det.get("quantization", (0.0, 0))
        self.out_dtype = out_det["dtype"]
        self.out_quant = out_det.get("quantization", (0.0, 0))
        self.img_size = int(in_det["shape"][1])
        self.hm_size = int(out_det["shape"][1])
        self.hm_stride = float(self.img_size) / float(self.hm_size)

    def detect_rgb(
        self, img_rgb_u8: np.ndarray, threshold: float = 0.45, max_peaks: int = 36
    ) -> Tuple[List[Tuple[float, float, float]], float]:
        """Letterbox any RGB uint8 image to model input size, run LiteRT, and return [(x, y, score), ...] in original pixels."""
        h, w = img_rgb_u8.shape[:2]
        if w <= 0 or h <= 0:
            return [], 0.0
        img_sz = self.img_size
        hm_sz = self.hm_size
        scale = min(img_sz / w, img_sz / h)
        # Round half up, as Kotlin's roundToInt does in StickerCenterModelTransform.letterbox.
        nw = max(1, min(img_sz, int(math.floor(w * scale + 0.5))))
        nh = max(1, min(img_sz, int(math.floor(h * scale + 0.5))))
        left, top = (img_sz - nw) // 2, (img_sz - nh) // 2
        resized = np.asarray(Image.fromarray(img_rgb_u8).resize((nw, nh), Image.BILINEAR), dtype=np.uint8)
        canvas = np.zeros((img_sz, img_sz, 3), dtype=np.uint8)
        canvas[top : top + nh, left : left + nw] = resized

        if self.inp_dtype == np.float32:
            inp = (canvas.astype(np.float32) / 255.0)[None]
        elif self.inp_dtype == np.uint8:
            inp = canvas[None]
        else:
            q_scale, q_zp = self.inp_quant
            inp = np.clip(np.rint((canvas.astype(np.float32) / 255.0) / max(q_scale, 1e-8) + q_zp), -128, 127).astype(
                self.inp_dtype
            )[None]

        t0 = time.perf_counter()
        self.interpreter.set_tensor(self.inp_idx, inp)
        self.interpreter.invoke()
        raw_out = self.interpreter.get_tensor(self.out_idx)[0]
        dt_ms = (time.perf_counter() - t0) * 1000.0

        if self.out_dtype == np.float32:
            out = raw_out
        else:
            q_scale, q_zp = self.out_quant
            out = (raw_out.astype(np.float32) - float(q_zp)) * float(q_scale)

        hm = out[:, :, 0]
        padded = np.pad(hm, 1, mode="constant", constant_values=-1.0)
        local_max = np.maximum.reduce(
            [
                padded[0:hm_sz, 0:hm_sz],
                padded[0:hm_sz, 1 : hm_sz + 1],
                padded[0:hm_sz, 2 : hm_sz + 2],
                padded[1 : hm_sz + 1, 0:hm_sz],
                padded[1 : hm_sz + 1, 1 : hm_sz + 1],
                padded[1 : hm_sz + 1, 2 : hm_sz + 2],
                padded[2 : hm_sz + 2, 0:hm_sz],
                padded[2 : hm_sz + 2, 1 : hm_sz + 1],
                padded[2 : hm_sz + 2, 2 : hm_sz + 2],
            ]
        )
        ys, xs = np.where((hm >= threshold) & (hm >= local_max - 1e-6))
        if not len(ys):
            return [], dt_ms
        scores = hm[ys, xs]
        # Same order as StickerCenterModelTransform.decodePeaks: stable by score, cap applied after the padding filter.
        order = np.argsort(-scores, kind="stable")
        peaks: List[Tuple[float, float, float]] = []
        for idx in order:
            gy, gx = int(ys[idx]), int(xs[idx])
            dx = float(out[gy, gx, 1])
            dy = float(out[gy, gx, 2])
            px_in = (gx + 0.5 + dx) * self.hm_stride
            py_in = (gy + 0.5 + dy) * self.hm_stride
            ox = (px_in - left) / scale
            oy = (py_in - top) / scale
            if 0.0 <= ox < w and 0.0 <= oy < h:
                peaks.append((float(ox), float(oy), float(scores[idx])))
                if len(peaks) >= max_peaks:
                    break
        return peaks, dt_ms


def _point_in_quad(pt: Tuple[float, float], quad: np.ndarray) -> bool:
    """Pure-NumPy point-in-convex-quad test."""
    x, y = pt
    pos = neg = 0
    for i in range(4):
        x1, y1 = quad[i]
        x2, y2 = quad[(i + 1) % 4]
        cross = (x2 - x1) * (y - y1) - (y2 - y1) * (x - x1)
        if cross > 0:
            pos += 1
        elif cross < 0:
            neg += 1
    return pos == 0 or neg == 0


def _aligned_pts(img_rgb_f32: np.ndarray, rec: dict) -> np.ndarray:
    h, w = img_rgb_f32.shape[:2]
    side = min(w, h)
    center = np.array([w / 2.0, h / 2.0])
    half = (side * cg.GRID_FRACTION / 2.0) * cg.CORNER_CROP_RADIUS_MULTIPLIER
    best = (1e9, None)
    for kname in ("detectorKeypoints", "keypoints"):
        kp = rec.get(kname)
        if not kp or len(kp) != 7:
            continue
        pts = center + (np.asarray(kp, dtype=np.float64) - 0.5) * 2.0 * half
        quads = cg.guide_quads(pts)
        unwarped = [cg.unwarp(img_rgb_f32, quads[fi]) for fi in range(len(rec["labeledSamples"]))]
        if any(crop is None for crop in unwarped):
            continue
        des = [
            cg.delta_e(
                cg.rgb_to_lab(np.stack(cg.cell_means(unwarped[fi], cg.CORNER_CELL_ROI)))[i],
                (s["l"], s["a"], s["b"]),
            )
            for fi, ls in enumerate(rec["labeledSamples"])
            for i, s in enumerate(ls["sample"]["stickers"])
        ]
        md = float(np.median(des))
        if md < best[0]:
            best = (md, pts)
    if best[1] is None:
        raise ValueError("No valid 7-keypoint alignment found in fixture record")
    return best[1]


def evaluate_on_fixtures(detector: LiteRtCenterDetector, split: str, threshold: float, vis_dir: Path | None) -> None:
    held = load_heldout_union()
    buckets = defaultdict(Counter)
    slots = defaultdict(Counter)
    latencies = []
    saved = 0

    corner_dir = REPO_ROOT / "Fixtures/corner"
    if corner_dir.is_dir():
        for d in sorted(p for p in corner_dir.iterdir() if p.is_dir()):
            in_held = f"corner/{d.name}" in held
            if split == "tuning" and in_held:
                continue
            if split == "heldout" and not in_held:
                continue
            dev_id, cube, _ = d.name.split("_", 2)
            dev = "Lenovo" if "lenovo" in dev_id else "S24FE"
            ctype = "stickered" if cube.startswith("stk") else ("pastel" if cube == "stlb" else "stickerless")
            for jpath in sorted(d.glob("corner-*.json")):
                rec = json.loads(jpath.read_text())
                if not rec.get("detectorKeypoints"):
                    continue
                with Image.open(d / rec["frame"]) as im_file:
                    img_rgb = np.asarray(im_file.convert("RGB"), dtype=np.uint8)
                pts = _aligned_pts(img_rgb.astype(np.float32), rec)
                polys = []
                for fi, quad in enumerate(cg.guide_quads(pts)):
                    H = cg.homography(quad)
                    if H is None:
                        continue
                    for r in range(3):
                        for c in range(3):
                            xs, ys = H(
                                np.array([c / 3, (c + 1) / 3, (c + 1) / 3, c / 3]),
                                np.array([r / 3, r / 3, (r + 1) / 3, (r + 1) / 3]),
                            )
                            polys.append((fi, np.stack([xs, ys], axis=-1)))

                peaks, dt_ms = detector.detect_rgb(img_rgb, threshold=threshold)
                latencies.append(dt_ms)

                matched = set()
                dup = out = 0
                c_gt = [p.mean(axis=0) for _, p in polys]
                for ox, oy, _ in peaks:
                    hits = [i for i, (_, p) in enumerate(polys) if _point_in_quad((ox, oy), p)]
                    if not hits:
                        out += 1
                        continue
                    unm = [i for i in hits if i not in matched]
                    if unm:
                        idx = min(unm, key=lambda i: float(np.hypot(ox - c_gt[i][0], oy - c_gt[i][1])))
                        matched.add(idx)
                        slots[("top", "left", "right")[polys[idx][0]]]["hit"] += 1
                    else:
                        dup += 1
                for fi, _ in polys:
                    slots[("top", "left", "right")[fi]]["tot"] += 1

                for k in (f"ALL_{split.upper()}", ctype, f"{dev}/{ctype}"):
                    b = buckets[k]
                    b["photos"] += 1
                    b["gt"] += 27
                    b["pred"] += len(peaks)
                    b["hit"] += len(matched)
                    b["dup"] += dup
                    b["out"] += out

                if vis_dir is not None and saved < 4:
                    vis_dir.mkdir(parents=True, exist_ok=True)
                    im = Image.fromarray(img_rgb).resize((600, 600), Image.NEAREST)
                    sv = 600.0 / img_rgb.shape[1]
                    draw = ImageDraw.Draw(im)
                    for _, p in polys:
                        draw.polygon([(float(x * sv), float(y * sv)) for x, y in p], outline=(0, 230, 0))
                    for ox, oy, _ in peaks:
                        x, y = ox * sv, oy * sv
                        draw.ellipse([x - 5, y - 5, x + 5, y + 5], fill=(255, 0, 255), outline=(0, 0, 0))
                    im.save(vis_dir / f"litert_{split}_{saved}_{d.name}_{jpath.stem}.jpg", quality=92)
                    saved += 1

    print(f"\n=== LiteRT {detector.model_name} on Fixtures/corner ({split}, thresh={threshold}) ===")
    if latencies:
        print(f"PC CPU LiteRT latency (2 threads): median={np.median(latencies):.2f} ms, p95={np.percentile(latencies, 95):.2f} ms")
    for k in (
        f"ALL_{split.upper()}",
        "stickered",
        "stickerless",
        "pastel",
        "Lenovo/stickered",
        "S24FE/stickered",
        "Lenovo/stickerless",
        "S24FE/stickerless",
        "Lenovo/pastel",
    ):
        b = buckets[k]
        if not b["photos"]:
            continue
        prec = 100 * b["hit"] / max(1, b["pred"])
        print(
            f"  {k:18s} photos={b['photos']:2d} gt={b['gt']:3d} pred={b['pred']:3d} "
            f"hit={b['hit']:3d} ({100*b['hit']/b['gt']:5.1f}%) prec={prec:5.1f}% dup={b['dup']:2d} out={b['out']:2d}"
        )
    for s in ("top", "left", "right"):
        if slots[s]["tot"]:
            print(f"  slot {s:5s}: {slots[s]['hit']}/{slots[s]['tot']} ({100*slots[s]['hit']/slots[s]['tot']:.1f}%)")

    faces = gt = pred = hit = dup_l = out_l = 0
    live_dir = REPO_ROOT / "Fixtures/live"
    if live_dir.is_dir():
        for d in sorted(p for p in live_dir.iterdir() if p.is_dir()):
            if d.name in EXCLUDED_LIVE:
                continue
            in_held = f"live/{d.name}" in held
            if split == "tuning" and in_held:
                continue
            if split == "heldout" and not in_held:
                continue
            for jpath in sorted(d.glob("*.json")):
                rec = json.loads(jpath.read_text())
                if not rec.get("frame") or rec["labeledSample"]["sample"].get("orientationDegrees", 0) != 0:
                    continue
                with Image.open(d / rec["frame"]) as im_file:
                    img_rgb = np.asarray(im_file.convert("RGB"), dtype=np.uint8)
                h, w = img_rgb.shape[:2]
                side = min(w, h) * cg.GRID_FRACTION
                left, top = (w - side) / 2.0, (h - side) / 2.0
                polys = [
                    np.array(
                        [
                            [left + c * side / 3, top + r * side / 3],
                            [left + (c + 1) * side / 3, top + r * side / 3],
                            [left + (c + 1) * side / 3, top + (r + 1) * side / 3],
                            [left + c * side / 3, top + (r + 1) * side / 3],
                        ]
                    )
                    for r in range(3)
                    for c in range(3)
                ]
                c_gt = [p.mean(axis=0) for p in polys]
                peaks, _ = detector.detect_rgb(img_rgb, threshold=threshold)
                matched = set()
                for ox, oy, _ in peaks:
                    hits = [i for i, p in enumerate(polys) if _point_in_quad((ox, oy), p)]
                    if not hits:
                        out_l += 1
                        continue
                    unm = [i for i in hits if i not in matched]
                    if unm:
                        idx = min(unm, key=lambda i: float(np.hypot(ox - c_gt[i][0], oy - c_gt[i][1])))
                        matched.add(idx)
                    else:
                        dup_l += 1
                faces += 1
                gt += 9
                pred += len(peaks)
                hit += len(matched)
    print(f"  LIVE ({split}): faces={faces} gt={gt} pred={pred} hit={hit} ({100*hit/max(1,gt):.1f}%) dup={dup_l} out={out_l}")

    neg = []
    if corner_dir.is_dir():
        for jpath in sorted(corner_dir.glob("*/keypoints/*.json")):
            group_name = jpath.parents[1].name
            in_held = f"corner/{group_name}" in held
            if split == "tuning" and in_held:
                continue
            if split == "heldout" and not in_held:
                continue
            rec = json.loads(jpath.read_text())
            if rec["label"] != "negative":
                continue
            with Image.open(jpath.parent / rec["frame"]) as im_file:
                img_rgb = np.asarray(im_file.convert("RGB"), dtype=np.uint8)
            peaks, _ = detector.detect_rgb(img_rgb, threshold=threshold)
            neg.append(len(peaks))
    fp_frames = sum(1 for c in neg if c > 0)
    print(
        f"  NEGATIVES ({split}, 128x128 crops, n={len(neg)}): fp_frames={fp_frames} "
        f"({100*fp_frames/max(1,len(neg)):.1f}%) total_fp={sum(neg)} max={max(neg, default=0)}"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--model",
        type=Path,
        default=REPO_ROOT / "app/src/camera/continuous/assets/sticker_centers_v2_int8.tflite",
    )
    parser.add_argument("--split", choices=("tuning", "heldout", "all"), default="tuning")
    parser.add_argument("--thresh", type=float, default=0.45)
    parser.add_argument("--vis-dir", type=Path, default=None)
    args = parser.parse_args()
    det = LiteRtCenterDetector(args.model)
    evaluate_on_fixtures(det, split=args.split, threshold=args.thresh, vis_dir=args.vis_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
