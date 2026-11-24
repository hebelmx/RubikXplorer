"""Score the pinned Super-Morse YOLO11n ONNX export on Cubentia's labeled fixtures.

Run from the repository root with a temporary environment:
    uv run --no-project --with onnxruntime --with opencv-python-headless --with numpy --with pillow \
        python _bmad-output/planning-artifacts/research/technical-continuous-facelet-scan-2026-11-24/digests/score_cubentia_fixtures.py <best.onnx>
"""

import argparse
import ast
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
EXCLUDED_LIVE_GROUPS = {"sm-s721u1_stkb_studio-a"}  # known bad truth (10 WHITE, 8 RED; assemble.py)
DEVICES = {"lenovo-tb128xu": "Lenovo", "sm-s721u1": "S24FE", "sm-t738u": "TabS7FE"}
TARGET = 640
THRESHOLD = 0.35


def load_color_map(session):
    names = ast.literal_eval(session.get_modelmeta().custom_metadata_map["names"])
    return {int(k): str(v).upper() for k, v in names.items() if str(v).lower() != "rubik_cube"}


def predict(session, image_bgr, color_map):
    h, w = image_bgr.shape[:2]
    scale = min(TARGET / w, TARGET / h)
    nw, nh = int(w * scale), int(h * scale)
    left, top = int((TARGET - nw) / 2.0), int((TARGET - nh) / 2.0)
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
    valid_cls = np.isin(classes, list(color_map.keys()))
    selected = np.flatnonzero((scores >= THRESHOLD) & valid_cls)
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
                color_map[int(classes[idx])],
                float(scores[idx]),
                (float((cx - left) / scale), float((cy - top) / scale), float(bw / scale), float(bh / scale)),
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


def match_predictions(preds, polys):
    matched = {}
    dup = outside = 0
    centroids = [poly.mean(axis=0) for poly in polys]
    for pred_color, _, (ox, oy, _, _) in preds:
        hits = [i for i, poly in enumerate(polys) if cv2.pointPolygonTest(poly, (ox, oy), False) >= 0]
        if not hits:
            outside += 1
            continue
        unmatched_hits = [i for i in hits if i not in matched]
        if unmatched_hits:
            idx = min(unmatched_hits, key=lambda i: float(np.hypot(ox - centroids[i][0], oy - centroids[i][1])))
            matched[idx] = pred_color
        else:
            dup += 1
    return matched, dup, outside


