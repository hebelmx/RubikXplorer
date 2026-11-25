"""Run the continuous-scan mapper on a real recorded video clip or live PC webcam using either:
  - Cubentia's clean-room LiteRT sticker-center heatmap (`--litert-model sticker_centers.tflite`), OR
  - the read-only PC comparator `yush-io/cubeSolver` (`--weights /tmp/cubesolver_best.pt`).

Pipeline: detector -> lattice_fit.py (unordered 2D sticker centers -> 1-3 projective face quads) -> mapper.py.
"""

from __future__ import annotations

import argparse
import hashlib
import sys
import time
from pathlib import Path
from typing import Callable, List, Optional, Tuple

import cv2
import numpy as np

from cube_model import FACES
from lattice_fit import fit_face_quads_from_centers
from mapper import ContinuousCubeMapper, MapperSnapshot
from run_spike import NET_BGR, NET_POS

CUBESOLVER_SHA = "68fd20b050a75f3268385e33afc8a9484ba498259e5a5b0e97914f28e2c1a739"


def render_real_frame(
    frame_bgr: np.ndarray,
    frame_idx: int,
    centers: List[Tuple[float, float]],
    snap: MapperSnapshot,
    title: str,
    observed_faces_count: int = 0,
    fps: float = 0.0,
    recording: bool = False,
    exp_label: str = "",
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
    rec_tag = " [REC]" if recording else ""
    fps_tag = f" | {fps:.0f}fps" if fps > 0 else ""
    exp_tag = f" | {exp_label}" if exp_label else ""
    cv2.putText(
        left,
        f"{title} | f{frame_idx:04d} | pts={len(centers)} q={len(snap.tracked_quads)}{fps_tag}{exp_tag}{rec_tag}",
        (10, 22),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.46,
        (90, 90, 255) if recording else (230, 230, 230),
        1,
        cv2.LINE_AA,
    )
    cv2.rectangle(left, (0, 444), (640, 480), (24, 24, 30), -1)
    cue_col = (80, 120, 255) if snap.twist_detected else (90, 235, 130) if snap.committed_count == 54 else (240, 220, 100)
    cv2.putText(left, f"Cue: {snap.cue}", (10, 468), cv2.FONT_HERSHEY_SIMPLEX, 0.50, cue_col, 1, cv2.LINE_AA)

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
        f"Sides mapped: {observed_faces_count}/6 | Committed: {snap.committed_count}/54 | Anchor: {'YES' if snap.global_anchored else 'WAIT'}",
        f"Conflicts: {len(snap.conflicted_slots)} | Pending regrip: {snap.pending_fragment_faces} | Twist: {'YES (press R)' if snap.twist_detected else 'none'}",
        "Keys: [R] Reset [V] Rec [-/+] Exp [A] AutoExp [Q] Quit",
    ]
    for k, txt in enumerate(lines):
        col = (150, 185, 220) if k == 2 else (215, 220, 230)
        cv2.putText(right, txt, (14, 392 + k * 26), cv2.FONT_HERSHEY_SIMPLEX, 0.44, col, 1, cv2.LINE_AA)
    return np.concatenate([left, right], axis=1)


