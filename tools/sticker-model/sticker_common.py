"""Shared constants and pure helpers for the Story 10.23 shadow sticker reader pipeline (`tools/sticker-model/`).

Nothing here is random: every function is a pure transform, so the assembly built on it is byte-identical across runs.
"""

from __future__ import annotations

import hashlib
import json
import math
from pathlib import Path
from typing import Sequence

import numpy as np
from PIL import Image

TOOL_DIR = Path(__file__).resolve().parent
REPO_ROOT = TOOL_DIR.parents[1]
ARTIFACTS = TOOL_DIR / "artifacts"
EVIDENCE = REPO_ROOT / "docs/evidence/10-23"
HOLDOUT_FILE = TOOL_DIR / "holdout.json"
SPLIT_FILE = TOOL_DIR / "split.json"
MODEL_CARD = TOOL_DIR / "model-card.json"
MODEL_OUTPUT = REPO_ROOT / "app/src/camera/debug/sticker/assets/sticker_reader.tflite"
CORNER_MODEL = REPO_ROOT / "app/src/camera/corner/assets/corner_keypoints.tflite"

# The model contract (README): input [1, 96, 96, 3] float32 RGB in [0, 1]; output [1, 9, 6] softmax in Kotlin cell order.
INPUT_SIZE = 96
CELLS = 9
COLOR_ORDER = ("WHITE", "YELLOW", "RED", "ORANGE", "BLUE", "GREEN")
COLOR_INDEX = {name: i for i, name in enumerate(COLOR_ORDER)}
MODEL_SIZE_LIMIT = 500 * 1024  # bytes; the exported .tflite must be strictly smaller
DEFAULT_SEED = 20261123
TAU_GRID = tuple(round(0.50 + 0.01 * i, 2) for i in range(50))  # 0.50 .. 0.99

# The app's camera geometry (FrameReader.GRID_FRACTION, CORNER_MODEL_CROP_RADIUS_MULTIPLIER, CornerReader ROI).
GRID_FRACTION = 0.8
CORNER_CROP_RADIUS_MULTIPLIER = 1.65
CORNER_MODEL_SIZE = 128
CORNER_PRESENCE = 0.55
CORNER_CELL_ROI = 0.48
GUIDED_CELL_ROI = 0.60

