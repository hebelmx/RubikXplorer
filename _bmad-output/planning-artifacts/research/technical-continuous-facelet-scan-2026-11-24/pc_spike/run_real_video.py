"""Run the continuous-scan mapper on a real recorded video clip or live PC webcam using a sticker detector.

Uses yush-io/cubeSolver (single-class 'sticker', read-only PC comparator — never bundled or used for
training) -> lattice_fit.py (unordered 2D sticker centers -> 1-3 projective face quads) -> mapper.py.

Usage:
    # Recorded phone MP4 clip (headless, writes side-by-side MP4 replay + contact sheet):
    uv run --no-project --extra-index-url https://download.pytorch.org/whl/cpu --index-strategy unsafe-best-match \
      --with torch --with torchvision --with ultralytics --with opencv-python-headless --with numpy \
      python run_real_video.py --weights /tmp/cubesolver_best.pt --video path/to/clip.mp4 --out-dir /tmp/real_replay

    # Live webcam interactive window (requires opencv-python with GUI instead of headless):
    uv run --no-project --extra-index-url https://download.pytorch.org/whl/cpu --index-strategy unsafe-best-match \
      --with torch --with torchvision --with ultralytics --with opencv-python --with numpy \
      python run_real_video.py --weights /tmp/cubesolver_best.pt --webcam 0 --show
"""

from __future__ import annotations

import argparse
import hashlib
import time
from pathlib import Path
from typing import List, Optional, Tuple

import cv2
import numpy as np

from lattice_fit import fit_face_quads_from_centers
from mapper import ContinuousCubeMapper, MapperSnapshot
from run_spike import NET_BGR, NET_POS
from cube_model import FACES

CUBESOLVER_SHA = "68fd20b050a75f3268385e33afc8a9484ba498259e5a5b0e97914f28e2c1a739"


