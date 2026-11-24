# PC Continuous-Scan Algorithm Spike

Standalone Python prototype proving the core continuous-scanning algorithm end-to-end on PC before touching Android stories or choosing a mobile detector.

## What this spike tests (honesty contract)

The synthetic 3D renderer (`synth_video.py`) passes the mapper (`mapper.py`) **only**:
- raw `640×480` BGR frames, and
- unordered, randomly cyclically shifted 2D clockwise face quads (`[p0, p1, p2, p3]`) with Gaussian corner jitter, random drops, and false-positive clutter quads.

The mapper receives **no** 3D pose, **no** camera intrinsics, **no** face names (`U/R/F/D/L/B`), **no** quarter-turn orientation, and **no** color labels.

## Core algorithm (`mapper.py`)

1. **Intrinsics-free 2D shared-edge topology graph:** Tracks face quads frame-to-frame and stitches adjacent faces strictly by shared 2D edges, assembling the 6-face cube graph even on solved or symmetric faces without `solvePnP` or camera intrinsics.
2. **Regrip recovery with symmetry guard:** After tracking breaks or a hand regrip blackout, builds a pending local fragment and merges it over the 24 proper SO(3) cube rotations only when top-1 beats top-2 by a clear margin (never locking quarter-turn rotation on a single symmetric/solved face).
3. **Shading-invariant + AWB-compensated deferred global color anchoring:** Tracks per-frame chromaticity AWB drift in log-RGB across overlapping faces, cancels scalar Lambertian shading via L2-normalized RGB before CIELAB conversion, and solves global 6-center orientation over 24 Western SO(3) rotations (working on pastel cubes, warm/cool lighting shifts, logo centers, and starting on Yellow/Orange/Blue with no White reference).
4. **Graded observation quality & local invariants:** Combines 2D foreshortening, Laplacian sharpness, specular glare detection, and patch heterogeneity to weight observations without deadlocking under overhead frontal glare; enforces immediate edge/corner cubie invariants (including corner cyclic chirality, `≤ 9` per color, duplicate-free cubies) with evidence-weighted conflict reopening and nonrigid mid-scan layer-twist detection.

## Run

From this directory (`pc_spike/`):

```sh
# Run all 7 stress scenarios (prints scored table)
uv run --no-project --with opencv-python-headless --with numpy python run_spike.py

# Render side-by-side MP4 replays + 2x3 contact sheets to /tmp/pc_spike_renders
uv run --no-project --with opencv-python-headless --with numpy python run_spike.py --render-dir /tmp/pc_spike_renders

# Run unit + scenario verification tests
uv run --no-project --with opencv-python-headless --with numpy python test_spike.py
```

## What stays open for real camera clips

This spike proves the mapping, color-fusion, invariant, and regrip-recovery algorithm under simulated detector noise, glare, AWB drift, occlusion, and twists. Next steps on real clips:
1. Record clean stickered + stickerless handheld clips with known 54-cell truth.
2. Plug an unpoisoned free-pose face-quad or sticker-center detector (e.g., LiteRT trained on an extended `render_cube.py`, or read-only PC comparator `yush-io/cubeSolver` / `YORO`) into the exact same `mapper.process_frame()` interface.
