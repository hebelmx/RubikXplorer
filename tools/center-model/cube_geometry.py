"""Self-contained 3x3 Rubik's Cube 3D geometry and pure-NumPy camera/color helpers for `tools/center-model/`.

Home frame (matches Cubentia AGENTS.md):
  U = WHITE (+Y), R = RED (+X), F = GREEN (+Z), D = YELLOW (-Y), L = ORANGE (-X), B = BLUE (-Z).
"""

from __future__ import annotations

import math
from pathlib import Path
from typing import Dict, List, Sequence, Tuple

import numpy as np
from PIL import Image

FACES: Tuple[str, ...] = ("U", "R", "F", "D", "L", "B")

# Orthonormal 3D face basis: (normal, right, down) matching Kociemba URFDLB row-major layout.
FACE_BASIS: Dict[str, Tuple[np.ndarray, np.ndarray, np.ndarray]] = {
    "U": (np.array([0.0, 1.0, 0.0]), np.array([1.0, 0.0, 0.0]), np.array([0.0, 0.0, 1.0])),
    "R": (np.array([1.0, 0.0, 0.0]), np.array([0.0, 0.0, -1.0]), np.array([0.0, -1.0, 0.0])),
    "F": (np.array([0.0, 0.0, 1.0]), np.array([1.0, 0.0, 0.0]), np.array([0.0, -1.0, 0.0])),
    "D": (np.array([0.0, -1.0, 0.0]), np.array([1.0, 0.0, 0.0]), np.array([0.0, 0.0, -1.0])),
    "L": (np.array([-1.0, 0.0, 0.0]), np.array([0.0, 0.0, 1.0]), np.array([0.0, -1.0, 0.0])),
    "B": (np.array([0.0, 0.0, -1.0]), np.array([-1.0, 0.0, 0.0]), np.array([0.0, -1.0, 0.0])),
}

# App camera geometry constants matching FrameReader / CornerModelTransform / CornerReader.
GRID_FRACTION = 0.8
CORNER_CROP_RADIUS_MULTIPLIER = 1.65
CORNER_CELL_ROI = 0.48


def face_quad_3d(face: str) -> np.ndarray:
    """Return 4x3 3D coordinates of [TL, TR, BR, BL] corners of `face` on [-1, 1]^3."""
    n, r, d = FACE_BASIS[face]
    return np.stack([n - r - d, n + r - d, n + r + d, n - r + d], axis=0)


def sticker_center_3d(face: str, cell_idx: int) -> np.ndarray:
    """3D center of sticker `cell_idx` (0..8 row-major) on [-1, 1]^3."""
    row, col = divmod(cell_idx, 3)
    n, r, d = FACE_BASIS[face]
    u = (col - 1) * (2.0 / 3.0)
    v = (row - 1) * (2.0 / 3.0)
    return n + u * r + v * d


def load_rgb(path: Path) -> np.ndarray:
    with Image.open(path) as image:
        return np.asarray(image.convert("RGB"), dtype=np.float32)


def rect_quad(left: float, top: float, right: float, bottom: float) -> np.ndarray:
    return np.array([[left, top], [right, top], [right, bottom], [left, bottom]], dtype=np.float64)


def guide_quads(points: Sequence[Sequence[float]]) -> List[np.ndarray]:
    """Map 7 corner keypoints [T, UL, LL, B, LR, UR, C] to the 3 face quads [top, left, right]."""
    t, ul, ll, b, lr, ur, c = (np.asarray(p, dtype=np.float64) for p in points)
    return [
        np.stack([t, ur, c, ul], axis=0),
        np.stack([ul, c, b, ll], axis=0),
        np.stack([c, ur, lr, b], axis=0),
    ]


