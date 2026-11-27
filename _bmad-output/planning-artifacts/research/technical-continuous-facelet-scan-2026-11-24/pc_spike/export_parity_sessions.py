"""Export mapper.py golden parity sessions for GitHub #411 (part a, synthetic).

Renders three synth_video scenarios from run_spike.build_scenarios() (configs and seeds unchanged), feeds them to
mapper.py's ContinuousCubeMapper, and writes each as a schema-1 continuous session (Story 10.34 format) whose
frames.jsonl holds the per-quad cell samples mapper.py itself computes (neutral observations, raw corner order) and
whose snapshots.jsonl holds mapper.py's per-frame snapshots (Python predictions, not Kotlin goldens).

The Kotlin test MapperParityReplayTest replays each session through a fresh ContinuousFaceletMapper and requires the
per-frame differences on the compared fields to equal the committed expected-differences.json list exactly.

The committed fixtures were exported with opencv-python-headless 5.1.0 and numpy 2.5.5 (unpinned: another version can
change the cell samples; check with the same uv command and `python -c "import cv2, numpy"`).

Usage (from pc_spike/; never install packages system-wide):
    uv run --no-project --with opencv-python-headless --with numpy python export_parity_sessions.py [--out DIR]
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from pathlib import Path
from typing import Dict, List, Optional

import numpy as np

from mapper import ContinuousCubeMapper, MapperSnapshot, _rotate_face_name, _sample_face_cells
from run_spike import build_scenarios
from synth_video import ScenarioConfig, generate_sequence

# The run_spike scenarios exported (regrip and merge, AWB drift, mid-scan layer twist).
PARITY_SCENARIOS = (
    "1_scrambled_stickered_regrip",
    "2_pastel_stickerless_awb_drift",
    "7_mid_scan_layer_twist_U",
)

FRAME_INTERVAL_MS = 33
WIDTH, HEIGHT = 640, 480
# Schema-1 net order and colour characters.
NET_FACES = ("U", "R", "F", "D", "L", "B")
COLOR_CHAR = {"WHITE": "W", "YELLOW": "Y", "GREEN": "G", "BLUE": "B", "RED": "R", "ORANGE": "O"}

_CUE_PATTERNS = (
    (re.compile(r"^Layer turned mid-scan"), lambda m: {"type": "LayerTwistDetected"}),
    (re.compile(r"^Bring cube into view$"), lambda m: {"type": "BringIntoView"}),
    (re.compile(r"^Tilt slightly to show an adjacent face$"), lambda m: {"type": "TiltAdjacent"}),
    (re.compile(r"^Keep turning to re-align"), lambda m: {"type": "KeepTurningRealign"}),
    (
        re.compile(r"^Tilt slightly away from light \((\d+) side\(s\) left\)$"),
        lambda m: {"type": "TiltAwayLightRemaining", "count": int(m.group(1))},
    ),
    (re.compile(r"^Tilt slightly away from light for clearer view$"), lambda m: {"type": "TiltAwayLight"}),
    (re.compile(r"^Turn cube to show remaining (\d+) side\(s\)$"), lambda m: {"type": "TurnRemaining", "count": int(m.group(1))}),
    (re.compile(r"^Show face ([URFDLB]) clearly"), lambda m: {"type": "ResolveConflict", "face": m.group(1)}),
    (re.compile(r"^All 54 stickers verified!$"), lambda m: {"type": "AllVerified"}),
    (
        re.compile(r"^Tilt remaining faces toward camera \((\d+)/54\)$"),
        lambda m: {"type": "TiltRemainingStickers", "count": int(m.group(1))},
    ),
)


def map_cue(cue: str) -> Dict[str, object]:
    """mapper.py's cue string as a schema-1 SessionCue (type, count, face); an unmapped string fails the export."""
    for pattern, build in _CUE_PATTERNS:
        m = pattern.match(cue)
        if m:
            out: Dict[str, object] = {"type": None, "count": None, "face": None}
            out.update(build(m))
            return out
    raise SystemExit(f"unmapped mapper.py cue string: {cue!r}")


def slug(name: str) -> str:
    s = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
    assert re.fullmatch(r"[a-z0-9-]+", s), s
    return s


def flat_quad(quad: np.ndarray) -> List[float]:
    return [float(v) for v in np.asarray(quad, dtype=np.float64).reshape(-1)]


def observation(image_bgr: np.ndarray, det) -> Dict[str, object]:
    """The cell samples mapper.py computes for this detection, in raw corner order (the Kotlin QuadObservation contract)."""
    rgbs, quals, sharp, glare = _sample_face_cells(image_bgr, det.corners)
    return {
        "quad": flat_quad(det.corners),
        "cellRgbs": [float(v) for v in rgbs.reshape(-1)],
        "cellQualities": [float(v) for v in quals],
        "sharpness": float(sharp),
        "hasGlare": bool(glare),
        "score": float(det.score),
    }


