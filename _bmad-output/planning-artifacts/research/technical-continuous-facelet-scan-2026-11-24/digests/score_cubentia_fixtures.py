"""Score the pinned Super-Morse YOLO11n ONNX export on Cubentia's labeled fixtures.

Run from the repository root with a temporary environment:
    uv run --no-project --with onnxruntime --with opencv-python-headless --with numpy --with pillow \
        python _bmad-output/planning-artifacts/research/technical-continuous-facelet-scan-2026-11-24/digests/score_cubentia_fixtures.py <best.onnx>
"""

import argparse
import hashlib
import json
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

MODEL_SHA = "f158df4560063686cf90483e21bc611790bc63f6fc2b343a7a4e1dd6b5063dd3"
COLORS = ("WHITE", "YELLOW", "GREEN", "BLUE", "RED", "ORANGE")
TARGET = 640
THRESHOLD = 0.35


def predict(session, image_bgr):
    h, w = image_bgr.shape[:2]
    scale = min(TARGET / w, TARGET / h)
    nw, nh = int(w * scale), int(h * scale)
    pad_x, pad_y = (TARGET - nw) / 2.0, (TARGET - nh) / 2.0
    left, top = int(pad_x), int(pad_y)
    canvas = np.zeros((TARGET, TARGET, 3), dtype=np.uint8)
    canvas[top : top + nh, left : left + nw] = cv2.resize(
        image_bgr, (nw, nh), interpolation=cv2.INTER_LINEAR
    )
    rgb = cv2.cvtColor(canvas, cv2.COLOR_BGR2RGB)
    tensor = np.ascontiguousarray((rgb.astype(np.float32) / 255.0).transpose(2, 0, 1)[None])
    t0 = time.perf_counter()
    rows = session.run(None, {session.get_inputs()[0].name: tensor})[0].squeeze(0).T
    dt_ms = (time.perf_counter() - t0) * 1000.0
    classes = rows[:, 4:].argmax(axis=1)
    scores = rows[:, 4:].max(axis=1)
    selected = np.flatnonzero((scores >= THRESHOLD) & (classes < len(COLORS)))
    if not len(selected):
        return [], dt_ms
    boxes = rows[selected, :4].copy()
    boxes[:, :2] -= boxes[:, 2:] / 2
    keep = cv2.dnn.NMSBoxes(boxes.tolist(), scores[selected].tolist(), THRESHOLD, THRESHOLD)
    indices = selected[np.asarray(keep, dtype=int).reshape(-1)]
    preds = []
    for idx in sorted(indices, key=lambda k: -scores[k]):
        cx, cy, bw, bh = rows[idx, :4]
        preds.append(
            (
                COLORS[classes[idx]],
                float(scores[idx]),
                (float((cx - pad_x) / scale), float((cy - pad_y) / scale), float(bw / scale), float(bh / scale)),
            )
        )
    return preds, dt_ms


def uv_to_image_points(uv, width, height, sc):
    side = min(width, height)
    center = np.array([width / 2.0, height / 2.0])
    radius = side * sc.GRID_FRACTION / 2.0
    half = radius * sc.CORNER_CROP_RADIUS_MULTIPLIER
    return center + (np.asarray(uv, dtype=np.float64) - 0.5) * 2.0 * half


def sticker_polygons(pts, sc):
    polygons = []
    for face_idx, quad in enumerate(sc.guide_quads(pts)):
        transform = sc.homography(quad)
        if transform is None:
            return None
        for row in range(3):
            for col in range(3):
                us = np.array([col / 3.0, (col + 1) / 3.0, (col + 1) / 3.0, col / 3.0])
                vs = np.array([row / 3.0, row / 3.0, (row + 1) / 3.0, (row + 1) / 3.0])
                xs, ys = transform(us, vs)
                polygons.append((face_idx, row * 3 + col, np.stack([xs, ys], axis=-1).astype(np.float32)))
    return polygons


