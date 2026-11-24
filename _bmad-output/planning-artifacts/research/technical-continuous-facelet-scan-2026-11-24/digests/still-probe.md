# PC still-image probe

We tested every square corner JPEG currently tracked under `Fixtures/corner/*/corner-*.jpg`. The set contains 46 pre-framed images of two views across 23 groups. The Lenovo and Galaxy S24 FE supplied the photos. The PC ran inference. Per-image results are in [still-counts.csv](still-counts.csv).

| Physical cube | Images | Sticker proposals per image, minimum / median / maximum |
|---|---:|---:|
| Stickered | 20 | 25 / 26 / 27 |
| Stickerless | 26 | 6 / 19 / 27 |

These are counts after filtering. A false positive can increase the count, a wrong color can leave it unchanged, and photos within a group are related. The two cohorts also differ in lighting, device mix and cube finish. No per-sticker accuracy, causal effect of cube type, free-framing performance or device latency follows from this test.

## Reproduce

The detector is the published Super-Morse YOLO11n ONNX export, 10,669,316 bytes, SHA-256 `f158df4560063686cf90483e21bc611790bc63f6fc2b343a7a4e1dd6b5063dd3`. It expects a 640-square RGB image. Its output includes six sticker colors and a seventh whole-cube class. We omit the whole-cube proposals, use a score cutoff of 0.35 and class-agnostic overlap suppression at 0.35, and retain all remaining sticker proposals. The upstream wrapper selects a single group of nine. The weights embed an AGPL-3.0 license field even though the repository's top-level license says MIT. We used a temporary local copy for evaluation. [Pinned export](https://github.com/Super-Morse/rubiks-visual-detector/blob/b2ed9246c3259dd684b69e36170292bc88a2e02c/app/infrastructure/models/best.onnx) · [Upstream wrapper](https://github.com/Super-Morse/rubiks-visual-detector/blob/b2ed9246c3259dd684b69e36170292bc88a2e02c/app/infrastructure/onnx_detector.py) · [Repository license](https://github.com/Super-Morse/rubiks-visual-detector/blob/b2ed9246c3259dd684b69e36170292bc88a2e02c/LICENSE)

From the worktree root, download the pinned export outside the repository and run the probe:

```sh
run_dir=_bmad-output/planning-artifacts/research/technical-continuous-facelet-scan-2026-11-24
weights=$(mktemp)
curl --fail --location --silent --show-error \
  https://raw.githubusercontent.com/Super-Morse/rubiks-visual-detector/b2ed9246c3259dd684b69e36170292bc88a2e02c/app/infrastructure/models/best.onnx \
  --output "$weights"
uv run --no-project --with onnxruntime==1.32.0 --with opencv-python-headless==5.1.0.95 \
  python "$run_dir/probe_stills.py" "$weights"
```