def net_string(net: Dict[str, List[Optional[str]]]) -> str:
    return "".join("." if c is None else COLOR_CHAR[c] for f in NET_FACES for c in net[f])


def tracked_quads(mapper: ContinuousCubeMapper) -> List[Dict[str, object]]:
    """The snapshot's tracked quads with their local face (mapper.py's own vis-quad rule; face null for '?' labels)."""
    active = mapper.pending if (mapper.pending is not None and mapper.pending.tracked) else mapper.master
    out: List[Dict[str, object]] = []
    if active is None:
        return out
    for fname, tf in active.tracked.items():
        canonical = _rotate_face_name(fname, mapper.global_R) if (active is mapper.master and mapper.global_R is not None) else None
        out.append({"face": canonical, "localFace": fname, "quad": flat_quad(tf.aligned_quad)})
    return out


def snapshot_json(snap: MapperSnapshot, mapper: ContinuousCubeMapper, quads: List[Dict[str, object]]) -> Dict[str, object]:
    face_order = {f: i for i, f in enumerate(NET_FACES)}
    conflicted = sorted(snap.conflicted_slots, key=lambda s: (face_order[s[0]], s[1]))
    labels = [q["face"] if q["face"] is not None else f"?{q['localFace']}" for q in quads]
    assert labels == list(snap.tracked_quads.keys()), (snap.frame_idx, labels, list(snap.tracked_quads.keys()))
    return {
        "frameIndex": int(snap.frame_idx),
        "kind": "TWIST_DETECTED" if snap.twist_detected else "SCANNING",
        "twistFrame": int(mapper.twist_frame) if (snap.twist_detected and mapper.twist_frame is not None) else None,
        "net": net_string(snap.net),
        "state": None,
        "conflictedSlots": [f"{f}{i}" for f, i in conflicted],
        "trackedQuads": quads,
        "globalAnchored": bool(snap.global_anchored),
        "cue": map_cue(snap.cue),
        "committedCount": int(snap.committed_count),
        "mappedFacesCount": len(mapper.master.confirmed_faces()) if mapper.master is not None else 0,
        "invariantViolationCount": int(snap.invariant_count),
        "pendingFragmentFaces": int(snap.pending_fragment_faces),
    }


def derive_outcome(last: Optional[Dict[str, object]]) -> str:
    """SessionOutcome.derive applied to mapper.py's last snapshot (mapper.py has no validated complete state)."""
    if last is None:
        return "LOST_TRACKING"
    if last["kind"] == "TWIST_DETECTED":
        return "FAILED"
    if last["committedCount"] == 54 and last["invariantViolationCount"] == 0 and not last["conflictedSlots"]:
        return "COMPLETE"
    if not last["trackedQuads"]:
        return "LOST_TRACKING"
    return "PARTIAL"


def labels_json(cfg: ScenarioConfig) -> Dict[str, object]:
    stickers = [{"color": cfg.state[f][i], "source": "entered", "reviewed": True} for f in NET_FACES for i in range(9)]
    counts: Dict[str, int] = {}
    for s in stickers:
        counts[s["color"]] = counts.get(s["color"], 0) + 1
    assert len(stickers) == 54 and sorted(counts.values()) == [9] * 6, counts  # StickerTruthCheck: PASSED
    return {"schemaVersion": 1, "stickers": stickers, "countCheck": "PASSED", "overrideReason": None}


def dumps_line(obj: object) -> str:
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"), allow_nan=False)


