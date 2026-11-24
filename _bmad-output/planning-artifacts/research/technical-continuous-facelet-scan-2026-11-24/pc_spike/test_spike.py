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
from run_spike import build_scenarios, run_scenario


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
    test_all_seven_scenarios()
    print("ALL PC SPIKE TESTS PASSED.")
