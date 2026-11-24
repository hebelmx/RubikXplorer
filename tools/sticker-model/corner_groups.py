"""Story 10.28: which recorded corner groups tuning and scoring may use (stdlib only).

The same rule as Kotlin's `CornerGroupUse`: a group whose `group.json` has no count check (older or supplemental-only
groups), or one that passed, is used; a pending or refused group is unsaved and never used; an overridden group is used
only when `Fixtures/corner/groups.json` lists it under `overridden` with `included: true`.
"""

from __future__ import annotations

import json
from pathlib import Path

MANIFEST = "group.json"
GROUP_INDEX = "groups.json"

USED = "used"
UNSAVED = "unsaved"
OVERRIDE_SKIPPED = "override-skipped"

SAVED_CHECKS = {None, "passed", "overridden"}

# The exclusion reason a skipped group's faces carry in the assembled manifest.
REASONS = {
    UNSAVED: "count check: unsaved",
    OVERRIDE_SKIPPED: "count check: override not included by the PO",
}


def load_index(corner_root: Path) -> dict | None:
    """The group index under `corner_root`, or None when there is none."""
    path = corner_root / GROUP_INDEX
    return json.loads(path.read_text()) if path.is_file() else None


def load_manifest(directory: Path) -> dict | None:
    """A group folder's `group.json`, or None for a group without one (pre-10.19)."""
    path = directory / MANIFEST
    return json.loads(path.read_text()) if path.is_file() else None


def group_use(name: str, manifest: dict | None, index: dict | None) -> str:
    """USED, UNSAVED or OVERRIDE_SKIPPED for group `name` with its manifest and the group index."""
    if manifest is None:
        return USED
    check = manifest.get("countCheck")
    if check not in SAVED_CHECKS:
        return UNSAVED
    if check == "overridden":
        overridden = (index or {}).get("overridden", [])
        included = any(entry.get("group") == name and entry.get("included", False) for entry in overridden)
        return USED if included else OVERRIDE_SKIPPED
    return USED


def folder_use(directory: Path, index: dict | None) -> str:
    """`group_use` for a group folder, read from its `group.json`."""
    return group_use(directory.name, load_manifest(directory), index)


def skipped_faces(directory: Path) -> list[tuple[str, str, str]]:
    """Every labelled face of a group folder as (hold file stem, face, frame name), in hold order."""
    faces = []
    for path in sorted(directory.glob("corner-*.json")):
        record = json.loads(path.read_text())
        for labeled in record.get("labeledSamples", []):
            faces.append((path.stem, labeled["sample"]["face"], record["frame"]))
    return faces
