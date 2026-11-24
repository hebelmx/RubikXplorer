"""Automated verification tests for the PC continuous-scan spike (cube invariants + all 7 scenarios)."""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from cube_model import (
    CORNER_CUBIES,
    EDGE_CUBIES,
    FACES,
    apply_move,
    apply_scramble,
    check_local_invariants,
    is_face_rotationally_symmetric,
    solved_cube,
)
from lattice_fit import fit_face_quads_from_centers
from mapper import _shared_edge
from run_spike import build_scenarios, run_scenario
from synth_video import generate_sequence


def test_lattice_fit_from_unordered_centers():
    """Verify unordered 2D sticker centers (with 2 dropped stickers + noise) recover 3 non-overlapping shared-edge quads."""
    cfg = build_scenarios()[0]
    frames = generate_sequence(cfg)
    # Pick a balanced 3-face corner frame (all 3 faces well-separated from grazing edge)
    fr = frames[25]
    assert len(fr.truth.visible_faces) == 3
    rng = __import__("numpy").random.default_rng(42)
    import cv2, numpy as np

    centers = []
    grid_uv = np.array([[c / 3.0 + 1 / 6.0, r / 3.0 + 1 / 6.0] for r in range(3) for c in range(3)], dtype=np.float32).reshape(-1, 1, 2)
    unit = np.array([[0, 0], [1, 0], [1, 1], [0, 1]], dtype=np.float32)
    for q in fr.truth.visible_faces.values():
        H = cv2.getPerspectiveTransform(unit, q.astype(np.float32))
        pts = cv2.perspectiveTransform(grid_uv, H).reshape(9, 2)
        for p in pts:
            centers.append((float(p[0] + rng.normal(0, 1.0)), float(p[1] + rng.normal(0, 1.0))))
    # Drop 2 stickers (simulating a missed logo center and one missed edge) and shuffle
    rng.shuffle(centers)
    centers = centers[:-2]

    fitted = fit_face_quads_from_centers(centers, max_faces=3, min_inliers=6)
    assert len(fitted) == 3, f"Expected 3 faces, got {len(fitted)}"
    shared = sum(1 for i in range(3) for j in range(i + 1, 3) if _shared_edge(fitted[i].quad.corners, fitted[j].quad.corners) is not None)
    assert shared >= 2, f"Expected >=2 shared edges among 3 faces, got {shared}"


def test_cube_geometry_and_invariants():
    s = solved_cube()
    assert len(EDGE_CUBIES) == 12
    assert len(CORNER_CUBIES) == 8
    assert check_local_invariants(s) == []

    # 4 identical quarter-turns return to solved
    t = apply_scramble(["R", "R", "R", "R"])
    assert t == s

    # Scrambled state satisfies all local invariants
    scr = apply_scramble(["R", "U", "R'", "F'", "L2", "D", "B'", "U2"])
    assert check_local_invariants(scr) == []

    # Injecting an impossible opposite-color edge (WHITE+YELLOW on UF edge) is caught immediately
    bad_edge = {f: list(v) for f, v in s.items()}
    bad_edge["F"][1] = "YELLOW"  # touches U[7] = WHITE
    kinds = {v.kind for v in check_local_invariants(bad_edge)}
    assert "impossible_edge" in kinds

    # Injecting a mirrored corner chirality (swapping two stickers on UFR corner) is caught immediately
    bad_corner = {f: list(v) for f, v in s.items()}
    bad_corner["F"][2], bad_corner["R"][0] = bad_corner["R"][0], bad_corner["F"][2]
    kinds_c = {v.kind for v in check_local_invariants(bad_corner)}
    assert "invalid_corner_chirality" in kinds_c

    # Rotational symmetry detection
    assert is_face_rotationally_symmetric(s["D"]) is True
    assert is_face_rotationally_symmetric(scr["F"]) is False


def test_all_seven_scenarios():
    for cfg in build_scenarios():
        res = run_scenario(cfg, render_out=None)
        assert res["passed"], f"Scenario failed: {res}"


if __name__ == "__main__":
    test_cube_geometry_and_invariants()
    test_lattice_fit_from_unordered_centers()
    test_all_seven_scenarios()
    print("ALL PC SPIKE TESTS PASSED.")
