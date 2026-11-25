"""Clean-room 1-3 visible face synthetic 3D cube generator for LiteRT sticker-center heatmap training.

Self-contained inside `tools/center-model/`.
Features:
  - Full SE(3) rotation with backface culling -> 1-face frontal (positive), 2-face edge, and 3-face corner views.
  - Arbitrary off-center placement and scale across the full 256x256 frame.
  - Per-sticker hand-occlusion mask (occluded stickers are zeroed out of the heatmap targets).
  - Strict canonical holdout union enforcement (frozen out of Fixtures/live real-crop textures).
  - Stickered, stickerless, pastel (stlb-like), real warped non-held-out live crops, logos, glare,
    dark glossy reflections (no heatmap on reflections), YUV chroma subsampling, and 24% hard negatives.
"""

from __future__ import annotations

import json
import math
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path
from typing import List, Sequence, Tuple

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

import cube_geometry as cg
from holdout_policy import (
    EXCLUDED_LIVE,
    REPO_ROOT,
    allowed_live_group_dirs,
    load_heldout_union,
    validate_background_paths,
)

IMG_SIZE = 256
HM_STRIDE = 4
HM_SIZE = IMG_SIZE // HM_STRIDE  # 64

BASE_PALETTES = [
    # Standard stickered
    np.array([[238, 240, 242], [236, 214, 30], [36, 170, 74], [28, 86, 208], [206, 38, 42], [240, 120, 28]], dtype=np.float32),
    # Bright stickerless (GAN/MoYu-like)
    np.array([[234, 238, 240], [236, 228, 36], [48, 194, 84], [34, 112, 220], [218, 46, 54], [244, 128, 46]], dtype=np.float32),
    # Pastel / muted (stlb-like)
    np.array([[228, 230, 228], [226, 218, 122], [110, 194, 134], [102, 146, 212], [208, 94, 102], [228, 148, 94]], dtype=np.float32),
    # Webcam neon/matte stickerless (crimson red + bright yellow-green + peach orange)
    np.array([[230, 222, 228], [224, 234, 78], [40, 186, 92], [44, 108, 212], [192, 36, 68], [242, 122, 66]], dtype=np.float32),
]


def load_real_backgrounds(bg_dir: Path | None, repo_root: Path = REPO_ROOT) -> List[np.ndarray]:
    """Load optional verified cube-free room backgrounds (strictly from non-held-out tuning clips)."""
    if bg_dir is None or not bg_dir.is_dir():
        return []
    bgs: List[np.ndarray] = []
    for p in validate_background_paths(bg_dir, repo_root=repo_root):
        with Image.open(p) as im:
            bgs.append(np.asarray(im.convert("RGB"), dtype=np.uint8))
    return bgs


def load_non_heldout_live_crops(repo_root: Path = REPO_ROOT) -> List[np.ndarray]:
    """Load upright 120x120 RGB face textures strictly from non-held-out Fixtures/live groups."""
    crops: List[np.ndarray] = []
    for d in allowed_live_group_dirs(repo_root):
        for jpath in sorted(d.glob("*.json")):
            rec = json.loads(jpath.read_text())
            if not rec.get("frame"):
                continue
            if rec["labeledSample"]["sample"].get("orientationDegrees", 0) != 0:
                continue
            img = cg.load_rgb(d / rec["frame"])
            h, w = img.shape[:2]
            side = min(w, h) * cg.GRID_FRACTION
            left, top = (w - side) / 2.0, (h - side) / 2.0
            crop = cg.unwarp(img, cg.rect_quad(left, top, left + side, top + side), side=120)
            if crop is not None:
                crops.append(crop.astype(np.float32))
    return crops


def _euler_xyz(rx: float, ry: float, rz: float) -> np.ndarray:
    cx, sx = math.cos(rx), math.sin(rx)
    cy, sy = math.cos(ry), math.sin(ry)
    cz, sz = math.cos(rz), math.sin(rz)
    Rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]], dtype=np.float64)
    Ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]], dtype=np.float64)
    Rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]], dtype=np.float64)
    return Rz @ Ry @ Rx


