"""Honest synthetic 3D cube video generator for the PC continuous-scan algorithm spike.

Renders realistic 640x480 BGR frames with:
  - arbitrary SE(3) rotation + off-center translation (1, 2, or 3 visible faces via backface culling),
  - stickered, stickerless, and pastel (stlb-like) palettes,
  - directional Lambertian shading + moving specular glare highlight + AWB warm/cool drift,
  - printed dark logo on the WHITE center,
  - finger occlusion polygons + complete regrip blackouts,
  - detector corner jitter, random face drops, and false-positive clutter quads,
  - optional mid-scan layer twist.

STRICT HONESTY CONTRACT:
  `DetectedQuad` exposes ONLY 2D clockwise image-space corners `[p0, p1, p2, p3]` (randomly cyclically
  shifted so the mapper CANNOT cheat by reading corner-0 as top-left!). Face identity, rotation,
  colors, and 3D pose stay strictly inside `FrameTruth` for post-hoc scoring only.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence, Tuple

import cv2
import numpy as np

from cube_model import (
    COLORS,
    FACE_BASIS,
    FACES,
    apply_move,
    face_quad_3d,
)

# Realistic sRGB palettes (BGR for OpenCV rendering converted from RGB).
PALETTES_RGB: Dict[str, Dict[str, np.ndarray]] = {
    "stickered": {
        "WHITE": np.array([236, 238, 240], dtype=np.float32),
        "YELLOW": np.array([235, 212, 32], dtype=np.float32),
        "GREEN": np.array([34, 168, 72], dtype=np.float32),
        "BLUE": np.array([28, 86, 206], dtype=np.float32),
        "RED": np.array([204, 38, 42], dtype=np.float32),
        "ORANGE": np.array([238, 118, 28], dtype=np.float32),
    },
    "stickerless": {
        "WHITE": np.array([232, 236, 238], dtype=np.float32),
        "YELLOW": np.array([232, 224, 38], dtype=np.float32),
        "GREEN": np.array([46, 188, 82], dtype=np.float32),
        "BLUE": np.array([32, 108, 216], dtype=np.float32),
        "RED": np.array([214, 48, 56], dtype=np.float32),
        "ORANGE": np.array([242, 126, 48], dtype=np.float32),
    },
    # Low-saturation pastel cube (stlb-like: harder Red/Orange and White/Yellow separation)
    "pastel": {
        "WHITE": np.array([228, 230, 228], dtype=np.float32),
        "YELLOW": np.array([226, 218, 124], dtype=np.float32),
        "GREEN": np.array([112, 192, 134], dtype=np.float32),
        "BLUE": np.array([104, 146, 210], dtype=np.float32),
        "RED": np.array([208, 96, 102], dtype=np.float32),
        "ORANGE": np.array([228, 148, 96], dtype=np.float32),
    },
}


@dataclass
class DetectedQuad:
    """What an unpoisoned free-pose face detector gives the mapper: 2D clockwise corners ONLY."""
    corners: np.ndarray  # shape (4, 2) float32 clockwise in image coordinates
    score: float = 0.95


@dataclass
class FrameTruth:
    """Ground truth kept strictly for offline scoring and visualization — never passed to mapper."""
    visible_faces: Dict[str, np.ndarray]  # face -> true (4, 2) [TL, TR, BR, BL]
    occluded_cells: Dict[str, List[int]]
    is_regrip: bool
    twist_applied: bool


@dataclass
class SyntheticFrame:
    frame_idx: int
    timestamp_ms: int
    image_bgr: np.ndarray
    detections: List[DetectedQuad]
    truth: FrameTruth


@dataclass
class ScenarioConfig:
    name: str
    state: Dict[str, List[str]]
    palette: str = "stickered"
    stickerless: bool = False
    logo_on_white: bool = False
    glare_strength: float = 0.0
    awb_drift: bool = False
    finger_occlusion: bool = False
    regrip_range: Optional[Tuple[int, int]] = None
    regrip_return_euler: Optional[Tuple[float, float, float]] = None
    start_euler: Tuple[float, float, float] = (0.45, -0.55, 0.08)
    waypoints: Optional[List[Tuple[float, float, float]]] = None
    jitter_px: float = 0.8
    drop_prob: float = 0.0
    false_quad_prob: float = 0.0
    motion_blur_prob: float = 0.0
    twist_at_frame: Optional[int] = None
    twist_move: str = "U"
    n_frames: int = 120
    seed: int = 20261124


def euler_xyz(rx: float, ry: float, rz: float) -> np.ndarray:
    cx, sx = np.cos(rx), np.sin(rx)
    cy, sy = np.cos(ry), np.sin(ry)
    cz, sz = np.cos(rz), np.sin(rz)
    Rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    Ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    Rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return Rz @ Ry @ Rx


def _make_face_texture(
    colors_9: Sequence[str],
    palette_rgb: Dict[str, np.ndarray],
    stickerless: bool,
    logo_on_white: bool,
    tex_size: int = 180,
) -> np.ndarray:
    """Create upright RGB float32 face texture."""
    body = np.array([26, 26, 28], dtype=np.float32) if not stickerless else np.array([50, 50, 52], dtype=np.float32)
    tex = np.broadcast_to(body, (tex_size, tex_size, 3)).copy()
    cell = tex_size // 3
    margin = 4 if stickerless else 8
    for idx, col_name in enumerate(colors_9):
        r, c = divmod(idx, 3)
        y0, y1 = r * cell + margin, (r + 1) * cell - margin
        x0, x1 = c * cell + margin, (c + 1) * cell - margin
        tex[y0:y1, x0:x1] = palette_rgb[col_name]
        if idx == 4 and logo_on_white and col_name == "WHITE":
            # Draw a realistic dark/blue Rubik's/GAN center logo covering the inner 38% of the center tile.
            cy, cx = (y0 + y1) // 2, (x0 + x1) // 2
            rad = int(cell * 0.21)
            cv2.circle(tex, (cx, cy), rad, (42.0, 58.0, 96.0), -1)
            cv2.rectangle(tex, (cx - rad // 2, cy - rad // 3), (cx + rad // 2, cy + rad // 3), (210.0, 50.0, 48.0), -1)
    return tex


def _project_points(pts_3d: np.ndarray, R: np.ndarray, t: np.ndarray, fx: float, fy: float, cx: float, cy: float) -> Tuple[np.ndarray, np.ndarray]:
    """Right-handed pinhole camera at (0, 0, +t[2]) looking toward -Z (det = +1, preserves cube chirality!)."""
    world = pts_3d @ R.T
    x_cam = world[:, 0] + t[0]
    y_cam = -(world[:, 1] + t[1])
    z_cam = t[2] - world[:, 2]
    cam = np.stack([x_cam, y_cam, z_cam], axis=-1)
    u = fx * (x_cam / z_cam) + cx
    v = fy * (y_cam / z_cam) + cy
    return np.stack([u, v], axis=-1).astype(np.float32), cam


def _ensure_clockwise(quad: np.ndarray) -> np.ndarray:
    # In image coords (Y down), positive signed shoelace area = clockwise!
    x, y = quad[:, 0], quad[:, 1]
    area2 = float(np.sum(x * np.roll(y, -1) - np.roll(x, -1) * y))
    if area2 < 0:
        return quad[::-1].copy()
    return quad


def _default_waypoints(start: Tuple[float, float, float]) -> List[Tuple[float, float, float]]:
    """Smooth 6-face tour passing every face near frontal AND showing adjacent corners."""
    return [
        start,
        (0.18, -0.15, 0.04),         # near-frontal F (+ slight U, R)
        (0.65, -0.65, 0.05),         # U-F-R corner
        (1.32, -0.10, 0.05),         # near-frontal U
        (0.55, -1.45, -0.05),        # U-R-B corner
        (0.12, -1.62, 0.03),         # near-frontal R
        (0.30, -2.55, 0.06),         # R-B corner
        (0.15, -3.10, -0.04),        # near-frontal B
        (-0.55, -3.75, 0.05),        # D-B-L corner
        (0.15, -4.68, -0.05),        # near-frontal L
        (-1.28, -4.75, 0.04),        # near-frontal D
        (-0.52, -5.65, 0.06),        # D-L-F corner
        (0.45, -6.28, 0.05),         # return to F-U-R
    ]


def generate_sequence(cfg: ScenarioConfig, width: int = 640, height: int = 480) -> List[SyntheticFrame]:
    rng = np.random.default_rng(cfg.seed)
    pal = PALETTES_RGB[cfg.palette]
    cur_state = {f: list(v) for f, v in cfg.state.items()}
    waypoints = cfg.waypoints or _default_waypoints(cfg.start_euler)

    fx = fy = 620.0
    cx0, cy0 = width / 2.0, height / 2.0
    # Direction TOWARD the front-above-left light source in world space (+Z is toward camera).
    to_light = np.array([-0.22, 0.42, 0.88], dtype=np.float64)
    to_light /= np.linalg.norm(to_light)

    frames: List[SyntheticFrame] = []
    twist_done = False

    # Precompute smooth Euler path across n_frames
    eulers = []
    segs = len(waypoints) - 1
    for i in range(cfg.n_frames):
        u = i / max(1, cfg.n_frames - 1) * segs
        s = min(segs - 1, int(u))
        alpha = u - s
        alpha = alpha * alpha * (3 - 2 * alpha)
        e0, e1 = np.array(waypoints[s]), np.array(waypoints[s + 1])
        eulers.append(tuple((1 - alpha) * e0 + alpha * e1))

    # Optional regrip jump: return at regrip_return_euler, hold briefly, tilt to show an adjacent face,
    # then tour the remaining faces so all 6 sides are actually presented.
    if cfg.regrip_range is not None and cfg.regrip_return_euler is not None:
        r0, r1 = cfg.regrip_range
        rem = cfg.n_frames - r1
        if rem > 1:
            e_start = np.array(cfg.regrip_return_euler)
            sub_wp = [
                e_start,
                e_start + np.array([0.04, 0.04, 0.02]),        # brief hold on return face (tests symmetry guard!)
                e_start + np.array([0.62, 0.55, -0.04]),       # tilt to reveal adjacent face (breaks symmetry!)
                np.array([0.15, -3.10, -0.04]),                # near-frontal B
                np.array([0.15, -4.68, -0.05]),                # near-frontal L
                np.array([-1.28, -4.75, 0.04]),                # near-frontal D
                np.array([-0.45, -5.65, 0.05]),                # D-L-F corner
                np.array([0.25, -6.28, 0.04]),                 # return to F
            ]
            for j in range(rem):
                u = j / max(1, rem - 1) * (len(sub_wp) - 1)
                s = min(len(sub_wp) - 2, int(u))
                a = u - s
                eulers[r1 + j] = tuple((1 - a) * sub_wp[s] + a * sub_wp[s + 1])

    for i in range(cfg.n_frames):
        if cfg.twist_at_frame is not None and i == cfg.twist_at_frame and not twist_done:
            cur_state = apply_move(cur_state, cfg.twist_move)
            twist_done = True

        # Cluttered desk background (warm grey gradient + subtle noise)
        bg = np.zeros((height, width, 3), dtype=np.float32)
        grad = np.linspace(55.0, 95.0, height, dtype=np.float32)[:, None, None]
        bg[:] = grad * np.array([0.92, 0.96, 1.02], dtype=np.float32)
        bg += rng.normal(0, 2.5, bg.shape).astype(np.float32)

        is_regrip = cfg.regrip_range is not None and cfg.regrip_range[0] <= i < cfg.regrip_range[1]
        if is_regrip:
            cv2.ellipse(bg, (width // 2, height // 2 + 40), (160, 110), 12, 0, 360, (125, 155, 195), -1)
            img_u8 = np.clip(bg, 0, 255).astype(np.uint8)
            frames.append(SyntheticFrame(i, i * 33, img_u8, [], FrameTruth({}, {}, True, twist_done)))
            continue

        rx, ry, rz = eulers[i]
        R = euler_xyz(rx, ry, rz)
        tx = 0.35 * np.sin(2 * np.pi * i / max(1, cfg.n_frames) * 1.7)
        ty = 0.25 * np.cos(2 * np.pi * i / max(1, cfg.n_frames) * 1.3)
        tz = 6.2 + 0.45 * np.sin(2 * np.pi * i / max(1, cfg.n_frames) * 1.1)
        t = np.array([tx, ty, tz], dtype=np.float64)
        cam_world = np.array([-tx, -ty, tz], dtype=np.float64)

        if cfg.awb_drift:
            phase = i / max(1, cfg.n_frames - 1)
            awb_rgb = np.array([0.90 + 0.24 * phase, 0.98 + 0.02 * phase, 1.14 - 0.34 * phase], dtype=np.float32)
        else:
            awb_rgb = np.array([1.0, 1.0, 1.0], dtype=np.float32)

        visible_faces: Dict[str, np.ndarray] = {}
        detections: List[DetectedQuad] = []
        occluded_cells: Dict[str, List[int]] = {}

        # Sort faces back-to-front by camera depth z_cam = tz - c_world[2] (largest depth drawn first)
        face_order = []
        for f in FACES:
            n_world = R @ FACE_BASIS[f][0]
            c_world = n_world.copy()
            view_dir = cam_world - c_world
            view_dir /= np.linalg.norm(view_dir)
            cos_view = float(np.dot(n_world, view_dir))
            if cos_view > 0.22:
                z_depth = float(tz - c_world[2])
                face_order.append((z_depth, f, n_world, view_dir, cos_view))
        face_order.sort(reverse=True)

        canvas_rgb = bg[:, :, ::-1].copy()
        tex_size = 180
        src_pts = np.array([[0, 0], [tex_size - 1, 0], [tex_size - 1, tex_size - 1], [0, tex_size - 1]], dtype=np.float32)

        for _, f, n_world, view_dir, cos_view in face_order:
            q3 = face_quad_3d(f)
            q2, _ = _project_points(q3, R, t, fx, fy, cx0, cy0)
            q2_cw = _ensure_clockwise(q2)
            visible_faces[f] = q2.copy()

            tex = _make_face_texture(cur_state[f], pal, cfg.stickerless, cfg.logo_on_white, tex_size)
            lambert = max(0.45, min(1.05, 0.48 + 0.58 * max(0.0, float(np.dot(n_world, to_light)))))
            tex = tex * lambert * awb_rgb[None, None, :]

            if cfg.glare_strength > 0.0:
                half_v = view_dir + to_light
                half_v /= np.linalg.norm(half_v)
                spec = max(0.0, float(np.dot(n_world, half_v))) ** 10
                if spec > 0.15:
                    yy, xx = np.mgrid[0:tex_size, 0:tex_size].astype(np.float32)
                    gx = tex_size * (0.3 + 0.4 * np.sin(i * 0.12))
                    gy = tex_size * (0.35 + 0.3 * np.cos(i * 0.09))
                    blob = np.exp(-((xx - gx) ** 2 + (yy - gy) ** 2) / (2 * (tex_size * 0.24) ** 2))
                    tex = tex + (255.0 * cfg.glare_strength * spec * blob[:, :, None])

            H = cv2.getPerspectiveTransform(src_pts, q2)
            warped = cv2.warpPerspective(tex, H, (width, height), flags=cv2.INTER_LINEAR)
            mask = np.zeros((height, width), dtype=np.uint8)
            cv2.fillConvexPoly(mask, np.rint(q2).astype(np.int32), 255)
            canvas_rgb[mask > 0] = warped[mask > 0]

            # Build honest detector output (random cyclic corner shift + jitter, optional drop)
            if rng.random() >= cfg.drop_prob:
                shift = int(rng.integers(0, 4))
                shifted = np.roll(q2_cw, shift, axis=0)
                if cfg.jitter_px > 0:
                    shifted = shifted + rng.normal(0.0, cfg.jitter_px, shifted.shape).astype(np.float32)
                detections.append(DetectedQuad(corners=_ensure_clockwise(shifted), score=float(rng.uniform(0.75, 0.98))))

        # Optional finger occlusion covering a corner of the cube on periodic frames
        if cfg.finger_occlusion and (i % 24) < 10 and visible_faces:
            # Draw a realistic skin-tone thumb overlapping the bottom-left area of the cube
            all_pts = np.concatenate(list(visible_faces.values()), axis=0)
            bx = int(np.percentile(all_pts[:, 0], 22))
            by = int(np.percentile(all_pts[:, 1], 78))
            skin_rgb = np.array([196.0, 150.0, 128.0], dtype=np.float32) * awb_rgb
            cv2.ellipse(canvas_rgb, (bx, by), (36, 22), -25, 0, 360, tuple(float(v) for v in skin_rgb), -1)

        # Optional false-positive clutter quad far from the cube
        if cfg.false_quad_prob > 0 and rng.random() < cfg.false_quad_prob:
            fx0 = float(rng.uniform(20, 90))
            fy0 = float(rng.uniform(20, 90))
            fq = np.array([[fx0, fy0], [fx0 + 48, fy0 + 5], [fx0 + 44, fy0 + 52], [fx0 - 4, fy0 + 47]], dtype=np.float32)
            detections.append(DetectedQuad(corners=fq, score=0.45))

        # Sensor noise + optional motion blur
        canvas_rgb += rng.normal(0, 2.0, canvas_rgb.shape).astype(np.float32)
        img_bgr = np.clip(canvas_rgb[:, :, ::-1], 0, 255).astype(np.uint8)
        if cfg.motion_blur_prob > 0 and rng.random() < cfg.motion_blur_prob:
            k = np.zeros((9, 9), dtype=np.float32)
            k[4, :] = 1.0 / 9.0
            img_bgr = cv2.filter2D(img_bgr, -1, k)

        # Shuffle detection order so the mapper cannot rely on painter order
        rng.shuffle(detections)
        frames.append(SyntheticFrame(i, i * 33, img_bgr, detections, FrameTruth(visible_faces, occluded_cells, False, twist_done)))

    return frames
