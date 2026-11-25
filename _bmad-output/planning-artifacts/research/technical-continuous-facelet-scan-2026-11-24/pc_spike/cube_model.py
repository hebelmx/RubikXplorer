"""Exact 3x3 Rubik's Cube geometry, Kociemba URFDLB net, moves, and local physical invariants.

Home frame (matches Cubentia AGENTS.md):
  U = WHITE (+Y), R = RED (+X), F = GREEN (+Z), D = YELLOW (-Y), L = ORANGE (-X), B = BLUE (-Z).
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np

FACES: Tuple[str, ...] = ("U", "R", "F", "D", "L", "B")
FACE_TO_IDX: Dict[str, int] = {f: i for i, f in enumerate(FACES)}
COLORS: Tuple[str, ...] = ("WHITE", "RED", "GREEN", "YELLOW", "ORANGE", "BLUE")
HOME_CENTER_COLOR: Dict[str, str] = {
    "U": "WHITE",
    "R": "RED",
    "F": "GREEN",
    "D": "YELLOW",
    "L": "ORANGE",
    "B": "BLUE",
}
COLOR_TO_HOME_FACE: Dict[str, str] = {c: f for f, c in HOME_CENTER_COLOR.items()}
OPPOSITE_COLOR: Dict[str, str] = {
    "WHITE": "YELLOW",
    "YELLOW": "WHITE",
    "RED": "ORANGE",
    "ORANGE": "RED",
    "GREEN": "BLUE",
    "BLUE": "GREEN",
}
OPPOSITE_FACE: Dict[str, str] = {
    "U": "D",
    "D": "U",
    "R": "L",
    "L": "R",
    "F": "B",
    "B": "F",
}

# Orthonormal 3D face basis: (normal, right, down) matching Kociemba URFDLB row-major layout.
FACE_BASIS: Dict[str, Tuple[np.ndarray, np.ndarray, np.ndarray]] = {
    "U": (np.array([0.0, 1.0, 0.0]), np.array([1.0, 0.0, 0.0]), np.array([0.0, 0.0, 1.0])),
    "R": (np.array([1.0, 0.0, 0.0]), np.array([0.0, 0.0, -1.0]), np.array([0.0, -1.0, 0.0])),
    "F": (np.array([0.0, 0.0, 1.0]), np.array([1.0, 0.0, 0.0]), np.array([0.0, -1.0, 0.0])),
    "D": (np.array([0.0, -1.0, 0.0]), np.array([1.0, 0.0, 0.0]), np.array([0.0, 0.0, -1.0])),
    "L": (np.array([-1.0, 0.0, 0.0]), np.array([0.0, 0.0, 1.0]), np.array([0.0, -1.0, 0.0])),
    "B": (np.array([0.0, 0.0, -1.0]), np.array([-1.0, 0.0, 0.0]), np.array([0.0, -1.0, 0.0])),
}

# Edge indices on a clockwise face quad [TL, TR, BR, BL]:
#   0 = top (TL -> TR), 1 = right (TR -> BR), 2 = bottom (BR -> BL), 3 = left (BL -> TL)
EDGE_DIRS_2D = (
    np.array([0.0, -1.0]),  # 0: top (outward = -down)
    np.array([1.0, 0.0]),   # 1: right (+right)
    np.array([0.0, 1.0]),   # 2: bottom (+down)
    np.array([-1.0, 0.0]),  # 3: left (-right)
)


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


def cubie_coord_3d(face: str, cell_idx: int) -> Tuple[int, int, int]:
    """Integer cubie lattice coordinate in {-1, 0, +1}^3 for sticker (face, cell_idx)."""
    row, col = divmod(cell_idx, 3)
    n, r, d = FACE_BASIS[face]
    pos = n + (col - 1) * r + (row - 1) * d
    return (int(round(pos[0])), int(round(pos[1])), int(round(pos[2])))


def _build_cubie_tables():
    by_cubie: Dict[Tuple[int, int, int], List[Tuple[str, int]]] = {}
    for f in FACES:
        for i in range(9):
            c = cubie_coord_3d(f, i)
            by_cubie.setdefault(c, []).append((f, i))

    edges: List[Tuple[Tuple[str, int], Tuple[str, int]]] = []
    corners: List[Tuple[Tuple[str, int], Tuple[str, int], Tuple[str, int]]] = []
    for coord, slots in sorted(by_cubie.items()):
        nz = sum(1 for v in coord if v != 0)
        if nz == 2:
            assert len(slots) == 2
            edges.append((slots[0], slots[1]))
        elif nz == 3:
            assert len(slots) == 3
            # Order the 3 faces CLOCKWISE when viewed from outside along `outward = coord`.
            outward = np.array(coord, dtype=np.float64)
            s0, s1, s2 = slots
            n0, n1, n2 = FACE_BASIS[s0[0]][0], FACE_BASIS[s1[0]][0], FACE_BASIS[s2[0]][0]
            # Triple product (n0 x n1) . outward > 0 means CCW from outside -> swap s1, s2 for CW.
            if float(np.dot(np.cross(n0, n1), outward)) > 0:
                corners.append((s0, s2, s1))
            else:
                corners.append((s0, s1, s2))
    assert len(edges) == 12 and len(corners) == 8
    return edges, corners


EDGE_CUBIES, CORNER_CUBIES = _build_cubie_tables()

# Valid color sets / oriented cyclic triples from the solved cube.
VALID_EDGE_COLOR_SETS = frozenset(
    frozenset((HOME_CENTER_COLOR[a[0]], HOME_CENTER_COLOR[b[0]])) for a, b in EDGE_CUBIES
)


def canonical_corner_cycle(c0: str, c1: str, c2: str) -> Tuple[str, str, str]:
    """Canonical cyclic representative of a clockwise 3-color corner triple."""
    rotations = ((c0, c1, c2), (c1, c2, c0), (c2, c0, c1))
    return min(rotations)


VALID_CORNER_CYCLES = frozenset(
    canonical_corner_cycle(HOME_CENTER_COLOR[a[0]], HOME_CENTER_COLOR[b[0]], HOME_CENTER_COLOR[c[0]])
    for a, b, c in CORNER_CUBIES
)


def _build_adjacency():
    """For each face and edge index 0..3, return (neighbor_face, neighbor_edge_idx)."""
    quads = {f: face_quad_3d(f) for f in FACES}
    adj: Dict[Tuple[str, int], Tuple[str, int]] = {}
    for f1 in FACES:
        q1 = quads[f1]
        for e1 in range(4):
            a1, b1 = q1[e1], q1[(e1 + 1) % 4]
            for f2 in FACES:
                if f2 == f1:
                    continue
                q2 = quads[f2]
                for e2 in range(4):
                    a2, b2 = q2[e2], q2[(e2 + 1) % 4]
                    # Shared edge on opposite clockwise faces runs in opposite vertex order!
                    if np.allclose(a1, b2) and np.allclose(b1, a2):
                        adj[(f1, e1)] = (f2, e2)
    assert len(adj) == 24
    return adj


FACE_ADJACENCY = _build_adjacency()


def _build_so3_rotations() -> List[np.ndarray]:
    """All 24 proper 3x3 integer rotation matrices of the cube (SO(3) symmetry group)."""
    rots = []
    axes = [np.array([1, 0, 0]), np.array([-1, 0, 0]), np.array([0, 1, 0]), np.array([0, -1, 0]), np.array([0, 0, 1]), np.array([0, 0, -1])]
    for x in axes:
        for y in axes:
            if int(np.dot(x, y)) != 0:
                continue
            z = np.cross(x, y)
            R = np.column_stack([x, y, z]).astype(np.float64)
            rots.append(R)
    assert len(rots) == 24
    return rots


CUBE_SO3_ROTATIONS = _build_so3_rotations()


def rotate_face_grid(cells: Sequence[Optional[str]], cw_turns: int) -> List[Optional[str]]:
    """Rotate a 3x3 row-major list clockwise by `cw_turns` quarter turns."""
    arr = np.array(cells, dtype=object).reshape(3, 3)
    k = (-cw_turns) % 4  # np.rot90 rotates CCW
    return list(np.rot90(arr, k).reshape(-1))


def solved_cube() -> Dict[str, List[str]]:
    return {f: [HOME_CENTER_COLOR[f]] * 9 for f in FACES}


def apply_move(state: Dict[str, List[str]], move: str) -> Dict[str, List[str]]:
    """Apply a standard Singmaster move (U, R, F, D, L, B with optional ' or 2) using exact 3D rotation."""
    face = move[0]
    suffix = move[1:]
    turns = 1 if suffix == "" else (3 if suffix == "'" else 2)
    axis = FACE_BASIS[face][0]

    # Map current 3D sticker center + normal -> color
    cur: Dict[Tuple[int, int, int, int, int, int], str] = {}
    for f in FACES:
        n = FACE_BASIS[f][0]
        for i in range(9):
            p = sticker_center_3d(f, i)
            key = tuple(int(round(v * 3)) for v in p) + tuple(int(round(v)) for v in n)
            cur[key] = state[f][i]

    # Rodrigues rotation around `axis` by -90 deg * turns (clockwise looking at `face`)
    theta = -0.5 * np.pi * turns
    ct, st = np.cos(theta), np.sin(theta)
    kx, ky, kz = axis
    K = np.array([[0, -kz, ky], [kz, 0, -kx], [-ky, kx, 0]], dtype=np.float64)
    R = np.eye(3) + st * K + (1.0 - ct) * (K @ K)

    nxt: Dict[Tuple[int, int, int, int, int, int], str] = {}
    for f in FACES:
        n = FACE_BASIS[f][0]
        for i in range(9):
            p = sticker_center_3d(f, i)
            c_coord = cubie_coord_3d(f, i)
            in_layer = int(round(np.dot(np.array(c_coord), axis))) == 1
            if in_layer:
                p2 = R @ p
                n2 = R @ n
            else:
                p2, n2 = p, n
            k_old = tuple(int(round(v * 3)) for v in p) + tuple(int(round(v)) for v in n)
            k_new = tuple(int(round(v * 3)) for v in p2) + tuple(int(round(v)) for v in n2)
            nxt[k_new] = cur[k_old]

    out = {f: [""] * 9 for f in FACES}
    for f in FACES:
        n = FACE_BASIS[f][0]
        for i in range(9):
            p = sticker_center_3d(f, i)
            key = tuple(int(round(v * 3)) for v in p) + tuple(int(round(v)) for v in n)
            out[f][i] = nxt[key]
    return out


def apply_scramble(moves: Sequence[str], base: Optional[Dict[str, List[str]]] = None) -> Dict[str, List[str]]:
    st = {f: list(v) for f, v in (base or solved_cube()).items()}
    for m in moves:
        st = apply_move(st, m)
    return st


@dataclass
class InvariantViolation:
    kind: str
    detail: str
    slots: Tuple[Tuple[str, int], ...]


def check_local_invariants(partial_net: Dict[str, List[Optional[str]]]) -> List[InvariantViolation]:
    """Check immediate physical invariants on a partial or full 54-sticker net.

    Checks:
      1. Per-color count <= 9.
      2. Shared edge cubies: cannot be same/opposite color; no duplicate edge cubie.
      3. Shared corner cubies: no same/opposite pair; valid clockwise cyclic chirality; no duplicate corner cubie.
    """
    violations: List[InvariantViolation] = []

    # 1. Color count upper bound
    by_color: Dict[str, List[Tuple[str, int]]] = {c: [] for c in COLORS}
    for f in FACES:
        for i, col in enumerate(partial_net[f]):
            if col is not None:
                by_color[col].append((f, i))
    for col, slots in by_color.items():
        if len(slots) > 9:
            violations.append(
                InvariantViolation("color_count_exceeded", f"{col} appears {len(slots)} times (> 9)", tuple(slots))
            )

    # 2. Edge cubies
    seen_edges: Dict[frozenset, Tuple[Tuple[str, int], Tuple[str, int]]] = {}
    for a, b in EDGE_CUBIES:
        ca, cb = partial_net[a[0]][a[1]], partial_net[b[0]][b[1]]
        if ca is None or cb is None:
            continue
        if ca == cb or OPPOSITE_COLOR[ca] == cb:
            violations.append(
                InvariantViolation("impossible_edge", f"Edge {a}-{b} has impossible pair ({ca}, {cb})", (a, b))
            )
            continue
        pair = frozenset((ca, cb))
        if pair in seen_edges:
            prev = seen_edges[pair]
            violations.append(
                InvariantViolation("duplicate_edge", f"Duplicate edge {sorted(pair)} at {prev} and {(a, b)}", (prev[0], prev[1], a, b))
            )
        else:
            seen_edges[pair] = (a, b)

    # 3. Corner cubies (including pairwise opposite/same check when 2 of 3 are observed + full chirality when 3 are observed)
    seen_corners: Dict[Tuple[str, str, str], Tuple[Tuple[str, int], ...]] = {}
    for a, b, c in CORNER_CUBIES:
        vals = [(a, partial_net[a[0]][a[1]]), (b, partial_net[b[0]][b[1]]), (c, partial_net[c[0]][c[1]])]
        obs = [(s, col) for s, col in vals if col is not None]
        if len(obs) < 2:
            continue
        pair_bad = False
        for i in range(len(obs)):
            for j in range(i + 1, len(obs)):
                s1, c1 = obs[i]
                s2, c2 = obs[j]
                if c1 == c2 or OPPOSITE_COLOR[c1] == c2:
                    violations.append(
                        InvariantViolation("impossible_corner_pair", f"Corner {s1}-{s2} has impossible pair ({c1}, {c2})", (s1, s2))
                    )
                    pair_bad = True
        if pair_bad or len(obs) < 3:
            continue
        cyc = canonical_corner_cycle(obs[0][1], obs[1][1], obs[2][1])
        if cyc not in VALID_CORNER_CYCLES:
            violations.append(
                InvariantViolation("invalid_corner_chirality", f"Corner {(a, b, c)} has invalid chirality {cyc}", (a, b, c))
            )
        elif cyc in seen_corners:
            prev = seen_corners[cyc]
            violations.append(
                InvariantViolation("duplicate_corner", f"Duplicate corner {cyc} at {prev} and {(a, b, c)}", prev + (a, b, c))
            )
        else:
            seen_corners[cyc] = (a, b, c)

    return violations


def rotate_cube_state(state: Dict[str, List[str]], R: np.ndarray) -> Dict[str, List[str]]:
    """Whole-cube rigid rotation by R in CUBE_SO3_ROTATIONS (for verifying orientation invariance)."""
    cur: Dict[Tuple[int, int, int, int, int, int], str] = {}
    for f in FACES:
        n = FACE_BASIS[f][0]
        for i in range(9):
            p = sticker_center_3d(f, i)
            k = tuple(int(round(v * 3)) for v in p) + tuple(int(round(v)) for v in n)
            cur[k] = state[f][i]
    out = {f: [""] * 9 for f in FACES}
    for f in FACES:
        n = FACE_BASIS[f][0]
        for i in range(9):
            p = sticker_center_3d(f, i)
            p_src = R.T @ p
            n_src = R.T @ n
            k = tuple(int(round(v * 3)) for v in p_src) + tuple(int(round(v)) for v in n_src)
            out[f][i] = cur[k]
    return out


def is_face_rotationally_symmetric(cells: Sequence[Optional[str]]) -> bool:
    """True if a 3x3 face matches itself under any non-identity 90/180/270-deg rotation."""
    if any(c is None for c in cells):
        return True
    for k in (1, 2, 3):
        if rotate_face_grid(cells, k) == list(cells):
            return True
    return False