def _project_3d(pts_3d: np.ndarray, R: np.ndarray, t: np.ndarray, f: float, cx: float, cy: float) -> np.ndarray:
    world = pts_3d @ R.T
    xc = world[:, 0] + t[0]
    yc = -(world[:, 1] + t[1])
    zc = t[2] - world[:, 2]
    u = f * (xc / zc) + cx
    v = f * (yc / zc) + cy
    return np.stack([u, v], axis=-1)


def _solve_homography_4pt(src: np.ndarray, dst: np.ndarray) -> np.ndarray | None:
    """Solve 3x3 projective homography mapping `src` (4x2) -> `dst` (4x2) using pure NumPy."""
    A = np.zeros((8, 8), dtype=np.float64)
    b = np.zeros(8, dtype=np.float64)
    for i in range(4):
        x, y = src[i]
        u, v = dst[i]
        A[2 * i] = [x, y, 1, 0, 0, 0, -u * x, -u * y]
        b[2 * i] = u
        A[2 * i + 1] = [0, 0, 0, x, y, 1, -v * x, -v * y]
        b[2 * i + 1] = v
    try:
        h = np.linalg.solve(A, b)
    except np.linalg.LinAlgError:
        return None
    return np.array([[h[0], h[1], h[2]], [h[3], h[4], h[5]], [h[6], h[7], 1.0]], dtype=np.float64)


def _warp_face_onto_canvas(canvas: np.ndarray, tex: np.ndarray, quad_xy: np.ndarray) -> None:
    """Fast vectorized projective warp of `tex` (S x S x 3) onto `canvas` inside `quad_xy`."""
    H_img, W_img = canvas.shape[:2]
    S = tex.shape[0]
    x0 = max(0, int(math.floor(float(np.min(quad_xy[:, 0])))))
    x1 = min(W_img - 1, int(math.ceil(float(np.max(quad_xy[:, 0])))))
    y0 = max(0, int(math.floor(float(np.min(quad_xy[:, 1])))))
    y1 = min(H_img - 1, int(math.ceil(float(np.max(quad_xy[:, 1])))))
    if x1 <= x0 or y1 <= y0:
        return
    src = np.array([[0, 0], [S - 1, 0], [S - 1, S - 1], [0, S - 1]], dtype=np.float64)
    H_inv = _solve_homography_4pt(quad_xy.astype(np.float64), src)
    if H_inv is None:
        return
    xs = np.arange(x0, x1 + 1, dtype=np.float64)
    ys = np.arange(y0, y1 + 1, dtype=np.float64)
    gx, gy = np.meshgrid(xs, ys)
    denom = H_inv[2, 0] * gx + H_inv[2, 1] * gy + H_inv[2, 2]
    valid = denom > 1e-5
    u = np.where(valid, (H_inv[0, 0] * gx + H_inv[0, 1] * gy + H_inv[0, 2]) / np.where(valid, denom, 1.0), -1.0)
    v = np.where(valid, (H_inv[1, 0] * gx + H_inv[1, 1] * gy + H_inv[1, 2]) / np.where(valid, denom, 1.0), -1.0)
    inside = valid & (u >= 0.0) & (u <= S - 1.001) & (v >= 0.0) & (v <= S - 1.001)
    if not np.any(inside):
        return
    ui = u[inside]
    vi = v[inside]
    u0 = np.floor(ui).astype(np.int64)
    v0 = np.floor(vi).astype(np.int64)
    fu = (ui - u0)[:, None]
    fv = (vi - v0)[:, None]
    top = tex[v0, u0] * (1.0 - fu) + tex[v0, u0 + 1] * fu
    bot = tex[v0 + 1, u0] * (1.0 - fu) + tex[v0 + 1, u0 + 1] * fu
    sub = canvas[y0 : y1 + 1, x0 : x1 + 1]
    sub[inside] = top * (1.0 - fv) + bot * fv


