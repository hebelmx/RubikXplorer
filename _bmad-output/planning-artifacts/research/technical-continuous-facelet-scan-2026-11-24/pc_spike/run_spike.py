"""Run the 7-scenario PC continuous-scan spike benchmark and render a side-by-side visual replay.

Usage:
    uv run --no-project --with opencv-python-headless --with numpy python run_spike.py [--render-video]
"""

from __future__ import annotations

import argparse
import time
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import cv2
import numpy as np

from cube_model import (
    CUBE_SO3_ROTATIONS,
    FACES,
    apply_scramble,
    rotate_cube_state,
    solved_cube,
)
from mapper import ContinuousCubeMapper, MapperSnapshot
from synth_video import ScenarioConfig, SyntheticFrame, generate_sequence

NET_BGR: Dict[str, Tuple[int, int, int]] = {
    "WHITE": (245, 245, 245),
    "YELLOW": (40, 225, 245),
    "GREEN": (70, 185, 50),
    "BLUE": (215, 95, 35),
    "RED": (45, 45, 220),
    "ORANGE": (30, 130, 245),
}

# 2D net layout in (grid_col, grid_row) of 3x3 face blocks:
#       [U]
#   [L] [F] [R] [B]
#       [D]
NET_POS: Dict[str, Tuple[int, int]] = {
    "U": (1, 0),
    "L": (0, 1),
    "F": (1, 1),
    "R": (2, 1),
    "B": (3, 1),
    "D": (1, 2),
}


def build_scenarios() -> List[ScenarioConfig]:
    scr1 = apply_scramble(["R", "U", "R'", "F'", "L2", "D", "B'", "U2", "R'", "F", "L", "D'"])
    scr2 = apply_scramble(["F", "R'", "D2", "B", "U'", "L'", "F2", "R", "U", "D'", "B2", "L"])
    # Mid-solve F2L solved: only U-layer (last layer) scrambled (OLL + PLL -> D is 100% solid yellow,
    # F/R/B/L bottom two rows are completely solved & symmetric!)
    f2l_solved = apply_scramble(["R", "U", "R'", "U", "R", "U2", "R'", "F", "R", "U", "R'", "U'", "F'"])

    return [
        ScenarioConfig(
            name="1_scrambled_stickered_regrip",
            state=scr1,
            palette="stickered",
            regrip_range=(52, 66),
            regrip_return_euler=(0.35, -2.6, 0.06),
            jitter_px=1.2,
            n_frames=130,
            seed=101,
        ),
        ScenarioConfig(
            name="2_pastel_stickerless_awb_drift",
            state=scr2,
            palette="pastel",
            stickerless=True,
            awb_drift=True,
            jitter_px=1.2,
            n_frames=120,
            seed=202,
        ),
        ScenarioConfig(
            name="3_logo_center_overhead_glare",
            state=scr1,
            palette="stickered",
            logo_on_white=True,
            glare_strength=0.95,
            jitter_px=1.0,
            n_frames=125,
            seed=303,
        ),
        ScenarioConfig(
            name="4_mid_solve_f2l_symmetric_regrip",
            state=f2l_solved,
            palette="stickerless",
            stickerless=True,
            # Regrip returns straight onto the 100% solid YELLOW bottom face (D), then tilts to an adjacent face!
            regrip_range=(55, 68),
            regrip_return_euler=(-1.55, 0.0, 0.0),
            jitter_px=1.0,
            n_frames=135,
            seed=404,
        ),
        ScenarioConfig(
            name="5_finger_occlusion_jitter_clutter",
            state=scr2,
            palette="stickered",
            finger_occlusion=True,
            jitter_px=2.2,
            drop_prob=0.10,
            false_quad_prob=0.25,
            motion_blur_prob=0.12,
            n_frames=140,
            seed=505,
        ),
        ScenarioConfig(
            name="6_start_no_white_yob_only",
            state=scr1,
            palette="stickerless",
            stickerless=True,
            # Start showing ONLY D-L-B (Yellow/Orange/Blue — NO WHITE!), then rotate around to F, U, R
            start_euler=(0.55, -3.75, 0.05),
            waypoints=[
                (0.55, -3.75, 0.05),   # D-L-B (Yellow/Orange/Blue — ZERO White!)
                (1.25, -3.14, 0.04),   # near-frontal D (from B side, still no white!)
                (0.15, -3.14, 0.04),   # near-frontal B
                (0.10, -4.68, -0.04),  # near-frontal L
                (0.15, -6.15, 0.04),   # near-frontal F
                (0.55, -5.60, 0.05),   # U-F-R
                (1.30, -6.20, 0.05),   # near-frontal U (White appears only near the end!)
                (0.12, -7.80, 0.03),   # near-frontal R
            ],
            jitter_px=1.0,
            n_frames=120,
            seed=606,
        ),
        ScenarioConfig(
            name="7_mid_scan_layer_twist_U",
            state=scr1,
            palette="stickered",
            twist_at_frame=55,
            twist_move="U",
            # Stay viewing F-U-R around the twist so the mapper actually sees the twisted layer!
            waypoints=[
                (0.48, -0.55, 0.05),
                (0.25, -0.20, 0.04),
                (0.55, -0.65, 0.05),
                (0.45, -0.55, 0.04),  # twist happens here while F-U-R is visible!
                (0.35, -0.45, 0.05),
                (0.55, -0.75, 0.04),
                (0.20, -1.55, 0.03),
            ],
            jitter_px=0.8,
            n_frames=95,
            seed=707,
        ),
    ]