def score_corner(session, repo_root, sc):
    buckets = defaultdict(Counter)
    slot_stats = defaultdict(Counter)
    ratios = []
    latencies = []
    for directory in sorted(p for p in (repo_root / "Fixtures/corner").iterdir() if p.is_dir()):
        device_id, cube, _ = directory.name.split("_", 2)
        cube_type = "stickered" if cube.startswith("stk") else "stickerless"
        device = "Lenovo" if "lenovo" in device_id else "S24FE"
        for jpath in sorted(directory.glob("corner-*.json")):
            rec = json.loads(jpath.read_text())
            kp = rec.get("keypoints")
            if not kp or len(kp) != 7:
                continue
            img = cv2.imread(str(directory / rec["frame"]))
            h, w = img.shape[:2]
            pts = uv_to_image_points(kp, w, h, sc)
            polys = sticker_polygons(pts, sc)
            truths = [(fi, col) for fi, ls in enumerate(rec["labeledSamples"]) for col in ls["truth"]]
            preds, dt_ms = predict(session, img)
            latencies.append(dt_ms)
            for _, _, (_, _, ow, oh) in preds:
                if oh > 0:
                    ratios.append(ow / oh)
            matched = set()
            dup = outside = correct = 0
            for pred_color, _, (ox, oy, _, _) in preds:
                hits = [i for i, (_, _, poly) in enumerate(polys) if cv2.pointPolygonTest(poly, (ox, oy), False) >= 0]
                if not hits:
                    outside += 1
                    continue
                idx = hits[0]
                if idx in matched:
                    dup += 1
                else:
                    matched.add(idx)
                    fi, gt_color = truths[idx]
                    slot = ("top(U/D)", "left(F/L)", "right(R/B)")[fi]
                    slot_stats[slot]["hit"] += 1
                    if pred_color == gt_color:
                        correct += 1
                        slot_stats[slot]["correct"] += 1
            for fi, _ in truths:
                slot_stats[("top(U/D)", "left(F/L)", "right(R/B)")[fi]]["total"] += 1
            for key in ("ALL", cube_type, device, f"{device}/{cube_type}"):
                b = buckets[key]
                b["photos"] += 1
                b["gt"] += 27
                b["pred"] += len(preds)
                b["hit"] += len(matched)
                b["correct"] += correct
                b["dup"] += dup
                b["outside"] += outside

    print(
        f"corner (human-keypoint subset): median_ms={np.median(latencies):.1f} "
        f"box_wh_median={np.median(ratios):.2f} p10={np.percentile(ratios, 10):.2f} p90={np.percentile(ratios, 90):.2f}"
    )
    for key in ("ALL", "stickered", "stickerless", "Lenovo/stickered", "S24FE/stickered", "Lenovo/stickerless", "S24FE/stickerless"):
        b = buckets[key]
        print(
            f"  {key:18s} photos={b['photos']:2d} gt={b['gt']:3d} pred={b['pred']:3d} "
            f"hit={b['hit']:3d} ({100*b['hit']/b['gt']:4.1f}%) "
            f"correct={b['correct']:3d} ({100*b['correct']/b['gt']:4.1f}%) "
            f"color|hit={100*b['correct']/max(1,b['hit']):4.1f}% dup={b['dup']:3d} out={b['outside']:2d}"
        )
    for slot in ("top(U/D)", "left(F/L)", "right(R/B)"):
        s = slot_stats[slot]
        print(f"  slot {slot:10s} hit={s['hit']}/{s['total']} ({100*s['hit']/s['total']:.1f}%) correct={s['correct']}/{s['total']} ({100*s['correct']/s['total']:.1f}%)")


def score_keypoints_negatives(session, repo_root):
    neg = []
    pos = []
    for jpath in sorted((repo_root / "Fixtures/corner").glob("*/keypoints/*.json")):
        rec = json.loads(jpath.read_text())
        img = cv2.imread(str(jpath.parent / rec["frame"]))
        if img is None:
            continue
        preds, _ = predict(session, img)
        (neg if rec["label"] == "negative" else pos).append(len(preds))
    fp_frames = sum(1 for c in neg if c > 0)
    print(f"keypoints negatives: n={len(neg)} fp_frames={fp_frames} ({100*fp_frames/max(1,len(neg)):.1f}%) total_fp={sum(neg)} max={max(neg, default=0)}")
    print(f"keypoints positives: n={len(pos)} median={np.median(pos) if pos else 0:.1f} min={min(pos, default=0)} max={max(pos, default=0)}")


def score_live(session, repo_root, sc):
    faces = gt = pred = hit = correct = 0
    ratios = []
    for directory in sorted(p for p in (repo_root / "Fixtures/live").iterdir() if p.is_dir()):
        for jpath in sorted(directory.glob("*.json")):
            rec = json.loads(jpath.read_text())
            if not rec.get("frame"):
                continue
            img = cv2.imread(str(directory / rec["frame"]))
            if img is None:
                continue
            h, w = img.shape[:2]
            side = min(w, h) * sc.GRID_FRACTION
            left, top = (w - side) / 2.0, (h - side) / 2.0
            polys = [
                np.array(
                    [
                        [left + c * side / 3.0, top + r * side / 3.0],
                        [left + (c + 1) * side / 3.0, top + r * side / 3.0],
                        [left + (c + 1) * side / 3.0, top + (r + 1) * side / 3.0],
                        [left + c * side / 3.0, top + (r + 1) * side / 3.0],
                    ],
                    dtype=np.float32,
                )
                for r in range(3)
                for c in range(3)
            ]
            truth = rec["labeledSample"]["truth"]
            preds, _ = predict(session, img)
            for _, _, (_, _, ow, oh) in preds:
                if oh > 0:
                    ratios.append(ow / oh)
            matched = set()
            corr = 0
            for pred_color, _, (ox, oy, _, _) in preds:
                hits = [i for i, poly in enumerate(polys) if cv2.pointPolygonTest(poly, (ox, oy), False) >= 0]
                if len(hits) == 1 and hits[0] not in matched:
                    matched.add(hits[0])
                    corr += int(pred_color == truth[hits[0]])
            faces += 1
            gt += 9
            pred += len(preds)
            hit += len(matched)
            correct += corr
    print(
        f"live frontal: faces={faces} gt={gt} pred={pred} hit={hit} ({100*hit/max(1,gt):.1f}%) "
        f"correct={correct} ({100*correct/max(1,gt):.1f}%) color|hit={100*correct/max(1,hit):.1f}% "
        f"box_wh_median={np.median(ratios) if ratios else 0:.2f}"
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("model", type=Path)
    args = parser.parse_args()
    if hashlib.sha256(args.model.read_bytes()).hexdigest() != MODEL_SHA:
        raise ValueError("Expected the ONNX export pinned in the research report")
    repo_root = Path.cwd()
    sys.path.insert(0, str(repo_root / "tools/sticker-model"))
    import sticker_common as sc

    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    session = ort.InferenceSession(str(args.model), options, providers=["CPUExecutionProvider"])
    score_corner(session, repo_root, sc)
    score_keypoints_negatives(session, repo_root)
    score_live(session, repo_root, sc)


if __name__ == "__main__":
    main()
