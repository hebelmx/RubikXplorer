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

## Real-camera detector bridge (`lattice_fit.py`, `run_real_video.py`)

`lattice_fit.py` converts any unordered set of 2D sticker-center points into 1–3 non-overlapping projective 3×3 face quads (`DetectedQuad`) using local basis seeding, projective homography refinement, non-overlap checks, and a rigid-cube shared-edge bonus. It plugs directly into `mapper.process_frame()`.

### Read-only PC detector benchmark (tuning split only; canonical holdout union untouched)

| Public detector (PC-only benchmark) | 3-face tuning hit (`n=14`, 378 stickers) | Top / Left / Right hit | Duplicates | Frontal `Fixtures/live` tuning (`n=30`) | `lattice_fit` 3-face recovery |
|---|---:|---|---:|---:|---:|
| **`yush-io/cubeSolver`** (single-class `sticker`) | **89.9% (340/378)** (`S24FE/stk` 96.3%, `Lenovo/stk` 90.7%, `S24FE/stl` 92.6%, `pastel` 70.4%) | **92.1% / 88.9% / 88.9%** | **0** (2 outside) | **99.3% (268/270)** | **40/42 faces (95.2%)**, 13/14 complete 3-face corners, 32 shared edges |
| **`YORO`** (6-color OBB + `cube face`) | 26.5% (100/378; only 3 `cube face` boxes) | 54.0% / 13.5% / 11.9% | 0 | 95.2% (257/270) | Disqualified |
| `Super-Morse` (for reference) | 46.3% (all 30; poisoned generator) | 63.0% / 40.0% / 35.9% | 320 | 96.8% | Disqualified |

> **Clean-room rule:** `yush-io/cubeSolver` has no license file and is used **strictly** as a read-only PC spike comparator to test the real-video pipeline — never for bundling, pseudo-labeling, or training Cubentia's LiteRT model.

### Run on a real phone MP4 clip or live PC webcam

Download the pinned `yush-io/cubeSolver` weights outside the repo (`SHA-256 68fd20b050a75f3268385e33afc8a9484ba498259e5a5b0e97914f28e2c1a739`), then run:

```sh
# Recorded phone clip -> writes side-by-side MP4 replay + contact sheet
uv run --no-project --extra-index-url https://download.pytorch.org/whl/cpu --index-strategy unsafe-best-match \
  --with torch --with torchvision --with ultralytics --with opencv-python-headless --with numpy \
  python run_real_video.py --weights /tmp/cubesolver_best.pt --video path/to/clip.mp4 --stride 2 --out-dir /tmp/real_replay

# Live PC webcam (/dev/video0) with interactive window (press r to reset, q to quit)
uv run --no-project --extra-index-url https://download.pytorch.org/whl/cpu --index-strategy unsafe-best-match \
  --with torch --with torchvision --with ultralytics --with opencv-python --with numpy \
  python run_real_video.py --weights /tmp/cubesolver_best.pt --webcam 0 --show
```

## What stays open for real camera clips

1. Record two clean handheld clips (stickered + stickerless) with known 54-cell truth and run `run_real_video.py` to score exact 54-cell agreement and regrip recovery end-to-end on real video.
2. Extend `tools/corner-model/render_cube.py` (1–3 visible faces, off-center, hand-occlusion masks) and train a clean Cubentia-owned LiteRT sticker-center / face-quad model on our RTX A2000 GPU to replace the PC-only comparator with a portable model that runs identically on PC and Android.
