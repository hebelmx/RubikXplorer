"""Synthetic 3D Rubik's cube renderer with real device palettes, real warped faces,
hands, cluttered desks, dark glossy reflections (#196), and hard negatives.

Coordinate contract (matches CornerModelTransform.kt & CornerLocator.projectCube):
- Upright square crop of size `img_size x img_size` (default 128x128) centered on the guide.
- `crop_half = guide_radius * 1.65`, so `guide_radius` in normalized crop units is `1 / 3.30`.
- Keypoint order (7 points, normalized to [0, 1] x [0, 1]):
    0: T  (-1,  1, -1)
    1: UL (-1,  1,  1)
    2: LL (-1, -1,  1)
    3: B  ( 1, -1,  1)
    4: LR ( 1, -1, -1)
    5: UR ( 1,  1, -1)
    6: C  ( 1,  1,  1)  (nearest corner)
- Visible quads (TL, TR, BR, BL):
    Face 0 (Top U/D):        [T, UR, C, UL]
    Face 1 (Front-left F/L): [UL, C, B, LL]
    Face 2 (Front-right R/B):[C, UR, LR, B]
"""

from __future__ import annotations

import json
import math
from concurrent.futures import ProcessPoolExecutor
from dataclasses import dataclass
import os
from pathlib import Path
from typing import Sequence

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

CROP_RADIUS_MULTIPLIER = 1.65
KEYPOINT_NAMES = ("T", "UL", "LL", "B", "LR", "UR", "C")
CUBE_CORNERS_3D = np.array(
    [
        [-1.0, 1.0, -1.0],   # T
        [-1.0, 1.0, 1.0],    # UL
        [-1.0, -1.0, 1.0],   # LL
        [1.0, -1.0, 1.0],    # B
        [1.0, -1.0, -1.0],   # LR
        [1.0, 1.0, -1.0],    # UR
        [1.0, 1.0, 1.0],     # C
    ],
    dtype=np.float64,
)
QUAD_INDICES = (
    (0, 5, 6, 1),  # Top face:        T, UR, C, UL
    (1, 6, 3, 2),  # Front-left face: UL, C, B, LL
    (6, 5, 4, 3),  # Front-right:     C, UR, LR, B
)

DEFAULT_PALETTE_RGB = {
    "WHITE": np.array([242, 240, 236], dtype=np.float32),
    "YELLOW": np.array([234, 218, 25], dtype=np.float32),
    "RED": np.array([210, 45, 48], dtype=np.float32),
    "ORANGE": np.array([237, 130, 40], dtype=np.float32),
    "GREEN": np.array([62, 180, 74], dtype=np.float32),
    "BLUE": np.array([45, 114, 218], dtype=np.float32),
}
COLOR_KEYS = ("WHITE", "YELLOW", "RED", "ORANGE", "GREEN", "BLUE")
HOLD_CENTERS = (
    ("WHITE", "GREEN", "RED"),
    ("YELLOW", "ORANGE", "BLUE"),
)


def lab_to_rgb(l: float, a: float, b: float) -> np.ndarray:
    """Convert CIELAB (D65) to sRGB in [0, 255]."""
    fy = (l + 16.0) / 116.0
    fx = fy + a / 500.0
    fz = fy - b / 200.0

    def inv(f: float) -> float:
        return f**3 if f**3 > 0.0088564517 else (f - 16.0 / 116.0) / 7.787037

    x = inv(fx) * 0.95047
    y = inv(fy)
    z = inv(fz) * 1.08883

    r_lin = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
    g_lin = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
    b_lin = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z

    def gamma(c: float) -> float:
        c = max(0.0, min(1.0, c))
        return 12.92 * c if c <= 0.0031308 else 1.055 * (c ** (1.0 / 2.4)) - 0.055

    return np.array([gamma(r_lin) * 255.0, gamma(g_lin) * 255.0, gamma(b_lin) * 255.0], dtype=np.float32)


@dataclass
class FixtureAssets:
    palettes: list[dict[str, np.ndarray]]
    face_crops: list[tuple[str, np.ndarray]]  # (group_name, HxWx3 uint8)


