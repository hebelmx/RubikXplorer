"""Export the GitHub #411 part (b) deviation parity sessions (synthetic) from pc_spike/mapper.py.

Part (a) (`pc_spike/export_parity_sessions.py`) exports three run_spike scenarios on which none of the six named
deviations of ContinuousFaceletMapper changes a compared field. This exporter adds one session per deviation, built so
that the deviation does change at least one compared field (net, globalAnchored, twistFrame, conflictedSlots, cue,
outcome). Each scenario is a pc_spike synth_video.py ScenarioConfig (from run_spike.build_scenarios(), with the changes
named below) whose rendered frames may get a deterministic synthetic post-processing step before mapper.py sees them.
The session format, the observation sampling and the snapshot mapping are part (a)'s `export_scenario`, unchanged; only
its module-level `generate_sequence` is substituted so the post-processing applies. Synthetic frames only, fixed seeds,
no random post-processing: with the same opencv-python-headless / numpy versions (5.1.0 / 2.5.5) a re-run is
byte-identical except `buildCommit`.

Scenarios (session id: base scenario and change -> the deviation it targets):

- pyspike-d1-early-twist-u-v1: 7_mid_scan_layer_twist_U with the U twist at frame 32 instead of 55, soon after F, U
  and R are first seen -> D1. mapper.py records the twisted cells while its twist streak builds, the short history
  absorbs them and the twist is never detected; Kotlin drops a line-twisted face's samples and detects it.
- pyspike-d2-strong-cast-v1: 1_scrambled_stickered_regrip without the regrip, every frame multiplied by per-channel
  gains that ramp from 1 to exp(0, -0.9, -1.2) (R, G, B) over the session -> D2. The zero-mean log-AWB passes the 0.36
  bound with two channels at once, where per-channel clipping and joint scaling differ.
- pyspike-d3-regrip-cast-drift-v1: 4_mid_solve_f2l_symmetric_regrip on the pastel palette, with gains ramping from 1 to
  exp(0, -0.6, -0.9) over frames 68-88, while the post-regrip pending fragment is alive (it merges at frame 87) -> D3.
  Kotlin adds the pending fragment's log-AWB into master's at the merge; mapper.py keeps master's.
- pyspike-d4-pastel-strong-cast-v1: 2_pastel_stickerless_awb_drift with its own drift off and the D2 gain ramp instead
  -> D4. Under the strong cast the raw centre Lab of a returning face is far from its AWB-compensated history.
- pyspike-i401-skin-occluder-v1: 1_scrambled_stickered_regrip without the regrip, a uniform skin-tone patch (RGB 150,
  100, 80) painted over cells 6 and 7 (bottom line) of the true F face on frames 14-21 -> I401. mapper.py counts the
  covered line as flipped for five frames and reports a layer twist that never happened.
- pyspike-i402-long-hold-v1: 1_scrambled_stickered_regrip without the regrip, seed 4020, 525 frames: a tour of every face
  (frames 0-199), a 275-frame hold on the U-F-R corner (frames 200-474) with a uniform magenta patch (RGB 200, 40, 200)
  over cell 1 of the true F face, then F frontal without the patch -> I402. F's per-cell lists pass 256 observations,
  so the capped lists turn back to the true colour at other frames than mapper.py's unbounded ones.

The committed fixtures were exported with opencv-python-headless 5.1.0 and numpy 2.5.5 (unpinned, as for part (a)).

Usage (from the repository root; never install packages system-wide):
    uv run --no-project --with opencv-python-headless --with numpy python tools/mapper-parity/export_deviation_sessions.py [--out DIR]
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import replace
from pathlib import Path
from typing import Callable, List, Optional, Sequence, Tuple

REPO = Path(__file__).resolve().parents[2]
PC_SPIKE = REPO / "_bmad-output" / "planning-artifacts" / "research" / "technical-continuous-facelet-scan-2026-11-24" / "pc_spike"
sys.path.insert(0, str(PC_SPIKE))

import cv2  # noqa: E402
import numpy as np  # noqa: E402

import export_parity_sessions  # noqa: E402
from export_parity_sessions import build_commit, export_scenario  # noqa: E402
from run_spike import build_scenarios  # noqa: E402
from synth_video import ScenarioConfig, SyntheticFrame  # noqa: E402
from synth_video import generate_sequence as synth_generate_sequence  # noqa: E402

PostProcess = Callable[[List[SyntheticFrame]], List[SyntheticFrame]]
TAU = 2.0 * np.pi


def cast_ramp(target_log_rgb: Tuple[float, float, float], start: int = 0, end: Optional[int] = None) -> PostProcess:
    """Multiply every frame by per-channel gains exp(phase * target) (R, G, B); phase ramps 0 -> 1 over [start, end]."""

    def post(frames: List[SyntheticFrame]) -> List[SyntheticFrame]:
        last = (len(frames) - 1) if end is None else end
        target = np.array(target_log_rgb, dtype=np.float64)
        for fr in frames:
            phase = 0.0 if fr.frame_idx < start else min(1.0, (fr.frame_idx - start) / max(1, last - start))
            gains_bgr = np.exp(target * phase)[::-1].astype(np.float32)
            scaled = fr.image_bgr.astype(np.float32) * gains_bgr[None, None, :]
            fr.image_bgr = np.clip(np.rint(scaled), 0, 255).astype(np.uint8)
        return frames

    return post


def cell_patch(face: str, cells: Sequence[int], first: int, count: int, rgb: Tuple[int, int, int]) -> PostProcess:
    """Paint a uniform `rgb` patch over `cells` of the true `face` (generator geometry) on frames first..first+count-1."""
    unit = np.array([[0, 0], [1, 0], [1, 1], [0, 1]], dtype=np.float32)
    bgr = (int(rgb[2]), int(rgb[1]), int(rgb[0]))

    def post(frames: List[SyntheticFrame]) -> List[SyntheticFrame]:
        for fr in frames:
            quad = fr.truth.visible_faces.get(face)
            if quad is None or not (first <= fr.frame_idx < first + count):
                continue
            homography = cv2.getPerspectiveTransform(unit, quad.astype(np.float32))
            for ci in cells:
                r, c = divmod(ci, 3)
                x0, x1, y0, y1 = c / 3 + 0.02, (c + 1) / 3 - 0.02, r / 3 + 0.02, (r + 1) / 3 - 0.02
                cell = np.array([[[x0, y0]], [[x1, y0]], [[x1, y1]], [[x0, y1]]], dtype=np.float32)
                pts = cv2.perspectiveTransform(cell, homography).reshape(-1, 2)
                cv2.fillConvexPoly(fr.image_bgr, np.rint(pts).astype(np.int32), bgr)
        return frames

    return post


def deviation_scenarios() -> List[Tuple[str, ScenarioConfig, Optional[PostProcess]]]:
    """(session index, config, post-processing) per deviation; see the module docstring."""
    base = {cfg.name: cfg for cfg in build_scenarios()}
    s1 = base["1_scrambled_stickered_regrip"]
    no_regrip = replace(s1, regrip_range=None, regrip_return_euler=None)
    strong_cast = (0.0, -0.9, -1.2)

    ufr = (0.65, -0.65, 0.05)
    tour = [
        ufr,
        (1.32, -0.10, 0.05),  # near-frontal U
        (0.55, -1.45, -0.05),  # near-frontal R
        (0.15, -3.10, -0.04),  # near-frontal B
        (-0.55, -3.75, 0.05),  # D-B-L corner
        (0.15, -4.68, -0.05),  # near-frontal L
        (-1.28, -4.75, 0.04),  # near-frontal D
        (-0.52, -5.65, 0.06),  # D-L-F corner
        (ufr[0], ufr[1] - TAU, ufr[2]),  # back to U-F-R
    ]
    hold_segments, frontal_segments, segment_frames = 11, 2, 25
    long_hold = tour + [(ufr[0], ufr[1] - TAU, ufr[2])] * hold_segments + [(0.18, -0.15 - TAU, 0.04)] * frontal_segments
    hold_first = (len(tour) - 1) * segment_frames

    return [
        ("d1", replace(base["7_mid_scan_layer_twist_U"], name="d1_early_twist_u", twist_at_frame=32), None),
        ("d2", replace(no_regrip, name="d2_strong_cast"), cast_ramp(strong_cast)),
        (
            "d3",
            replace(base["4_mid_solve_f2l_symmetric_regrip"], name="d3_regrip_cast_drift", palette="pastel"),
            cast_ramp((0.0, -0.6, -0.9), start=68, end=88),
        ),
        (
            "d4",
            replace(base["2_pastel_stickerless_awb_drift"], name="d4_pastel_strong_cast", awb_drift=False),
            cast_ramp(strong_cast),
        ),
        ("i401", replace(no_regrip, name="i401_skin_occluder"), cell_patch("F", (6, 7), 14, 8, (150, 100, 80))),
        (
            "i402",
            replace(no_regrip, name="i402_long_hold", waypoints=long_hold, n_frames=(len(long_hold) - 1) * segment_frames, seed=4020),
            cell_patch("F", (1,), hold_first, hold_segments * segment_frames, (200, 40, 200)),
        ),
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--out",
        type=Path,
        default=REPO / "Fixtures" / "continuous" / "mapper-parity",
        help="Output directory (default: the repo's Fixtures/continuous/mapper-parity)",
    )
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    commit = build_commit(REPO)
    for index, cfg, post in deviation_scenarios():

        def generate(c: ScenarioConfig, width: int, height: int, _post: Optional[PostProcess] = post) -> List[SyntheticFrame]:
            frames = synth_generate_sequence(c, width, height)
            return _post(frames) if _post is not None else frames

        export_parity_sessions.generate_sequence = generate
        target = export_scenario(index, cfg, args.out, commit)
        manifest = json.loads((target / "manifest.json").read_text(encoding="utf-8"))
        print(f"Wrote {target} ({manifest['outcome']}, {manifest['frameCount']} frames)")
    export_parity_sessions.generate_sequence = synth_generate_sequence
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