def score_net_exact(pred_net: Dict[str, List[Optional[str]]], truth: Dict[str, List[str]]) -> Tuple[int, int, int]:
    """Return (committed, correct, wrong) against canonical truth (also checks SO(3)-rotated equivalence? No, canonical!)."""
    committed = correct = wrong = 0
    for f in FACES:
        for p, t in zip(pred_net[f], truth[f]):
            if p is not None:
                committed += 1
                if p == t:
                    correct += 1
                else:
                    wrong += 1
    return committed, correct, wrong


def render_side_by_side(frame: SyntheticFrame, snap: MapperSnapshot, scenario_name: str) -> np.ndarray:
    """Render 1120x480 side-by-side view: left = live camera + quads, right = growing 6-face net."""
    left = frame.image_bgr.copy()
    for label, quad in snap.tracked_quads.items():
        pts = np.rint(quad).astype(np.int32)
        color = (0, 230, 80) if not label.startswith("?") else (0, 200, 255)
        cv2.polylines(left, [pts], True, color, 2, cv2.LINE_AA)
        c = pts.mean(axis=0).astype(int)
        cv2.putText(left, label, (int(c[0]) - 10, int(c[1]) + 6), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 0, 0), 3)
        cv2.putText(left, label, (int(c[0]) - 10, int(c[1]) + 6), cv2.FONT_HERSHEY_SIMPLEX, 0.7, color, 2)

    # Top & bottom HUD bars on camera panel
    cv2.rectangle(left, (0, 0), (left.shape[1], 34), (20, 20, 24), -1)
    cv2.putText(
        left,
        f"{scenario_name} | frame {frame.frame_idx:03d} | t={frame.timestamp_ms/1000:.1f}s",
        (10, 23),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.55,
        (230, 230, 230),
        1,
        cv2.LINE_AA,
    )
    cv2.rectangle(left, (0, left.shape[0] - 36), (left.shape[1], left.shape[0]), (24, 24, 30), -1)
    cue_col = (80, 120, 255) if snap.twist_detected else (90, 235, 130) if snap.committed_count == 54 else (240, 220, 100)
    cv2.putText(left, f"Cue: {snap.cue}", (10, left.shape[0] - 12), cv2.FONT_HERSHEY_SIMPLEX, 0.55, cue_col, 1, cv2.LINE_AA)

    # Right panel: 480x480 dark canvas with 6-face URFDLB net
    right = np.full((480, 480, 3), 26, dtype=np.uint8)
    cv2.putText(right, "Growing Cube Map (URFDLB)", (20, 32), cv2.FONT_HERSHEY_SIMPLEX, 0.68, (235, 235, 235), 1, cv2.LINE_AA)
    cell = 30
    face_px = cell * 3
    gap = 10
    ox, oy = 32, 66
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
            thick = 2 if (f, idx) in conflicted_set else 1
            cv2.rectangle(right, (x0 + 2, y0 + 2), (x0 + cell - 2, y0 + cell - 2), border, thick)
        cv2.putText(right, f, (fx0 + face_px // 2 - 6, fy0 + face_px // 2 + 5), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (15, 15, 18), 2)

    status_y = 392
    lines = [
        f"Committed: {snap.committed_count}/54 | Anchored: {'YES' if snap.global_anchored else 'WAIT'}",
        f"Conflicts reopened: {len(snap.conflicted_slots)} | Invariants broken: {snap.invariant_count}",
        f"Pending regrip faces: {snap.pending_fragment_faces} | Twist: {'DETECTED' if snap.twist_detected else 'none'}",
    ]
    for k, txt in enumerate(lines):
        cv2.putText(right, txt, (20, status_y + k * 26), cv2.FONT_HERSHEY_SIMPLEX, 0.52, (215, 220, 230), 1, cv2.LINE_AA)

    return np.concatenate([left, right], axis=1)


def run_scenario(cfg: ScenarioConfig, render_out: Optional[Path] = None) -> dict:
    frames = generate_sequence(cfg)
    mapper = ContinuousCubeMapper()
    max_transient_wrong = 0
    first_commit_frame: Optional[int] = None
    exact_54_frame: Optional[int] = None
    step_ms: List[float] = []
    writer = None
    key_vis: List[np.ndarray] = []

    if render_out is not None:
        fourcc = cv2.VideoWriter_fourcc(*"mp4v")
        writer = cv2.VideoWriter(str(render_out / f"{cfg.name}.mp4"), fourcc, 24.0, (1120, 480))

    last_snap: Optional[MapperSnapshot] = None
    sample_indices = set(np.linspace(0, len(frames) - 1, 6, dtype=int).tolist())

    for fr in frames:
        t0 = time.perf_counter()
        snap = mapper.process_frame(fr.frame_idx, fr.image_bgr, fr.detections)
        step_ms.append((time.perf_counter() - t0) * 1000.0)
        last_snap = snap

        committed, correct, wrong = score_net_exact(snap.net, cfg.state)
        if committed > 0 and first_commit_frame is None:
            first_commit_frame = fr.frame_idx
        if not cfg.twist_at_frame:
            max_transient_wrong = max(max_transient_wrong, wrong)
            if correct == 54 and wrong == 0 and exact_54_frame is None:
                exact_54_frame = fr.frame_idx

        if writer is not None or fr.frame_idx in sample_indices:
            vis = render_side_by_side(fr, snap, cfg.name)
            if writer is not None:
                writer.write(vis)
            if render_out is not None and fr.frame_idx in sample_indices:
                key_vis.append(vis)

    if writer is not None:
        writer.release()

    if render_out is not None and key_vis:
        # 2x3 contact sheet
        tiles = [cv2.resize(v, (560, 240)) for v in key_vis[:6]]
        while len(tiles) < 6:
            tiles.append(np.zeros_like(tiles[0]))
        grid = np.vstack([np.hstack(tiles[0:2]), np.hstack(tiles[2:4]), np.hstack(tiles[4:6])])
        cv2.imwrite(str(render_out / f"{cfg.name}_sheet.jpg"), grid, [int(cv2.IMWRITE_JPEG_QUALITY), 90])

    assert last_snap is not None
    committed, correct, wrong = score_net_exact(last_snap.net, cfg.state)
    passed = (
        last_snap.twist_detected and wrong == 0
        if cfg.twist_at_frame is not None
        else (correct == 54 and wrong == 0 and max_transient_wrong == 0)
    )
    return {
        "name": cfg.name,
        "frames": len(frames),
        "committed": committed,
        "correct": correct,
        "wrong_final": wrong,
        "max_transient_wrong": max_transient_wrong,
        "first_commit_frame": first_commit_frame,
        "exact_54_frame": exact_54_frame,
        "reappear_merges": len(mapper.reappearance_events),
        "twist_detected": last_snap.twist_detected,
        "median_ms": float(np.median(step_ms)),
        "passed": passed,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--render-dir", type=Path, default=None, help="Directory to write MP4 replays & contact sheets")
    parser.add_argument("--only", type=str, default=None, help="Run only scenarios matching substring")
    args = parser.parse_args()

    if args.render_dir is not None:
        args.render_dir.mkdir(parents=True, exist_ok=True)

    scenarios = build_scenarios()
    if args.only:
        scenarios = [s for s in scenarios if args.only in s.name]

    print(
        f"{'Scenario':36s} {'Pass':>4s} {'Final':>7s} {'Wrong':>5s} {'TransW':>6s} "
        f"{'1stFrm':>6s} {'54Frm':>6s} {'Merge':>5s} {'Twist':>5s} {'MedMs':>6s}"
    )
    print("-" * 102)
    results = []
    for cfg in scenarios:
        res = run_scenario(cfg, render_out=args.render_dir)
        results.append(res)
        print(
            f"{res['name']:36s} {'PASS' if res['passed'] else 'FAIL':>4s} "
            f"{res['correct']:2d}/{res['committed']:<2d}  {res['wrong_final']:5d} {res['max_transient_wrong']:6d} "
            f"{str(res['first_commit_frame'] or '-'):>6s} {str(res['exact_54_frame'] or '-'):>6s} "
            f"{res['reappear_merges']:5d} {'YES' if res['twist_detected'] else '-':>5s} {res['median_ms']:6.2f}"
        )

    passed_n = sum(1 for r in results if r["passed"])
    print(f"\nSummary: {passed_n}/{len(results)} scenarios passed.")
    return 0 if passed_n == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