def load_live_fixtures(fixtures_live_dir: Path, exclude_suffixes: Sequence[str] = ()) -> FixtureAssets:
    """Load real calibration palettes and cropped 3x3 face photos from Fixtures/live/."""
    palettes: list[dict[str, np.ndarray]] = [DEFAULT_PALETTE_RGB]
    face_crops: list[tuple[str, np.ndarray]] = []
    if not fixtures_live_dir.is_dir():
        return FixtureAssets(palettes, face_crops)

    for group_dir in sorted(fixtures_live_dir.iterdir()):
        if not group_dir.is_dir():
            continue
        if any(group_dir.name.endswith(s) for s in exclude_suffixes):
            continue
        u_json = group_dir / "U.json"
        if u_json.is_file():
            data = json.loads(u_json.read_text(encoding="utf-8"))
            centers = data.get("calibration", {}).get("centers", {})
            if all(k in centers for k in COLOR_KEYS):
                pal = {k: lab_to_rgb(centers[k]["l"], centers[k]["a"], centers[k]["b"]) for k in COLOR_KEYS}
                palettes.append(pal)
        for face_name in ("U", "F", "R", "D", "L", "B"):
            jpg = group_dir / f"{face_name}.jpg"
            if jpg.is_file():
                with Image.open(jpg) as im:
                    rgb = im.convert("RGB")
                    w, h = rgb.size
                    side = int(min(w, h) * 0.80)
                    left = (w - side) // 2
                    top = (h - side) // 2
                    crop = rgb.crop((left, top, left + side, top + side)).resize((96, 96), Image.Resampling.BILINEAR)
                    face_crops.append((group_dir.name, np.asarray(crop, dtype=np.uint8)))
    return FixtureAssets(palettes, face_crops)


def project_cube_normalized(
    center_uv: tuple[float, float],
    radius_ratio: float,
    pitch_deg: float,
    yaw_deg: float,
    roll_deg: float,
    persp: float,
) -> np.ndarray:
    """Project the 7 cube corners into normalized upright crop coords [0, 1]^2.

    Matches `CornerLocator.projectCube` with `frameRotation = 0` and
    `guide.radius = 1 / (2 * CROP_RADIUS_MULTIPLIER)`.
    """
    guide_radius = 1.0 / (2.0 * CROP_RADIUS_MULTIPLIER)
    radius = guide_radius * radius_ratio
    yaw = math.radians(45.0 + yaw_deg)
    pitch = math.radians(pitch_deg)
    roll = math.radians(-roll_deg)

    cos_y, sin_y = math.cos(yaw), math.sin(yaw)
    cos_p, sin_p = math.cos(pitch), math.sin(pitch)
    cos_r, sin_r = math.cos(roll), math.sin(roll)
    unit_scale = radius / 1.632993161855452

    cx_arr = CUBE_CORNERS_3D[:, 0]
    cy_arr = CUBE_CORNERS_3D[:, 1]
    cz_arr = CUBE_CORNERS_3D[:, 2]

    x1 = cx_arr * cos_y - cz_arr * sin_y
    z1 = cx_arr * sin_y + cz_arr * cos_y
    y2 = cy_arr * cos_p - z1 * sin_p
    z2 = cy_arr * sin_p + z1 * cos_p

    depth = np.maximum(1.0 - z2 * persp / 1.732, 0.35)
    px = x1 / depth
    py = -y2 / depth

    rx = px * cos_r - py * sin_r
    ry = px * sin_r + py * cos_r

    pts = np.empty((7, 2), dtype=np.float32)
    pts[:, 0] = center_uv[0] + rx * unit_scale
    pts[:, 1] = center_uv[1] + ry * unit_scale
    return pts


def is_valid_geometry_normalized(pts: np.ndarray) -> bool:
    """Exact mirror of `CornerLocator.isValidCubeGeometry` in normalized crop units."""
    guide_radius = 1.0 / (2.0 * CROP_RADIUS_MULTIPLIER)
    c = pts[6]
    if math.hypot(float(c[0] - 0.5), float(c[1] - 0.5)) > guide_radius * 0.55:
        return False
    min_edge = guide_radius * 0.24
    for i0, i1, i2, i3 in QUAD_INDICES:
        a, b, d, e = pts[i0], pts[i1], pts[i2], pts[i3]
        top = b - a
        bottom = d - e
        left = e - a
        right = d - b
        top_len = float(np.hypot(top[0], top[1]))
        bot_len = float(np.hypot(bottom[0], bottom[1]))
        left_len = float(np.hypot(left[0], left[1]))
        right_len = float(np.hypot(right[0], right[1]))
        if min(top_len, bot_len, left_len, right_len) < min_edge:
            return False
        cos_h = float(np.dot(top, bottom)) / (top_len * bot_len)
        cos_v = float(np.dot(left, right)) / (left_len * right_len)
        if not (cos_h >= 0.82 and cos_v >= 0.82):
            return False
        if not (0.50 <= top_len / bot_len <= 2.00 and 0.50 <= left_len / right_len <= 2.00):
            return False
    return True


