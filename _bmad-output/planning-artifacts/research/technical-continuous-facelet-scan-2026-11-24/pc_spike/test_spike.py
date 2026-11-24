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
from mapper import _shared_edge, _solve_balanced_8_per_color, shading_invariant_lab, white_referenced_hue_dist
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


def test_red_orange_hue_separation_and_balanced_solver():
    """Real webcam Red vs Orange RGBs under simultaneous shadow + lamp glare separate cleanly."""
    import numpy as np

    white_ab = shading_invariant_lab(np.array([191.0, 145.0, 160.0]))[1:3]  # pinkish webcam white
    orange_proto = shading_invariant_lab(np.array([213.0, 108.0, 74.0]))    # lamp-lit orange center
    red_proto = shading_invariant_lab(np.array([123.0, 27.0, 41.0]))        # deep red
    orange_samples = [
        np.array([152.0, 54.0, 42.0]),
        np.array([209.0, 100.0, 60.0]),
        np.array([114.0, 42.0, 30.0]),
        np.array([95.0, 24.0, 18.0]),   # deep shadow orange
    ]
    red_samples = [
        np.array([123.0, 27.0, 41.0]),
        np.array([157.0, 79.0, 89.0]),  # lamp-glared red
        np.array([157.0, 46.0, 60.0]),
        np.array([84.0, 22.0, 28.0]),   # deep shadow red
    ]
    for rgb in orange_samples:
        lab = shading_invariant_lab(rgb)
        assert white_referenced_hue_dist(lab, orange_proto, white_ab, False) < white_referenced_hue_dist(lab, red_proto, white_ab, False)
    for rgb in red_samples:
        lab = shading_invariant_lab(rgb)
        assert white_referenced_hue_dist(lab, red_proto, white_ab, False) < white_referenced_hue_dist(lab, orange_proto, white_ab, False)

    # Balanced 8-per-color solver assigns exactly 8 per color
    rng = np.random.default_rng(123)
    cost = rng.uniform(1.0, 50.0, size=(48, 6)) ** 2
    assign = _solve_balanced_8_per_color(cost, cap=8)
    assert np.all(np.bincount(assign, minlength=6) == 8)


def test_all_seven_scenarios():
    for cfg in build_scenarios():
        res = run_scenario(cfg, render_out=None)
        assert res["passed"], f"Scenario failed: {res}"


if __name__ == "__main__":
    test_cube_geometry_and_invariants()
    test_lattice_fit_from_unordered_centers()
    test_red_orange_hue_separation_and_balanced_solver()
    test_all_seven_scenarios()
    print("ALL PC SPIKE TESTS PASSED.")