def render_real_frame(
    frame_bgr: np.ndarray,
    frame_idx: int,
    centers: List[Tuple[float, float]],
    snap: MapperSnapshot,
    title: str,
) -> np.ndarray:
    """Render 1120x480 side-by-side view: left = live frame + sticker dots + fitted quads, right = growing net."""
    h, w = frame_bgr.shape[:2]
    scale = min(640.0 / w, 480.0 / h)
    nw, nh = int(round(w * scale)), int(round(h * scale))
    ox_l, oy_l = (640 - nw) // 2, (480 - nh) // 2
    left = np.full((480, 640, 3), 20, dtype=np.uint8)
    left[oy_l : oy_l + nh, ox_l : ox_l + nw] = cv2.resize(frame_bgr, (nw, nh), interpolation=cv2.INTER_LINEAR)

    # Raw sticker center proposals (magenta dots)
    for cx, cy in centers:
        cv2.circle(left, (int(round(cx * scale)) + ox_l, int(round(cy * scale)) + oy_l), 3, (255, 0, 255), -1)

    # Fitted / tracked face quads
    for label, quad in snap.tracked_quads.items():
        pts = np.rint(quad * scale + np.array([ox_l, oy_l])).astype(np.int32)
        color = (0, 230, 80) if not label.startswith("?") else (0, 200, 255)
        cv2.polylines(left, [pts], True, color, 2, cv2.LINE_AA)
        c = pts.mean(axis=0).astype(int)
        cv2.putText(left, label, (int(c[0]) - 10, int(c[1]) + 6), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 0, 0), 3)
        cv2.putText(left, label, (int(c[0]) - 10, int(c[1]) + 6), cv2.FONT_HERSHEY_SIMPLEX, 0.7, color, 2)

    cv2.rectangle(left, (0, 0), (640, 32), (20, 20, 24), -1)
    cv2.putText(
        left,
        f"{title} | frame {frame_idx:04d} | stickers={len(centers)} quads={len(snap.tracked_quads)}",
        (10, 22),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.52,
        (230, 230, 230),
        1,
        cv2.LINE_AA,
    )
    cv2.rectangle(left, (0, 444), (640, 480), (24, 24, 30), -1)
    cue_col = (80, 120, 255) if snap.twist_detected else (90, 235, 130) if snap.committed_count == 54 else (240, 220, 100)
    cv2.putText(left, f"Cue: {snap.cue}", (10, 468), cv2.FONT_HERSHEY_SIMPLEX, 0.52, cue_col, 1, cv2.LINE_AA)

    # Right panel: 480x480 growing URFDLB net
    right = np.full((480, 480, 3), 26, dtype=np.uint8)
    cv2.putText(right, "Growing Cube Map (URFDLB)", (20, 32), cv2.FONT_HERSHEY_SIMPLEX, 0.68, (235, 235, 235), 1, cv2.LINE_AA)
    cell, gap, ox, oy = 30, 10, 32, 66
    face_px = cell * 3
    conflicted_set = set(snap.conflicted_slots)
    for f in FACES:
        gx, gy = NET_POS[f]
        fx0 = ox + gx * (face_px + gap)
        fy0 = oy + gy * (face_px + gap)
        for idx in range(9):
            r, c = divmod(idx, 3)
            x0, y0 = fx0 + c * cell, fy0 + r * cell
            col_name = snap.net[f][idx]
            fill = NET_BGR[col_name] if col_name is not None else (52, 52, 58)
            cv2.rectangle(right, (x0 + 2, y0 + 2), (x0 + cell - 2, y0 + cell - 2), fill, -1)
            border = (0, 165, 255) if (f, idx) in conflicted_set else (22, 22, 26)
            cv2.rectangle(right, (x0 + 2, y0 + 2), (x0 + cell - 2, y0 + cell - 2), border, 2 if (f, idx) in conflicted_set else 1)
        cv2.putText(right, f, (fx0 + face_px // 2 - 6, fy0 + face_px // 2 + 5), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (15, 15, 18), 2)

    lines = [
        f"Committed: {snap.committed_count}/54 | Anchored: {'YES' if snap.global_anchored else 'WAIT'}",
        f"Conflicts reopened: {len(snap.conflicted_slots)} | Invariants broken: {snap.invariant_count}",
        f"Pending regrip faces: {snap.pending_fragment_faces} | Twist: {'DETECTED' if snap.twist_detected else 'none'}",
    ]
    for k, txt in enumerate(lines):
        cv2.putText(right, txt, (20, 392 + k * 26), cv2.FONT_HERSHEY_SIMPLEX, 0.52, (215, 220, 230), 1, cv2.LINE_AA)
    return np.concatenate([left, right], axis=1)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--weights", type=Path, required=True, help="Path to pinned yush-io/cubeSolver best.pt")
    parser.add_argument("--video", type=Path, default=None, help="Input MP4/MOV clip")
    parser.add_argument("--webcam", type=int, default=None, help="Webcam index (e.g. 0)")
    parser.add_argument("--stride", type=int, default=2, help="Process every Nth frame from video file")
    parser.add_argument("--max-frames", type=int, default=300, help="Max processed frames")
    parser.add_argument("--conf", type=float, default=0.25, help="Sticker detector confidence threshold")
    parser.add_argument("--crop", type=str, default=None, help="Optional normalized ROI crop x0,y0,x1,y1 (e.g. 0,0,0.48,1)")
    parser.add_argument("--out-dir", type=Path, default=None, help="Write replay MP4 and contact sheet here")
    parser.add_argument("--show", action="store_true", help="Display live interactive OpenCV window (q=quit, r=reset)")
    args = parser.parse_args()

    sha = hashlib.sha256(args.weights.read_bytes()).hexdigest()
    if sha != CUBESOLVER_SHA:
        raise ValueError(f"Unexpected weights SHA-256 {sha}; expected pinned yush-io/cubeSolver {CUBESOLVER_SHA}")

    from ultralytics import YOLO

    model = YOLO(str(args.weights))
    src = str(args.video) if args.video is not None else (args.webcam if args.webcam is not None else 0)
    cap = cv2.VideoCapture(src)
    if not cap.isOpened():
        raise RuntimeError(f"Could not open video source {src!r}")

    mapper = ContinuousCubeMapper()
    writer = None
    if args.out_dir is not None:
        args.out_dir.mkdir(parents=True, exist_ok=True)
        fourcc = cv2.VideoWriter_fourcc(*"mp4v")
        stem = args.video.stem if args.video else f"webcam_{src}"
        writer = cv2.VideoWriter(str(args.out_dir / f"{stem}_replay.mp4"), fourcc, 15.0, (1120, 480))

    title = args.video.name if args.video else f"webcam:{src}"
    raw_idx = proc_idx = 0
    det_ms_list, map_ms_list = [], []
    quad_counts = []
    sheet_frames: List[np.ndarray] = []

    crop_box = None
    if args.crop:
        crop_box = tuple(float(v) for v in args.crop.split(","))
        if len(crop_box) != 4:
            raise ValueError("--crop expects x0,y0,x1,y1")

    while proc_idx < args.max_frames:
        ok, frame = cap.read()
        if not ok:
            break
        raw_idx += 1
        if args.video is not None and (raw_idx - 1) % max(1, args.stride) != 0:
            continue
        if crop_box is not None:
            fh, fw = frame.shape[:2]
            x0, y0, x1, y1 = int(crop_box[0] * fw), int(crop_box[1] * fh), int(crop_box[2] * fw), int(crop_box[3] * fh)
            frame = frame[y0:y1, x0:x1].copy()

        t0 = time.perf_counter()
        res = model.predict(frame, conf=args.conf, iou=0.35, verbose=False, device="cpu")[0]
        centers = [(float(x), float(y)) for x, y, _, _ in (res.boxes.xywh.cpu().numpy() if res.boxes is not None else [])]
        fitted = fit_face_quads_from_centers(centers)
        det_ms_list.append((time.perf_counter() - t0) * 1000.0)

        t1 = time.perf_counter()
        snap = mapper.process_frame(proc_idx, frame, [f.quad for f in fitted])
        map_ms_list.append((time.perf_counter() - t1) * 1000.0)
        quad_counts.append(len(fitted))

        vis = render_real_frame(frame, proc_idx, centers, snap, title)
        if writer is not None:
            writer.write(vis)
        if args.out_dir is not None and (len(fitted) > 0 or proc_idx % 20 == 0):
            sheet_frames.append(vis)

        if args.show:
            cv2.imshow("Cubentia Continuous Scan Spike", vis)
            key = cv2.waitKey(1) & 0xFF
            if key == ord("q"):
                break
            if key == ord("r"):
                mapper = ContinuousCubeMapper()
        proc_idx += 1

    cap.release()
    if writer is not None:
        writer.release()
    if args.show:
        cv2.destroyAllWindows()

    if args.out_dir is not None and sheet_frames:
        picks = [sheet_frames[i] for i in np.linspace(0, len(sheet_frames) - 1, min(6, len(sheet_frames)), dtype=int)]
        tiles = [cv2.resize(v, (560, 240)) for v in picks]
        while len(tiles) < 6:
            tiles.append(np.zeros_like(tiles[0]))
        grid = np.vstack([np.hstack(tiles[0:2]), np.hstack(tiles[2:4]), np.hstack(tiles[4:6])])
        stem = args.video.stem if args.video else f"webcam_{src}"
        cv2.imwrite(str(args.out_dir / f"{stem}_sheet.jpg"), grid, [int(cv2.IMWRITE_JPEG_QUALITY), 90])

    print(
        f"Processed {proc_idx} frames | det+lattice median={np.median(det_ms_list):.1f} ms | "
        f"mapper median={np.median(map_ms_list):.1f} ms | frames with >=1 quad={sum(c>0 for c in quad_counts)}/{proc_idx} "
        f"(>=2 quads={sum(c>=2 for c in quad_counts)}, 3 quads={sum(c==3 for c in quad_counts)}) | "
        f"final committed={snap.committed_count if proc_idx else 0}/54"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