def _quad_coeffs(dst_quad: np.ndarray, src_size: float) -> tuple[float, ...]:
    """PIL PERSPECTIVE transform coefficients mapping destination pixel -> source pixel."""
    # dst_quad: 4x2 (TL, TR, BR, BL) in pixel coordinates
    src = np.array(
        [[0.0, 0.0], [src_size, 0.0], [src_size, src_size], [0.0, src_size]],
        dtype=np.float64,
    )
    matrix = []
    rhs = []
    for (dx, dy), (sx, sy) in zip(dst_quad, src):
        matrix.append([dx, dy, 1.0, 0.0, 0.0, 0.0, -sx * dx, -sx * dy])
        rhs.append(sx)
        matrix.append([0.0, 0.0, 0.0, dx, dy, 1.0, -sy * dx, -sy * dy])
        rhs.append(sy)
    coeffs = np.linalg.solve(np.asarray(matrix, dtype=np.float64), np.asarray(rhs, dtype=np.float64))
    return tuple(float(c) for c in coeffs)


def _render_face_texture(
    rng: np.random.Generator,
    palette: dict[str, np.ndarray],
    center_color: str,
    solved: bool,
    stickerless: bool,
    shade: float,
    real_crop: np.ndarray | None = None,
    tex_size: int = 96,
) -> Image.Image:
    """Render a 96x96 face texture (either synthetic stickered/stickerless or real photo)."""
    if real_crop is not None:
        arr = real_crop.astype(np.float32)
        if rng.random() < 0.75:
            k = int(rng.integers(0, 4))
            arr = np.rot90(arr, k)
        arr = np.clip(arr * shade, 0, 255)
    else:
        if stickerless:
            gap_frac = float(rng.uniform(0.015, 0.045))
            body_val = float(rng.uniform(18, 55))
            body = np.array([body_val, body_val, body_val], dtype=np.float32)
        else:
            gap_frac = float(rng.uniform(0.075, 0.145))
            if rng.random() < 0.88:
                b_val = float(rng.uniform(12, 46))
                body = np.array([b_val, b_val, b_val + rng.uniform(-3, 4)], dtype=np.float32)
            else:
                # Occasional white-body or dark-grey cube
                b_val = float(rng.uniform(210, 240)) if rng.random() < 0.5 else float(rng.uniform(55, 85))
                body = np.array([b_val, b_val, b_val], dtype=np.float32)

        arr = np.broadcast_to(body * shade, (tex_size, tex_size, 3)).copy()
        cell = tex_size / 3.0
        pad = max(1, int(round(cell * gap_frac)))
        corner_cut = max(1, int(round(cell * (0.04 if stickerless else 0.09))))

        # Choose 9 sticker colors
        colors = [center_color] * 9 if solved else [str(rng.choice(COLOR_KEYS)) for _ in range(9)]
        colors[4] = center_color

        for idx, cname in enumerate(colors):
            r_idx, c_idx = divmod(idx, 3)
            x0 = int(round(c_idx * cell)) + pad
            y0 = int(round(r_idx * cell)) + pad
            x1 = int(round((c_idx + 1) * cell)) - pad
            y1 = int(round((r_idx + 1) * cell)) - pad
            if x1 <= x0 or y1 <= y0:
                continue
            base_col = palette[cname] * rng.uniform(0.92, 1.08) + rng.normal(0.0, 4.0, size=3)
            base_col = np.clip(base_col * shade, 0, 255)
            arr[y0:y1, x0:x1] = base_col
            # Slight rounded corners for stickered or beveled stickerless tiles
            if corner_cut > 0 and (x1 - x0) > 2 * corner_cut and (y1 - y0) > 2 * corner_cut:
                arr[y0 : y0 + corner_cut, x0 : x0 + corner_cut] = body * shade
                arr[y0 : y0 + corner_cut, x1 - corner_cut : x1] = body * shade
                arr[y1 - corner_cut : y1, x0 : x0 + corner_cut] = body * shade
                arr[y1 - corner_cut : y1, x1 - corner_cut : x1] = body * shade

        # Occasional center logo on white/yellow center
        if center_color in ("WHITE", "YELLOW") and rng.random() < 0.35:
            cx, cy = tex_size // 2, tex_size // 2
            rad = int(rng.integers(4, 9))
            logo_col = rng.uniform(25, 110, size=3) * shade
            arr[cy - rad : cy + rad, cx - rad : cx + rad] = logo_col

    # Linear illumination gradient across the face
    gx = float(rng.uniform(-0.14, 0.14))
    gy = float(rng.uniform(-0.14, 0.14))
    xs = np.linspace(-1.0, 1.0, tex_size, dtype=np.float32)[None, :]
    ys = np.linspace(-1.0, 1.0, tex_size, dtype=np.float32)[:, None]
    grad = np.clip(1.0 + gx * xs + gy * ys, 0.65, 1.30)
    arr = np.clip(arr * grad[:, :, None], 0, 255)

    # Occasional specular glare blob on a face
    if rng.random() < 0.35:
        sx = float(rng.uniform(0.15, 0.85)) * tex_size
        sy = float(rng.uniform(0.15, 0.85)) * tex_size
        sigma = float(rng.uniform(6.0, 18.0))
        strength = float(rng.uniform(45.0, 130.0))
        yy, xx = np.mgrid[0:tex_size, 0:tex_size]
        spot = np.exp(-((xx - sx) ** 2 + (yy - sy) ** 2) / (2.0 * sigma * sigma)).astype(np.float32)
        arr = np.clip(arr + spot[:, :, None] * strength, 0, 255)

    return Image.fromarray(arr.astype(np.uint8), mode="RGB")


