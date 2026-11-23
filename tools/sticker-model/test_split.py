"""AC2: the committed split keeps whole groups apart, and the check fails on a crafted manifest."""

from __future__ import annotations

import copy
import json
import unittest

import split
from sticker_common import HOLDOUT_FILE, SPLIT_FILE


class SplitTest(unittest.TestCase):
    def setUp(self) -> None:
        self.holdout = json.loads(HOLDOUT_FILE.read_text())
        self.split = json.loads(SPLIT_FILE.read_text())

    def test_the_committed_split_passes_with_rehashed_images(self) -> None:
        self.assertEqual([], split.verify(self.split, self.holdout))

    def test_holdout_covers_both_devices_cubes_and_two_backlit_groups(self) -> None:
        held = [g for g in self.split["groups"] if g["side"] == "heldout"]
        self.assertEqual(sorted(self.holdout["heldout"]), sorted(g["group"] for g in held))
        self.assertEqual(2, len({g["device"] for g in held}))
        self.assertEqual({True, False}, {g["stickered"] for g in held})
        self.assertGreaterEqual(sum("backlit" in g["light"] for g in held), 2)

    def test_a_group_on_two_sides_fails_naming_it(self) -> None:
        bad = copy.deepcopy(self.split)
        item = next(i for i in bad["items"] if i["side"] == "heldout")
        item["side"] = "train"
        failures = split.verify(bad, self.holdout)
        self.assertTrue(any(f"group {item['group']} is on two sides" in f for f in failures), failures)

    def test_an_image_hash_on_two_sides_fails_naming_it(self) -> None:
        bad = copy.deepcopy(self.split)
        held = next(i for i in bad["items"] if i["side"] == "heldout")
        train = next(i for i in bad["items"] if i["side"] == "train")
        train["sha256"] = held["sha256"]
        failures = split.verify(bad, self.holdout, root=None)
        self.assertTrue(any(f"image hash {held['sha256']} is on 2 sides" in f for f in failures), failures)

    def test_synthetic_outside_train_fails(self) -> None:
        bad = copy.deepcopy(self.split)
        next(g for g in bad["groups"] if g["kind"] == "synthetic")["side"] = "heldout"
        bad["items"].append({"item": "synthetic/sticker-faces/0", "group": split.SYNTHETIC_GROUP, "side": "tune", "sha256": "0" * 64, "source": "x"})
        failures = split.verify(bad, self.holdout, root=None)
        self.assertTrue(any(f.startswith("synthetic group") for f in failures), failures)
        self.assertTrue(any(f.startswith("synthetic item") for f in failures), failures)

    def test_a_named_heldout_group_missing_from_the_heldout_side_fails(self) -> None:
        bad = copy.deepcopy(self.split)
        name = self.holdout["heldout"][0]
        bad["groups"] = [g for g in bad["groups"] if g["group"] != name]
        bad["items"] = [i for i in bad["items"] if i["group"] != name]
        failures = split.verify(bad, self.holdout)
        self.assertTrue(any(f"holdout.json names {name} held out" in f for f in failures), failures)

    def test_sides_follow_holdout_json(self) -> None:
        sides = split.sides_for(self.holdout, ["live/a", self.holdout["heldout"][0], self.holdout["tune"][0]])
        self.assertEqual({"live/a": "train", self.holdout["heldout"][0]: "heldout", self.holdout["tune"][0]: "tune"}, sides)


if __name__ == "__main__":
    unittest.main()
