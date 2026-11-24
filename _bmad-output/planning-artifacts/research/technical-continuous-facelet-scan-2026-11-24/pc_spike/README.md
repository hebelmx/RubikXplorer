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

## Clean-room Cubentia LiteRT sticker-center model (`litert_center/`)

Cubentia's own clean-room MobileNetV2 inverted-residual FPN heatmap model (`sticker_centers.tflite`, **523,900 bytes / 511.6 KB FP16**, 255K params, input `192×192×3` RGB → `48×48×3` `[hm_prob, dx, dy]`, pure LiteRT built-in ops, **0 extra runtime `.so` bytes** on existing `litert:2.3.2`) was trained purely on synthetic 1–3 visible-face renders + non-held-out `Fixtures/live` textures (**zero** real `Fixtures/corner/` photos in training; canonical holdout union strictly untouched).

### Detector comparison on real Cubentia fixtures (canonical holdout union strictly untouched during training/tuning)

| Detector | Model size | PC CPU latency | 3-face tuning hit (`n=14`, 378) | 3-face **held-out** hit (`n=16`, 432) | Frontal `Fixtures/live` (tuning / held-out) | Negative rejection (`n=73`) | `lattice_fit` 3-face recovery (tuning / held-out) |
|---|---:|---:|---:|---:|---:|---:|---:|
| **Cubentia `sticker_centers.tflite` (clean-room LiteRT)** | **511.6 KB FP16** | **1.1–1.4 ms** | **97.1% (367/378)** (`prec 97.1%`; pastel `90.7%`) | **91.0% (393/432)** (`prec 92.0%`; `S24FE/stk 98.1%`, `Lenovo/stl 91.4%`) | **97.0%** (0 FP) / **99.1%** | **93.2%** (6.8% FP) | **42/42 (100%)** / **48/48 (100%)** — **90/90 faces, 30/30 corners, 89/90 shared edges** |
| `yush-io/cubeSolver` (read-only PC comparator, no license) | 6.25 MB `.pt` | ~63 ms | 89.9% (340/378) (pastel 70.4%) | Untested (holdout untouched) | 99.3% / — | — | 40/42 (95.2%) / — |
| `YORO` (read-only PC comparator, no license) | 6.40 MB `.pt` | ~65 ms | 26.5% (100/378) | Disqualified | 95.2% / — | — | Disqualified |
| `Super-Morse` (AGPL-3.0, poisoned generator) | 10.67 MB `.onnx` | ~15 ms | 46.3% (all 30; 320 dupes) | Disqualified | 96.8% (all 42) | 80.8% (19.2% FP) | Disqualified |

### End-to-end `pixels -> sticker_centers.tflite -> lattice_fit -> mapper` (zero simulated detections)

All **7/7 continuous-scan stress scenarios** pass end-to-end from raw RGB pixels through `sticker_centers.tflite` → `lattice_fit.py` → `mapper.py` (**54/54 exact, 0 wrong, 0 transient wrong** across regrip, pastel+AWB drift, logo center+glare, mid-solve F2L symmetric regrip, finger occlusion+clutter, and starting without White; mid-scan layer twist caught cleanly on Scenario 7; LiteRT **1.0–1.2 ms**, mapper **4.0–7.7 ms** on PC CPU).

> **Clean-room rule:** `yush-io/cubeSolver`, `YORO`, and `Super-Morse` are read-only PC comparators only — never bundled, distilled, or used for pseudo-labeling or training `sticker_centers.tflite`.

### Train, evaluate, and run live on webcam or phone clip (LiteRT)

```sh
# 1. Re-train and export Cubentia's clean-room LiteRT model (~2.5 min on RTX A2000 GPU)
/mnt/e/Dynamic/IndFun/RubikXplorer/tools/corner-model/.venv/bin/python litert_center/train_center_model.py \
  --train-n 8000 --val-n 800 --epochs 20 --batch-size 48 --out /tmp/sticker_centers.tflite

# 2. Evaluate on real Cubentia fixtures + all 7 end-to-end continuous scenarios
uv run --no-project --python 3.12 --with ai-edge-litert --with opencv-python-headless --with numpy --with pillow \
  python litert_center/eval_end_to_end.py --model /tmp/sticker_centers.tflite --split tuning --thresh 0.35

# 3. Run on a recorded phone MP4 clip -> writes side-by-side MP4 replay + contact sheet
uv run --no-project --python 3.12 --with ai-edge-litert --with opencv-python-headless --with numpy --with pillow \
  python run_real_video.py --litert-model /tmp/sticker_centers.tflite --video path/to/clip.mp4 --stride 2 --out-dir /tmp/real_replay

# 4. Run live on PC webcam (/dev/video0) with interactive window (press r to reset, q to quit)
uv run --no-project --python 3.12 --with ai-edge-litert --with opencv-python-headless --with numpy --with pillow \
  python run_real_video.py --litert-model /tmp/sticker_centers.tflite --webcam 0 --show
```

## What stays open for real camera clips

1. Record two clean handheld clips (stickered + stickerless) with known 54-cell truth (or test live via `--webcam 0 --show`) and score exact 54-cell agreement and regrip recovery end-to-end on real physical video.
2. Benchmark `sticker_centers.tflite` (and an INT8 quantized variant) on the physical Lenovo TB128XU, Galaxy Tab S7 FE 5G, and Galaxy S24 FE once real-video PC replay passes.
