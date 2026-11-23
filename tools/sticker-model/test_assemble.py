"""AC1: the assembly is byte-identical across runs, real-only, and its crops sit in Kotlin cell order."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image

import assemble
from sticker_common import REPO_ROOT, cell_means, rgb_to_lab


class AssembleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls._tmp = tempfile.TemporaryDirectory()
        cls.first = Path(cls._tmp.name) / "first"
        cls.second = Path(cls._tmp.name) / "second"
        cls.manifest = assemble.assemble(cls.first)
        assemble.assemble(cls.second)

    @classmethod
    def tearDownClass(cls) -> None:
        cls._tmp.cleanup()

    def test_two_runs_give_byte_identical_manifests_and_crops(self) -> None:
        self.assertEqual((self.first / "manifest.json").read_bytes(), (self.second / "manifest.json").read_bytes())
        self.assertEqual((self.first / "inventory.json").read_bytes(), (self.second / "inventory.json").read_bytes())
        crops = sorted(p.name for p in (self.first / "crops").glob("*.png"))
        self.assertEqual(crops, sorted(p.name for p in (self.second / "crops").glob("*.png")))
        for name in crops:
            self.assertEqual((self.first / "crops" / name).read_bytes(), (self.second / "crops" / name).read_bytes(), name)

    def test_the_manifest_holds_real_faces_only_and_synthetic_trains_only(self) -> None:
        self.assertTrue(self.manifest["items"])
        self.assertTrue(all(r["kind"] == "real" for r in self.manifest["items"]))
        self.assertEqual("train", self.manifest["synthetic"]["side"])
        self.assertFalse([r for r in self.manifest["items"] if r["group"].startswith("synthetic/")])

    def test_known_bad_truth_is_excluded_with_its_reason(self) -> None:
        excluded = {r["item"]: r["reason"] for r in self.manifest["excluded"]}
        self.assertIn("live/sm-s721u1_stkb_studio-a/F", excluded)
        self.assertFalse([r for r in self.manifest["items"] if r["group"] == "live/sm-s721u1_stkb_studio-a"])

    def test_a_crop_with_known_truth_is_upright_in_kotlin_cell_order(self) -> None:
        """Each non-centre cell of the crop is nearest the recorded calibration centre of its truth colour."""
        for item in ("live/lenovo-tb128xu_stka_studio-a/F", "corner/lenovo-tb128xu_stka_warm/corner-1/F"):
            record = next(r for r in self.manifest["items"] if r["item"] == item)
            centers = json.loads((REPO_ROOT / record["source"]).with_suffix(".json").read_text())["calibration"]["centers"]
            crop = np.asarray(Image.open(self.first / record["crop"]).convert("RGB"), dtype=np.float32)
            self.assertEqual((96, 96, 3), crop.shape)
            labs = rgb_to_lab(np.stack(cell_means(crop, 0.5)))
            for index, lab in enumerate(labs):
                if index == 4:
                    continue
                nearest = min(centers, key=lambda c: float(np.linalg.norm(lab - np.array([centers[c][k] for k in "lab"]))))
                self.assertEqual(record["labels"][index], nearest, f"{item} cell {index}")
            # A quarter turn breaks the order: the same check then fails on some cell.
            turned = np.rot90(crop).copy()
            turned_labs = rgb_to_lab(np.stack(cell_means(turned, 0.5)))
            mismatched = [
                i
                for i, lab in enumerate(turned_labs)
                if i != 4 and min(centers, key=lambda c: float(np.linalg.norm(lab - np.array([centers[c][k] for k in "lab"])))) != record["labels"][i]
            ]
            self.assertTrue(mismatched, f"{item}: a turned crop should not match")


if __name__ == "__main__":
    unittest.main()
