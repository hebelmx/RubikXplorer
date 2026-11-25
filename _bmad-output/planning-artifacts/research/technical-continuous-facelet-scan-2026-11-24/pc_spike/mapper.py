"""Intrinsics-free 2D face-graph continuous Rubik's Cube scanner (PC algorithm spike).

Design principles (from the updated research package + adversarial review):
  1. ZERO pose/color/identity cheating: consumes ONLY raw BGR frames + unordered 2D clockwise quads.
  2. Intrinsics-free 2D shared-edge topology graph: stitches adjacent faces purely from shared 2D edges
     (works even on a 100% solved or F2L-solved symmetric cube).
  3. Regrip recovery with symmetry guard: never locks quarter-turn orientation from a single symmetric
     face's colors; merges a post-regrip fragment only when SO(3) alignment is uniquely disambiguated.
  4. Deferred global 6-cluster color anchoring + tracked log-RGB AWB compensation: handles starting on
     Yellow/Orange/Blue (no White), warm/cool lighting drift, logo centers, and pastel cubes (stlb).
  5. Graded observation quality (foreshortening + glare + sharpness + occlusion) avoiding frontal-glare
     deadlock while keeping oblique faces for topological stitching.
  6. Immediate local physical invariants (same/opposite cubie, corner cyclic chirality, <= 9 count,
     duplicate cubie) with evidence-weighted conflict reopening + mid-scan layer-twist detection.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence, Tuple

import cv2
import numpy as np

from cube_model import (
    COLORS,
    CUBE_SO3_ROTATIONS,
    FACE_ADJACENCY,
    FACE_BASIS,
    FACES,
    HOME_CENTER_COLOR,
    check_local_invariants,
    is_face_rotationally_symmetric,
    rotate_face_grid,
    sticker_center_3d,
)
from synth_video import DetectedQuad

def rgb_to_lab_vec(rgb: np.ndarray) -> np.ndarray:
    """Convert sRGB in [0, 255] (shape (..., 3)) to CIELAB D65."""
    c = np.clip(rgb, 0.0, 255.0) / 255.0
    lin = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    r, g, b = lin[..., 0], lin[..., 1], lin[..., 2]
    x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
    y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
    z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883

    def f(v):
        return np.where(v > 0.0088564517, np.cbrt(np.maximum(v, 0.0)), 7.787037 * v + 16.0 / 116.0)

    fx, fy, fz = f(x), f(y), f(z)
    return np.stack([np.clip(116.0 * fy - 16.0, 0.0, 100.0), 500.0 * (fx - fy), 200.0 * (fy - fz)], axis=-1)


def shading_invariant_lab(rgb: np.ndarray) -> np.ndarray:
    """Cancel scalar Lambertian shading (`s * RGB`) by L2-normalizing RGB before CIELAB conversion.

    Target norm 210.0 (strictly < 255) prevents saturating the R channel of vivid Red stickers,
    which previously clipped R at 255 while boosting G/B and collapsing Red toward Orange.
    """
    arr = np.clip(rgb, 1.0, 255.0).astype(np.float64)
    norms = np.linalg.norm(arr, axis=-1, keepdims=True)
    scaled = np.clip(arr * (210.0 / np.maximum(norms, 1.0)), 0.0, 255.0)
    return rgb_to_lab_vec(scaled)


def white_referenced_hue_dist(
    lab: np.ndarray, proto: np.ndarray, white_ab: np.ndarray, is_white_proto: bool
) -> float:
    """CIEDE2000-style hue-weighted distance relative to the cube's own WHITE center `(a_W, b_W)`.

    Soft specular glare and deep shadows change radial saturation (`C*`) far more than hue angle.
    Weighting angular hue (`ΔH*`) strongly and radial saturation (`ΔC*`) lightly keeps Red vs Orange
    cleanly separated even when one sticker is under lamp glare and another is in shadow.
    """
    dL = float(lab[0] - proto[0])
    if is_white_proto:
        da = float(lab[1] - proto[1])
        db = float(lab[2] - proto[2])
        return float(np.sqrt(0.35 * dL * dL + da * da + db * db))
    ux = lab[1:3] - white_ab
    up = proto[1:3] - white_ab
    cx = float(np.linalg.norm(ux))
    cp = float(np.linalg.norm(up))
    if cx < 6.0 or cp < 6.0:
        da = float(lab[1] - proto[1])
        db = float(lab[2] - proto[2])
        return float(np.sqrt(0.35 * dL * dL + da * da + db * db))
    cos_t = float(np.clip(np.dot(ux, up) / (cx * cp), -1.0, 1.0))
    sin_half = float(np.sqrt(max(0.0, 0.5 * (1.0 - cos_t))))
    d_hue = 2.0 * float(np.sqrt(cx * cp)) * sin_half
    d_rad = abs(cx - cp)
    return float(np.sqrt(0.25 * dL * dL + (1.80 * d_hue) ** 2 + (0.22 * d_rad) ** 2))


def _solve_balanced_8_per_color(cost_mat: np.ndarray, cap: int = 8) -> np.ndarray:
    """Exact global min-cost assignment of 48 non-center cells to 6 colors (exactly `cap=8` each).

    Pure-NumPy successive shortest augmenting paths on the 6-node color residual graph (<0.5 ms).
    """
    n_cells, k = cost_mat.shape
    assign = np.argmin(cost_mat, axis=1).astype(np.int32)
    counts = np.bincount(assign, minlength=k)
    inf = 1e18

    for _ in range(n_cells):
        over = [c for c in range(k) if counts[c] > cap]
        under = [c for c in range(k) if counts[c] < cap]
        if not over or not under:
            break
        # Build 6x6 residual edge weights: cheapest cell currently in color u to move to color v
        edge_w = np.full((k, k), inf, dtype=np.float64)
        best_cell = np.full((k, k), -1, dtype=np.int32)
        for u in range(k):
            idxs = np.where(assign == u)[0]
            if not len(idxs):
                continue
            base = cost_mat[idxs, u]
            for v in range(k):
                if u == v:
                    continue
                deltas = cost_mat[idxs, v] - base
                j = int(np.argmin(deltas))
                edge_w[u, v] = float(deltas[j])
                best_cell[u, v] = int(idxs[j])

        # Bellman-Ford from all excess colors (multi-source) to find shortest augmenting path
        dist = np.full(k, inf, dtype=np.float64)
        parent = np.full(k, -1, dtype=np.int32)
        for u in over:
            dist[u] = 0.0
        for _step in range(k - 1):
            updated = False
            for u in range(k):
                if dist[u] >= inf * 0.5:
                    continue
                for v in range(k):
                    w = edge_w[u, v]
                    if w < inf * 0.5 and dist[u] + w < dist[v] - 1e-12:
                        dist[v] = dist[u] + w
                        parent[v] = u
                        updated = True
            if not updated:
                break

        tgt = min(under, key=lambda v: dist[v])
        if dist[tgt] >= inf * 0.5:
            break
        # Reconstruct path and shift 1 cell per edge along the augmenting path
        path = []
        cur = tgt
        while parent[cur] != -1:
            path.append((int(parent[cur]), int(cur)))
            cur = int(parent[cur])
        for u, v in path:
            cell_i = int(best_cell[u, v])
            assign[cell_i] = v
            counts[u] -= 1
            counts[v] += 1
    return assign


# Reference shading-invariant CIELAB prototypes used ONLY for global 24-rotation center matching.
REF_CENTER_LAB: Dict[str, np.ndarray] = {
    "WHITE": shading_invariant_lab(np.array([235.0, 235.0, 235.0])),
    "YELLOW": shading_invariant_lab(np.array([235.0, 215.0, 35.0])),
    "GREEN": shading_invariant_lab(np.array([40.0, 175.0, 75.0])),
    "BLUE": shading_invariant_lab(np.array([30.0, 95.0, 210.0])),
    "RED": shading_invariant_lab(np.array([208.0, 42.0, 46.0])),
    "ORANGE": shading_invariant_lab(np.array([240.0, 122.0, 34.0])),
}


def _quad_quality(quad: np.ndarray) -> Tuple[float, float]:
    """Intrinsics-free viewing-angle / foreshortening quality in [0, 1] and mean edge length."""
    edges = np.array([quad[(k + 1) % 4] - quad[k] for k in range(4)], dtype=np.float64)
    lens = np.linalg.norm(edges, axis=1)
    if np.min(lens) < 18.0:
        return 0.0, float(np.mean(lens))
    h_len = 0.5 * (lens[0] + lens[2])
    v_len = 0.5 * (lens[1] + lens[3])
    aspect = min(h_len, v_len) / max(h_len, v_len)
    sins = []
    for k in range(4):
        u = -edges[(k - 1) % 4] / max(1e-6, lens[(k - 1) % 4])
        v = edges[k] / max(1e-6, lens[k])
        cross = abs(float(u[0] * v[1] - u[1] * v[0]))
        sins.append(cross)
    rect_score = min(sins)
    # Graded score: remains high (> 0.7) up to ~35 deg tilt so frontal glare does not deadlock.
    raw = aspect * rect_score
    q_angle = float(np.clip((raw - 0.22) / 0.62, 0.05, 1.0))
    return q_angle, float(np.mean(lens))


def _quad_area(quad: np.ndarray) -> float:
    x, y = quad[:, 0], quad[:, 1]
    return 0.5 * abs(float(np.sum(x * np.roll(y, -1) - np.roll(x, -1) * y)))


def _sample_face_cells(image_bgr: np.ndarray, aligned_quad: np.ndarray) -> Tuple[np.ndarray, np.ndarray, float, bool]:
    """Unwarp clockwise quad [TL, TR, BR, BL] -> (9x3 RGB, 9 quality weights, sharpness, glare_flag)."""
    side = 96
    dst = np.array([[0, 0], [side - 1, 0], [side - 1, side - 1], [0, side - 1]], dtype=np.float32)
    H = cv2.getPerspectiveTransform(aligned_quad.astype(np.float32), dst)
    crop_bgr = cv2.warpPerspective(image_bgr, H, (side, side), flags=cv2.INTER_LINEAR)
    crop_rgb = cv2.cvtColor(crop_bgr, cv2.COLOR_BGR2RGB).astype(np.float32)

    # Sharpness via Laplacian variance (normalized to [0.25, 1.0])
    gray = cv2.cvtColor(crop_bgr, cv2.COLOR_BGR2GRAY)
    lap_var = float(cv2.Laplacian(gray, cv2.CV_64F).var())
    q_sharp = float(np.clip((lap_var - 25.0) / 140.0, 0.20, 1.0))

    q_angle, _ = _quad_quality(aligned_quad)
    cell_sz = side / 3.0
    rgbs = np.zeros((9, 3), dtype=np.float32)
    quals = np.zeros(9, dtype=np.float32)
    any_glare = False

    for idx in range(9):
        r, c = divmod(idx, 3)
        y0 = int(round((r + 0.16) * cell_sz))
        y1 = int(round((r + 0.84) * cell_sz))
        x0 = int(round((c + 0.16) * cell_sz))
        x1 = int(round((c + 0.84) * cell_sz))
        patch = crop_rgb[y0:y1, x0:x1]

        if idx == 4:
            # Logo-tolerant outer ring for center cell (4 corner sub-patches avoiding central logo)
            ph, pw = patch.shape[:2]
            bh, bw = max(3, ph // 3), max(3, pw // 3)
            corners = [
                patch[:bh, :bw].reshape(-1, 3),
                patch[:bh, pw - bw :].reshape(-1, 3),
                patch[ph - bh :, :bw].reshape(-1, 3),
                patch[ph - bh :, pw - bw :].reshape(-1, 3),
            ]
            # Pick the 3 most mutually consistent corner sub-patches (rejects asymmetric logo/finger corner)
            meds = np.array([np.median(p, axis=0) for p in corners])
            dists = np.sum(np.linalg.norm(meds[:, None, :] - meds[None, :, :], axis=-1), axis=1)
            keep3 = np.argsort(dists)[:3]
            pixels = np.concatenate([corners[k] for k in keep3], axis=0)
        else:
            # Inner 48% ROI for non-center cells
            iy0 = int(round((r + 0.26) * cell_sz))
            iy1 = int(round((r + 0.74) * cell_sz))
            ix0 = int(round((c + 0.26) * cell_sz))
            ix1 = int(round((c + 0.74) * cell_sz))
            pixels = crop_rgb[iy0:iy1, ix0:ix1].reshape(-1, 3)

        med_rgb = np.median(pixels, axis=0)
        std_rgb = float(np.mean(np.std(pixels, axis=0)))

        # Glare detection: real specular highlight blows out all channels toward white (min > 238),
        # or clips max > 248 WITH either desaturation (min > 180) or a steep highlight gradient across
        # the cell (p95 - p25 > 22). Do NOT penalize uniformly vibrant neon-yellow/orange/green
        # stickerless cells whose single bright channel naturally reaches 248-255 under webcam exposure.
        clipped_max = float(np.mean(np.max(pixels, axis=1) > 248.0))
        clipped_min = float(np.mean(np.min(pixels, axis=1) > 238.0))
        min_med = float(np.min(med_rgb))
        bright_p95 = float(np.percentile(np.mean(pixels, axis=1), 95))
        bright_p25 = float(np.percentile(np.mean(pixels, axis=1), 25))
        grad = bright_p95 - bright_p25
        glare_pen = 0.0
        if (
            clipped_min > 0.15
            or (clipped_max > 0.12 and (min_med > 115.0 or grad > 22.0))
            or (bright_p95 > 244.0 and grad > 20.0)
        ):
            glare_pen = min(0.95, 0.60 + max(clipped_max, clipped_min) * 1.2 + max(0.0, grad - 18.0) / 80.0)
            any_glare = True

        # Occlusion / heterogeneity penalty (finger edge or partial cover creates high patch std)
        occ_pen = float(np.clip((std_rgb - 12.0) / 28.0, 0.0, 0.85))
        # Also penalize typical skin-tone intrusion? No — avoid hard-coding skin tones; instead rely on
        # temporal outlier rejection + heterogeneity penalty!
        q = q_angle * q_sharp * (1.0 - glare_pen) * (1.0 - occ_pen)
        rgbs[idx] = med_rgb
        quals[idx] = max(0.01, q)

    return rgbs, quals, q_sharp, any_glare


def _shared_edge(quad_a: np.ndarray, quad_b: np.ndarray) -> Optional[Tuple[int, int]]:
    """If clockwise quads share a 2D edge, return (edge_a, edge_b); else None."""
    _, len_a = _quad_quality(quad_a)
    _, len_b = _quad_quality(quad_b)
    tol = 0.14 * min(len_a, len_b)
    best = None
    best_err = tol
    for ea in range(4):
        a0, a1 = quad_a[ea], quad_a[(ea + 1) % 4]
        for eb in range(4):
            b0, b1 = quad_b[eb], quad_b[(eb + 1) % 4]
            # Opposite vertex traversal on shared edge of two CW faces
            err = 0.5 * (float(np.linalg.norm(a0 - b1)) + float(np.linalg.norm(a1 - b0)))
            if err < best_err:
                best_err = err
                best = (ea, eb)
    return best


def _rotate_fragment_slot(face: str, cell_idx: int, R: np.ndarray) -> Tuple[str, int]:
    """Map (face, cell_idx) under whole-cube rotation R in CUBE_SO3_ROTATIONS."""
    n = R @ FACE_BASIS[face][0]
    p = R @ sticker_center_3d(face, cell_idx)
    # Find target face whose normal matches n
    tgt_face = max(FACES, key=lambda f: float(np.dot(FACE_BASIS[f][0], n)))
    _, r_vec, d_vec = FACE_BASIS[tgt_face]
    col = int(round(float(np.dot(p, r_vec)) * 1.5)) + 1
    row = int(round(float(np.dot(p, d_vec)) * 1.5)) + 1
    return tgt_face, row * 3 + col


def _rotate_face_name(face: str, R: np.ndarray) -> str:
    n = R @ FACE_BASIS[face][0]
    return max(FACES, key=lambda f: float(np.dot(FACE_BASIS[f][0], n)))


@dataclass
class CellObs:
    frame_idx: int
    rgb_awb: np.ndarray  # AWB-compensated RGB in [0, 255]
    lab: np.ndarray      # CIELAB of rgb_awb
    quality: float


@dataclass
class TrackedFace:
    frag_face: str
    aligned_quad: np.ndarray  # (4, 2) in fragment's canonical orientation
    raw_rgbs: np.ndarray      # (9, 3) raw RGB from last frame
    quals: np.ndarray         # (9,) qualities from last frame


class CubeFragment:
    """A connected topological cube graph built purely from 2D shared edges + frame tracking."""

    def __init__(self, frag_id: int) -> None:
        self.frag_id = frag_id
        self.obs: Dict[Tuple[str, int], List[CellObs]] = {(f, i): [] for f in FACES for i in range(9)}
        self.observed_faces: set[str] = set()
        self.tracked: Dict[str, TrackedFace] = {}
        self.log_awb: np.ndarray = np.zeros(3, dtype=np.float64)  # running chromaticity drift estimate
        self.twist_streak: Dict[str, int] = {f: 0 for f in FACES}
        self.frame_count: int = 0

    def face_robust_lab(self, face: str, cell_idx: int, top_k_frac: float = 0.45) -> Optional[Tuple[np.ndarray, float, int]]:
        """Quality-weighted robust median Lab using clean top observations (rejects transient glare/finger)."""
        lst = self.obs[(face, cell_idx)]
        if not lst:
            return None
        ordered = sorted(lst, key=lambda o: o.quality, reverse=True)
        max_q = ordered[0].quality
        # Keep only observations near the best achieved quality so glare-washed frames (q << max_q) never dilute clean views.
        clean = [o for o in ordered if o.quality >= max(0.12, 0.45 * max_q)]
        if not clean:
            clean = ordered[:1]
        k = max(min(3, len(clean)), int(np.ceil(len(clean) * top_k_frac)))
        top = clean[:k]
        labs = np.array([o.lab for o in top], dtype=np.float64)
        ws = np.array([o.quality for o in top], dtype=np.float64)
        # Never average two wildly disagreeing samples (e.g. 1 noisy frame + 1 clean frame); wait 1 frame for tie-break!
        if len(top) == 2 and float(np.linalg.norm(labs[0] - labs[1])) > 18.0:
            return None
        # Reject strong color outliers from the top pool (e.g. a sharp unblended finger covering the cell)
        if len(top) >= 4:
            med = np.median(labs, axis=0)
            ds = np.linalg.norm(labs - med[None, :], axis=1)
            inliers = ds <= max(12.0, float(np.percentile(ds, 75)) * 1.4)
            if np.any(inliers):
                labs = labs[inliers]
                ws = ws[inliers]
        w_sum = float(np.sum(ws))
        est = np.sum(labs * ws[:, None], axis=0) / max(1e-6, w_sum)
        clean_q_sum = float(sum(o.quality for o in clean))
        return est, clean_q_sum, len(clean)

    def confirmed_faces(self, min_n: int = 2, min_q: float = 0.40) -> set[str]:
        """Return faces backed by >= `min_n` observations and >= `min_q` center quality (filters 1-frame ghosts)."""
        out: set[str] = set()
        for f in self.observed_faces:
            r = self.face_robust_lab(f, 4)
            if r is not None and r[2] >= min_n and r[1] >= min_q:
                out.add(f)
        return out


@dataclass
class MapperSnapshot:
    frame_idx: int
    net: Dict[str, List[Optional[str]]]
    conflicted_slots: List[Tuple[str, int]]
    tracked_quads: Dict[str, np.ndarray]  # canonical or local face -> (4, 2)
    global_anchored: bool
    twist_detected: bool
    cue: str
    committed_count: int
    invariant_count: int
    pending_fragment_faces: int


class ContinuousCubeMapper:
    """End-to-end PC continuous scanner with zero ground-truth access."""

    def __init__(self) -> None:
        self.master: Optional[CubeFragment] = None
        self.pending: Optional[CubeFragment] = None
        self._next_frag_id = 0
        self._prev_unattached: List[Tuple[np.ndarray, int]] = []  # (quad, consecutive_frames)
        self.global_R: Optional[np.ndarray] = None  # rotates master local frame -> canonical URFDLB
        self.center_prototypes: Optional[Dict[str, np.ndarray]] = None
        self.net: Dict[str, List[Optional[str]]] = {f: [None] * 9 for f in FACES}
        self.slot_support: Dict[Tuple[str, int], float] = {}
        self.conflicted_slots: set[Tuple[str, int]] = set()
        self.twist_detected: bool = False
        self.twist_frame: Optional[int] = None
        self.empty_streak: int = 0
        self._pending_miss_streak: int = 0
        self.last_cue: str = "Show the cube to the camera"
        self.reappearance_events: List[Tuple[int, bool, str]] = []  # (frame, merged_ok, detail)

    def _new_fragment(self) -> CubeFragment:
        f = CubeFragment(self._next_frag_id)
        self._next_frag_id += 1
        return f

    def _filter_plausible_quads(self, detections: Sequence[DetectedQuad]) -> List[DetectedQuad]:
        if not detections:
            return []
        areas = [_quad_area(d.corners) for d in detections]
        max_a = max(areas)
        out = []
        for d, a in zip(detections, areas):
            # Reject tiny background clutter quads (< 28% of largest quad or < 2800 px^2)
            if a < 2800.0 or a < 0.28 * max_a:
                continue
            q_ang, _ = _quad_quality(d.corners)
            if q_ang <= 0.0:
                continue
            out.append(d)
        return out

    def _match_to_tracked(
        self, frag: CubeFragment, image_bgr: np.ndarray, detections: Sequence[DetectedQuad]
    ) -> Tuple[Dict[int, Tuple[str, np.ndarray]], List[int]]:
        """Match current detections to frag.tracked by vertex displacement + center-color continuity guard."""
        matched_det: Dict[int, Tuple[str, np.ndarray]] = {}
        used_faces: set[str] = set()
        candidates = []
        det_center_cache: Dict[int, Tuple[np.ndarray, float]] = {}

        def _det_center(di: int, aligned: np.ndarray) -> Tuple[np.ndarray, float]:
            if di not in det_center_cache:
                rgbs, quals, _, _ = _sample_face_cells(image_bgr, aligned)
                det_center_cache[di] = (shading_invariant_lab(rgbs[4]), float(quals[4]))
            return det_center_cache[di]

        for di, det in enumerate(detections):
            for fname, tf in frag.tracked.items():
                _, edge_len = _quad_quality(tf.aligned_quad)
                max_disp = 0.42 * edge_len
                best_shift, best_d = 0, 1e9
                for shift in range(4):
                    cand = np.roll(det.corners, -shift, axis=0)
                    d = float(np.mean(np.linalg.norm(cand - tf.aligned_quad, axis=1)))
                    if d < best_d:
                        best_d = d
                        best_shift = shift
                if best_d < max_disp:
                    aligned = np.roll(det.corners, -best_shift, axis=0)
                    # Center-color continuity guard: reject any match whose glare-free center color
                    # jumped sharply vs. EITHER the last frame OR the face's accumulated historical center
                    # (so 1 blurry/glared transition frame can never let face R slide onto Blue!).
                    cur_lab, cur_q = _det_center(di, aligned)
                    prev_lab = shading_invariant_lab(tf.raw_rgbs[4])
                    center_de = float(np.linalg.norm(cur_lab - prev_lab))
                    if cur_q > 0.20 and float(tf.quals[4]) > 0.20 and center_de > 24.0:
                        continue
                    hist_c = frag.face_robust_lab(fname, 4)
                    if cur_q > 0.20 and hist_c is not None and hist_c[2] >= 2 and hist_c[1] >= 0.40:
                        # Compare in fragment's AWB-adjusted Lab space against accumulated center history
                        awb_scale = np.exp(-frag.log_awb)
                        rgbs_c, _, _, _ = _sample_face_cells(image_bgr, aligned)
                        cur_awb_lab = shading_invariant_lab(np.clip(rgbs_c[4] * awb_scale, 0.0, 255.0))
                        if float(np.linalg.norm(cur_awb_lab - hist_c[0])) > 26.0:
                            continue
                    candidates.append((best_d, di, fname, aligned))
        candidates.sort(key=lambda x: x[0])
        for _, di, fname, aligned in candidates:
            if di in matched_det or fname in used_faces:
                continue
            matched_det[di] = (fname, aligned)
            used_faces.add(fname)
        unmatched = [i for i in range(len(detections)) if i not in matched_det]
        return matched_det, unmatched

    def _attach_by_shared_edge(
        self,
        frag: CubeFragment,
        image_bgr: np.ndarray,
        detections: Sequence[DetectedQuad],
        assigned: Dict[int, Tuple[str, np.ndarray]],
        unmatched: List[int],
    ) -> Tuple[Dict[int, Tuple[str, np.ndarray]], List[int]]:
        """Iteratively attach unmatched detections that share a 2D edge with any assigned face.

        Guards against shifted duplicate quads (identical center color to an already-assigned face)
        and against noisy quads contradicting an already-confirmed face center in `frag`.
        """
        remaining = list(unmatched)
        center_cache: Dict[int, Tuple[np.ndarray, float]] = {}

        def _center_of(di: int, quad: np.ndarray) -> Tuple[np.ndarray, float]:
            if di not in center_cache:
                rgbs, quals, _, _ = _sample_face_cells(image_bgr, quad)
                center_cache[di] = (shading_invariant_lab(rgbs[4]), float(quals[4]))
            return center_cache[di]

        assigned_centers: List[Tuple[np.ndarray, float]] = [
            _center_of(di, q) for di, (_, q) in assigned.items()
        ]
        confirmed = frag.confirmed_faces()

        changed = True
        while changed and remaining:
            changed = False
            used_faces = {f for f, _ in assigned.values()}
            for di in list(remaining):
                det_q = detections[di].corners
                attached = False
                for _, (fa, qa) in list(assigned.items()):
                    edge = _shared_edge(qa, det_q)
                    if edge is None:
                        continue
                    ea, eb_raw = edge
                    fb, eb_canon = FACE_ADJACENCY[(fa, ea)]
                    if fb in used_faces:
                        continue
                    rot_b = (eb_raw - eb_canon) % 4
                    qb = np.roll(det_q, -rot_b, axis=0)
                    c_lab, c_q = _center_of(di, qb)
                    # Reject if glare-free center matches an already-visible face on this same frame (duplicate quad)
                    if c_q > 0.25 and any(
                        aq > 0.25 and float(np.linalg.norm(c_lab - alab)) < 11.0 for alab, aq in assigned_centers
                    ):
                        continue
                    # Reject if `fb` is already confirmed in `frag` with a sharply different center color
                    if fb in confirmed and c_q > 0.25:
                        hist = frag.face_robust_lab(fb, 4)
                        if hist is not None and float(np.linalg.norm(c_lab - hist[0])) > 26.0:
                            continue
                    assigned[di] = (fb, qb)
                    assigned_centers.append((c_lab, c_q))
                    used_faces.add(fb)
                    remaining.remove(di)
                    changed = True
                    attached = True
                    break
                if attached:
                    break
        return assigned, remaining

    def _update_fragment_observations(
        self,
        frag: CubeFragment,
        frame_idx: int,
        image_bgr: np.ndarray,
        assigned: Dict[int, Tuple[str, np.ndarray]],
    ) -> bool:
        """Sample cells, update tracked log-RGB AWB drift, check for mid-scan twist, and store observations."""
        any_glare = False
        sampled: Dict[str, Tuple[np.ndarray, np.ndarray, np.ndarray]] = {}
        for _, (fname, aligned) in assigned.items():
            rgbs, quals, _, glare = _sample_face_cells(image_bgr, aligned)
            any_glare = any_glare or glare
            sampled[fname] = (aligned, rgbs, quals)

        # 1. Estimate incremental chromaticity AWB drift from overlapping tracked faces (cancels Lambertian scalar)
        chroma_deltas = []
        for fname, (_, rgbs, quals) in sampled.items():
            if fname in frag.tracked:
                prev = frag.tracked[fname]
                good = (quals > 0.50) & (prev.quals > 0.50)
                if np.sum(good) >= 5:
                    lr_cur = np.log(np.clip(rgbs[good], 4.0, 255.0))
                    lr_prev = np.log(np.clip(prev.raw_rgbs[good], 4.0, 255.0))
                    diff = lr_cur - lr_prev
                    # Subtract per-cell scalar brightness change (Lambertian shading) -> pure 3-ch chromaticity drift
                    diff_chroma = diff - np.mean(diff, axis=1, keepdims=True)
                    chroma_deltas.append(np.median(diff_chroma, axis=0))
        if chroma_deltas:
            step = np.median(np.stack(chroma_deltas, axis=0), axis=0)
            # Clamp per-frame AWB step and keep log_awb zero-mean & bounded (prevents long-clip random-walk drift
            # while still covering full 3000K <-> 6500K chromaticity shifts).
            step = np.clip(step, -0.025, 0.025)
            frag.log_awb = frag.log_awb + step
            frag.log_awb = frag.log_awb - float(np.mean(frag.log_awb))
            frag.log_awb = np.clip(frag.log_awb, -0.36, 0.36)

        awb_scale = np.exp(-frag.log_awb)

        # 2. Check for nonrigid layer twist BEFORE adding potentially twisted samples!
        # Only check on master (pending fragments with a twist simply fail SO(3) merge cleanly).
        for fname, (aligned, rgbs, quals) in sampled.items():
            rgbs_awb = np.clip(rgbs * awb_scale[None, :], 0.0, 255.0)
            labs = shading_invariant_lab(rgbs_awb)

            # Check if an outer row/column (>=2 non-center cells on one outer line) simultaneously disagrees
            # sharply (de > 33) with prior high-q history while the center and >=4 other cells stay consistent
            # (hallmark of a physical 90/180-deg layer twist — rejects webcam shading/saturation shifts!).
            center_hist = frag.face_robust_lab(fname, 4)
            if (
                frag is self.master
                and not any_glare
                and center_hist is not None
                and center_hist[2] >= 5
                and quals[4] > 0.55
            ):
                center_de = float(np.linalg.norm(labs[4] - center_hist[0]))
                if center_de < 12.0:
                    flipped_cells: set[int] = set()
                    stable_cells = 0
                    for ci in range(9):
                        if ci == 4 or quals[ci] < 0.60:
                            continue
                        h = frag.face_robust_lab(fname, ci)
                        if h is not None and h[2] >= 5 and h[1] >= 2.2:
                            de_all = float(np.linalg.norm(labs[ci] - h[0]))
                            if de_all < 14.0:
                                stable_cells += 1
                            elif de_all > 33.0:
                                # Distinguish additive specular glare (brightness jumps up while chroma drops toward white)
                                # from a real physical layer twist (color changes hue or becomes more saturated/darker).
                                chroma_cur = float(np.linalg.norm(labs[ci, 1:]))
                                chroma_hist = float(np.linalg.norm(h[0][1:]))
                                raw_bright = float(np.mean(rgbs[ci]))
                                face_med_bright = float(np.median(np.mean(rgbs, axis=1)))
                                is_glare_washout = (chroma_cur < chroma_hist - 10.0) and (raw_bright > face_med_bright + 18.0)
                                if not is_glare_washout:
                                    flipped_cells.add(ci)
                    outer_lines = ({0, 1, 2}, {6, 7, 8}, {0, 3, 6}, {2, 5, 8})
                    line_twist = (
                        len(flipped_cells) >= 2
                        and stable_cells >= 4
                        and any(flipped_cells.issubset(line) for line in outer_lines)
                    )
                    if line_twist:
                        frag.twist_streak[fname] = frag.twist_streak.get(fname, 0) + 1
                        if frag.twist_streak[fname] >= 5:
                            self.twist_detected = True
                            if self.twist_frame is None:
                                self.twist_frame = frame_idx
                    else:
                        frag.twist_streak[fname] = 0

            # Record observations (if no twist triggered)
            if not self.twist_detected:
                for ci in range(9):
                    frag.obs[(fname, ci)].append(
                        CellObs(frame_idx=frame_idx, rgb_awb=rgbs_awb[ci], lab=labs[ci], quality=float(quals[ci]))
                    )
                frag.observed_faces.add(fname)

        # Update active tracked state for next frame
        frag.tracked = {
            fname: TrackedFace(frag_face=fname, aligned_quad=aligned, raw_rgbs=rgbs, quals=quals)
            for fname, (aligned, rgbs, quals) in sampled.items()
        }
        frag.frame_count += 1
        return any_glare

    def _try_merge_pending(self, frame_idx: int) -> None:
        """Try to align & merge self.pending into self.master over 24 SO(3) rotations with symmetry guard."""
        if self.master is None or self.pending is None:
            return
        p = self.pending
        m = self.master
        p_conf = p.confirmed_faces()
        m_conf = m.confirmed_faces()
        if len(p_conf) == 0:
            return

        # If master was never globally anchored and holds fewer confirmed faces than pending (<3 confirmed),
        # promote pending so 1-frame ghosts or pre-scan clutter can never stall the scan forever.
        if self.global_R is None and len(m_conf) < 3 and len(p_conf) > len(m_conf):
            self.master = p
            self.pending = None
            self._pending_miss_streak = 0
            return

        # Gather robust Labs per confirmed face slot in pending and master
        p_slots: Dict[Tuple[str, int], Tuple[np.ndarray, float]] = {}
        for f in p_conf:
            for i in range(9):
                r = p.face_robust_lab(f, i)
                if r is not None and r[1] >= 0.35:
                    p_slots[(f, i)] = (r[0], r[1])
        m_slots: Dict[Tuple[str, int], Tuple[np.ndarray, float]] = {}
        for f in m_conf:
            for i in range(9):
                r = m.face_robust_lab(f, i)
                if r is not None and r[1] >= 0.35:
                    m_slots[(f, i)] = (r[0], r[1])

        if not p_slots or not m_slots:
            return

        # Score all 24 SO(3) rotations mapping pending -> master
        rot_scores: List[Tuple[float, int, np.ndarray, np.ndarray, float, int]] = []
        for R in CUBE_SO3_ROTATIONS:
            # First estimate chromaticity offset between pending and master on overlapping slots
            pairs_p, pairs_m, weights, is_center = [], [], [], []
            overlap_faces = set()
            for (pf, pi), (plab, pq) in p_slots.items():
                mf, mi = _rotate_fragment_slot(pf, pi, R)
                if (mf, mi) in m_slots:
                    mlab, mq = m_slots[(mf, mi)]
                    pairs_p.append(plab)
                    pairs_m.append(mlab)
                    weights.append(min(pq, mq) * (2.5 if pi == 4 else 1.0))
                    is_center.append(pi == 4)
                    overlap_faces.add(mf)
            if not overlap_faces:
                continue
            arr_p = np.array(pairs_p)
            arr_m = np.array(pairs_m)
            w = np.array(weights)
            # Estimate modest AWB shift between fragments (clamped so bad rotations can't cheat by shifting colors!)
            shift = np.clip(np.median(arr_m - arr_p, axis=0), [-12.0, -14.0, -14.0], [12.0, 14.0, 14.0])
            errs = np.linalg.norm((arr_p + shift[None, :]) - arr_m, axis=1)
            mean_err = float(np.sum(errs * w) / max(1e-6, float(np.sum(w))))
            center_errs = [float(e) for e, ic in zip(errs, is_center) if ic]
            max_center_err = max(center_errs) if center_errs else 0.0
            # Count severe non-center cell contradictions (catches wrong 90-deg quarter turns on almost-solved F2L faces!)
            severe_mismatches = int(
                sum(1 for e, ic, wi in zip(errs, is_center, weights) if (not ic) and wi >= 0.8 and float(e) > 35.0)
            )
            rot_scores.append((mean_err, len(overlap_faces), R, shift, max_center_err, severe_mismatches))

        if not rot_scores:
            return
        rot_scores.sort(key=lambda x: x[0])
        best_err, best_ov_faces, best_R, best_shift, best_max_c_err, best_severe = rot_scores[0]
        second_err = rot_scores[1][0] if len(rot_scores) > 1 else 999.0

        # SYMMETRY & CONTRADICTION GUARD (Adversarial Finding #2 + F2L near-solved live clip fix):
        # 1. Never lock rotation on 1 face unless top-1 beats top-2 by a wide margin.
        # 2. Never merge if ANY overlapping center disagrees sharply (> 26) or >=2 high-quality stickers
        #    contradict (> 35) — prevents wrong quarter-turns on almost-solved faces from poisoning master!
        margin = second_err - best_err
        max_allowed_err = 14.5 if best_ov_faces == 1 else 16.5
        can_merge = not (
            (len(p_conf) < 2 and margin < 12.0)
            or best_err > max_allowed_err
            or margin < 6.0
            or best_max_c_err > 26.0
            or best_severe >= 2
        )
        if not can_merge:
            # If master never reached 6 faces and pending has cleanly mapped all 6 (or +2 more while master is unanchored),
            # promote pending so a noisy early fragment can never block a clean complete scan!
            if (len(m_conf) < 6 and len(p_conf) == 6) or (
                self.global_R is None and len(m_conf) < 5 and len(p_conf) >= len(m_conf) + 2
            ):
                self.master = p
                self.pending = None
                self.global_R = None
                self._pending_miss_streak = 0
                self.reappearance_events.append(
                    (frame_idx, True, f"promoted pending ({len(p_conf)}>{len(m_conf)} faces)")
                )
            return

        # Unique rigid alignment confirmed! Merge confirmed + actively-tracked multi-frame faces into master
        # (filters 1-frame phantom ghosts while preserving glared faces actively tracked across >=2 frames).
        merge_faces = p_conf | {pf for pf in p.tracked if len(p.obs[(pf, 4)]) >= 2}
        for (pf, pi), obs_list in p.obs.items():
            if not obs_list or pf not in merge_faces:
                continue
            mf, mi = _rotate_fragment_slot(pf, pi, best_R)
            for o in obs_list:
                m.obs[(mf, mi)].append(
                    CellObs(frame_idx=o.frame_idx, rgb_awb=o.rgb_awb, lab=o.lab + best_shift, quality=o.quality)
                )
            m.observed_faces.add(mf)

        # Rotate pending's active tracked quads into master's frame!
        new_tracked: Dict[str, TrackedFace] = {}
        for pf, tf in p.tracked.items():
            mf, _ = _rotate_fragment_slot(pf, 4, best_R)
            # Find which canonical corner shift maps cell 0 (TL) of pf to cell 0 (TL) of mf
            _, m_tl_idx = _rotate_fragment_slot(pf, 0, best_R)
            # Cell indices of [TL, TR, BR, BL] are [0, 2, 8, 6]
            corner_order = [0, 2, 8, 6]
            cw_rot = corner_order.index(m_tl_idx)
            aligned_m = np.roll(tf.aligned_quad, cw_rot, axis=0)
            new_tracked[mf] = TrackedFace(
                frag_face=mf,
                aligned_quad=aligned_m,
                raw_rgbs=np.rot90(tf.raw_rgbs.reshape(3, 3, 3), (-cw_rot) % 4).reshape(9, 3).copy(),
                quals=np.rot90(tf.quals.reshape(3, 3), (-cw_rot) % 4).reshape(9).copy(),
            )
        m.tracked = new_tracked
        self.pending = None
        self.reappearance_events.append((frame_idx, True, f"merged err={best_err:.1f} margin={margin:.1f} ov={best_ov_faces}"))

    def _solve_global_colors_and_net(self) -> None:
        """Deferred global 6-cluster color assignment + canonical URFDLB anchoring + invariant checking."""
        if self.master is None or self.twist_detected:
            return
        m = self.master

        # Gather robust center Lab for every observed face in master
        center_labs: Dict[str, np.ndarray] = {}
        center_quals: Dict[str, float] = {}
        for f in m.observed_faces:
            r = m.face_robust_lab(f, 4)
            if r is not None and r[1] >= 0.40:
                center_labs[f] = r[0]
                center_quals[f] = r[1]

        # Need at least 2 adjacent centers (or all 6) to anchor global orientation cleanly;
        # wait for >= 3 centers (or all 6) when chromatic separation is tight (e.g. pastel or no-white start).
        if len(center_labs) < 2:
            return

        # Normalize observed centers' mean (a, b) slightly when >= 4 centers are seen to handle residual AWB bias
        labs_arr = np.stack(list(center_labs.values()), axis=0)
        if len(center_labs) == 6:
            # Global white-balance anchor using all 6 centers!
            # The brightest lowest-chroma center is WHITE; shifting global Lab so all 6 centers align best
            # with the 24 proper Western cube orientations works even for pastel cubes and heavy AWB shifts!
            ref_mean = np.mean(np.stack(list(REF_CENTER_LAB.values()), axis=0), axis=0)
            obs_mean = np.mean(labs_arr, axis=0)
            obs_std = np.maximum(np.std(labs_arr, axis=0), 6.0)
            ref_std = np.std(np.stack(list(REF_CENTER_LAB.values()), axis=0), axis=0)
            # Standardized (z-score) comparison makes matching completely invariant to pastel desaturation & AWB offset!
            norm_centers = {f: (lab - obs_mean) / obs_std for f, lab in center_labs.items()}
            norm_refs = {c: (lab - ref_mean) / ref_std for c, lab in REF_CENTER_LAB.items()}

            rot_costs = []
            for R in CUBE_SO3_ROTATIONS:
                cost = 0.0
                for mf, nlab in norm_centers.items():
                    canon_f = _rotate_face_name(mf, R)
                    canon_c = HOME_CENTER_COLOR[canon_f]
                    cost += float(np.linalg.norm(nlab - norm_refs[canon_c]))
                rot_costs.append((cost, R))
            rot_costs.sort(key=lambda x: x[0])
            if rot_costs[1][0] - rot_costs[0][0] >= 0.35:
                self.global_R = rot_costs[0][1]
        elif self.global_R is None and len(center_labs) >= 3:
            # Early tentative anchoring only if 3+ centers clearly separate top-1 from top-2 in raw Lab
            rot_costs = []
            for R in CUBE_SO3_ROTATIONS:
                cost = 0.0
                for mf, lab in center_labs.items():
                    canon_f = _rotate_face_name(mf, R)
                    canon_c = HOME_CENTER_COLOR[canon_f]
                    cost += float(np.linalg.norm(lab - REF_CENTER_LAB[canon_c]))
                rot_costs.append((cost / len(center_labs), R))
            rot_costs.sort(key=lambda x: x[0])
            if rot_costs[0][0] < 20.0 and (rot_costs[1][0] - rot_costs[0][0]) >= 8.0:
                self.global_R = rot_costs[0][1]

        if self.global_R is None:
            return

        # Build canonical center prototypes from the cube's OWN observed centers!
        protos: Dict[str, np.ndarray] = {}
        for mf, lab in center_labs.items():
            cf = _rotate_face_name(mf, self.global_R)
            protos[HOME_CENTER_COLOR[cf]] = lab
        self.center_prototypes = protos

        # Reset and recompute net from accumulated evidence (reversible & non-greedy!)
        new_net: Dict[str, List[Optional[str]]] = {f: [None] * 9 for f in FACES}
        new_support: Dict[Tuple[str, int], float] = {}

        # Centers of observed faces are locked by global orientation
        for mf in center_labs:
            cf = _rotate_face_name(mf, self.global_R)
            new_net[cf][4] = HOME_CENTER_COLOR[cf]
            new_support[(cf, 4)] = 1e6  # centers anchored globally

        # Non-center classification: require all 6 center prototypes for final commitment (avoids greedy
        # misassignment before seeing e.g. Orange vs Red or Yellow vs White!).
        # Uses WHITE-centered CIEDE2000-style hue distance + exact global 8-per-color balanced refinement
        # once all 48 non-center cells have sufficient quality, cleanly separating Red vs Orange even
        # under mixed warm-lamp + cool-monitor lighting.
        if len(protos) == 6:
            proto_names = list(protos.keys())
            white_ab = protos["WHITE"][1:3]
            ready_cells: List[Tuple[str, int, float, np.ndarray]] = []
            for mf in m.observed_faces:
                for mi in range(9):
                    if mi == 4:
                        continue
                    r = m.face_robust_lab(mf, mi)
                    if r is None:
                        continue
                    lab, q_sum, n_obs = r
                    if q_sum < 0.55 or n_obs < 2:
                        continue
                    cf, ci = _rotate_fragment_slot(mf, mi, self.global_R)
                    dists = np.array(
                        [white_referenced_hue_dist(lab, protos[c], white_ab, c == "WHITE") for c in proto_names],
                        dtype=np.float64,
                    )
                    ready_cells.append((cf, ci, q_sum, dists))

            if len(ready_cells) == 48:
                cost_mat = np.stack([d for _, _, _, d in ready_cells], axis=0)
                bal_assign = _solve_balanced_8_per_color(cost_mat ** 2, cap=8)
                for idx_c, (cf, ci, q_sum, dists) in enumerate(ready_cells):
                    chosen = int(bal_assign[idx_c])
                    chosen_d = float(dists[chosen])
                    other_d = float(np.min(np.delete(dists, chosen)))
                    margin = other_d - chosen_d
                    if chosen_d < 55.0:
                        new_net[cf][ci] = proto_names[chosen]
                        new_support[(cf, ci)] = q_sum * (1.0 + 0.1 * max(0.0, margin))
            else:
                for cf, ci, q_sum, dists in ready_cells:
                    order = np.argsort(dists)
                    best_d, second_d = float(dists[order[0]]), float(dists[order[1]])
                    margin = second_d - best_d
                    if best_d < 36.0 and margin >= 8.0:
                        new_net[cf][ci] = proto_names[int(order[0])]
                        new_support[(cf, ci)] = q_sum * (1.0 + 0.1 * margin)

        # Run immediate local physical invariants + evidence-weighted conflict reopening (Adversarial Finding #12)!
        conflicted: set[Tuple[str, int]] = set()
        for _ in range(12):  # iteratively reopen lowest-support conflicting non-center slot
            viols = check_local_invariants(new_net)
            if not viols:
                break
            reopened_any = False
            for v in viols:
                cand_slots = [s for s in v.slots if s[1] != 4 and new_net[s[0]][s[1]] is not None]
                if not cand_slots:
                    continue
                weakest = min(cand_slots, key=lambda s: new_support.get(s, 0.0))
                new_net[weakest[0]][weakest[1]] = None
                conflicted.add(weakest)
                reopened_any = True
                break
            if not reopened_any:
                break

        self.net = new_net
        self.slot_support = new_support
        self.conflicted_slots = conflicted

    def _compute_cue(self, any_glare: bool, tracked_now: Dict[str, np.ndarray]) -> str:
        if self.twist_detected:
            return "Layer turned mid-scan — hold layers fixed and rescan"
        if not tracked_now:
            return "Bring cube into view"
        if self.pending is not None:
            if len(self.pending.observed_faces) < 2:
                return "Tilt slightly to show an adjacent face"
            return "Keep turning to re-align with previous faces"
        if self.master is None or len(self.master.observed_faces) < 6:
            missing = 6 - (len(self.master.observed_faces) if self.master else 0)
            if any_glare:
                return f"Tilt slightly away from light ({missing} side(s) left)"
            return f"Turn cube to show remaining {missing} side(s)"
        if self.conflicted_slots:
            f = sorted(self.conflicted_slots)[0][0]
            return f"Show face {f} clearly to resolve conflict"
        committed = sum(1 for f in FACES for c in self.net[f] if c is not None)
        if committed == 54:
            return "All 54 stickers verified!"
        if any_glare:
            return "Tilt slightly away from light for clearer view"
        return f"Tilt remaining faces toward camera ({committed}/54)"

    def process_frame(self, frame_idx: int, image_bgr: np.ndarray, detections: Sequence[DetectedQuad]) -> MapperSnapshot:
        plausible = self._filter_plausible_quads(detections)

        if not plausible:
            self.empty_streak += 1
            if self.empty_streak >= 3:
                # Regrip / cube lost: clear active per-frame trackers so returning cube starts a pending fragment!
                if self.master is not None:
                    self.master.tracked = {}
                if self.pending is not None:
                    self.pending.tracked = {}
            cue = self._compute_cue(False, {})
            self.last_cue = cue
            return self._snapshot(frame_idx, {}, cue)

        self.empty_streak = 0
        any_glare = False

        # Choose active fragment: master if it matches tracked faces (or is not yet created);
        # otherwise pending fragment after a regrip!
        if self.master is None:
            # Start master once we have a valid face (prefer multi-face frame or 2 consecutive frames)
            if len(plausible) >= 2 or len(self._prev_unattached) > 0:
                self.master = self._new_fragment()
                # Seed largest quad as local "F" at rot=0, then attach neighbors by shared edge
                order = sorted(range(len(plausible)), key=lambda i: _quad_area(plausible[i].corners), reverse=True)
                assigned = {order[0]: ("F", plausible[order[0]].corners)}
                assigned, _ = self._attach_by_shared_edge(self.master, image_bgr, plausible, assigned, order[1:])
                any_glare = self._update_fragment_observations(self.master, frame_idx, image_bgr, assigned)
                self._prev_unattached = []
            else:
                self._prev_unattached = [(plausible[0].corners, 1)]
        else:
            # Try tracking on master first if master currently has live tracked faces
            if self.master.tracked:
                assigned, unmatched = self._match_to_tracked(self.master, image_bgr, plausible)
                if assigned:
                    assigned, _ = self._attach_by_shared_edge(self.master, image_bgr, plausible, assigned, unmatched)
                    any_glare = self._update_fragment_observations(self.master, frame_idx, image_bgr, assigned)
                else:
                    # Sudden jump / tracking break -> treat as regrip into pending fragment
                    self.master.tracked = {}

            if not self.master.tracked:
                # We are recovering after a regrip blackout (or tracking jump): use pending fragment!
                if self.pending is None:
                    self.pending = self._new_fragment()
                    self._pending_miss_streak = 0
                    order = sorted(range(len(plausible)), key=lambda i: _quad_area(plausible[i].corners), reverse=True)
                    assigned = {order[0]: ("F", plausible[order[0]].corners)}
                    assigned, _ = self._attach_by_shared_edge(self.pending, image_bgr, plausible, assigned, order[1:])
                    any_glare = self._update_fragment_observations(self.pending, frame_idx, image_bgr, assigned)
                else:
                    assigned, unmatched = self._match_to_tracked(self.pending, image_bgr, plausible)
                    if not assigned:
                        self._pending_miss_streak += 1
                        # Survive 1-2 blurry frames once pending has >=1 confirmed face; reset 1-frame stubs immediately
                        max_miss = 3 if len(self.pending.confirmed_faces()) >= 1 else 1
                        if self._pending_miss_streak >= max_miss:
                            # Reset pending fragment after consecutive unmatched frames
                            self.pending = self._new_fragment()
                            self._pending_miss_streak = 0
                            order = sorted(range(len(plausible)), key=lambda i: _quad_area(plausible[i].corners), reverse=True)
                            assigned = {order[0]: ("F", plausible[order[0]].corners)}
                            unmatched = order[1:]
                            assigned, _ = self._attach_by_shared_edge(self.pending, image_bgr, plausible, assigned, unmatched)
                            any_glare = self._update_fragment_observations(self.pending, frame_idx, image_bgr, assigned)
                    else:
                        self._pending_miss_streak = 0
                        assigned, _ = self._attach_by_shared_edge(self.pending, image_bgr, plausible, assigned, unmatched)
                        any_glare = self._update_fragment_observations(self.pending, frame_idx, image_bgr, assigned)

                self._try_merge_pending(frame_idx)

        self._solve_global_colors_and_net()

        # Prepare active tracked quads for visualization
        vis_quads: Dict[str, np.ndarray] = {}
        active = self.pending if (self.pending is not None and self.pending.tracked) else self.master
        if active is not None:
            for fname, tf in active.tracked.items():
                label = (
                    _rotate_face_name(fname, self.global_R)
                    if (active is self.master and self.global_R is not None)
                    else f"?{fname}"
                )
                vis_quads[label] = tf.aligned_quad

        cue = self._compute_cue(any_glare, vis_quads)
        self.last_cue = cue
        return self._snapshot(frame_idx, vis_quads, cue)

    def _snapshot(self, frame_idx: int, vis_quads: Dict[str, np.ndarray], cue: str) -> MapperSnapshot:
        committed = sum(1 for f in FACES for c in self.net[f] if c is not None)
        viols = check_local_invariants(self.net)
        return MapperSnapshot(
            frame_idx=frame_idx,
            net={f: list(self.net[f]) for f in FACES},
            conflicted_slots=sorted(self.conflicted_slots),
            tracked_quads=vis_quads,
            global_anchored=self.global_R is not None,
            twist_detected=self.twist_detected,
            cue=cue,
            committed_count=committed,
            invariant_count=len(viols),
            pending_fragment_faces=len(self.pending.observed_faces) if self.pending else 0,
        )
