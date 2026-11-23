"""Seeded single-face renderer for the sticker reader's training side (Story 10.23). Training only.

Each call returns one upright 96x96 face as the app's unwarp would hand it over, plus its nine labels (indices into
`COLOR_ORDER`, Kotlin cell order). It targets what the Kotlin reads find hard: shaded side faces and backlight, glare
(blown highlights that turn yellow towards white), red against orange and white against yellow, logo centres, a slightly
misplaced quad, oblique foreshortening, then the corner model's camera effects (white balance, blur, YUV 4:2:0, noise).
Palettes come only from training-side groups (the caller passes them), so no tune or held-out calibration leaks in.
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "corner-model"))

from render_cube import DEFAULT_PALETTE_RGB, _apply_camera_effects, lab_to_rgb  # noqa: E402
from PIL import Image  # noqa: E402

from sticker_common import COLOR_ORDER, INPUT_SIZE, unwarp  # noqa: E402

CANVAS = 128
CONFUSABLE = (("RED", "ORANGE"), ("WHITE", "YELLOW"))


def palette_from_centers(centers: dict) -> dict[str, np.ndarray]:
    """An RGB palette from a record's calibration centres (CIELAB)."""
    return {name: lab_to_rgb(centers[name]["l"], centers[name]["a"], centers[name]["b"]) for name in COLOR_ORDER}


def default_palettes() -> list[dict[str, np.ndarray]]:
    return [{k: np.asarray(v, dtype=np.float32) for k, v in DEFAULT_PALETTE_RGB.items()}]


def _labels(rng: np.random.Generator) -> list[int]:
    mode = rng.random()
    if mode < 0.40:
        # Confusable pair: most cells drawn from one of red/orange or white/yellow.
        pair = CONFUSABLE[int(rng.integers(0, 2))]
        labels = [COLOR_ORDER.index(pair[int(rng.integers(0, 2))]) if rng.random() < 0.75 else int(rng.integers(0, 6)) for _ in range(9)]
    elif mode < 0.50:
        # Solved-looking face: one colour, a few others.
        main = int(rng.integers(0, 6))
        labels = [main if rng.random() < 0.8 else int(rng.integers(0, 6)) for _ in range(9)]
    else:
        labels = [int(rng.integers(0, 6)) for _ in range(9)]
    return labels


def _face_texture(rng: np.random.Generator, palette: dict[str, np.ndarray], labels: list[int], stickerless: bool) -> np.ndarray:
    """A CANVAS x CANVAS face with a margin of cube body / background around it (the 3x3 face spans [M, CANVAS - M])."""
    margin = CANVAS // 8
    face = CANVAS - 2 * margin
    if stickerless:
        body = np.full(3, rng.uniform(15, 55), dtype=np.float32)
        gap = float(rng.uniform(0.015, 0.05))
    else:
        b = rng.uniform(8, 45) if rng.random() < 0.9 else rng.uniform(200, 235)
        body = np.array([b, b, b + rng.uniform(-3, 4)], dtype=np.float32)
        gap = float(rng.uniform(0.07, 0.15))
    background = rng.uniform(20, 235, size=3).astype(np.float32)
    canvas = np.broadcast_to(background, (CANVAS, CANVAS, 3)).copy()
    canvas[margin : margin + face, margin : margin + face] = body
    cell = face / 3.0
    pad = max(1, int(round(cell * gap)))
    round_cut = max(1, int(round(cell * (0.05 if stickerless else 0.10))))
    for index, label in enumerate(labels):
        row, col = divmod(index, 3)
        x0 = margin + int(round(col * cell)) + pad
        y0 = margin + int(round(row * cell)) + pad
        x1 = margin + int(round((col + 1) * cell)) - pad
        y1 = margin + int(round((row + 1) * cell)) - pad
        base = palette[COLOR_ORDER[label]].astype(np.float32)
        colour = np.clip(base * rng.uniform(0.9, 1.1) + rng.normal(0.0, 5.0, size=3), 0, 255)
        canvas[y0:y1, x0:x1] = colour
        canvas[y0 : y0 + round_cut, x0 : x0 + round_cut] = body
        canvas[y0 : y0 + round_cut, x1 - round_cut : x1] = body
        canvas[y1 - round_cut : y1, x0 : x0 + round_cut] = body
        canvas[y1 - round_cut : y1, x1 - round_cut : x1] = body
        if index == 4 and rng.random() < 0.35:
            # A logo on the centre sticker (Rubik's, GAN): a dark or saturated mark, the sticker colour around it.
            r = int(cell * rng.uniform(0.12, 0.28))
            cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
            logo = rng.uniform(20, 120, size=3) if rng.random() < 0.6 else rng.uniform(0, 255, size=3)
            canvas[cy - r : cy + r, cx - r : cx + r] = logo
    return canvas


