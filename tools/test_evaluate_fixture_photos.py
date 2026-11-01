import json
import unittest
from copy import deepcopy

from evaluate_fixture_photos import MANIFEST, ROOT, validate_manifest


class PhotoManifestTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = json.loads(MANIFEST.read_text())

    def test_checked_in_manifest_has_complete_visible_truth_and_sources(self):
        validate_manifest(self.manifest)
        self.assertEqual(4, len(self.manifest["groups"]))
        self.assertEqual(24, sum(len(group["samples"]) for group in self.manifest["groups"]))

    def test_rejects_missing_sticker_truth(self):
        manifest = deepcopy(self.manifest)
        manifest["groups"][0]["samples"][0]["truth"].pop()
        with self.assertRaisesRegex(ValueError, "nine explicit color labels"):
            validate_manifest(manifest)

    def test_rejects_grid_outside_normalized_image(self):
        manifest = deepcopy(self.manifest)
        manifest["groups"][0]["samples"][0]["rect"] = [-0.1, 0, 0.8, 0.8]
        with self.assertRaisesRegex(ValueError, "invalid normalized grid rectangle"):
            validate_manifest(manifest)

    def test_rejects_unavailable_calibration_source(self):
        manifest = deepcopy(self.manifest)
        manifest["groups"][0]["calibration"][0]["image"] = "Fixtures/absent.jpg"
        with self.assertRaisesRegex(ValueError, "calibration image is missing"):
            validate_manifest(manifest)

    def test_rejects_duplicate_calibration_colors(self):
        manifest = deepcopy(self.manifest)
        manifest["groups"][0]["calibration"][0]["color"] = manifest["groups"][0]["calibration"][1]["color"]
        with self.assertRaisesRegex(ValueError, "one calibration image for each of six colors"):
            validate_manifest(manifest)


if __name__ == "__main__":
    unittest.main()