def _sample_real_bg_patch(rng: np.random.Generator, real_bgs: Sequence[np.ndarray]) -> np.ndarray:
    """Randomly crop, resize, flip, and mild-jitter a real non-held-out room background to IMG_SIZE."""
    src = real_bgs[int(rng.integers(0, len(real_bgs)))]
    sh, sw = src.shape[:2]
    crop_frac = float(rng.uniform(0.45, 1.0))
    ch = max(64, int(round(min(sh, sw) * crop_frac)))
    cw = ch
    y0 = int(rng.integers(0, max(1, sh - ch + 1)))
    x0 = int(rng.integers(0, max(1, sw - cw + 1)))
    patch = Image.fromarray(src[y0 : y0 + ch, x0 : x0 + cw]).resize((IMG_SIZE, IMG_SIZE), Image.BILINEAR)
    arr = np.asarray(patch, dtype=np.float32)
    if rng.random() < 0.5:
        arr = np.fliplr(arr).copy()
    gain = rng.uniform(0.65, 1.30, size=(1, 1, 3)).astype(np.float32)
    return np.clip(arr * gain, 0.0, 255.0)


def _make_background(rng: np.random.Generator, dark_glossy: bool, real_bgs: Sequence[np.ndarray] = ()) -> np.ndarray:
    """Generate realistic cluttered desk, table, fabric, dark glossy surface, or real room background."""
    if not dark_glossy and real_bgs and rng.random() < 0.30:
        return _sample_real_bg_patch(rng, real_bgs)
    if dark_glossy:
        base = rng.uniform(8, 34, size=3).astype(np.float32)
        grad = np.linspace(0.65, 1.35, IMG_SIZE, dtype=np.float32)[:, None, None]
        bg = np.broadcast_to(np.clip(base[None, None, :] * grad, 0, 255), (IMG_SIZE, IMG_SIZE, 3)).copy()
    else:
        c1 = rng.uniform(25, 225, size=3).astype(np.float32)
        c2 = np.clip(c1 + rng.uniform(-55, 55, size=3).astype(np.float32), 10, 245)
        t = np.linspace(0.0, 1.0, IMG_SIZE, dtype=np.float32)
        if rng.random() < 0.5:
            bg = c1[None, None, :] * (1 - t[:, None, None]) + c2[None, None, :] * t[:, None, None]
        else:
            bg = c1[None, None, :] * (1 - t[None, :, None]) + c2[None, None, :] * t[None, :, None]
        bg = np.broadcast_to(bg, (IMG_SIZE, IMG_SIZE, 3)).copy()

    if rng.random() < 0.70:
        im = Image.fromarray(np.clip(bg, 0, 255).astype(np.uint8))
        draw = ImageDraw.Draw(im)
        for _ in range(int(rng.integers(1, 5))):
            col = tuple(int(v) for v in rng.integers(15, 235, size=3))
            x0, y0 = int(rng.integers(-30, IMG_SIZE)), int(rng.integers(-30, IMG_SIZE))
            x1, y1 = x0 + int(rng.integers(30, 180)), y0 + int(rng.integers(20, 160))
            if rng.random() < 0.65:
                draw.rectangle([x0, y0, x1, y1], fill=col)
            else:
                draw.ellipse([x0, y0, x1, y1], fill=col)
        if rng.random() < 0.5:
            im = im.filter(ImageFilter.GaussianBlur(radius=float(rng.uniform(0.8, 3.0))))
        bg = np.asarray(im, dtype=np.float32)
    return bg


