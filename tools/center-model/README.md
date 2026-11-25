# Cubentia Continuous Quick Scan Sticker-Center Model (`tools/center-model/`)

Clean-room, self-contained training, export, and evaluation pipeline for `app/src/camera/continuous/assets/sticker_centers_v2_int8.tflite` (Story 10.31, AD-9, SCP 2026-11-24).

## Model Contract

- **Asset:** `app/src/camera/continuous/assets/sticker_centers_v2_int8.tflite` (`378,288 B`, SHA-256 `d18fb2c2fe2bb975cf646877f811718c16775ce6bcde907f12547d143174be7c`).
- **Input:** `UINT8[1, 256, 256, 3]` zero-padded RGB letterbox of the upright camera crop.
- **Output:** `FLOAT32[1, 64, 64, 3]` where channel 0 is sticker-center probability (sigmoid) and channels 1..2 are sub-cell offsets `(dx, dy)` in `[-0.55, 0.55]`.
- **Peak decoding:** 3×3 local maximum suppression at `threshold = 0.45`, `maxPeaks = 36`, mapped back to upright crop pixel coordinates `((gx + 0.5 + dx) * 4 - padLeft) / scale` and fed into `:core:domain`'s `FaceLatticeFitter` + `ContinuousFaceletMapper`.
- **On-device runtime:** LiteRT `2.3.2` (`com.google.ai.edge.litert:litert`, `ai-delivery` excluded), XNNPACK CPU with `2` threads, off the main thread.

## Provenance & Holdout Policy

- Trained purely on synthetic 1–3 visible-face 3D cube renders (`synth_heatmap_data.py`), optional non-held-out `Fixtures/live/` face textures, and optional cube-free room backgrounds from non-held-out tuning clips.
- **Zero** `Fixtures/corner/` photos and **zero** held-out groups enter training.
- Strictly excludes the canonical holdout union (`tools/sticker-model/holdout.json` ∪ held-out `Fixtures/corner/groups.json`) and excludes `live/sm-s721u1_stkb_studio-a`.
- No third-party model weights, pseudo-labels, or distillation are used.

## Commands

Run holdout-disjointness and provenance unit tests (stdlib only, no GPU or TensorFlow required):

```bash
python3 -m unittest discover -s tools/center-model -p 'test_*.py' -v
```

Train and export the INT8 LiteRT model (Linux x86_64 with NVIDIA GPU + CUDA via `uv`):

```bash
uv run --project tools/center-model python tools/center-model/train_center_model.py \
  --train-n 12000 --val-n 1000 --epochs 24 --batch 32 \
  --out tools/center-model/artifacts/sticker_centers_v2_fp16.tflite \
  --out-int8 app/src/camera/continuous/assets/sticker_centers_v2_int8.tflite
```

Evaluate on real `Fixtures/corner/` and `Fixtures/live/` (tuning split by default):

```bash
uv run --project tools/center-model python tools/center-model/eval_litert_center.py \
  --model app/src/camera/continuous/assets/sticker_centers_v2_int8.tflite \
  --split tuning --thresh 0.45
```