def build_commit(repo: Path) -> str:
    try:
        out = subprocess.run(["git", "rev-parse", "HEAD"], cwd=repo, capture_output=True, text=True, check=True).stdout.strip()
        return out if len(out) == 40 else "unknown"
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def export_scenario(index: int, cfg: ScenarioConfig, out_root: Path, commit: str) -> Path:
    session_id = f"pyspike-{index}-{slug(cfg.name.split('_', 1)[1])}-v1"
    frames = generate_sequence(cfg, WIDTH, HEIGHT)
    mapper = ContinuousCubeMapper()
    frame_lines: List[str] = []
    snap_lines: List[str] = []
    last: Optional[Dict[str, object]] = None
    for fr in frames:
        obs = [observation(fr.image_bgr, d) for d in fr.detections]
        snap = mapper.process_frame(fr.frame_idx, fr.image_bgr, fr.detections)
        # An empty frame's snapshot shows no quad even while fragments keep their trackers.
        last = snapshot_json(snap, mapper, tracked_quads(mapper) if snap.tracked_quads else [])
        frame_lines.append(
            dumps_line(
                {
                    "frameIndex": int(fr.frame_idx),
                    "timestampMs": int(fr.frame_idx) * FRAME_INTERVAL_MS,
                    "analyzerMs": 0.0,
                    "inferenceMs": 0.0,
                    "observations": obs,
                    "peaks": [],
                    "image": None,
                    "skipped": False,
                }
            )
        )
        snap_lines.append(dumps_line(last))

    target = out_root / session_id
    target.mkdir(parents=True, exist_ok=True)
    # Drop the manifest first so an interrupted re-export never leaves a complete-looking session.
    (target / "manifest.json").unlink(missing_ok=True)
    frames_text = "".join(line + "\n" for line in frame_lines)
    snaps_text = "".join(line + "\n" for line in snap_lines)
    (target / "frames.jsonl").write_text(frames_text, encoding="utf-8")
    (target / "snapshots.jsonl").write_text(snaps_text, encoding="utf-8")
    (target / "labels.json").write_text(json.dumps(labels_json(cfg), indent=4, ensure_ascii=False) + "\n", encoding="utf-8")

    last_ms = (len(frames) - 1) * FRAME_INTERVAL_MS if frames else None
    manifest = {
        "schemaVersion": 1,
        "sessionId": session_id,
        "synthetic": True,
        # Fixed (not the export time) so re-exports are byte-identical.
        "createdAt": "2026-11-27T00:00:00Z",
        "device": {"model": "synthetic (pc_spike synth_video.py)", "manufacturer": "synthetic"},
        "cube": f"pc_spike run_spike scenario {cfg.name} (seed {cfg.seed})",
        "lighting": f"synthetic {cfg.palette} palette",
        "partition": "UNASSIGNED",
        "model": {
            "file": "none (synthetic observations)",
            "sha256": "none",
            "litertVersion": "none",
            "delegate": "none",
            "threads": 0,
        },
        "buildCommit": commit,
        "frameCount": len(frames),
        "framesWithObservations": sum(1 for fr in frames if fr.detections),
        "imageEvery": 0,
        "imageFrames": 0,
        "geometry": {
            "rotationDegrees": 0,
            "analysisWidth": WIDTH,
            "analysisHeight": HEIGHT,
            "cropLeft": 0,
            "cropTop": 0,
            "cropRight": WIDTH,
            "cropBottom": HEIGHT,
            "uprightWidth": WIDTH,
            "uprightHeight": HEIGHT,
            "inputSize": 256,
            "letterboxScale": 0.4,
            "padLeft": 0,
            "padTop": 32,
        },
        "geometryChanges": [],
        "timing": {
            "firstTimestampMs": 0 if frames else None,
            "lastTimestampMs": last_ms,
            "durationMs": last_ms or 0,
            "meanFrameIntervalMs": float(FRAME_INTERVAL_MS),
        },
        "captureSkips": 0,
        "droppedFrames": 0,
        "stopReason": "OPERATOR",
        "outcome": derive_outcome(last),
        "latencyP50Ms": None,
        "latencyP95Ms": None,
        "latencyBaselineP50Ms": None,
        "defective": False,
        "defectReasons": [],
        # Bytes as the device writer counts them: frame lines + snapshot lines (no PNGs), never labels or manifest.
        "bytes": len(frames_text.encode("utf-8")) + len(snaps_text.encode("utf-8")),
        "goldenRevision": None,
    }
    # manifest.json is written last: a directory without it is never a complete session.
    (target / "manifest.json").write_text(json.dumps(manifest, indent=4, ensure_ascii=False) + "\n", encoding="utf-8")
    return target


def main() -> int:
    here = Path(__file__).resolve().parent
    repo = next((p for p in [here, *here.parents] if (p / "Fixtures" / "continuous").is_dir()), None)
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--out",
        type=Path,
        default=(repo / "Fixtures" / "continuous" / "mapper-parity") if repo else None,
        help="Output directory (default: the repo's Fixtures/continuous/mapper-parity)",
    )
    args = parser.parse_args()
    if args.out is None:
        parser.error("--out is required outside the repository")
    args.out.mkdir(parents=True, exist_ok=True)
    commit = build_commit(repo or here)
    by_name = {cfg.name: (i + 1, cfg) for i, cfg in enumerate(build_scenarios())}
    for name in PARITY_SCENARIOS:
        index, cfg = by_name[name]
        target = export_scenario(index, cfg, args.out, commit)
        manifest = json.loads((target / "manifest.json").read_text(encoding="utf-8"))
        print(f"Wrote {target} ({manifest['outcome']}, {manifest['frameCount']} frames)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