def _make_face_texture(
    rng: np.random.Generator,
    palette: np.ndarray,
    stickerless: bool,
    body_rgb: np.ndarray,
    live_crops: Sequence[np.ndarray],
    use_real_crop: bool,
    tex_size: int = 96,
) -> np.ndarray:
    if use_real_crop and live_crops:
        crop = live_crops[int(rng.integers(0, len(live_crops)))].copy()
        k = int(rng.integers(0, 4))
        if k:
            crop = np.rot90(crop, k).copy()
        if rng.random() < 0.5:
            crop = np.fliplr(crop).copy()
        gain = rng.uniform(0.85, 1.15, size=(1, 1, 3)).astype(np.float32)
        return np.clip(crop * gain, 0, 255)

    tex = np.broadcast_to(body_rgb, (tex_size, tex_size, 3)).copy()
    cell = tex_size // 3
    margin = int(rng.integers(0, 3)) if stickerless else int(rng.integers(3, 6))
    im = Image.fromarray(np.clip(tex, 0, 255).astype(np.uint8))
    draw = ImageDraw.Draw(im)
    pat_u = rng.random()
    solved_face = pat_u < 0.12
    checker_face = 0.12 <= pat_u < 0.30
    fixed_idx = int(rng.integers(0, 6))
    opp_idx = (fixed_idx + 1) if (fixed_idx % 2 == 0) else (fixed_idx - 1)
    rad = int(rng.integers(4, 9)) if stickerless else int(rng.integers(2, 5))

    for r in range(3):
        for c in range(3):
            if solved_face:
                col_i = fixed_idx
            elif checker_face:
                col_i = fixed_idx if ((r + c) % 2 == 0) else opp_idx
            else:
                col_i = int(rng.integers(0, 6))
            rgb = np.clip(palette[col_i] + rng.normal(0, 5.5, size=3), 5, 250)
            x0, y0 = c * cell + margin, r * cell + margin
            x1, y1 = (c + 1) * cell - margin, (r + 1) * cell - margin
            draw.rounded_rectangle([x0, y0, x1, y1], radius=rad, fill=tuple(int(v) for v in rgb))
            if r == 1 and c == 1 and rng.random() < 0.28:
                cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
                lr = max(3, int(cell * rng.uniform(0.16, 0.26)))
                lcol = tuple(int(v) for v in rng.integers(20, 120, size=3))
                draw.ellipse([cx - lr, cy - lr, cx + lr, cy + lr], fill=lcol)
    return np.asarray(im, dtype=np.float32)


def _draw_hand_occlusion(rng: np.random.Generator, img_rgb: np.ndarray, cube_pts: np.ndarray) -> np.ndarray:
    """Draw realistic gripping finger(s) onto `img_rgb` and return boolean occlusion mask (H, W)."""
    mask_im = Image.new("L", (IMG_SIZE, IMG_SIZE), 0)
    rgb_im = Image.fromarray(np.clip(img_rgb, 0, 255).astype(np.uint8))
    mdraw = ImageDraw.Draw(mask_im)
    rdraw = ImageDraw.Draw(rgb_im)

    skin_tones = [
        np.array([218, 172, 146]),
        np.array([186, 138, 108]),
        np.array([138, 92, 68]),
        np.array([232, 194, 170]),
    ]
    skin = np.clip(skin_tones[int(rng.integers(0, len(skin_tones)))] + rng.normal(0, 10, 3), 30, 245)
    skin_t = tuple(int(v) for v in skin)

    n_fingers = int(rng.integers(1, 3))
    xmin, ymin = np.min(cube_pts, axis=0)
    xmax, ymax = np.max(cube_pts, axis=0)
    for _ in range(n_fingers):
        side = int(rng.integers(0, 3))
        if side == 0:
            fx = float(rng.uniform(xmin, xmax))
            fy = float(rng.uniform(ymax - 0.35 * (ymax - ymin), ymax + 10))
            rx, ry = float(rng.uniform(14, 28)), float(rng.uniform(18, 42))
        elif side == 1:
            fx = float(rng.uniform(xmin - 12, xmin + 0.32 * (xmax - xmin)))
            fy = float(rng.uniform(ymin, ymax))
            rx, ry = float(rng.uniform(18, 38)), float(rng.uniform(12, 22))
        else:
            fx = float(rng.uniform(xmax - 0.32 * (xmax - xmin), xmax + 12))
            fy = float(rng.uniform(ymin, ymax))
            rx, ry = float(rng.uniform(18, 38)), float(rng.uniform(12, 22))
        box = [int(fx - rx), int(fy - ry), int(fx + rx), int(fy + ry)]
        rdraw.ellipse(box, fill=skin_t)
        mdraw.ellipse(box, fill=255)

    img_rgb[:] = np.asarray(rgb_im, dtype=np.float32)
    return np.asarray(mask_im) > 127