def _render_background(rng: np.random.Generator, size: int, force_dark_glossy: bool = False) -> tuple[Image.Image, bool]:
    """Generate diverse indoor backgrounds: plain light, wood, cluttered desk, or dark glossy (#196)."""
    mode = "dark_glossy" if force_dark_glossy else str(
        rng.choice(["plain_light", "plain_mid", "wood_desk", "cluttered", "dark_glossy"], p=[0.24, 0.16, 0.20, 0.22, 0.18])
    )
    if mode == "plain_light":
        base = rng.uniform(185, 245, size=3) + rng.normal(0, 8, size=3)
        img = np.broadcast_to(np.clip(base, 155, 252), (size, size, 3)).copy()
    elif mode == "plain_mid":
        base = rng.uniform(75, 175, size=3)
        img = np.broadcast_to(base, (size, size, 3)).copy()
    elif mode == "wood_desk":
        base = np.array([rng.uniform(110, 190), rng.uniform(75, 145), rng.uniform(45, 105)], dtype=np.float32)
        img = np.broadcast_to(base, (size, size, 3)).copy()
        stripes = np.sin(np.linspace(0, rng.uniform(10, 35), size, dtype=np.float32) + rng.uniform(0, 6.28)) * rng.uniform(6, 20)
        if rng.random() < 0.5:
            img += stripes[:, None, None]
        else:
            img += stripes[None, :, None]
    elif mode == "dark_glossy":
        base = rng.uniform(10, 48, size=3)
        img = np.broadcast_to(base, (size, size, 3)).copy()
        # Glossy sheen gradient across dark table
        angle = float(rng.uniform(0, math.pi))
        yy, xx = np.mgrid[0:size, 0:size].astype(np.float32) / size - 0.5
        sheen = np.clip(np.cos(angle) * xx + np.sin(angle) * yy + 0.3, 0.0, 1.0) ** 2 * rng.uniform(18, 65)
        img += sheen[:, :, None]
    else:  # cluttered desk
        base = rng.uniform(55, 210, size=3)
        img = np.broadcast_to(base, (size, size, 3)).copy()

    # Smooth lighting gradient
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32) / size - 0.5
    img += (rng.uniform(-22, 22) * xx + rng.uniform(-22, 22) * yy)[:, :, None]
    pil = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), mode="RGB")

    if mode in ("cluttered", "wood_desk") or (mode == "dark_glossy" and rng.random() < 0.45):
        draw = ImageDraw.Draw(pil)
        n_shapes = int(rng.integers(4, 14)) if mode == "cluttered" else int(rng.integers(1, 5))
        for _ in range(n_shapes):
            col = tuple(int(c) for c in rng.integers(15, 240, size=3))
            x0, y0 = int(rng.integers(-20, size)), int(rng.integers(-20, size))
            x1 = x0 + int(rng.integers(12, size // 2 + 16))
            y1 = y0 + int(rng.integers(12, size // 2 + 16))
            shape_type = int(rng.integers(0, 3))
            if shape_type == 0:
                draw.rectangle([x0, y0, x1, y1], fill=col)
            elif shape_type == 1:
                draw.ellipse([x0, y0, x1, y1], fill=col)
            else:
                draw.line([x0, y0, x1, y1], fill=col, width=int(rng.integers(2, 7)))
        if rng.random() < 0.5:
            pil = pil.filter(ImageFilter.GaussianBlur(radius=float(rng.uniform(0.6, 2.0))))
    return pil, mode == "dark_glossy"


def _draw_hand(rng: np.random.Generator, canvas: Image.Image, pts_px: np.ndarray | None, size: int) -> None:
    """Draw realistic fingers/hand gripping the bottom or side of the cube (or an empty hand negative)."""
    draw = ImageDraw.Draw(canvas)
    skin_tones = (
        (235, 195, 165),
        (215, 168, 132),
        (185, 135, 98),
        (145, 96, 68),
        (102, 66, 48),
    )
    base = np.array(skin_tones[int(rng.integers(0, len(skin_tones)))], dtype=np.float32) * rng.uniform(0.82, 1.12)
    base_col = tuple(int(c) for c in np.clip(base, 25, 250))
    shadow_col = tuple(int(c) for c in np.clip(base * 0.78, 15, 240))

    if pts_px is not None:
        # Pick 1 or 2 grip anchors near B (3), LL (2), or LR (4)
        anchors = [3] if rng.random() < 0.5 else [2, 4]
        for idx in anchors:
            ax, ay = float(pts_px[idx, 0]), float(pts_px[idx, 1])
            # Finger coming in from the bottom/side edge toward the cube corner
            edge_x = ax + float(rng.uniform(-24, 24))
            edge_y = float(size + 14) if idx == 3 else ay + float(rng.uniform(22, 45))
            if idx == 2:
                edge_x = float(-14)
            elif idx == 4:
                edge_x = float(size + 14)
            fw = float(rng.uniform(9, 17))
            # Slightly overlap the outer edge without hiding the whole cube
            tip_x = ax + (edge_x - ax) * float(rng.uniform(0.05, 0.25))
            tip_y = ay + (edge_y - ay) * float(rng.uniform(0.05, 0.25))
            draw.line([edge_x, edge_y, tip_x, tip_y], fill=shadow_col, width=int(fw + 3))
            draw.line([edge_x, edge_y, tip_x, tip_y], fill=base_col, width=int(fw))
            r = fw * 0.52
            draw.ellipse([tip_x - r, tip_y - r, tip_x + r, tip_y + r], fill=base_col)
    else:
        # Empty hand across the frame (negative sample)
        cx = float(rng.uniform(0.2, 0.8)) * size
        cy = float(rng.uniform(0.35, 0.85)) * size
        for i in range(int(rng.integers(2, 5))):
            dx = (i - 1.5) * rng.uniform(10, 16)
            draw.line([cx + dx, size + 10, cx + dx * 0.6, cy + rng.uniform(-18, 12)], fill=base_col, width=int(rng.uniform(10, 18)))


def _warp_cube_onto_canvas(
    canvas: Image.Image,
    pts_px: np.ndarray,
    face_textures: Sequence[Image.Image],
    alpha: float = 1.0,
) -> None:
    """Perspective-warp the 3 face textures onto `canvas` using the 7 pixel keypoints."""
    size = canvas.size[0]
    overlay = Image.new("RGB", (size, size), (0, 0, 0))
    mask = Image.new("L", (size, size), 0)
    mask_draw = ImageDraw.Draw(mask)

    for face_idx, (i0, i1, i2, i3) in enumerate(QUAD_INDICES):
        quad = pts_px[[i0, i1, i2, i3]]
        tex = face_textures[face_idx]
        coeffs = _quad_coeffs(quad, float(tex.size[0]))
        warped = tex.transform((size, size), Image.Transform.PERSPECTIVE, coeffs, resample=Image.Resampling.BILINEAR)
        face_mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(face_mask).polygon([(float(p[0]), float(p[1])) for p in quad], fill=255)
        overlay.paste(warped, (0, 0), face_mask)
        mask_draw.polygon([(float(p[0]), float(p[1])) for p in quad], fill=int(round(255 * alpha)))

    canvas.paste(overlay, (0, 0), mask)


def _apply_camera_effects(rng: np.random.Generator, pil: Image.Image) -> np.ndarray:
    """Apply realistic mobile camera optics/ISP artifacts (WB tint, blur, YUV 4:2:0 chroma blur, noise)."""
    if rng.random() < 0.45:
        pil = pil.filter(ImageFilter.GaussianBlur(radius=float(rng.uniform(0.3, 1.15))))

    arr = np.asarray(pil, dtype=np.float32)

    # Global white-balance / color temperature shift & exposure jitter
    wb = np.array(
        [rng.uniform(0.88, 1.14), rng.uniform(0.92, 1.08), rng.uniform(0.86, 1.16)],
        dtype=np.float32,
    )
    gain = float(rng.uniform(0.75, 1.22))
    bias = float(rng.uniform(-14.0, 14.0))
    arr = np.clip(arr * wb * gain + bias, 0.0, 255.0)

    # Simulate YUV 4:2:0 chroma subsampling (matches CameraX YUV_420_888)
    if rng.random() < 0.70:
        r, g, b = arr[:, :, 0], arr[:, :, 1], arr[:, :, 2]
        y = 0.299 * r + 0.587 * g + 0.114 * b
        u = -0.168736 * r - 0.331264 * g + 0.5 * b
        v = 0.5 * r - 0.418688 * g - 0.081312 * b
        h, w = y.shape
        u_sub = u.reshape(h // 2, 2, w // 2, 2).mean(axis=(1, 3)).repeat(2, axis=0).repeat(2, axis=1)
        v_sub = v.reshape(h // 2, 2, w // 2, 2).mean(axis=(1, 3)).repeat(2, axis=0).repeat(2, axis=1)
        arr[:, :, 0] = y + 1.402 * v_sub
        arr[:, :, 1] = y - 0.344136 * u_sub - 0.714136 * v_sub
        arr[:, :, 2] = y + 1.772 * u_sub

    # Sensor read noise
    noise_std = float(rng.uniform(1.5, 8.5))
    arr += rng.normal(0.0, noise_std, size=arr.shape).astype(np.float32)
    return np.clip(arr / 255.0, 0.0, 1.0).astype(np.float32)


def render_positive_sample(
    rng: np.random.Generator,
    assets: FixtureAssets,
    img_size: int = 128,
    force_style: str | None = None,
    force_dark_glossy: bool = False,
) -> tuple[np.ndarray, np.ndarray]:
    """Render one positive 3D cube sample -> (image [H,W,3] float32 in [0,1], target [15])."""
    guide_radius = 1.0 / (2.0 * CROP_RADIUS_MULTIPLIER)
    for _ in range(32):
        radius_ratio = float(rng.uniform(0.46, 1.08))
        pitch_deg = float(rng.uniform(16.0, 52.0))
        yaw_deg = float(rng.uniform(-26.0, 26.0))
        roll_deg = float(rng.uniform(-28.0, 28.0))
        persp = float(rng.uniform(0.05, 0.38))
        # Center vertex C must stay within 0.55 * guide_radius (we sample up to 0.44 for clean margin)
        dx = float(rng.uniform(-0.42, 0.42)) * guide_radius
        dy = float(rng.uniform(-0.42, 0.42)) * guide_radius
        pts = project_cube_normalized((0.5 + dx, 0.5 + dy), radius_ratio, pitch_deg, yaw_deg, roll_deg, persp)
        if np.all((pts >= 0.03) & (pts <= 0.97)) and is_valid_geometry_normalized(pts):
            break
    else:
        pts = project_cube_normalized((0.5, 0.5), 0.80, 35.26, 0.0, 0.0, 0.18)

    pts_px = pts * img_size
    canvas, is_dark_glossy = _render_background(rng, img_size, force_dark_glossy=force_dark_glossy)

    palette = assets.palettes[int(rng.integers(0, len(assets.palettes)))]
    hold = HOLD_CENTERS[int(rng.integers(0, len(HOLD_CENTERS)))]
    solved = bool(rng.random() < 0.25)

    if force_style == "real_crop" and assets.face_crops:
        use_real = True
        stickerless = False
    elif force_style == "stickered":
        use_real = False
        stickerless = False
    elif force_style == "stickerless":
        use_real = False
        stickerless = True
    else:
        use_real = bool(assets.face_crops) and rng.random() < 0.22
        stickerless = bool(rng.random() < 0.45)

    # Realistic directional 3D lighting: top brightest, left/right shaded differently
    top_shade = float(rng.uniform(0.92, 1.12))
    left_shade = float(rng.uniform(0.68, 1.02))
    right_shade = float(rng.uniform(0.58, 0.98))
    if rng.random() < 0.5:
        left_shade, right_shade = right_shade, left_shade
    shades = (top_shade, left_shade, right_shade)

    textures = []
    for f_idx in range(3):
        crop = assets.face_crops[int(rng.integers(0, len(assets.face_crops)))][1] if use_real else None
        textures.append(
            _render_face_texture(
                rng,
                palette,
                hold[f_idx],
                solved=solved,
                stickerless=stickerless,
                shade=shades[f_idx],
                real_crop=crop,
            )
        )

    # On dark glossy tables (#196), draw a faint vertically-flipped reflection BELOW the cube first
    if is_dark_glossy and rng.random() < 0.75:
        b_y = float(pts_px[3, 1])
        refl_pts = pts_px.copy()
        refl_pts[:, 1] = 2.0 * b_y - refl_pts[:, 1] + float(rng.uniform(2.0, 10.0))
        refl_layer = canvas.copy()
        _warp_cube_onto_canvas(refl_layer, refl_pts, textures, alpha=float(rng.uniform(0.22, 0.45)))
        refl_layer = refl_layer.filter(ImageFilter.GaussianBlur(radius=float(rng.uniform(1.2, 3.0))))
        canvas = refl_layer

    # Soft drop shadow underneath the cube
    if rng.random() < 0.65:
        shadow_layer = Image.new("RGB", (img_size, img_size), (0, 0, 0))
        shadow_mask = Image.new("L", (img_size, img_size), 0)
        sx, sy = float(rng.uniform(-6, 6)), float(rng.uniform(4, 12))
        hull = [(float(pts_px[i, 0] + sx), float(pts_px[i, 1] + sy)) for i in range(6)]
        ImageDraw.Draw(shadow_mask).polygon(hull, fill=int(rng.integers(40, 105)))
        shadow_mask = shadow_mask.filter(ImageFilter.GaussianBlur(radius=float(rng.uniform(3.0, 7.0))))
        canvas.paste(shadow_layer, (0, 0), shadow_mask)

    _warp_cube_onto_canvas(canvas, pts_px, textures, alpha=1.0)

    # Hands gripping cube (~45% of positives)
    if rng.random() < 0.45:
        _draw_hand(rng, canvas, pts_px, img_size)

    img = _apply_camera_effects(rng, canvas)
    target = np.zeros(15, dtype=np.float32)
    target[0] = 1.0
    target[1:] = pts.reshape(-1)
    return img, target


def render_negative_sample(
    rng: np.random.Generator,
    assets: FixtureAssets,
    img_size: int = 128,
    neg_kind: str | None = None,
) -> tuple[np.ndarray, np.ndarray]:
    """Render one hard negative sample (presence = 0): empty, dark glossy reflection (#196), flat 2D face, or hand."""
    kind = neg_kind or str(
        rng.choice(
            ["empty_surface", "dark_glossy_reflection", "flat_2d_face", "hand_only", "clutter_blocks"],
            p=[0.22, 0.28, 0.22, 0.14, 0.14],
        )
    )
    if kind == "dark_glossy_reflection":
        canvas, _ = _render_background(rng, img_size, force_dark_glossy=True)
        draw = ImageDraw.Draw(canvas)
        # Faint colored glossy reflection patches (no solid 3-face cube)
        palette = assets.palettes[int(rng.integers(0, len(assets.palettes)))]
        cx, cy = float(rng.uniform(0.3, 0.7)) * img_size, float(rng.uniform(0.3, 0.7)) * img_size
        for _ in range(int(rng.integers(2, 7))):
            col = palette[str(rng.choice(COLOR_KEYS))] * float(rng.uniform(0.18, 0.48))
            col_t = tuple(int(c) for c in np.clip(col, 0, 255))
            ox, oy = float(rng.uniform(-32, 32)), float(rng.uniform(-24, 24))
            rw, rh = float(rng.uniform(10, 30)), float(rng.uniform(8, 22))
            draw.polygon(
                [
                    (cx + ox - rw, cy + oy),
                    (cx + ox, cy + oy - rh),
                    (cx + ox + rw, cy + oy),
                    (cx + ox, cy + oy + rh),
                ],
                fill=col_t,
            )
        canvas = canvas.filter(ImageFilter.GaussianBlur(radius=float(rng.uniform(2.0, 5.0))))
    elif kind == "flat_2d_face":
        canvas, _ = _render_background(rng, img_size)
        palette = assets.palettes[int(rng.integers(0, len(assets.palettes)))]
        tex = _render_face_texture(
            rng,
            palette,
            str(rng.choice(COLOR_KEYS)),
            solved=bool(rng.random() < 0.3),
            stickerless=bool(rng.random() < 0.5),
            shade=float(rng.uniform(0.8, 1.1)),
        )
        side = int(rng.uniform(0.38, 0.72) * img_size)
        angle = float(rng.uniform(-25.0, 25.0))
        tex_r = tex.resize((side, side), Image.Resampling.BILINEAR).rotate(angle, expand=True, fillcolor=(0, 0, 0))
        mask = Image.new("L", (side, side), 255).rotate(angle, expand=True, fillcolor=0)
        ox = int((img_size - tex_r.size[0]) // 2 + rng.uniform(-16, 16))
        oy = int((img_size - tex_r.size[1]) // 2 + rng.uniform(-16, 16))
        canvas.paste(tex_r, (ox, oy), mask)
    elif kind == "hand_only":
        canvas, _ = _render_background(rng, img_size)
        _draw_hand(rng, canvas, None, img_size)
    elif kind == "clutter_blocks":
        canvas, _ = _render_background(rng, img_size)
        draw = ImageDraw.Draw(canvas)
        palette = assets.palettes[int(rng.integers(0, len(assets.palettes)))]
        for _ in range(int(rng.integers(5, 16))):
            col = tuple(int(c) for c in np.clip(palette[str(rng.choice(COLOR_KEYS))] * rng.uniform(0.6, 1.0), 0, 255))
            x0, y0 = int(rng.integers(0, img_size - 16)), int(rng.integers(0, img_size - 16))
            draw.rectangle([x0, y0, x0 + int(rng.integers(10, 36)), y0 + int(rng.integers(10, 36))], fill=col)
    else:  # empty_surface
        canvas, _ = _render_background(rng, img_size)

    img = _apply_camera_effects(rng, canvas)
    target = np.zeros(15, dtype=np.float32)
    return img, target


def _generate_chunk(args: tuple[int, int, int, FixtureAssets, int, float]) -> tuple[np.ndarray, np.ndarray]:
    n_chunk, n_neg_chunk, seed, assets, img_size, _ = args
    rng = np.random.default_rng(seed)
    x = np.empty((n_chunk, img_size, img_size, 3), dtype=np.float32)
    y = np.empty((n_chunk, 15), dtype=np.float32)
    for i in range(n_chunk):
        if i < n_neg_chunk:
            x[i], y[i] = render_negative_sample(rng, assets, img_size=img_size)
        else:
            x[i], y[i] = render_positive_sample(rng, assets, img_size=img_size)
    return x, y


def generate_dataset(
    n_samples: int,
    seed: int,
    assets: FixtureAssets,
    img_size: int = 128,
    neg_fraction: float = 0.26,
) -> tuple[np.ndarray, np.ndarray]:
    """Generate `(X, Y)` arrays of synthetic 3D cubes + hard negatives in parallel."""
    # A fixed chunk count keeps the dataset (chunk sizes and per-chunk seeds) independent of the machine's core count;
    # only the pool size follows the cores.
    chunk_count = 8
    workers = min(os.cpu_count() or 1, chunk_count)
    n_neg = int(round(n_samples * neg_fraction))
    if n_samples < 512:
        x, y = _generate_chunk((n_samples, n_neg, seed, assets, img_size, neg_fraction))
    else:
        chunks = []
        base = n_samples // chunk_count
        rem = n_samples % chunk_count
        neg_base = n_neg // chunk_count
        neg_rem = n_neg % chunk_count
        for w in range(chunk_count):
            c_n = base + (1 if w < rem else 0)
            c_neg = neg_base + (1 if w < neg_rem else 0)
            chunks.append((c_n, c_neg, seed + w * 1009, assets, img_size, neg_fraction))
        with ProcessPoolExecutor(max_workers=workers) as pool:
            parts = list(pool.map(_generate_chunk, chunks))
        x = np.concatenate([p[0] for p in parts], axis=0)
        y = np.concatenate([p[1] for p in parts], axis=0)
    rng = np.random.default_rng(seed + 99991)
    perm = rng.permutation(n_samples)
    return x[perm], y[perm]
