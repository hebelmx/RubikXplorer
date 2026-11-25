"""Stdlib-only canonical holdout & training-group exclusion policy for `tools/center-model/`."""

from __future__ import annotations

import json
from pathlib import Path
from typing import List, Set

REPO_ROOT = Path(__file__).resolve().parents[2]
EXCLUDED_LIVE = frozenset({"sm-s721u1_stkb_studio-a"})


def load_heldout_union(repo_root: Path = REPO_ROOT) -> Set[str]:
    """Return canonical holdout union; raises FileNotFoundError if `tools/sticker-model/holdout.json` is missing."""
    holdout_path = repo_root / "tools/sticker-model/holdout.json"
    if not holdout_path.is_file():
        raise FileNotFoundError(f"Canonical holdout file is missing: {holdout_path}")
    held: Set[str] = set(json.loads(holdout_path.read_text())["heldout"])
    groups_path = repo_root / "Fixtures/corner/groups.json"
    if groups_path.is_file():
        for entry in json.loads(groups_path.read_text())["groups"]:
            if entry["split"] == "heldout":
                held.add(f"corner/{entry['group']}")
    return held


def allowed_live_group_dirs(repo_root: Path = REPO_ROOT) -> List[Path]:
    """Return sorted `Fixtures/live/*` directories strictly disjoint from the canonical holdout union and excluded groups."""
    held = load_heldout_union(repo_root)
    live_dir = repo_root / "Fixtures/live"
    if not live_dir.is_dir():
        return []
    allowed: List[Path] = []
    for d in sorted(p for p in live_dir.iterdir() if p.is_dir()):
        if d.name in EXCLUDED_LIVE or f"live/{d.name}" in held:
            continue
        allowed.append(d)
    return allowed


def validate_background_paths(bg_dir: Path, repo_root: Path = REPO_ROOT) -> List[Path]:
    """Return sorted `.jpg`/`.png` paths under `bg_dir`, rejecting any held-out, excluded, or corner-fixture path."""
    held = load_heldout_union(repo_root)
    held_stems = {g.split("/", 1)[-1] for g in held}
    paths = sorted(bg_dir.glob("*.jpg")) + sorted(bg_dir.glob("*.png"))
    for p in paths:
        parts_lower = str(p).lower()
        if "fixtures/corner" in parts_lower:
            raise ValueError(f"Background path leaks Fixtures/corner/: {p}")
        for excl in EXCLUDED_LIVE:
            if excl in p.name or excl in p.parts:
                raise ValueError(f"Background path leaks excluded group {excl}: {p}")
        for stem in held_stems:
            if stem in p.name or stem in p.parts:
                raise ValueError(f"Background path leaks held-out group {stem}: {p}")
    return paths
