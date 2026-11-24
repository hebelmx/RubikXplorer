"""Convert unordered 2D sticker-center detections into 1-3 projective 3x3 face quads (DetectedQuad).

Detector-agnostic: works identically whether sticker centers come from a PC benchmark detector
(e.g., yush-io/cubeSolver single-class sticker model) or a future Cubentia LiteRT heatmap model.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import List, Optional, Sequence, Tuple

import cv2
import numpy as np

from synth_video import DetectedQuad


@dataclass
class FittedFace:
    quad: DetectedQuad
    inlier_indices: Tuple[int, ...]
    grid_centers: np.ndarray  # (9, 2) projected 3x3 centers
    mean_err: float


def _ensure_clockwise(quad: np.ndarray) -> np.ndarray:
    x, y = quad[:, 0], quad[:, 1]
    area2 = float(np.sum(x * np.roll(y, -1) - np.roll(x, -1) * y))
    return quad[::-1].copy() if area2 < 0 else quad


def _is_valid_face_quad(quad: np.ndarray, d_med: float) -> bool:
    """Reject degenerate, self-intersecting, or wildly skewed quads."""
    if not np.all(np.isfinite(quad)):
        return False
    # Must be convex
    if not cv2.isContourConvex(np.rint(quad).astype(np.int32)):
        return False
    edges = np.array([quad[(k + 1) % 4] - quad[k] for k in range(4)], dtype=np.float64)
    lens = np.linalg.norm(edges, axis=1)
    if np.min(lens) < 1.15 * d_med or np.max(lens) > 5.4 * d_med:
        return False
    if np.max(lens) / max(1e-6, np.min(lens)) > 3.2:
        return False
    # Opposite edges must be reasonably parallel and comparable in length (perspective cube face)
    if max(lens[0], lens[2]) / max(1e-6, min(lens[0], lens[2])) > 2.1:
        return False
    if max(lens[1], lens[3]) / max(1e-6, min(lens[1], lens[3])) > 2.1:
        return False
    for k in range(4):
        u = -edges[(k - 1) % 4] / max(1e-6, lens[(k - 1) % 4])
        v = edges[k] / max(1e-6, lens[k])
        if abs(float(u[0] * v[1] - u[1] * v[0])) < 0.32:  # corner angle >= ~19 deg
            return False
    return True


def _quad_area(quad: np.ndarray) -> float:
    x, y = quad[:, 0], quad[:, 1]
    return 0.5 * abs(float(np.sum(x * np.roll(y, -1) - np.roll(x, -1) * y)))


def _overlaps_existing(quad: np.ndarray, existing_quads: Sequence[np.ndarray], max_overlap_frac: float = 0.08) -> bool:
    """Two distinct visible faces of a convex 3D cube never overlap in 2D image space."""
    if not existing_quads:
        return False
    q1 = quad.astype(np.float32)
    a1 = _quad_area(q1)
    for eq in existing_quads:
        q2 = eq.astype(np.float32)
        inter_area, _ = cv2.intersectConvexConvex(q1, q2)
        if inter_area > max_overlap_frac * min(a1, _quad_area(q2)):
            return True
    return False


def _shares_edge_with_any(quad: np.ndarray, existing_quads: Sequence[np.ndarray]) -> bool:
    """True if clockwise `quad` shares a 2D cube edge with any already-extracted face quad."""
    if not existing_quads:
        return False
    lens = [float(np.linalg.norm(quad[(k + 1) % 4] - quad[k])) for k in range(4)]
    mean_l = sum(lens) / 4.0
    for eq in existing_quads:
        elens = [float(np.linalg.norm(eq[(k + 1) % 4] - eq[k])) for k in range(4)]
        tol = 0.16 * min(mean_l, sum(elens) / 4.0)
        for ea in range(4):
            a0, a1 = quad[ea], quad[(ea + 1) % 4]
            for eb in range(4):
                b0, b1 = eq[eb], eq[(eb + 1) % 4]
                if 0.5 * (float(np.linalg.norm(a0 - b1)) + float(np.linalg.norm(a1 - b0))) < tol:
                    return True
    return False


def _fit_one_face(
    points: np.ndarray,
    avail_idx: Sequence[int],
    d_med: float,
    existing_quads: Sequence[np.ndarray],
    min_inliers: int = 6,
) -> Optional[FittedFace]:
    if len(avail_idx) < min_inliers:
        return None
    pts = points[list(avail_idx)]
    n = len(pts)

    diff = pts[:, None, :] - pts[None, :, :]
    dist_mat = np.linalg.norm(diff, axis=-1)

    grid_uv = np.array([[c, r] for r in range(3) for c in range(3)], dtype=np.float32)
    outer_uv = np.array([[-0.5, -0.5], [2.5, -0.5], [2.5, 2.5], [-0.5, 2.5]], dtype=np.float32).reshape(-1, 1, 2)
    g_c = grid_uv[:, 0:1].astype(np.float64)
    g_r = grid_uv[:, 1:2].astype(np.float64)

    # Try points with ~4 near neighbors first (face centers hit a 9-inlier face on the first few iterations!)
    nbrs_per_pt: List[List[int]] = []
    for i in range(n):
        nb = [j for j in range(n) if j != i and 0.48 * d_med <= dist_mat[i, j] <= 1.65 * d_med]
        nb.sort(key=lambda j: dist_mat[i, j])
        nbrs_per_pt.append(nb[:5])
    seed_order = sorted(range(n), key=lambda i: abs(len(nbrs_per_pt[i]) - 4))

    seen_match_sets = set()
    best_candidate: Optional[Tuple[Tuple[int, int, float], int, float, np.ndarray, Tuple[int, ...], np.ndarray]] = None
    early_stop = False

    for i in seed_order:
        if early_stop:
            break
        nbrs = nbrs_per_pt[i]
        if len(nbrs) < 2:
            continue
        pi_xy = pts[i]
        for a_idx in range(len(nbrs)):
            if early_stop:
                break
            j = nbrs[a_idx]
            v1 = pts[j] - pi_xy
            l1 = float(dist_mat[i, j])
            for b_idx in range(len(nbrs)):
                if a_idx == b_idx:
                    continue
                k = nbrs[b_idx]
                v2 = pts[k] - pi_xy
                l2 = float(dist_mat[i, k])
                cross = float(v1[0] * v2[1] - v1[1] * v2[0])
                sin_th = cross / max(1e-6, l1 * l2)
                if sin_th < 0.40:
                    continue
                if max(l1, l2) / max(1e-6, min(l1, l2)) > 2.3:
                    continue

                d_loc = min(d_med, max(l1, l2))
                tol_init_sq = (0.34 * d_loc) ** 2
                tol_final = 0.26 * d_loc

                for r0 in (0, 1):
                    for c0 in (0, 1):
                        pred_grid = pi_xy[None, :] + (g_c - c0) * v1[None, :] + (g_r - r0) * v2[None, :]
                        dx = pred_grid[:, None, 0] - pts[None, :, 0]
                        dy = pred_grid[:, None, 1] - pts[None, :, 1]
                        d2 = dx * dx + dy * dy
                        best_pi = np.argmin(d2, axis=1)
                        best_d2 = d2[np.arange(9), best_pi]
                        if int(np.sum(best_d2 <= tol_init_sq)) < min_inliers:
                            continue

                        matches: List[Tuple[int, int]] = []
                        used_p = set()
                        rows_mask = 0
                        cols_mask = 0
                        for gi in range(9):
                            if best_d2[gi] <= tol_init_sq:
                                pi = int(best_pi[gi])
                                if pi not in used_p:
                                    matches.append((gi, pi))
                                    used_p.add(pi)
                                    rows_mask |= 1 << (gi // 3)
                                    cols_mask |= 1 << (gi % 3)
                        # Must span all 3 rows AND all 3 columns so a 2-row/2-col face can never shift by +-1!
                        if len(matches) < min_inliers or rows_mask != 7 or cols_mask != 7:
                            continue
                        sig = frozenset(matches)
                        if sig in seen_match_sets:
                            continue
                        seen_match_sets.add(sig)

                        src_g = np.array([grid_uv[gi] for gi, _ in matches], dtype=np.float32)
                        dst_p = np.array([pts[pi] for _, pi in matches], dtype=np.float32)
                        H, _ = cv2.findHomography(src_g, dst_p, method=0)
                        if H is None:
                            continue
                        proj_grid = cv2.perspectiveTransform(grid_uv.reshape(-1, 1, 2), H).reshape(9, 2)
                        proj_quad = _ensure_clockwise(cv2.perspectiveTransform(outer_uv, H).reshape(4, 2))
                        if not _is_valid_face_quad(proj_quad, d_med):
                            continue
                        if _overlaps_existing(proj_quad, existing_quads):
                            continue

                        fx = proj_grid[:, None, 0] - pts[None, :, 0]
                        fy = proj_grid[:, None, 1] - pts[None, :, 1]
                        d_final = np.sqrt(fx * fx + fy * fy)
                        f_pi = np.argmin(d_final, axis=1)
                        f_matches = []
                        used_f = set()
                        f_rows = 0
                        f_cols = 0
                        for gi in range(9):
                            pi = int(f_pi[gi])
                            dist_v = float(d_final[gi, pi])
                            if dist_v <= tol_final and pi not in used_f:
                                f_matches.append((gi, pi, dist_v))
                                used_f.add(pi)
                                f_rows |= 1 << (gi // 3)
                                f_cols |= 1 << (gi % 3)
                        if len(f_matches) < min_inliers or f_rows != 7 or f_cols != 7:
                            continue
                        mean_err = float(np.mean([e for _, _, e in f_matches]))
                        max_err = max(e for _, _, e in f_matches)
                        if mean_err > 0.16 * d_loc:
                            continue
                        inlier_orig = tuple(sorted(avail_idx[pi] for _, pi, _ in f_matches))
                        # On a rigid 3D cube, every additional visible face MUST share a 2D edge with an already-extracted face;
                        # rewarding shared-edge attachment eliminates cross-seam phantom parallelograms.
                        edge_bonus = 2 if _shares_edge_with_any(proj_quad, existing_quads) else 0
                        score_key = (len(f_matches) + edge_bonus, len(f_matches), -mean_err)
                        if best_candidate is None or score_key > best_candidate[0]:
                            best_candidate = (score_key, len(f_matches), mean_err, proj_quad.astype(np.float32), inlier_orig, proj_grid.astype(np.float32))
                            if len(f_matches) == 9 and mean_err < 0.032 * d_loc and max_err < 0.065 * d_loc and (not existing_quads or edge_bonus > 0):
                                early_stop = True
                                break
                    if early_stop:
                        break
                if early_stop:
                    break

    if best_candidate is None:
        return None
    _, count, mean_err, quad, inliers, proj_grid = best_candidate
    return FittedFace(
        quad=DetectedQuad(corners=quad, score=float(count) / 9.0),
        inlier_indices=inliers,
        grid_centers=proj_grid,
        mean_err=mean_err,
    )


def fit_face_quads_from_centers(
    centers_xy: Sequence[Tuple[float, float]],
    max_faces: int = 3,
    min_inliers: int = 6,
) -> List[FittedFace]:
    """Extract up to `max_faces` non-overlapping projective 3x3 face quads from unordered 2D sticker centers."""
    if len(centers_xy) < min_inliers:
        return []
    pts = np.asarray(centers_xy, dtype=np.float64).reshape(-1, 2)
    diff = pts[:, None, :] - pts[None, :, :]
    dmat = np.linalg.norm(diff, axis=-1)
    np.fill_diagonal(dmat, np.inf)
    nn1 = np.min(dmat, axis=1)
    d_med = float(np.median(nn1))
    if d_med < 6.0:
        return []

    avail = list(range(len(pts)))
    faces: List[FittedFace] = []
    existing_quads: List[np.ndarray] = []
    for _ in range(max_faces):
        fitted = _fit_one_face(pts, avail, d_med, existing_quads=existing_quads, min_inliers=min_inliers)
        if fitted is None:
            break
        faces.append(fitted)
        existing_quads.append(fitted.quad.corners)
        claimed = set(fitted.inlier_indices)
        avail = [i for i in avail if i not in claimed]
        if len(avail) < min_inliers:
            break
    return faces
