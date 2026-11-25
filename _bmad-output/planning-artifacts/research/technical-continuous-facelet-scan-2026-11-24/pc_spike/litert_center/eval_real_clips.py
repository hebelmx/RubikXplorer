"""Head-to-head real-video benchmark for Cubentia's LiteRT sticker-center models (v1 vs v2-FP16 vs v2-INT8).

Reads the frozen train/heldout split from `captures/continuous-scan/manifest.json` and reports
tuning vs. strictly untouched held-out metrics side by side, plus optional visual comparison sheets.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path
from typing import Dict, List, Optional

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import cv2
import numpy as np

SPIKE_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SPIKE_DIR))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from eval_litert_center import LiteRtCenterDetector  # noqa: E402
from lattice_fit import fit_face_quads_from_centers  # noqa: E402
from mapper import ContinuousCubeMapper  # noqa: E402
from run_real_video import render_real_frame  # noqa: E402


def eval_clips(
    model_path: Path,
    captures_dir: Path,
    split: str = "all",
    thresh: float = 0.45,
    stride: int = 1,
    vis_dir: Optional[Path] = None,
    tag: str = "model",
) -> List[Dict]:
    mp = captures_dir / "manifest.json"
    manifest = json.loads(mp.read_text())
    det = LiteRtCenterDetector(model_path)
    model_kb = model_path.stat().st_size / 1024.0

    print(
        f"\n=== Real-Video Benchmark: {tag} ({model_path.name}, {model_kb:.1f} KB, "
        f"{det.img_size}x{det.img_size}->{det.hm_size}x{det.hm_size}, thresh={thresh}, split={split}) ==="
    )
    print(
        f"{'Session':26s} {'Split':7s} {'Frm':>4s} {'Q>=1%':>6s} {'Q>=2%':>6s} "
        f"{'Sides':>5s} {'Comm':>5s} {'Conf':>4s} {'Twist':>5s} {'Merge':>5s} {'DetMs':>6s}"
    )
    print("-" * 100)

    rows: List[Dict] = []
    for s in manifest["sessions"]:
        if split != "all" and s["split"] != split:
            continue
        cap = cv2.VideoCapture(str(captures_dir / s["id"] / "raw.mp4"))
        mapper = ContinuousCubeMapper()
        q_ge1 = q_ge2 = q_3 = n_proc = raw_idx = 0
        dms: List[float] = []
        vis_samples: List[np.ndarray] = []

        while True:
            ok, fr = cap.read()
            if not ok:
                break
            raw_idx += 1
            if (raw_idx - 1) % max(1, stride) != 0:
                continue
            peaks, dt = det.detect_rgb(cv2.cvtColor(fr, cv2.COLOR_BGR2RGB), threshold=thresh)
            dms.append(dt)
            centers = [(x, y) for x, y, _ in peaks]
            fitted = fit_face_quads_from_centers(centers)
            if len(fitted) >= 1:
                q_ge1 += 1
            if len(fitted) >= 2:
                q_ge2 += 1
            if len(fitted) == 3:
                q_3 += 1
            snap = mapper.process_frame(n_proc, fr, [f.quad for f in fitted])
            if vis_dir is not None and (len(fitted) >= 2 or (n_proc % 40 == 0 and len(fitted) >= 1)):
                obs_now = len(mapper.master.confirmed_faces()) if mapper.master else 0
                vis_samples.append(render_real_frame(fr, n_proc, centers, snap, f"{tag}:{s['id']}", observed_faces_count=obs_now))
            n_proc += 1
        cap.release()

        obs = len(mapper.master.confirmed_faces()) if mapper.master else 0
        row = {
            "id": s["id"],
            "split": s["split"],
            "cube": s["cube"],
            "light": s["light"],
            "has_twist": bool(s.get("has_twist", False)),
            "frames": n_proc,
            "q_ge1": q_ge1,
            "q_ge2": q_ge2,
            "q_3": q_3,
            "q_ge1_pct": round(100.0 * q_ge1 / max(1, n_proc), 1),
            "q_ge2_pct": round(100.0 * q_ge2 / max(1, n_proc), 1),
            "sides": obs,
            "committed": snap.committed_count if n_proc else 0,
            "anchored": snap.global_anchored if n_proc else False,
            "conflicts": len(snap.conflicted_slots) if n_proc else 0,
            "twist": snap.twist_detected if n_proc else False,
            "merges": len(mapper.reappearance_events),
            "det_ms": round(float(np.median(dms)), 2) if dms else 0.0,
        }
        rows.append(row)
        print(
            f"{row['id']:26s} {row['split']:7s} {n_proc:4d} {row['q_ge1_pct']:5.1f}% {row['q_ge2_pct']:5.1f}% "
            f"{obs:3d}/6 {row['committed']:2d}/54 {row['conflicts']:4d} {'YES' if row['twist'] else '-':>5s} "
            f"{row['merges']:5d} {row['det_ms']:6.2f}"
        )

        if vis_dir is not None and vis_samples:
            vis_dir.mkdir(parents=True, exist_ok=True)
            picks = [vis_samples[i] for i in np.linspace(0, len(vis_samples) - 1, min(6, len(vis_samples)), dtype=int)]
            tiles = [cv2.resize(v, (560, 240)) for v in picks]
            while len(tiles) < 6:
                tiles.append(np.zeros_like(tiles[0]))
            grid = np.vstack([np.hstack(tiles[0:2]), np.hstack(tiles[2:4]), np.hstack(tiles[4:6])])
            cv2.imwrite(str(vis_dir / f"{tag}_{s['id']}_sheet.jpg"), grid, [int(cv2.IMWRITE_JPEG_QUALITY), 88])

    for grp in ("tuning", "heldout"):
        sub = [r for r in rows if r["split"] == grp]
        if not sub:
            continue
        mean_q1 = float(np.mean([r["q_ge1_pct"] for r in sub]))
        mean_q2 = float(np.mean([r["q_ge2_pct"] for r in sub]))
        mean_sides = float(np.mean([r["sides"] for r in sub]))
        med_ms = float(np.median([r["det_ms"] for r in sub]))
        print(
            f"SUMMARY [{grp:7s}] (n={len(sub):2d}): mean Q>=1={mean_q1:5.1f}% | mean Q>=2={mean_q2:5.1f}% | "
            f"mean sides={mean_sides:.2f}/6 | median det={med_ms:.2f} ms"
        )
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--captures-dir", type=Path, default=Path("/mnt/e/Dynamic/IndFun/RubikXplorer/captures/continuous-scan"))
    parser.add_argument("--split", choices=("tuning", "heldout", "all"), default="all")
    parser.add_argument("--thresh", type=float, default=0.45)
    parser.add_argument("--stride", type=int, default=1)
    parser.add_argument("--tag", type=str, default="v2")
    parser.add_argument("--vis-dir", type=Path, default=None)
    parser.add_argument("--out-json", type=Path, default=None)
    args = parser.parse_args()

    rows = eval_clips(
        model_path=args.model,
        captures_dir=args.captures_dir,
        split=args.split,
        thresh=args.thresh,
        stride=args.stride,
        vis_dir=args.vis_dir,
        tag=args.tag,
    )
    if args.out_json is not None:
        args.out_json.parent.mkdir(parents=True, exist_ok=True)
        args.out_json.write_text(json.dumps(rows, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