def score_corner(session, repo_root, sc, color_map):
    buckets = defaultdict(Counter)
    slot_stats = defaultdict(Counter)
    ratios = []
    latencies = []
    for directory in sorted(p for p in (repo_root / "Fixtures/corner").iterdir() if p.is_dir()):
        device_id, cube, _ = directory.name.split("_", 2)
        if device_id not in DEVICES:
            raise ValueError(f"Unknown device id {device_id!r} in {directory.name}")
        device = DEVICES[device_id]
        cube_type = "stickered" if cube.startswith("stk") else "stickerless"
        is_pastel = cube == "stlb"
        for jpath in sorted(directory.glob("corner-*.json")):
            rec = json.loads(jpath.read_text())
            kp = rec.get("keypoints")
            if not kp or len(kp) != 7:
                continue
            img = cv2.imread(str(directory / rec["frame"]))
            h, w = img.shape[:2]
            pts = uv_to_image_points(kp, w, h, sc)
            poly_entries = sticker_polygons(pts, sc)
            if poly_entries is None:
                raise ValueError(f"Degenerate quad in {jpath}")
            polys = [p for _, _, p in poly_entries]
            truths = [(fi, col) for fi, ls in enumerate(rec["labeledSamples"]) for col in ls["truth"]]
            preds, dt_ms = predict(session, img, color_map)
            latencies.append(dt_ms)
            for _, _, (_, _, ow, oh) in preds:
                if oh > 0:
                    ratios.append(ow / oh)
            matched, dup, outside = match_predictions(preds, polys)
            correct = 0
            for idx, pred_color in matched.items():
                fi, gt_color = truths[idx]
                slot = ("top(U/D)", "left(F/L)", "right(R/B)")[fi]
                slot_stats[slot]["hit"] += 1
                if pred_color == gt_color:
                    correct += 1
                    slot_stats[slot]["correct"] += 1
            for fi, _ in truths:
                slot_stats[("top(U/D)", "left(F/L)", "right(R/B)")[fi]]["total"] += 1
            keys = ["ALL", cube_type, device, f"{device}/{cube_type}"]
            if is_pastel:
                keys.append("pastel(stlb)")
            for key in keys:
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
    for key in ("ALL", "stickered", "stickerless", "Lenovo/stickered", "S24FE/stickered", "Lenovo/stickerless", "S24FE/stickerless", "pastel(stlb)"):
        b = buckets[key]
        if not b["photos"]:
            continue
        print(
            f"  {key:18s} photos={b['photos']:2d} gt={b['gt']:3d} pred={b['pred']:3d} "
            f"hit={b['hit']:3d} ({100*b['hit']/b['gt']:4.1f}%) "
            f"correct={b['correct']:3d} ({100*b['correct']/b['gt']:4.1f}%) "
            f"color|hit={100*b['correct']/max(1,b['hit']):4.1f}% dup={b['dup']:3d} out={b['outside']:2d}"
        )
    for slot in ("top(U/D)", "left(F/L)", "right(R/B)"):
        s = slot_stats[slot]
        print(f"  slot {slot:10s} hit={s['hit']}/{s['total']} ({100*s['hit']/s['total']:.1f}%) correct={s['correct']}/{s['total']} ({100*s['correct']/s['total']:.1f}%)")


def score_keypoints_negatives(session, repo_root, color_map):
    neg = []
    for jpath in sorted((repo_root / "Fixtures/corner").glob("*/keypoints/*.json")):
        rec = json.loads(jpath.read_text())
        if rec["label"] != "negative":
            continue
        img = cv2.imread(str(jpath.parent / rec["frame"]))
        if img is None:
            continue
        preds, _ = predict(session, img, color_map)
        neg.append(len(preds))
    fp_frames = sum(1 for c in neg if c > 0)
    print(f"keypoints negatives (128x128 crops): n={len(neg)} fp_frames={fp_frames} ({100*fp_frames/max(1,len(neg)):.1f}%) total_fp={sum(neg)} max={max(neg, default=0)}")


def score_live(session, repo_root, sc, color_map):
    faces = gt = pred = hit = correct = dup_tot = out_tot = 0
    ratios = []
    for directory in sorted(p for p in (repo_root / "Fixtures/live").iterdir() if p.is_dir()):
        if directory.name in EXCLUDED_LIVE_GROUPS:
            continue
        for jpath in sorted(directory.glob("*.json")):
            rec = json.loads(jpath.read_text())
            if not rec.get("frame"):
                continue
            if rec["labeledSample"]["sample"].get("orientationDegrees", 0) != 0:
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
            preds, _ = predict(session, img, color_map)
            for _, _, (_, _, ow, oh) in preds:
                if oh > 0:
                    ratios.append(ow / oh)
            matched, dup, outside = match_predictions(preds, polys)
            corr = sum(int(pred_color == truth[idx]) for idx, pred_color in matched.items())
            faces += 1
            gt += 9
            pred += len(preds)
            hit += len(matched)
            correct += corr
            dup_tot += dup
            out_tot += outside
    print(
        f"live frontal (excluding known-bad truth): faces={faces} gt={gt} pred={pred} "
        f"hit={hit} ({100*hit/max(1,gt):.1f}%) correct={correct} ({100*correct/max(1,gt):.1f}%) "
        f"color|hit={100*correct/max(1,hit):.1f}% dup={dup_tot} out={out_tot} "
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
    color_map = load_color_map(session)
    score_corner(session, repo_root, sc, color_map)
    score_keypoints_negatives(session, repo_root, color_map)
    score_live(session, repo_root, sc, color_map)


if __name__ == "__main__":
    main()
