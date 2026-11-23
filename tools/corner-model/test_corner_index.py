"""Tests for corner_index.py (stdlib unittest; run with `python3 -m unittest discover -s tools/corner-model -p 'test_*.py'`)."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import corner_index
from corner_index import ALL, HELDOUT, TUNING, GroupIndexError


def _index(*groups: dict) -> dict:
    return {"kind": "corner-group-index", "schemaVersion": 1, "groups": list(groups)}


class ParseIndexTest(unittest.TestCase):
    def test_a_valid_index_parses_with_pastel_defaulting_to_false(self) -> None:
        parsed = corner_index.parse_index(
            _index({"group": "a", "split": "heldout"}, {"group": "b", "split": "tuning", "pastel": True})
        )
        self.assertEqual({"a": {"split": HELDOUT, "pastel": False}, "b": {"split": TUNING, "pastel": True}}, parsed)
        self.assertTrue(corner_index.is_index(_index()))
        self.assertFalse(corner_index.is_index({"kind": "corner-keypoints"}))

    def test_a_malformed_index_fails_naming_the_group(self) -> None:
        cases = {
            "split": _index({"group": "g1", "split": "training"}),
            "more than once": _index({"group": "g2", "split": "tuning"}, {"group": "g2", "split": "heldout"}),
            "pastel": _index({"group": "g3", "split": "tuning", "pastel": "yes"}),
        }
        for words, data in cases.items():
            with self.subTest(words), self.assertRaises(GroupIndexError) as caught:
                corner_index.parse_index(data)
            self.assertIn(words, str(caught.exception))
            self.assertRegex(str(caught.exception), r"g\d")
        for data in ({"kind": "other"}, {**_index(), "schemaVersion": 2}, {**_index(), "groups": {}}, _index({"split": "tuning"})):
            with self.subTest(data=data), self.assertRaises(GroupIndexError):
                corner_index.parse_index(data)


class CornerDirTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        for group in ("held", "tune", "unlisted"):
            (self.root / group / "keypoints").mkdir(parents=True)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def write_index(self, *groups: dict) -> None:
        (self.root / "groups.json").write_text(json.dumps(_index(*groups)), encoding="utf-8")

    def test_no_index_means_all_fixtures_with_a_notice(self) -> None:
        self.assertIsNone(corner_index.load_index(self.root))
        split, notice = corner_index.resolve_split(None, None)
        self.assertEqual(ALL, split)
        self.assertIn("no group index", notice)
        with self.assertRaises(GroupIndexError):
            corner_index.resolve_split(HELDOUT, None)
        self.assertEqual((ALL, None), corner_index.resolve_split(ALL, None))

    def test_the_index_maps_fixtures_to_groups_and_splits(self) -> None:
        self.write_index({"group": "held", "split": "heldout"}, {"group": "tune", "split": "tuning"})
        index = corner_index.load_index(self.root)
        self.assertEqual((HELDOUT, None), corner_index.resolve_split(None, index))
        self.assertEqual((TUNING, None), corner_index.resolve_split(TUNING, index))
        held = self.root / "held" / "keypoints" / "0001.json"
        tune = self.root / "tune" / "keypoints" / "0001.json"
        other = self.root / "unlisted" / "keypoints" / "0001.json"
        self.assertEqual("held", corner_index.group_of(held, self.root))
        self.assertIsNone(corner_index.group_of(self.root / "groups.json", self.root))
        self.assertIsNone(corner_index.group_of(Path("/elsewhere/x.json"), self.root))
        self.assertEqual(HELDOUT, corner_index.split_of(held, self.root, index))
        self.assertIsNone(corner_index.split_of(other, self.root, index))
        paths = [held, tune, other]
        # The evaluator's default scores the held-out groups only; --split all scores every fixture.
        self.assertEqual([held], corner_index.select(paths, self.root, index, HELDOUT))
        self.assertEqual([tune], corner_index.select(paths, self.root, index, TUNING))
        self.assertEqual(paths, corner_index.select(paths, self.root, index, ALL))
        # Training never takes a held-out fixture.
        self.assertEqual([tune, other], [p for p in paths if corner_index.training_allowed(p, self.root, index)])
        self.assertTrue(corner_index.training_allowed(held, self.root, None))

    def manifest(self, group: str, pipeline: str) -> None:
        (self.root / group / "group.json").write_text(json.dumps({"pipeline": pipeline, "lightDirection": "front"}), encoding="utf-8")

    def test_the_index_file_is_recognised(self) -> None:
        self.write_index({"group": "held", "split": "heldout"})
        self.assertTrue(corner_index.is_index(json.loads((self.root / "groups.json").read_text(encoding="utf-8"))))
        self.assertFalse(corner_index.is_index({"pipeline": "10.22", "lightDirection": "front"}))

    def test_training_takes_tuning_and_legacy_groups_only(self) -> None:
        for group in ("held", "tune", "unlisted"):
            self.manifest(group, "10.22")
        (self.root / "legacy" / "keypoints").mkdir(parents=True)
        (self.root / "old").mkdir()
        self.manifest("old", "10.19")
        fixture = {g: self.root / g / "keypoints" / "0001.json" for g in ("held", "tune", "unlisted", "legacy", "old")}
        # Without an index no 10.22 group is trained on: any of them may be held out later.
        self.assertFalse(corner_index.training_allowed(fixture["tune"], self.root, None))
        self.assertFalse(corner_index.training_allowed(fixture["unlisted"], self.root, None))
        self.assertTrue(corner_index.training_allowed(fixture["legacy"], self.root, None))
        self.assertTrue(corner_index.training_allowed(fixture["old"], self.root, None))
        self.write_index({"group": "held", "split": "heldout"}, {"group": "tune", "split": "tuning"})
        index = corner_index.load_index(self.root)
        allowed = {g for g, p in fixture.items() if corner_index.training_allowed(p, self.root, index)}
        self.assertEqual({"tune", "legacy", "old"}, allowed)

    def test_an_index_naming_a_missing_folder_fails_with_its_name(self) -> None:
        self.write_index({"group": "held", "split": "heldout"}, {"group": "gone-group", "split": "tuning"})
        with self.assertRaises(GroupIndexError) as caught:
            corner_index.load_index(self.root)
        self.assertIn("gone-group", str(caught.exception))

    def test_an_unreadable_index_fails(self) -> None:
        (self.root / "groups.json").write_text("{not json", encoding="utf-8")
        with self.assertRaises(GroupIndexError):
            corner_index.load_index(self.root)


if __name__ == "__main__":
    unittest.main()
