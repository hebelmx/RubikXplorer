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
    if np.min(lens) < 1.4 * d_med or np.max(lens) > 5.2 * d_med:
        return False
    # Opposite edges must be reasonably parallel and comparable in length (perspective cube face)
    if max(lens[0], lens[2]) / max(1e-6, min(lens[0], lens[2])) > 2.1:
        return False
    if max(lens[1], lens[3]) / max(1e-6, min(lens[1], lens[3])) > 2.1:
        return False
    for k in range(4):
        u = -edges[(k - 1) % 4] / max(1e-6, lens[(k - 1) % 4])
        v = edges[k] / max(1e-6, lens[k])
        if abs(float(u[0] * v[1] - u[1] * v[0])) < 0.34:  # corner angle >= ~20 deg
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

    seen_match_sets = set()
    best_candidate: Optional[Tuple[int, float, np.ndarray, Tuple[int, ...], np.ndarray]] = None
    for i in range(n):
        nbrs = [j for j in range(n) if j != i and 0.55 * d_med <= dist_mat[i, j] <= 1.65 * d_med]
        if len(nbrs) < 2:
            continue
        nbrs.sort(key=lambda j: dist_mat[i, j])
        nbrs = nbrs[:5]
        for a_idx in range(len(nbrs)):
            j = nbrs[a_idx]
            v1 = pts[j] - pts[i]
            l1 = float(np.linalg.norm(v1))
            for b_idx in range(len(nbrs)):
                if a_idx == b_idx:
                    continue
                k = nbrs[b_idx]
                v2 = pts[k] - pts[i]
                l2 = float(np.linalg.norm(v2))
                cross = float(v1[0] * v2[1] - v1[1] * v2[0])
                sin_th = cross / max(1e-6, l1 * l2)
                if sin_th < 0.42:
                    continue
                if max(l1, l2) / max(1e-6, min(l1, l2)) > 2.2:
                    continue

                for r0 in (0, 1):
                    for c0 in (0, 1):
                        pred_grid = pts[i][None, :] + (grid_uv[:, 0:1] - c0) * v1[None, :] + (grid_uv[:, 1:2] - r0) * v2[None, :]
                        d_pg = np.linalg.norm(pred_grid[:, None, :] - pts[None, :, :], axis=-1)
                        matches: List[Tuple[int, int]] = []
                        used_p = set()
                        for gi in range(9):
                            pi = int(np.argmin(d_pg[gi]))
                            if d_pg[gi, pi] <= 0.36 * d_med and pi not in used_p:
                                matches.append((gi, pi))
                                used_p.add(pi)
                        if len(matches) < min_inliers:
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

                        d_final = np.linalg.norm(proj_grid[:, None, :] - pts[None, :, :], axis=-1)
                        f_matches = []
                        used_f = set()
                        for gi in range(9):
                            pi = int(np.argmin(d_final[gi]))
                            if d_final[gi, pi] <= 0.28 * d_med and pi not in used_f:
                                f_matches.append((gi, pi, float(d_final[gi, pi])))
                                used_f.add(pi)
                        if len(f_matches) < min_inliers:
                            continue
                        mean_err = float(np.mean([e for _, _, e in f_matches]))
                        if mean_err > 0.16 * d_med:
                            continue
                        inlier_orig = tuple(sorted(avail_idx[pi] for _, pi, _ in f_matches))
                        # On a rigid 3D cube, every additional visible face MUST share a 2D edge with an already-extracted face;
                        # rewarding shared-edge attachment eliminates cross-seam phantom parallelograms.
                        edge_bonus = 2 if _shares_edge_with_any(proj_quad, existing_quads) else 0
                        score_key = (len(f_matches) + edge_bonus, len(f_matches), -mean_err)
                        if best_candidate is None or score_key > best_candidate[0]:
                            best_candidate = (score_key, len(f_matches), mean_err, proj_quad.astype(np.float32), inlier_orig, proj_grid.astype(np.float32))

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
