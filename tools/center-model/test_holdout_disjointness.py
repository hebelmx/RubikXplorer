"""Stdlib-only provenance & canonical holdout-disjointness tests for `tools/center-model/` (Story 10.31 AC3)."""

from __future__ import annotations

import copy
import hashlib
import json
import os
import re
import tempfile
import unittest
from pathlib import Path
from typing import Dict, List, Set

import holdout_policy

TOOL_DIR = Path(__file__).resolve().parent
REPO_ROOT = holdout_policy.REPO_ROOT
MODEL_CARD_PATH = TOOL_DIR / "model-card.json"
MODEL_ASSET_PATH = REPO_ROOT / "app/src/camera/continuous/assets/sticker_centers_v2_int8.tflite"
EXCLUDED_LIVE_GROUP = "live/sm-s721u1_stkb_studio-a"
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


def validate_provenance(
    card: Dict[str, object],
    model_bytes: bytes,
    holdout_union: Set[str],
) -> List[str]:
    """Return a list of provenance or holdout-leak violations; empty when clean."""
    violations: List[str] = []

    sha_recorded = str(card.get("sha256", ""))
    if not SHA256_RE.match(sha_recorded):
        violations.append(f"model-card sha256 is not 64 lowercase hex characters: {sha_recorded!r}")
    sha_actual = hashlib.sha256(model_bytes).hexdigest()
    if sha_actual != sha_recorded:
        violations.append(f"model sha256 mismatch: actual {sha_actual} != recorded {sha_recorded}")

    bytes_recorded = card.get("bytes")
    if bytes_recorded != len(model_bytes):
        violations.append(f"model byte length mismatch: actual {len(model_bytes)} != recorded {bytes_recorded}")

    dataset = card.get("dataset")
    if not isinstance(dataset, dict):
        return violations + ["model-card missing dataset manifest"]

    card_holdout = set(dataset.get("holdoutUnion", []))
    missing_from_card = sorted(holdout_union - card_holdout)
    if missing_from_card:
        violations.append(f"model-card holdoutUnion is missing canonical held-out groups: {missing_from_card}")

    train_live = list(dataset.get("trainingLiveGroups", []))
    train_corner = list(dataset.get("trainingCornerGroups", []))
    excluded_live = set(dataset.get("excludedLiveGroups", []))

    if EXCLUDED_LIVE_GROUP not in excluded_live:
        violations.append(f"excludedLiveGroups must list {EXCLUDED_LIVE_GROUP}")

    if train_corner:
        violations.append(f"trainingCornerGroups must be empty (zero Fixtures/corner/ photos), got {train_corner}")

    for group in train_live + train_corner:
        if group in holdout_union or group in card_holdout:
            violations.append(f"held-out group leaked into training manifest: {group}")
        if group == EXCLUDED_LIVE_GROUP or group in excluded_live:
            violations.append(f"excluded group leaked into training manifest: {group}")
        if group.startswith("corner/"):
            violations.append(f"Fixtures/corner/ group leaked into training manifest: {group}")

    return violations


class CenterModelProvenanceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.card = json.loads(MODEL_CARD_PATH.read_text())
        self.model_bytes = MODEL_ASSET_PATH.read_bytes()
        self.holdout_union = holdout_policy.load_heldout_union(REPO_ROOT)

    def test_committed_model_and_card_pass_provenance_and_holdout_disjointness(self) -> None:
        self.assertTrue(self.holdout_union, "Expected non-empty canonical holdout union")
        self.assertEqual([], validate_provenance(self.card, self.model_bytes, self.holdout_union))
        self.assertEqual(378288, len(self.model_bytes))

    def test_recipe_scripts_are_self_contained_with_zero_planning_or_shadow_imports(self) -> None:
        for script_name in (
            "holdout_policy.py",
            "cube_geometry.py",
            "synth_heatmap_data.py",
            "train_center_model.py",
            "eval_litert_center.py",
        ):
            text = (TOOL_DIR / script_name).read_text()
            self.assertNotIn("_bmad-output", text, f"{script_name} must not import from _bmad-output")
            self.assertNotIn("sticker_common", text, f"{script_name} must not import from tools/sticker-model")
        policy_text = (TOOL_DIR / "holdout_policy.py").read_text()
        self.assertIn("sm-s721u1_stkb_studio-a", policy_text)

    def test_holdout_policy_filters_live_groups_validates_backgrounds_and_rejects_missing_holdout_file(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            # Missing holdout.json must raise FileNotFoundError instead of silently returning empty.
            with self.assertRaises(FileNotFoundError):
                holdout_policy.load_heldout_union(root)

            (root / "tools/sticker-model").mkdir(parents=True)
            (root / "tools/sticker-model/holdout.json").write_text(
                json.dumps({"heldout": ["live/held_live_group", "corner/held_corner_group"]})
            )
            (root / "Fixtures/corner").mkdir(parents=True)
            (root / "Fixtures/corner/groups.json").write_text(
                json.dumps({"groups": [{"group": "extra_held_corner", "split": "heldout"}]})
            )
            for name in ("allowed_group", "held_live_group", "sm-s721u1_stkb_studio-a"):
                (root / "Fixtures/live" / name).mkdir(parents=True)

            union = holdout_policy.load_heldout_union(root)
            self.assertEqual({"live/held_live_group", "corner/held_corner_group", "corner/extra_held_corner"}, union)

            allowed = [d.name for d in holdout_policy.allowed_live_group_dirs(root)]
            self.assertEqual(["allowed_group"], allowed)

            # Background path guard accepts clean tuning room frames and rejects held-out/excluded/corner leaks.
            clean_bg = root / "bg_clean"
            clean_bg.mkdir()
            (clean_bg / "room_01.jpg").write_bytes(b"jpg")
            self.assertEqual([clean_bg / "room_01.jpg"], holdout_policy.validate_background_paths(clean_bg, root))

            leaked_bg = root / "bg_leaked"
            leaked_bg.mkdir()
            (leaked_bg / "held_corner_group_01.jpg").write_bytes(b"jpg")
            with self.assertRaises(ValueError):
                holdout_policy.validate_background_paths(leaked_bg, root)

            excl_bg = root / "bg_excl"
            excl_bg.mkdir()
            (excl_bg / "sm-s721u1_stkb_studio-a_01.png").write_bytes(b"png")
            with self.assertRaises(ValueError):
                holdout_policy.validate_background_paths(excl_bg, root)

            # A relative directory or a symlink must not hide a held-out or corner-fixture location.
            corner_group = root / "Fixtures/corner/some_tuning_group"
            corner_group.mkdir(parents=True)
            (corner_group / "corner-1.jpg").write_bytes(b"jpg")
            previous_cwd = Path.cwd()
            os.chdir(corner_group)
            try:
                with self.assertRaises(ValueError):
                    holdout_policy.validate_background_paths(Path("."), root)
            finally:
                os.chdir(previous_cwd)
            linked_bg = root / "bg_linked"
            linked_bg.mkdir()
            (linked_bg / "room_02.jpg").symlink_to(corner_group / "corner-1.jpg")
            with self.assertRaises(ValueError):
                holdout_policy.validate_background_paths(linked_bg, root)

    def test_injected_heldout_group_fails_loudly(self) -> None:
        leaked = copy.deepcopy(self.card)
        target = sorted(self.holdout_union)[0]
        leaked["dataset"]["trainingLiveGroups"].append(target)
        failures = validate_provenance(leaked, self.model_bytes, self.holdout_union)
        self.assertTrue(any(target in msg for msg in failures), failures)

    def test_injected_excluded_live_group_fails_loudly(self) -> None:
        leaked = copy.deepcopy(self.card)
        leaked["dataset"]["trainingLiveGroups"].append(EXCLUDED_LIVE_GROUP)
        failures = validate_provenance(leaked, self.model_bytes, self.holdout_union)
        self.assertTrue(any(EXCLUDED_LIVE_GROUP in msg for msg in failures), failures)

    def test_injected_corner_group_fails_loudly(self) -> None:
        leaked = copy.deepcopy(self.card)
        leaked["dataset"]["trainingCornerGroups"].append("corner/lenovo-tb128xu_stka_bar-warm")
        failures = validate_provenance(leaked, self.model_bytes, self.holdout_union)
        self.assertTrue(any("trainingCornerGroups must be empty" in msg for msg in failures), failures)

    def test_sha256_mismatch_fails_loudly(self) -> None:
        bad = copy.deepcopy(self.card)
        bad["sha256"] = "0" * 64
        failures = validate_provenance(bad, self.model_bytes, self.holdout_union)
        self.assertTrue(any("sha256 mismatch" in msg for msg in failures), failures)


if __name__ == "__main__":
    unittest.main()