def _light(rng: np.random.Generator, canvas: np.ndarray) -> np.ndarray:
    size = canvas.shape[0]
    # Face-level light: front-lit, side-shaded, or backlit (a dark face).
    mode = rng.random()
    gain = rng.uniform(0.85, 1.15) if mode < 0.45 else rng.uniform(0.55, 0.9) if mode < 0.8 else rng.uniform(0.3, 0.6)
    xs = np.linspace(-1.0, 1.0, size, dtype=np.float32)[None, :]
    ys = np.linspace(-1.0, 1.0, size, dtype=np.float32)[:, None]
    gradient = np.clip(1.0 + rng.uniform(-0.3, 0.3) * xs + rng.uniform(-0.3, 0.3) * ys, 0.45, 1.5)
    tint = np.array([rng.uniform(0.9, 1.1), 1.0, rng.uniform(0.88, 1.12)], dtype=np.float32)
    out = canvas * (gain * gradient)[:, :, None] * tint
    if rng.random() < 0.4:
        # Specular glare: a blob that can saturate a sticker (white and yellow merge, red goes orange-pink).
        cx, cy = rng.uniform(0.2, 0.8, size=2) * size
        sigma = rng.uniform(5.0, 22.0)
        strength = rng.uniform(40.0, 200.0)
        yy, xx = np.mgrid[0:size, 0:size]
        spot = np.exp(-((xx - cx) ** 2 + (yy - cy) ** 2) / (2.0 * sigma * sigma)).astype(np.float32)
        out = out + spot[:, :, None] * strength
    return np.clip(out, 0, 255)


def _view(rng: np.random.Generator, canvas: np.ndarray) -> np.ndarray:
    """The face as an imperfect detector would unwarp it: each corner off by up to ~5% of the face, foreshortened blur."""
    margin = CANVAS // 8
    face = CANVAS - 2 * margin
    corners = np.array([[margin, margin], [CANVAS - margin, margin], [CANVAS - margin, CANVAS - margin], [margin, CANVAS - margin]], dtype=np.float64)
    jitter = rng.normal(0.0, 0.022 * face, size=(4, 2)).clip(-0.06 * face, 0.06 * face)
    if rng.random() < 0.5:
        # A shift of the whole quad (the detector lands consistently off).
        jitter += rng.uniform(-0.04, 0.04, size=2) * face
    crop = unwarp(canvas, corners + jitter, INPUT_SIZE)
    if rng.random() < 0.5:
        # Oblique side face: one axis sampled coarser, then back up (the unwarp of a foreshortened face).
        axis = int(rng.integers(0, 2))
        factor = rng.uniform(0.35, 0.8)
        small = (INPUT_SIZE, max(8, int(INPUT_SIZE * factor))) if axis == 0 else (max(8, int(INPUT_SIZE * factor)), INPUT_SIZE)
        image = Image.fromarray(np.clip(np.rint(crop), 0, 255).astype(np.uint8))
        crop = np.asarray(image.resize(small, Image.Resampling.BILINEAR).resize((INPUT_SIZE, INPUT_SIZE), Image.Resampling.BILINEAR), dtype=np.float32)
    return crop


def render_face(rng: np.random.Generator, palettes: list[dict[str, np.ndarray]]) -> tuple[np.ndarray, list[int]]:
    """One synthetic face: (96x96x3 float32 RGB in [0, 1], nine labels in Kotlin cell order)."""
    palette = palettes[int(rng.integers(0, len(palettes)))]
    labels = _labels(rng)
    canvas = _face_texture(rng, palette, labels, stickerless=bool(rng.random() < 0.5))
    canvas = _light(rng, canvas)
    crop = _view(rng, canvas)
    image = Image.fromarray(np.clip(np.rint(crop), 0, 255).astype(np.uint8))
    return _apply_camera_effects(rng, image), labels


def generate(count: int, seed: int, palettes: list[dict[str, np.ndarray]]) -> tuple[np.ndarray, np.ndarray]:
    """`count` faces as uint8 [N, 96, 96, 3] and int labels [N, 9]; the same seed gives the same arrays."""
    rng = np.random.default_rng(seed)
    images = np.empty((count, INPUT_SIZE, INPUT_SIZE, 3), dtype=np.uint8)
    labels = np.empty((count, 9), dtype=np.int64)
    for i in range(count):
        image, label = render_face(rng, palettes)
        images[i] = np.clip(np.rint(image * 255.0), 0, 255).astype(np.uint8)
        labels[i] = label
    return images, labels


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="Render a preview sheet of synthetic faces.")
    parser.add_argument("--count", type=int, default=32)
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--out", type=Path, default=Path(__file__).resolve().parent / "artifacts/synthetic-preview.png")
    args = parser.parse_args()
    images, labels = generate(args.count, args.seed, default_palettes())
    cols = 8
    rows = (args.count + cols - 1) // cols
    sheet = Image.new("RGB", (cols * INPUT_SIZE, rows * INPUT_SIZE))
    for i, image in enumerate(images):
        sheet.paste(Image.fromarray(image), ((i % cols) * INPUT_SIZE, (i // cols) * INPUT_SIZE))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(args.out)
    print(f"wrote {args.out}")