def _build_detector(args) -> Callable[[np.ndarray], List[Tuple[float, float]]]:
    if args.litert_model is not None:
        sys.path.insert(0, str(Path(__file__).resolve().parent / "litert_center"))
        from eval_litert_center import LiteRtCenterDetector

        det = LiteRtCenterDetector(args.litert_model)

        def run_litert(frame_bgr: np.ndarray) -> List[Tuple[float, float]]:
            rgb = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)
            peaks, _ = det.detect_rgb(rgb, threshold=args.conf)
            # On tall/wide frames (e.g. 16:9 phone preview or portrait recording), letterboxing shrinks
            # a mid-sized cube to <1 heatmap cell per sticker; retry on the center square ROI (+1.4 ms).
            fh, fw = rgb.shape[:2]
            if len(peaks) < 6 and max(fh, fw) > 1.35 * min(fh, fw):
                side = min(fh, fw)
                x0, y0 = (fw - side) // 2, (fh - side) // 2
                sq_peaks, _ = det.detect_rgb(rgb[y0 : y0 + side, x0 : x0 + side], threshold=args.conf)
                if len(sq_peaks) > len(peaks):
                    return [(x + x0, y + y0) for x, y, _ in sq_peaks]
            return [(x, y) for x, y, _ in peaks]

        return run_litert

    if args.weights is None:
        raise ValueError("Provide either --litert-model or --weights")
    sha = hashlib.sha256(args.weights.read_bytes()).hexdigest()
    if sha != CUBESOLVER_SHA:
        raise ValueError(f"Unexpected weights SHA-256 {sha}; expected pinned yush-io/cubeSolver {CUBESOLVER_SHA}")

    from ultralytics import YOLO

    model = YOLO(str(args.weights))

    def run_yolo(frame_bgr: np.ndarray) -> List[Tuple[float, float]]:
        res = model.predict(frame_bgr, conf=args.conf, iou=0.35, verbose=False, device="cpu")[0]
        return [(float(x), float(y)) for x, y, _, _ in (res.boxes.xywh.cpu().numpy() if res.boxes is not None else [])]

    return run_yolo


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--litert-model", type=Path, default=None, help="Path to Cubentia LiteRT sticker_centers.tflite")
    parser.add_argument("--weights", type=Path, default=None, help="Path to pinned yush-io/cubeSolver best.pt")
    parser.add_argument("--video", type=Path, default=None, help="Input MP4/MOV clip")
    parser.add_argument("--webcam", type=int, default=None, help="Webcam index (e.g. 0)")
    parser.add_argument("--stride", type=int, default=2, help="Process every Nth frame from video file")
    parser.add_argument("--max-frames", type=int, default=None, help="Max processed frames (default: 300 for --video, unlimited for --webcam)")
    parser.add_argument("--conf", type=float, default=None, help="Sticker detector confidence threshold (default: 0.45 for LiteRT, 0.30 for YOLO)")
    parser.add_argument("--crop", type=str, default=None, help="Optional normalized ROI crop x0,y0,x1,y1 (e.g. 0,0,0.48,1)")
    parser.add_argument("--out-dir", type=Path, default=None, help="Write replay MP4 and contact sheet here")
    parser.add_argument("--show", action="store_true", help="Display live interactive OpenCV window (r=reset, v=record, q/Esc=quit)")
    args = parser.parse_args()
    if args.conf is None:
        args.conf = 0.45 if args.litert_model is not None else 0.30

    detect_centers = _build_detector(args)
    src = str(args.video) if args.video is not None else (args.webcam if args.webcam is not None else 0)
    cap = cv2.VideoCapture(src)
    if not cap.isOpened():
        raise RuntimeError(f"Could not open video source {src!r}")

    mapper = ContinuousCubeMapper()
    writer = None
    raw_writer = None
    live_rec_dir: Optional[Path] = None
    if args.out_dir is not None:
        args.out_dir.mkdir(parents=True, exist_ok=True)
        fourcc = cv2.VideoWriter_fourcc(*"mp4v")
        stem = args.video.stem if args.video else f"webcam_{src}"
        writer = cv2.VideoWriter(str(args.out_dir / f"{stem}_replay.mp4"), fourcc, 15.0, (1120, 480))

    title = args.video.name if args.video else f"webcam:{src}"
    win_name = "Cubentia Continuous Scan Spike"
    raw_idx = proc_idx = 0
    det_ms_list, map_ms_list = [], []
    quad_counts = []
    sheet_frames: List[np.ndarray] = []
    last_state_log = None
    prev_t = time.perf_counter()
    fps_ema = 0.0
    is_webcam = args.video is None
    manual_exp: Optional[float] = None
    exp_label = "Exp:Auto" if (is_webcam and args.show) else ""

    def _adjust_exposure(delta_dir: int) -> str:
        nonlocal manual_exp
        # Linux V4L2: 1 = manual mode, 3 = aperture priority auto
        cap.set(cv2.CAP_PROP_AUTO_EXPOSURE, 1)
        cur = manual_exp if manual_exp is not None else float(cap.get(cv2.CAP_PROP_EXPOSURE))
        if cur <= 0:
            # Log-EV webcam range (e.g. -13 .. -1)
            nxt = float(np.clip(cur + delta_dir, -13.0, -1.0))
        else:
            # Linear UVC exposure-time range (e.g. 3 .. 2500)
            factor = 1.35 if delta_dir > 0 else (1.0 / 1.35)
            nxt = float(np.clip(round(cur * factor), 3.0, 2500.0))
            if nxt == cur:
                nxt = float(np.clip(cur + delta_dir * 5.0, 3.0, 2500.0))
        cap.set(cv2.CAP_PROP_EXPOSURE, nxt)
        actual = float(cap.get(cv2.CAP_PROP_EXPOSURE))
        manual_exp = actual if np.isfinite(actual) else nxt
        lbl = f"Exp:{manual_exp:.0f}"
        print(f"[f{proc_idx:04d}] --- EXPOSURE -> {lbl} (req={nxt:.0f}) ---", flush=True)
        return lbl

    crop_box = None
    if args.crop:
        crop_box = tuple(float(v) for v in args.crop.split(","))
        if len(crop_box) != 4:
            raise ValueError("--crop expects x0,y0,x1,y1")

    max_frames = args.max_frames if args.max_frames is not None else (300 if args.video is not None else 0)
    while max_frames <= 0 or proc_idx < max_frames:
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
        centers = detect_centers(frame)
        fitted = fit_face_quads_from_centers(centers)
        det_ms_list.append((time.perf_counter() - t0) * 1000.0)

        t1 = time.perf_counter()
        snap = mapper.process_frame(proc_idx, frame, [f.quad for f in fitted])
        map_ms_list.append((time.perf_counter() - t1) * 1000.0)
        quad_counts.append(len(fitted))

        now = time.perf_counter()
        dt = max(1e-4, now - prev_t)
        prev_t = now
        fps_ema = (1.0 / dt) if fps_ema == 0.0 else (0.85 * fps_ema + 0.15 / dt)
        obs_faces = len(mapper.master.confirmed_faces()) if mapper.master is not None else 0

        vis = render_real_frame(
            frame,
            proc_idx,
            centers,
            snap,
            title,
            observed_faces_count=obs_faces,
            fps=fps_ema if args.show else 0.0,
            recording=(raw_writer is not None),
            exp_label=exp_label,
        )
        if writer is not None:
            writer.write(vis)
        if raw_writer is not None:
            raw_writer.write(frame)
        if args.out_dir is not None and (len(fitted) > 0 or proc_idx % 20 == 0):
            sheet_frames.append(vis)

        if args.show:
            state_sig = (obs_faces, snap.committed_count, snap.global_anchored, snap.twist_detected, snap.pending_fragment_faces)
            if state_sig != last_state_log:
                print(
                    f"[f{proc_idx:04d}] sides={obs_faces}/6 committed={snap.committed_count}/54 "
                    f"anchored={snap.global_anchored} pending={snap.pending_fragment_faces} "
                    f"twist={snap.twist_detected} | cue: {snap.cue}",
                    flush=True,
                )
                last_state_log = state_sig

            cv2.imshow(win_name, vis)
            key = cv2.waitKey(1) & 0xFF
            if key in (ord("q"), ord("Q"), 27):
                break
            if key in (ord("r"), ord("R")):
                mapper = ContinuousCubeMapper()
                last_state_log = None
                print(f"[f{proc_idx:04d}] --- SCAN RESET ---", flush=True)
            if is_webcam and key in (ord("-"), ord("_"), ord("[")):
                exp_label = _adjust_exposure(-1)
            if is_webcam and key in (ord("="), ord("+"), ord("]")):
                exp_label = _adjust_exposure(+1)
            if is_webcam and key in (ord("a"), ord("A")):
                cap.set(cv2.CAP_PROP_AUTO_EXPOSURE, 3)
                manual_exp = None
                exp_label = "Exp:Auto"
                print(f"[f{proc_idx:04d}] --- EXPOSURE -> Auto ---", flush=True)
            if key in (ord("v"), ord("V")):
                if raw_writer is None:
                    ts = time.strftime("%Y%m%d-%H%M%S")
                    live_rec_dir = Path("/tmp/cubentia_live") / ts
                    live_rec_dir.mkdir(parents=True, exist_ok=True)
                    fourcc = cv2.VideoWriter_fourcc(*"mp4v")
                    fh, fw = frame.shape[:2]
                    raw_writer = cv2.VideoWriter(str(live_rec_dir / "raw.mp4"), fourcc, 15.0, (fw, fh))
                    if writer is None:
                        writer = cv2.VideoWriter(str(live_rec_dir / "hud.mp4"), fourcc, 15.0, (1120, 480))
                    print(f"[f{proc_idx:04d}] --- RECORDING STARTED -> {live_rec_dir} ---", flush=True)
                else:
                    raw_writer.release()
                    raw_writer = None
                    if args.out_dir is None and writer is not None:
                        writer.release()
                        writer = None
                    print(f"[f{proc_idx:04d}] --- RECORDING SAVED -> {live_rec_dir} ---", flush=True)
            if cv2.getWindowProperty(win_name, cv2.WND_PROP_VISIBLE) < 1:
                break
        proc_idx += 1

    cap.release()
    if writer is not None:
        writer.release()
    if raw_writer is not None:
        raw_writer.release()
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