SIDES = ("train", "tune", "heldout")


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def dump_json(data: object) -> str:
    """Deterministic JSON text (sorted keys, fixed separators, trailing newline)."""
    return json.dumps(data, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def load_rgb(path: Path) -> np.ndarray:
    with Image.open(path) as image:
        return np.asarray(image.convert("RGB"), dtype=np.float32)


# --- colour ---------------------------------------------------------------------------------------------------------

_LINEAR = np.array(
    [c / 255.0 / 12.92 if c / 255.0 <= 0.04045 else ((c / 255.0 + 0.055) / 1.055) ** 2.4 for c in range(256)],
    dtype=np.float64,
)


def rgb_to_lab(rgb: np.ndarray) -> np.ndarray:
    """sRGB (0..255, any shape [..., 3]) to CIELAB D65 with the recorder's formula (`FrameReader.lab`)."""
    idx = np.clip(np.rint(rgb), 0, 255).astype(np.int64)
    lin = _LINEAR[idx]
    r, g, b = lin[..., 0], lin[..., 1], lin[..., 2]
    x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
    y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
    z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883

    def f(v: np.ndarray) -> np.ndarray:
        return np.where(v > 0.0088564517, np.cbrt(v), 7.787037 * v + 16.0 / 116.0)

    fx, fy, fz = f(x), f(y), f(z)
    return np.stack([np.clip(116.0 * fy - 16.0, 0.0, 100.0), 500.0 * (fx - fy), 200.0 * (fy - fz)], axis=-1)


def delta_e(a: Sequence[float], b: Sequence[float]) -> float:
    return float(math.sqrt(sum((float(x) - float(y)) ** 2 for x, y in zip(a, b))))


# --- geometry (ports of CornerLocator.kt) ---------------------------------------------------------------------------


def homography(quad: np.ndarray):
    """`Homography.from`: maps the unit square to [TL, TR, BR, BL]; None when degenerate."""
    a, b, c, d = (np.asarray(p, dtype=np.float64) for p in quad)
    u = b - c
    v = d - c
    w = a - b + c - d
    det = u[0] * v[1] - u[1] * v[0]
    if abs(det) < 1e-6:
        return None
    g = (w[0] * v[1] - w[1] * v[0]) / det
    h = (u[0] * w[1] - u[1] * w[0]) / det
    if min(1 + g, 1 + h, 1 + g + h) <= 0:
        return None
    horizontal = b * (1 + g) - a
    vertical = d * (1 + h) - a

    def apply(x: np.ndarray, y: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        scale = 1.0 / (1.0 + g * x + h * y)
        return (a[0] + horizontal[0] * x + vertical[0] * y) * scale, (a[1] + horizontal[1] * x + vertical[1] * y) * scale

    return apply


def unwarp(image: np.ndarray, quad: np.ndarray, side: int = INPUT_SIZE) -> np.ndarray | None:
    """`unwarp()`: the quad [TL, TR, BR, BL] resampled bilinearly into an upright side x side RGB crop (float32 0..255)."""
    transform = homography(quad)
    if transform is None:
        return None
    height, width = image.shape[:2]
    grid = (np.arange(side, dtype=np.float64) + 0.5) / side
    gx, gy = np.meshgrid(grid, grid)
    px, py = transform(gx, gy)
    x = np.clip(px, 0.0, width - 1.0)
    y = np.clip(py, 0.0, height - 1.0)
    x0 = np.floor(x).astype(np.int64)
    y0 = np.floor(y).astype(np.int64)
    x1 = np.minimum(x0 + 1, width - 1)
    y1 = np.minimum(y0 + 1, height - 1)
    fx = (x - x0)[..., None]
    fy = (y - y0)[..., None]
    top = image[y0, x0] * (1 - fx) + image[y0, x1] * fx
    bottom = image[y1, x0] * (1 - fx) + image[y1, x1] * fx
    return (top * (1 - fy) + bottom * fy).astype(np.float32)


def rect_quad(left: float, top: float, right: float, bottom: float) -> np.ndarray:
    return np.array([[left, top], [right, top], [right, bottom], [left, bottom]], dtype=np.float64)


def guide_quads(points: np.ndarray) -> list[np.ndarray]:
    """`CornerGuide.quads`: [U/D, F/L, R/B] from the 7 keypoints [T, UL, LL, B, LR, UR, C]."""
    t, ul, ll, b, lr, ur, c = (np.asarray(p, dtype=np.float64) for p in points)
    return [np.array([t, ur, c, ul]), np.array([ul, c, b, ll]), np.array([c, ur, lr, b])]


def valid_cube_geometry(points: np.ndarray, center: np.ndarray, radius: float) -> bool:
    """`CornerLocator.isValidCubeGeometry`."""
    if len(points) != 7:
        return False
    if math.hypot(*(points[6] - center)) > radius * 0.55:
        return False
    min_edge = radius * 0.24
    for a, b, d, e in guide_quads(points):
        top, bottom, left, right = b - a, d - e, e - a, d - b
        lens = [math.hypot(*v) for v in (top, bottom, left, right)]
        if min(lens) < min_edge:
            return False
        cos_h = float(np.dot(top, bottom)) / (lens[0] * lens[1])
        cos_v = float(np.dot(left, right)) / (lens[2] * lens[3])
        if cos_h < 0.82 or cos_v < 0.82:
            return False
        if not (0.5 <= lens[0] / lens[1] <= 2.0 and 0.5 <= lens[2] / lens[3] <= 2.0):
            return False
    return True


def cell_means(crop: np.ndarray, roi: float) -> list[np.ndarray]:
    """Mean RGB of each cell's central `roi` share, row-major (Kotlin cell order)."""
    side = crop.shape[0]
    cell = side / 3.0
    inset = (1.0 - roi) / 2.0
    means = []
    for row in range(3):
        for col in range(3):
            x0 = int(math.floor((col + inset) * cell))
            x1 = int(math.floor((col + 1 - inset) * cell))
            y0 = int(math.floor((row + inset) * cell))
            y1 = int(math.floor((row + 1 - inset) * cell))
            means.append(crop[y0:y1, x0:x1].reshape(-1, 3).mean(axis=0))
    return means


def to_uint8(crop: np.ndarray) -> np.ndarray:
    return np.clip(np.rint(crop), 0, 255).astype(np.uint8)


def rotate_labels(labels: Sequence[int], turns: int) -> list[int]:
    """Labels of a 3x3 face after rotating its image `turns` quarter turns counter-clockwise (`np.rot90`)."""
    grid = np.asarray(labels).reshape(3, 3)
    return [int(v) for v in np.rot90(grid, turns).reshape(-1)]
