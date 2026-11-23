# Cubentia Quick Scan shadow sticker reader (`tools/sticker-model/`)

Seeded data assembly, synthetic faces, training, LiteRT export and held-out evaluation for
`app/src/camera/debug/sticker/assets/sticker_reader.tflite` (Story 10.23, SCP 2026-11-22 quick-scan-sticker-reader,
AD-9 / AD-13 / AD-18). The model runs in **shadow mode only**, in debug builds: it reads each captured Quick Scan face and
logs its read beside both Kotlin reads; it decides nothing, and the Kotlin classifier stays the reader of record.

## Contract

- **Input:** `[1, 96, 96, 3]` `float32` RGB in `[0, 1]`: one unwarped face as `unwarp()` hands it over (96x96 YUV,
  converted with the formula of `CornerModelTransform.fillRgbInput`). Image only: no Kotlin reading, centre or
  calibration goes in, so its read is independent of both Kotlin reads.
- **Output:** `[1, 9, 6]` softmax, one row per sticker in Kotlin cell order (row-major, as `FrameReader` /
  `CornerReader` read the face).
- **Colour order:** `WHITE, YELLOW, RED, ORANGE, BLUE, GREEN` (not `Color.entries` order).
- **Confidence rule:** a sticker is `CONFIDENT` when its top-1 probability is at least **tau**, else `UNCERTAIN`. tau is
  the smallest value on the 0.50-0.99 grid (step 0.01) giving 0 confidently wrong stickers on the tune split, 0.99 when
  none does. Only the colour and the `StickerConfidence` cross the reader seam; probabilities never leave the reader.
- **Size:** the `.tflite` must be under 500 KB (`train_sticker_model.py` fails otherwise). fp16 weights, no delegate.
- `model-card.json` records tau, the colour order, input size, SHA-256, bytes, seed, quant, recipe and tool versions;
  `StickerModelContract` (app) must equal it (`StickerModelContractTest`).

## Requirements

As `tools/corner-model/`: Linux x86_64 with an NVIDIA GPU + CUDA (`tensorflow[and-cuda]`), Python 3.11-3.12 via `uv`
(the committed model: Python 3.12.13, TensorFlow 2.19.1 and its bundled `TFLiteConverter`, NumPy 2.1.3, Pillow 11.3.0).
Assembly runs the committed `corner_keypoints.tflite` on CPU to find the quads of the corner fixtures.

## Commands

```bash
# Tests: assembly determinism, split disjointness, synthetic train-only, crop orientation, size limit.
uv run --project tools/sticker-model python -m unittest discover -s tools/sticker-model

# 1. Assemble (artifacts/ is gitignored); rewrites split.json and docs/evidence/10-23/inventory.md; exit 1 on a split failure.
uv run --project tools/sticker-model python tools/sticker-model/assemble.py
# 2. Train and export (refuses on a failing or stale split.json); writes model-card.json and model-provenance.md.
uv run --project tools/sticker-model python tools/sticker-model/train_sticker_model.py
# 3. The Kotlin reads of the held-out faces (self, second, gated) for the evaluation.
./gradlew :core:domain:exportKotlinStickerReads
# 4. Evaluate on the held-out groups, with the learning curve (three retrains); --skip-curve to skip it.
uv run --project tools/sticker-model python tools/sticker-model/evaluate.py
# Check the committed split on its own (also run by StickerModelSplitTest in CI).
uv run --project tools/sticker-model python tools/sticker-model/split.py --manifest tools/sticker-model/artifacts/manifest.json
```

After a retrain, copy tau and the SHA-256 from `model-card.json` into `StickerModelContract`. GPU training is not
bit-exact: a retrain reproduces the recipe and its metrics, not the bytes.

## Data and split

- **Sources** (`assemble.py`): `Fixtures/live/*_studio-a` frames (the recorder grid: the centred square of 80% of the
  short edge, split 3x3), `Fixtures/corner/*/corner-{1,2}` holds (quads from `corner_keypoints.tflite` on the saved
  200x200 crop, unwarped as `unwarp()` orders corners: `[U/D, F/L, R/B]`, each `[TL, TR, BR, BL]`), and the S4
  photos of `Fixtures/labeled/photo-evaluation.json` (their `rect`). Each face's crop is checked against the JSON's
  measured stickers by the dE76 between each crop cell's mean colour and its measured sticker: the face is used only
  when the median over all 9 cells is <= 15 and the max over the 8 non-centre cells is <= 30 (the centre is left out of the
  max because a logo centre is measured around the logo). A failing face is excluded and listed with the reason; photos
  have no measured stickers, so the check does not apply to them.
  `sm-s721u1_stkb_studio-a` is excluded (its truth is not a valid cube, `docs/scan-accuracy.md`).
- **Synthetic faces** (`synth_faces.py`, seeded): shaded side faces and backlight, glare, red against orange, white
  against yellow, logos, a slightly misplaced quad, foreshortening, then the corner model's camera effects (YUV 4:2:0,
  white balance, blur, noise). Palettes come from training groups only. **Training side only.**
- **Split** (`holdout.json`, committed): whole capture groups. Held out: `live/sm-s721u1_stla_studio-a`,
  `live/sm-s721u1_stka_studio-a`, `corner/lenovo-tb128xu_stla_cool`, `corner/lenovo-tb128xu_stka_cool` (both devices,
  stickered and stickerless, two backlit). Tune (tau only): `live/lenovo-tb128xu_stkb_studio-a`,
  `corner/lenovo-tb128xu_stka_cool-front`, `photo/RubikStickered_GalaxyTab_S4`. Every other usable real group trains.
  `split.json` (committed, generated) lists each group's side and each item's image SHA-256; `split.py`,
  `test_split.py` and `StickerModelSplitTest` fail when a group or an image hash is on two sides, a synthetic item is
  outside train, or a `holdout.json` group is not held out.
- **Story 10.20's held-out corner groups (H3):** add them to `holdout.json` `heldout` **before** re-running
  `assemble.py` (so no retrain ever sees them), then `evaluate.py --groups <file listing them> --name evaluation-10-20`.
  `evaluate.py` refuses groups the model trained on or tuned tau on, and groups with no Kotlin reads in `kotlin-reads.json`.

## Device use (debug build, `QUICK_SCAN` raised)

The reader is on by default. Each corner capture logs, per face:

```
quickScan shadow hold=1 face=U center=W inferMs=7 model=WWBROYGWW/CCCCUCCCC self=WWBROYGWW/CCCCCCCCC second=cal:WWBROYGWW/CCCCUCCCC
```

Each scan starts with `quickScan shadowReader state=on|off`. Switch it without reinstalling (takes effect at the next
capture):

```bash
adb shell am broadcast -p com.exxerpro.cubentia -a dev.cubentia.debug.SHADOW_READER --ez on false
```
