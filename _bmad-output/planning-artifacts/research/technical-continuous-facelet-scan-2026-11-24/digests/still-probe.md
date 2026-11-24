# PC still-image probe

We tested every square corner JPEG currently tracked under `Fixtures/corner/*/corner-*.jpg` (46 pre-framed images of two views across 23 groups from Lenovo TB128XU and Galaxy S24 FE), then scored localization and color accuracy against the ground-truth keypoints and sticker labels already present in `Fixtures/corner/` and `Fixtures/live/`. Per-image proposal counts are in [still-counts.csv](still-counts.csv).

## Proposal counts by device and cube type (46 corner photos)

| Cohort | Images | Sticker proposals per image, min / median / max |
|---|---:|---:|
| Stickered (all) | 20 | 25 / 26 / 27 |
| — Lenovo TB128XU, stickered | 12 | 25 / 26 / 27 |
| — Galaxy S24 FE, stickered | 8 | 25 / 26.5 / 27 |
| Stickerless (all) | 26 | 6 / 19 / 27 |
| — Galaxy S24 FE, stickerless | 10 | 22 / 24 / 27 |
| — Lenovo TB128XU, stickerless | 16 | 6 / 17 / 22 |

Grouping Lenovo and Galaxy S24 FE stickerless cohorts into one median (19) hides a device split: Galaxy S24 FE stickerless stays near 24 proposals per image, while Lenovo stickerless drops to a median of 17 (down to 6).

## Ground-truth localization and color accuracy

We projected the 27 labeled sticker quads from the human-verified 7-corner keypoints on all 30 schema-4 corner photos (`Fixtures/corner/*/corner-*.json` carrying `keypoints`, 810 stickers total on pre-framed square crops), evaluated all 73 cube-free negative crops (`128×128`, upscaled 5× to `640×640`) under `Fixtures/corner/*/keypoints/*.png`, and scored all 42 valid guided single-face frontal photos under `Fixtures/live/` (378 stickers; excluding `live/sm-s721u1_stkb_studio-a`, whose ground truth is known invalid with 10 WHITE and 8 RED per `tools/sticker-model/assemble.py`).

| Cubentia slice | Ground-truth stickers | Proposals | Unique cell hit | Exact cell + color | Color given hit | Duplicate hits | Box `w/h` median |
|---|---:|---:|---:|---:|---:|---:|---:|
| **`Fixtures/live`** (42 valid frontal 1-face photos) | 378 | 368 | **366 (96.8%)** | **336 (88.9%)** | 91.8% | 0 | **1.01** |
| **`Fixtures/corner`** (30 3-face photos) | 810 | 699 | **375 (46.3%)** | **239 (29.5%)** | 63.7% | **320** | **0.30** |
| — Lenovo / stickered (4 photos) | 108 | 106 | 63 (58.3%) | 42 (38.9%) | 66.7% | 43 | — |
| — S24 FE / stickered (8 photos) | 216 | 210 | 105 (48.6%) | 38 (17.6%) | 36.2% | 102 | — |
| — Lenovo / stickerless (8 photos) | 216 | 139 | 86 (39.8%) | 67 (31.0%) | 77.9% | 53 | — |
| — S24 FE / stickerless (10 photos) | 270 | 244 | 121 (44.8%) | 92 (34.1%) | 76.0% | 122 | — |
| — Pastel cube `stlb` subset (2 photos) | 54 | 25 | 16 (29.6%) | 10 (18.5%) | 62.5% | 9 | — |

By face slot across the 30 3-face corner photos:
- **Top face (`U`/`D`):** 170/270 hit (**63.0%**), 112/270 exact cell+color (**41.5%**).
- **Left side (`F`/`L`):** 108/270 hit (**40.0%**), 70/270 exact cell+color (**25.9%**).
- **Right side (`R`/`B`):** 97/270 hit (**35.9%**), 57/270 exact cell+color (**21.1%**).

Additional checks:
- **Negative rejection (`Fixtures/corner/*/keypoints/*.png`, 73 cube-free `128×128` crops):** 14 of 73 frames (**19.2%**) produced false-positive sticker boxes (46 false boxes total, up to 25 on one frame). Note that these are upscaled `128×128` corner crops rather than full-frame camera negatives; future detector benchmarks must include uncropped full-frame negatives.
- **Off-center check:** Placing `corner-1.jpg` off-center (`x=[20, 226]`) inside a `640×640` canvas still compressed predicted boxes horizontally around the **cube's** vertical midline (`x` span 54.0 px vs. `y` span 125.6 px, median `w/h = 0.35`).
- **Why raw counts misled:** `S24 FE / stickered` emitted 210 proposals for 216 stickers yet scored only **17.6% exact cell+color accuracy** because **102 boxes duplicated inner cells** while outer columns were horizontally pulled onto neighboring stickers. The upstream cause is confirmed in [facelets.md](facelets.md).

## Reproduce

The detector is the published Super-Morse YOLO11n ONNX export, 10,669,316 bytes, SHA-256 `f158df4560063686cf90483e21bc611790bc63f6fc2b343a7a4e1dd6b5063dd3`. It expects a 640-square RGB image. Its output includes six sticker colors and a seventh whole-cube class. We omit the whole-cube proposals, use a score cutoff of 0.35 and class-agnostic overlap suppression at 0.35, and retain all remaining sticker proposals. The upstream wrapper selects a single group of nine. The weights embed an AGPL-3.0 license field even though the repository's top-level license says MIT. We used a temporary local copy for evaluation. [Pinned export](https://github.com/Super-Morse/rubiks-visual-detector/blob/b2ed9246c3259dd684b69e36170292bc88a2e02c/app/infrastructure/models/best.onnx) · [Upstream wrapper](https://github.com/Super-Morse/rubiks-visual-detector/blob/b2ed9246c3259dd684b69e36170292bc88a2e02c/app/infrastructure/onnx_detector.py) · [Repository license](https://github.com/Super-Morse/rubiks-visual-detector/blob/b2ed9246c3259dd684b69e36170292bc88a2e02c/LICENSE)

From the worktree root, download the pinned export outside the repository and run both probes:

```sh
run_dir=_bmad-output/planning-artifacts/research/technical-continuous-facelet-scan-2026-11-24
weights=$(mktemp)
curl --fail --location --silent --show-error \
  https://raw.githubusercontent.com/Super-Morse/rubiks-visual-detector/b2ed9246c3259dd684b69e36170292bc88a2e02c/app/infrastructure/models/best.onnx \
  --output "$weights"
uv run --no-project --with onnxruntime==1.32.0 --with opencv-python-headless==5.1.0.95 \
  python "$run_dir/probe_stills.py" "$weights"
uv run --no-project --with onnxruntime==1.32.0 --with opencv-python-headless==5.1.0.95 --with numpy==2.5.5 --with pillow \
  python "$run_dir/digests/score_cubentia_fixtures.py" "$weights"
```
