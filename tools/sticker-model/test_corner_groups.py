"""Story 10.28: the corner-group use rule matches Kotlin's `CornerGroupUse` (stdlib only)."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import corner_groups
from corner_groups import OVERRIDE_SKIPPED, UNSAVED, USED

INDEX = {
    "kind": "corner-group-index",
    "schemaVersion": 1,
    "groups": [{"group": "a", "split": "tuning"}],
    "overridden": [
        {"group": "in", "split": "tuning", "reason": "peeling sticker", "included": True},
        {"group": "out", "split": "heldout", "reason": "logo centre"},
    ],
}


def manifest(check: str | None = None, reason: str | None = None) -> dict:
    return {"pipeline": "10.22", "lightDirection": "front", "cube": "stla", "light": "warm", "countCheck": check, "overrideReason": reason}


class GroupUseTest(unittest.TestCase):
    def test_saved_groups_are_used(self) -> None:
        self.assertEqual(USED, corner_groups.group_use("legacy", None, INDEX))
        self.assertEqual(USED, corner_groups.group_use("a", manifest(), INDEX))
        self.assertEqual(USED, corner_groups.group_use("a", manifest("passed"), None))
        # A manifest written before Story 10.28 has no countCheck key at all.
        old = manifest()
        del old["countCheck"]
        self.assertEqual(USED, corner_groups.group_use("a", old, INDEX))

    def test_pending_and_refused_groups_are_unsaved(self) -> None:
        self.assertEqual(UNSAVED, corner_groups.group_use("a", manifest("pending"), INDEX))
        self.assertEqual(UNSAVED, corner_groups.group_use("a", manifest("refused"), INDEX))

    def test_an_override_is_used_only_when_the_po_includes_it(self) -> None:
        over = manifest("overridden", "reason")
        self.assertEqual(USED, corner_groups.group_use("in", over, INDEX))
        self.assertEqual(OVERRIDE_SKIPPED, corner_groups.group_use("out", over, INDEX))
        self.assertEqual(OVERRIDE_SKIPPED, corner_groups.group_use("unlisted", over, INDEX))
        self.assertEqual(OVERRIDE_SKIPPED, corner_groups.group_use("in", over, None))
        self.assertEqual(OVERRIDE_SKIPPED, corner_groups.group_use("in", over, {"groups": []}))

    def test_folders_are_read_from_disk_with_their_faces(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / corner_groups.GROUP_INDEX).write_text(json.dumps(INDEX))
            for name, data in {"a": manifest(), "out": manifest("overridden", "r"), "refused": manifest("refused")}.items():
                folder = root / name
                folder.mkdir()
                (folder / corner_groups.MANIFEST).write_text(json.dumps(data))
            (root / "legacy").mkdir()
            index = corner_groups.load_index(root)
            uses = {p.name: corner_groups.folder_use(p, index) for p in sorted(root.iterdir()) if p.is_dir()}
            self.assertEqual({"a": USED, "legacy": USED, "out": OVERRIDE_SKIPPED, "refused": UNSAVED}, uses)
            self.assertIsNone(corner_groups.load_index(root / "legacy"))
            for hold, faces in ((1, ("U", "F", "R")), (2, ("D", "B", "L"))):
                record = {"hold": hold, "frame": f"corner-{hold}.jpg", "labeledSamples": [{"sample": {"face": f}, "truth": []} for f in faces]}
                (root / "refused" / f"corner-{hold}.json").write_text(json.dumps(record))
            self.assertEqual(
                [("corner-1", "U", "corner-1.jpg"), ("corner-1", "F", "corner-1.jpg"), ("corner-1", "R", "corner-1.jpg"),
                 ("corner-2", "D", "corner-2.jpg"), ("corner-2", "B", "corner-2.jpg"), ("corner-2", "L", "corner-2.jpg")],
                corner_groups.skipped_faces(root / "refused"),
            )
            self.assertEqual(
                [
                    {"item": f"corner/refused/corner-{h}/{f}", "source": f"refused/corner-{h}.jpg", "reason": "count check: unsaved"}
                    for h, faces in ((1, "UFR"), (2, "DBL"))
                    for f in faces
                ],
                corner_groups.excluded_entries(root / "refused", UNSAVED, root),
            )
            self.assertEqual([], corner_groups.excluded_entries(root / "a", USED, root))
            self.assertEqual("count check: unsaved", corner_groups.REASONS[UNSAVED])
            self.assertEqual("count check: override not included by the PO", corner_groups.REASONS[OVERRIDE_SKIPPED])

    def test_no_committed_corner_group_is_unsaved(self) -> None:
        corner = Path(__file__).resolve().parents[2] / "Fixtures/corner"
        if not corner.is_dir():
            self.skipTest("no corner fixtures")
        index = corner_groups.load_index(corner)
        uses = {p.name: corner_groups.folder_use(p, index) for p in corner.iterdir() if p.is_dir()}
        self.assertTrue(uses)
        # An override the PO has not included may be committed; an unsaved group never is.
        self.assertNotIn(UNSAVED, set(uses.values()), uses)


if __name__ == "__main__":
    unittest.main()