def _draw_hard_negative(rng: np.random.Generator, real_bgs: Sequence[np.ndarray] = ()) -> np.ndarray:
    """Generate a cube-free hard negative: real room/bookshelf, warm multi-finger hands, or random colored UI grids."""
    dark = rng.random() < 0.25
    bg = _make_background(rng, dark_glossy=dark, real_bgs=real_bgs)
    im = Image.fromarray(np.clip(bg, 0, 255).astype(np.uint8))
    draw = ImageDraw.Draw(im)

    kind = int(rng.integers(0, 4))
    if kind == 0:
        skin_base = np.array([212, 152, 134]) if rng.random() < 0.55 else np.array([175, 130, 108])
        skin = np.clip(skin_base + rng.normal(0, 24, 3), 35, 248)
        cx, cy = int(rng.integers(50, IMG_SIZE - 50)), int(rng.integers(80, IMG_SIZE))
        pw, ph = int(rng.integers(32, 65)), int(rng.integers(35, 70))
        draw.ellipse([cx - pw, cy - ph, cx + pw, cy + ph], fill=tuple(int(v) for v in skin))
        for k in range(int(rng.integers(2, 5))):
            fx = cx + int((k - 1.5) * rng.uniform(14, 24))
            fy = cy - ph + int(rng.uniform(-35, 10))
            fw, fh = int(rng.integers(8, 16)), int(rng.integers(22, 46))
            fcol = tuple(int(v) for v in np.clip(skin + rng.normal(0, 12, 3), 30, 250))
            draw.rounded_rectangle([fx - fw, fy - fh, fx + fw, fy + fh // 2], radius=fw, fill=fcol)
    elif kind == 1:
        pal = BASE_PALETTES[int(rng.integers(0, len(BASE_PALETTES)))]
        n_sq = int(rng.integers(2, 7))
        for _ in range(n_sq):
            c = tuple(int(v) for v in pal[int(rng.integers(0, 6))])
            x0, y0 = int(rng.integers(10, IMG_SIZE - 50)), int(rng.integers(10, IMG_SIZE - 50))
            sz = int(rng.integers(16, 48))
            draw.rectangle([x0, y0, x0 + sz, y0 + sz], fill=c, outline=(20, 20, 20), width=2)
    elif kind == 2:
        pal = BASE_PALETTES[int(rng.integers(0, len(BASE_PALETTES)))]
        cx, cy = int(rng.integers(60, IMG_SIZE - 60)), int(rng.integers(80, IMG_SIZE - 40))
        for _ in range(4):
            c = tuple(int(v * 0.35) for v in pal[int(rng.integers(0, 6))])
            ox, oy = int(rng.integers(-35, 35)), int(rng.integers(-25, 25))
            draw.polygon([(cx + ox, cy + oy), (cx + ox + 22, cy + oy + 8), (cx + ox + 16, cy + oy + 28), (cx + ox - 6, cy + oy + 18)], fill=c)
        im = im.filter(ImageFilter.GaussianBlur(radius=float(rng.uniform(2.5, 5.5))))
    else:
        if rng.random() < 0.4:
            im = im.filter(ImageFilter.GaussianBlur(radius=float(rng.uniform(0.5, 1.8))))

    arr = np.asarray(im, dtype=np.float32)
    arr += rng.normal(0, float(rng.uniform(1.5, 6.5)), arr.shape).astype(np.float32)
    return np.clip(arr, 0, 255).astype(np.uint8)


def _render_one_sample(
    rng: np.random.Generator, live_crops: Sequence[np.ndarray], real_bgs: Sequence[np.ndarray] = ()
) -> Tuple[np.ndarray, np.ndarray]:
    """Render one (image_u8 [256,256,3], target [64,64,3]) training example."""
    target = np.zeros((HM_SIZE, HM_SIZE, 3), dtype=np.float32)

    if rng.random() < 0.24:
        return _draw_hard_negative(rng, real_bgs=real_bgs), target

    dark_glossy = rng.random() < 0.18
    canvas = _make_background(rng, dark_glossy=dark_glossy, real_bgs=real_bgs)

    u_mode = rng.random()
    if u_mode < 0.28:
        base_rot = int(rng.integers(0, 6))
        base_eulers = [(0, 0), (0, math.pi / 2), (0, math.pi), (0, -math.pi / 2), (math.pi / 2, 0), (-math.pi / 2, 0)]
        brx, bry = base_eulers[base_rot]
        rx = brx + float(rng.uniform(-0.26, 0.26))
        ry = bry + float(rng.uniform(-0.26, 0.26))
        rz = float(rng.uniform(-math.pi, math.pi))
    elif u_mode < 0.65:
        rx = float(rng.uniform(-0.22, 0.22))
        ry = float(rng.uniform(0.28, 1.28)) * (1.0 if rng.random() < 0.5 else -1.0)
        rz = float(rng.uniform(-math.pi, math.pi))
    else:
        rx = float(rng.uniform(0.28, 1.10)) * (1.0 if rng.random() < 0.5 else -1.0)
        ry = float(rng.uniform(0.32, 1.25)) * (1.0 if rng.random() < 0.5 else -1.0)
        rz = float(rng.uniform(-math.pi, math.pi))

    R = _euler_xyz(rx, ry, rz)
    f = float(rng.uniform(250.0, 420.0))
    tz = float(rng.uniform(4.0, 11.2))
    tx = float(rng.uniform(-1.35, 1.35)) * (tz / 6.5)
    ty = float(rng.uniform(-1.35, 1.35)) * (tz / 6.5)
    t = np.array([tx, ty, tz], dtype=np.float64)
    cam_world = np.array([-tx, -ty, tz], dtype=np.float64)
    cx0, cy0 = IMG_SIZE / 2.0, IMG_SIZE / 2.0

    pal_idx = int(rng.integers(0, len(BASE_PALETTES)))
    palette = BASE_PALETTES[pal_idx] * rng.uniform(0.86, 1.12, size=(1, 3)).astype(np.float32)
    stickerless = (pal_idx >= 1) or (rng.random() < 0.50)
    body_choice = rng.random()
    body_rgb = (
        np.array([22, 22, 24], dtype=np.float32)
        if body_choice < 0.72
        else (np.array([225, 225, 228], dtype=np.float32) if body_choice < 0.86 else np.array([75, 78, 82], dtype=np.float32))
    )
    use_real_crop = bool(live_crops) and (rng.random() < 0.28)

    to_light = np.array([rng.uniform(-0.7, 0.7), rng.uniform(-0.3, 0.85), rng.uniform(0.45, 1.0)], dtype=np.float64)
    to_light /= np.linalg.norm(to_light)
    exp_u = rng.random()
    if exp_u < 0.25:
        exp_gain = float(rng.uniform(0.26, 0.58))
    elif exp_u < 0.80:
        exp_gain = float(rng.uniform(0.60, 1.12))
    else:
        exp_gain = float(rng.uniform(1.12, 1.42))
    awb = rng.uniform([0.78, 0.90, 0.75], [1.26, 1.08, 1.24]).astype(np.float32) * exp_gain
    glare_on = rng.random() < 0.35

    visible = []
    for fname in cg.FACES:
        n_world = R @ cg.FACE_BASIS[fname][0]
        c_world = n_world.copy()
        view_dir = cam_world - c_world
        view_dir /= np.linalg.norm(view_dir)
        cos_view = float(np.dot(n_world, view_dir))
        if cos_view > 0.24:
            z_depth = float(tz - c_world[2])
            visible.append((z_depth, fname, n_world, view_dir, cos_view))
    visible.sort(reverse=True)

    if dark_glossy and visible:
        for _, fname, n_world, _, _ in visible:
            q3 = cg.face_quad_3d(fname)
            q2 = _project_3d(q3, R, t, f, cx0, cy0)
            ymax_c = float(np.max(q2[:, 1]))
            q2_ref = q2.copy()
            q2_ref[:, 1] = ymax_c + (ymax_c - q2[:, 1]) * 0.55 + 6.0
            ref_tex = _make_face_texture(rng, palette, stickerless, body_rgb, live_crops, False, tex_size=64) * 0.28
            _warp_face_onto_canvas(canvas, ref_tex, q2_ref)

    all_cube_pts = []
    sticker_points: List[Tuple[float, float, float]] = []

    for _, fname, n_world, view_dir, cos_view in visible:
        q3 = cg.face_quad_3d(fname)
        q2 = _project_3d(q3, R, t, f, cx0, cy0)
        all_cube_pts.append(q2)
        tex = _make_face_texture(rng, palette, stickerless, body_rgb, live_crops, use_real_crop, tex_size=96)
        lambert = max(0.38, min(1.08, 0.44 + 0.62 * max(0.0, float(np.dot(n_world, to_light)))))
        tex = tex * lambert * awb[None, None, :]

        if glare_on:
            half_v = view_dir + to_light
            half_v /= np.linalg.norm(half_v)
            spec = max(0.0, float(np.dot(n_world, half_v))) ** 8
            if spec > 0.15:
                yy, xx = np.mgrid[0 : tex.shape[0], 0 : tex.shape[1]].astype(np.float32)
                gx, gy = rng.uniform(0.15, 0.85) * tex.shape[1], rng.uniform(0.15, 0.85) * tex.shape[0]
                blob = np.exp(-((xx - gx) ** 2 + (yy - gy) ** 2) / (2.0 * (tex.shape[0] * 0.26) ** 2))
                tex = tex + 220.0 * spec * blob[:, :, None]

        _warp_face_onto_canvas(canvas, np.clip(tex, 0, 255), q2)

        edge_len = float(np.mean([np.linalg.norm(q2[(k + 1) % 4] - q2[k]) for k in range(4)]))
        approx_r = max(3.0, edge_len / 6.0)
        pts3 = np.stack([cg.sticker_center_3d(fname, ci) for ci in range(9)], axis=0)
        pts2 = _project_3d(pts3, R, t, f, cx0, cy0)
        for sx, sy in pts2:
            if 2.0 <= sx <= IMG_SIZE - 3.0 and 2.0 <= sy <= IMG_SIZE - 3.0:
                sticker_points.append((float(sx), float(sy), approx_r))

    occ_mask = None
    if all_cube_pts and rng.random() < 0.42:
        cube_pts_all = np.concatenate(all_cube_pts, axis=0)
        occ_mask = _draw_hand_occlusion(rng, canvas, cube_pts_all)

    yy_hm, xx_hm = np.mgrid[0:HM_SIZE, 0:HM_SIZE].astype(np.float32)
    for sx, sy, approx_r in sticker_points:
        ix, iy = int(round(sx)), int(round(sy))
        if occ_mask is not None and occ_mask[iy, ix]:
            continue
        hx = sx / HM_STRIDE
        hy = sy / HM_STRIDE
        gx = int(math.floor(hx))
        gy = int(math.floor(hy))
        if not (0 <= gx < HM_SIZE and 0 <= gy < HM_SIZE):
            continue
        sigma = float(np.clip((approx_r / HM_STRIDE) * 0.42, 0.85, 2.0))
        g = np.exp(-(((xx_hm + 0.5) - hx) ** 2 + ((yy_hm + 0.5) - hy) ** 2) / (2.0 * sigma * sigma))
        g = np.minimum(g, 0.95)
        target[:, :, 0] = np.maximum(target[:, :, 0], g)
        target[gy, gx, 0] = 1.0
        target[gy, gx, 1] = hx - (gx + 0.5)
        target[gy, gx, 2] = hy - (gy + 0.5)

    im = Image.fromarray(np.clip(canvas, 0, 255).astype(np.uint8))
    if rng.random() < 0.28:
        im = im.filter(ImageFilter.GaussianBlur(radius=float(rng.uniform(0.4, 1.3))))
    arr = np.asarray(im, dtype=np.float32)
    if rng.random() < 0.16:
        k = int(rng.choice([3, 5]))
        axis = int(rng.integers(0, 2))
        arr = sum(np.roll(arr, s, axis=axis) for s in range(-(k // 2), k // 2 + 1)) / float(k)
    if rng.random() < 0.35:
        y = 0.299 * arr[:, :, 0] + 0.587 * arr[:, :, 1] + 0.114 * arr[:, :, 2]
        u = -0.1687 * arr[:, :, 0] - 0.3313 * arr[:, :, 1] + 0.5 * arr[:, :, 2]
        v = 0.5 * arr[:, :, 0] - 0.4187 * arr[:, :, 1] - 0.0813 * arr[:, :, 2]
        u2 = np.repeat(np.repeat(u.reshape(IMG_SIZE // 2, 2, IMG_SIZE // 2, 2).mean(axis=(1, 3)), 2, axis=0), 2, axis=1)
        v2 = np.repeat(np.repeat(v.reshape(IMG_SIZE // 2, 2, IMG_SIZE // 2, 2).mean(axis=(1, 3)), 2, axis=0), 2, axis=1)
        arr = np.stack([y + 1.402 * v2, y - 0.3441 * u2 - 0.7141 * v2, y + 1.772 * u2], axis=-1)
    noise_sd = float(rng.uniform(3.0, 9.0)) if exp_gain < 0.58 else float(rng.uniform(1.0, 5.5))
    arr += rng.normal(0, noise_sd, arr.shape).astype(np.float32)
    return np.clip(arr, 0, 255).astype(np.uint8), target


def _worker_chunk(args: Tuple[int, int, List[np.ndarray], List[np.ndarray]]) -> Tuple[np.ndarray, np.ndarray]:
    n, seed, live_crops, real_bgs = args
    rng = np.random.default_rng(seed)
    xs = np.empty((n, IMG_SIZE, IMG_SIZE, 3), dtype=np.uint8)
    ys = np.empty((n, HM_SIZE, HM_SIZE, 3), dtype=np.float16)
    for i in range(n):
        img, tgt = _render_one_sample(rng, live_crops, real_bgs)
        xs[i] = img
        ys[i] = tgt.astype(np.float16)
    return xs, ys


def generate_dataset(
    n_samples: int, seed: int, chunks: int = 8, bg_dir: Path | None = None
) -> Tuple[np.ndarray, np.ndarray]:
    """Generate `n_samples` examples deterministically in parallel across CPU cores."""
    if n_samples <= 0:
        raise ValueError(f"n_samples must be positive, got {n_samples}")
    if chunks <= 0:
        raise ValueError(f"chunks must be positive, got {chunks}")
    live_crops = load_non_heldout_live_crops()
    real_bgs = load_real_backgrounds(bg_dir)
    base = n_samples // chunks
    sizes = [base + (1 if i < n_samples % chunks else 0) for i in range(chunks)]
    tasks = [(sz, seed + i * 10007, live_crops, real_bgs) for i, sz in enumerate(sizes) if sz > 0]
    with ProcessPoolExecutor(max_workers=min(len(tasks), 12)) as pool:
        parts = list(pool.map(_worker_chunk, tasks))
    xs = np.concatenate([p[0] for p in parts], axis=0)
    ys = np.concatenate([p[1] for p in parts], axis=0)
    return xs, ys
