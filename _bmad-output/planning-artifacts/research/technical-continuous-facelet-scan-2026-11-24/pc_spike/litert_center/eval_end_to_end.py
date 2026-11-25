"""End-to-end PC benchmark of Cubentia's clean-room LiteRT sticker-center model (`sticker_centers.tflite`):
  1. Per-sticker recall/precision + lattice_fit 3-face recovery on real Cubentia tuning Fixtures/corner.
  2. Real Fixtures/live tuning frontal recall + 73 negative crops.
  3. Full pixels -> LiteRT -> lattice_fit -> mapper continuous-scan run across all 7 stress scenarios
     (ZERO simulated detections — 100% end-to-end from rendered RGB pixels!).
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path
from typing import List, Optional

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import cv2
import numpy as np

REPO_ROOT = Path(__file__).resolve().parents[6]
SPIKE_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SPIKE_DIR))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from eval_litert_center import LiteRtCenterDetector, evaluate_on_fixtures  # noqa: E402
from lattice_fit import fit_face_quads_from_centers  # noqa: E402
from mapper import ContinuousCubeMapper, _shared_edge  # noqa: E402
from run_spike import build_scenarios, score_net_exact  # noqa: E402
from synth_heatmap_data import load_heldout_union  # noqa: E402
from synth_video import generate_sequence  # noqa: E402


def eval_real_lattice_recovery(det: LiteRtCenterDetector, split: str, thresh: float, vis_dir: Optional[Path]) -> None:
    held = load_heldout_union()
    tot_photos = tot_faces = tot_3face = tot_shared = 0
    fit_ms: List[float] = []
    det_ms: List[float] = []
    saved = 0
    print(f"\n=== LiteRT -> lattice_fit 3-face recovery on real Fixtures/corner ({split}, thresh={thresh}) ===")
    for d in sorted(p for p in (REPO_ROOT / "Fixtures/corner").iterdir() if p.is_dir()):
        in_held = f"corner/{d.name}" in held
        if split == "tuning" and in_held:
            continue
        if split == "heldout" and not in_held:
            continue
        for jpath in sorted(d.glob("corner-*.json")):
            rec = json.loads(jpath.read_text())
            if not rec.get("detectorKeypoints"):
                continue
            img_bgr = cv2.imread(str(d / rec["frame"]))
            img_rgb = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB)
            peaks, d_ms = det.detect_rgb(img_rgb, threshold=thresh)
            det_ms.append(d_ms)
            centers = [(x, y) for x, y, _ in peaks]
            t0 = time.perf_counter()
            fitted = fit_face_quads_from_centers(centers, max_faces=3, min_inliers=6)
            fit_ms.append((time.perf_counter() - t0) * 1000.0)
            shared = sum(
                1
                for i in range(len(fitted))
                for j in range(i + 1, len(fitted))
                if _shared_edge(fitted[i].quad.corners, fitted[j].quad.corners) is not None
            )
            tot_photos += 1
            tot_faces += len(fitted)
            tot_3face += int(len(fitted) == 3)
            tot_shared += shared
            print(f"  {d.name}/{jpath.stem:8s}: peaks={len(centers):2d} -> quads={len(fitted)}/3 shared_edges={shared}")
            if vis_dir is not None and saved < 4:
                vis_dir.mkdir(parents=True, exist_ok=True)
                vis = cv2.resize(img_bgr, (600, 600), interpolation=cv2.INTER_NEAREST)
                sv = 600.0 / img_bgr.shape[1]
                for cx, cy in centers:
                    cv2.circle(vis, (int(cx * sv), int(cy * sv)), 4, (255, 0, 255), -1)
                cols = [(0, 255, 0), (0, 220, 255), (255, 180, 0)]
                for k, ff in enumerate(fitted):
                    cv2.polylines(vis, [np.rint(ff.quad.corners * sv).astype(np.int32)], True, cols[k % 3], 2)
                cv2.imwrite(str(vis_dir / f"litert_fitted_{split}_{saved}_{d.name}_{jpath.stem}.jpg"), vis)
                saved += 1
    if tot_photos:
        print(
            f"SUMMARY ({split}): {tot_photos} photos -> {tot_faces}/{tot_photos*3} faces ({100*tot_faces/(tot_photos*3):.1f}%), "
            f"3-face={tot_3face}/{tot_photos}, shared_edges={tot_shared} | "
            f"LiteRT median={np.median(det_ms):.1f} ms, lattice_fit median={np.median(fit_ms):.1f} ms"
        )


def eval_end_to_end_continuous(det: LiteRtCenterDetector, thresh: float) -> None:
    print(f"\n=== END-TO-END PIXELS -> LiteRT -> lattice_fit -> mapper (ALL 7 STRESS SCENARIOS, thresh={thresh}) ===")
    print(
        f"{'Scenario':36s} {'Pass':>4s} {'Final':>7s} {'Wrong':>5s} {'TransW':>6s} "
        f"{'54Frm':>6s} {'Merge':>5s} {'Twist':>5s} {'DetMs':>6s} {'MapMs':>6s}"
    )
    print("-" * 104)
    passed_n = 0
    scenarios = build_scenarios()
    for cfg in scenarios:
        frames = generate_sequence(cfg)
        mapper = ContinuousCubeMapper()
        max_tw = 0
        f54 = None
        d_ms_all, m_ms_all = [], []
        last_snap = None
        for fr in frames:
            rgb = cv2.cvtColor(fr.image_bgr, cv2.COLOR_BGR2RGB)
            peaks, d_ms = det.detect_rgb(rgb, threshold=thresh)
            fitted = fit_face_quads_from_centers([(x, y) for x, y, _ in peaks], max_faces=3, min_inliers=6)
            d_ms_all.append(d_ms)
            t0 = time.perf_counter()
            snap = mapper.process_frame(fr.frame_idx, fr.image_bgr, [f.quad for f in fitted])
            m_ms_all.append((time.perf_counter() - t0) * 1000.0)
            last_snap = snap
            _, corr, wrong = score_net_exact(snap.net, cfg.state)
            if not cfg.twist_at_frame:
                max_tw = max(max_tw, wrong)
                if corr == 54 and wrong == 0 and f54 is None:
                    f54 = fr.frame_idx
        assert last_snap is not None
        comm, corr, wrong = score_net_exact(last_snap.net, cfg.state)
        ok = (
            last_snap.twist_detected and wrong == 0
            if cfg.twist_at_frame is not None
            else (corr == 54 and wrong == 0 and max_tw == 0)
        )
        passed_n += int(ok)
        print(
            f"{cfg.name:36s} {'PASS' if ok else 'FAIL':>4s} {corr:2d}/{comm:<2d}  {wrong:5d} {max_tw:6d} "
            f"{str(f54 or '-'):>6s} {len(mapper.reappearance_events):5d} "
            f"{'YES' if last_snap.twist_detected else '-':>5s} {np.median(d_ms_all):6.1f} {np.median(m_ms_all):6.1f}"
        )
    print(f"\nEnd-to-end LiteRT continuous summary: {passed_n}/{len(scenarios)} passed.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--split", choices=("tuning", "heldout"), default="tuning")
    parser.add_argument("--thresh", type=float, default=0.35)
    parser.add_argument("--vis-dir", type=Path, default=None)
    parser.add_argument("--skip-continuous", action="store_true")
    args = parser.parse_args()

    det = LiteRtCenterDetector(args.model)
    evaluate_on_fixtures(det, split=args.split, threshold=args.thresh, vis_dir=args.vis_dir)
    eval_real_lattice_recovery(det, split=args.split, thresh=args.thresh, vis_dir=args.vis_dir)
    if not args.skip_continuous:
        eval_end_to_end_continuous(det, thresh=args.thresh)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