def homography(quad: np.ndarray):
    """Map the unit square [0, 1]^2 to [TL, TR, BR, BL]; returns None when degenerate."""
    a, b, c, d = (np.asarray(p, dtype=np.float64) for p in quad)
    u = b - c
    v = d - c
    w = a - b + c - d
    det = u[0] * v[1] - u[1] * v[0]
    if abs(det) < 1e-6:
        return None
    g = (w[0] * v[1] - w[1] * v[0]) / det
    h = (u[0] * w[1] - u[1] * w[0]) / det
    if min(1.0 + g, 1.0 + h, 1.0 + g + h) <= 0.0:
        return None
    horizontal = b * (1.0 + g) - a
    vertical = d * (1.0 + h) - a

    def apply(x: np.ndarray, y: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
        scale = 1.0 / (1.0 + g * x + h * y)
        return (a[0] + horizontal[0] * x + vertical[0] * y) * scale, (
            a[1] + horizontal[1] * x + vertical[1] * y
        ) * scale

    return apply


def unwarp(image: np.ndarray, quad: np.ndarray, side: int = 96) -> np.ndarray | None:
    """Bilinearly unwarp [TL, TR, BR, BL] into an upright `side x side` RGB float32 crop."""
    h_fn = homography(quad)
    if h_fn is None:
        return None
    height, width = image.shape[:2]
    grid = (np.arange(side, dtype=np.float64) + 0.5) / side
    gx, gy = np.meshgrid(grid, grid)
    xs, ys = h_fn(gx, gy)
    xs = np.clip(xs, 0.0, width - 1.0)
    ys = np.clip(ys, 0.0, height - 1.0)
    x0 = np.floor(xs).astype(np.int64)
    y0 = np.floor(ys).astype(np.int64)
    x1 = np.minimum(x0 + 1, width - 1)
    y1 = np.minimum(y0 + 1, height - 1)
    fx = (xs - x0)[..., None]
    fy = (ys - y0)[..., None]
    top = image[y0, x0] * (1.0 - fx) + image[y0, x1] * fx
    bot = image[y1, x0] * (1.0 - fx) + image[y1, x1] * fx
    return (top * (1.0 - fy) + bot * fy).astype(np.float32)


def cell_means(crop: np.ndarray, roi_fraction: float = CORNER_CELL_ROI) -> List[np.ndarray]:
    """Mean RGB inside the central `roi_fraction` of each 3x3 cell of `crop`."""
    side = crop.shape[0]
    cell = side / 3.0
    pad = (1.0 - roi_fraction) / 2.0
    out: List[np.ndarray] = []
    for r in range(3):
        for c in range(3):
            y0 = max(0, int(round((r + pad) * cell)))
            y1 = min(side, int(round((r + 1.0 - pad) * cell)))
            x0 = max(0, int(round((c + pad) * cell)))
            x1 = min(side, int(round((c + 1.0 - pad) * cell)))
            out.append(crop[y0:y1, x0:x1].reshape(-1, 3).mean(axis=0))
    return out


_LINEAR = np.array(
    [c / 255.0 / 12.92 if c / 255.0 <= 0.04045 else ((c / 255.0 + 0.055) / 1.055) ** 2.4 for c in range(256)],
    dtype=np.float64,
)


def rgb_to_lab(rgb: np.ndarray) -> np.ndarray:
    """Convert sRGB in [0, 255] to D65 CIELAB."""
    idx = np.clip(np.rint(rgb), 0, 255).astype(np.int64)
    lin = _LINEAR[idx]
    r, g, b = lin[..., 0], lin[..., 1], lin[..., 2]
    x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
    y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
    z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883

    def _f(v: np.ndarray) -> np.ndarray:
        return np.where(v > 0.0088564517, np.cbrt(v), 7.787037 * v + 16.0 / 116.0)

    fx, fy, fz = _f(x), _f(y), _f(z)
    return np.stack([np.clip(116.0 * fy - 16.0, 0.0, 100.0), 500.0 * (fx - fy), 200.0 * (fy - fz)], axis=-1)


def delta_e(a: Sequence[float], b: Sequence[float]) -> float:
    return float(math.sqrt(sum((float(x) - float(y)) ** 2 for x, y in zip(a, b))))
