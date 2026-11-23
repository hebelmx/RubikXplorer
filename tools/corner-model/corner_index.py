"""The Story 10.20 corner group index (`Fixtures/corner/groups.json`): which recorded group is held out and which tunes.

Stdlib only, so the evaluator, the training script and their tests share it without TensorFlow. The index lists every
10.22-pipeline group folder under `Fixtures/corner/` once:

    {"kind": "corner-group-index", "schemaVersion": 1,
     "groups": [{"group": "sm-s721u1_stla_warm", "split": "heldout"}, {"group": "sm-s721u1_stlp_warm", "split": "tuning", "pastel": true}]}

Whole groups are held out: a held-out group is never trained or tuned on.
"""

from __future__ import annotations

import json
from pathlib import Path

INDEX_NAME = "groups.json"
INDEX_KIND = "corner-group-index"
INDEX_SCHEMA = 1
HELDOUT = "heldout"
TUNING = "tuning"
ALL = "all"
SPLITS = (HELDOUT, TUNING)


class GroupIndexError(ValueError):
    """A malformed group index, or one naming a group that does not exist."""


def is_index(data: object) -> bool:
    """Whether a JSON value is the group index (by its `kind`)."""
    return isinstance(data, dict) and data.get("kind") == INDEX_KIND


def parse_index(data: object, source: str = INDEX_NAME) -> dict[str, dict]:
    """Validates a decoded index and returns `{group: {"split": ..., "pastel": ...}}`; raises GroupIndexError naming the problem."""
    if not is_index(data):
        raise GroupIndexError(f"{source}: not a corner group index (kind must be {INDEX_KIND!r})")
    if data.get("schemaVersion") != INDEX_SCHEMA:
        raise GroupIndexError(f"{source}: unsupported schemaVersion {data.get('schemaVersion')!r}")
    groups = data.get("groups")
    if not isinstance(groups, list):
        raise GroupIndexError(f"{source}: 'groups' must be a list")
    parsed: dict[str, dict] = {}
    for entry in groups:
        name = entry.get("group") if isinstance(entry, dict) else None
        if not isinstance(name, str) or not name.strip():
            raise GroupIndexError(f"{source}: an entry without a group name: {entry!r}")
        if name in parsed:
            raise GroupIndexError(f"{source}: group {name} is listed more than once")
        split = entry.get("split")
        if split not in SPLITS:
            raise GroupIndexError(f"{source}: group {name} has split {split!r}; it must be one of {', '.join(SPLITS)}")
        pastel = entry.get("pastel", False)
        if not isinstance(pastel, bool):
            raise GroupIndexError(f"{source}: group {name} has a non-boolean 'pastel'")
        parsed[name] = {"split": split, "pastel": pastel}
    return parsed


def load_index(corner_dir: Path) -> dict[str, dict] | None:
    """The index in `corner_dir`, checked against its folders; None when there is none. Raises GroupIndexError when it is
    malformed or names a group folder that does not exist."""
    path = Path(corner_dir) / INDEX_NAME
    if not path.is_file():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise GroupIndexError(f"{path}: unreadable JSON ({error})") from error
    index = parse_index(data, str(path))
    for name in index:
        if not (Path(corner_dir) / name).is_dir():
            raise GroupIndexError(f"{path}: group {name} has no folder under {corner_dir}")
    return index


def group_of(path: Path, corner_dir: Path) -> str | None:
    """The group folder a fixture file belongs to (its first folder under `corner_dir`); None for a file outside a group."""
    try:
        parts = Path(path).resolve().relative_to(Path(corner_dir).resolve()).parts
    except ValueError:
        return None
    return parts[0] if len(parts) > 1 else None


def split_of(path: Path, corner_dir: Path, index: dict[str, dict] | None) -> str | None:
    """The split of the group `path` is in; None without an index or for a group the index does not list."""
    if index is None:
        return None
    entry = index.get(group_of(path, corner_dir) or "")
    return entry["split"] if entry else None


def resolve_split(requested: str | None, index: dict[str, dict] | None) -> tuple[str, str | None]:
    """The split to evaluate and a notice to print: the requested one, else `heldout` with an index and `all` without
    (with a notice). Asking for a held-out or tuning split without an index is an error."""
    if requested is None:
        if index is None:
            return ALL, f"Notice: no group index ({INDEX_NAME}); evaluating every fixture (no held-out split yet)."
        return HELDOUT, None
    if requested not in SPLITS + (ALL,):
        raise GroupIndexError(f"unknown split {requested!r}")
    if requested != ALL and index is None:
        raise GroupIndexError(f"--split {requested} needs the group index {INDEX_NAME}")
    return requested, None


def select(paths: list[Path], corner_dir: Path, index: dict[str, dict] | None, split: str) -> list[Path]:
    """The fixture `paths` in `split` (`all`: every one)."""
    if split == ALL:
        return list(paths)
    return [p for p in paths if split_of(p, corner_dir, index) == split]


PIPELINE_10_22 = "10.22"
MANIFEST_NAME = "group.json"


def pipeline_of(path: Path, corner_dir: Path) -> str | None:
    """The `pipeline` of the group `path` is in, from its `group.json`; None without a group, manifest or tag. Raises
    GroupIndexError when the manifest exists but cannot be read, so a damaged 10.22 manifest never passes as an older group."""
    group = group_of(path, corner_dir)
    manifest = Path(corner_dir) / group / MANIFEST_NAME if group else None
    if manifest is None or not manifest.is_file():
        return None
    try:
        data = json.loads(manifest.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise GroupIndexError(f"{manifest}: unreadable group manifest ({error})") from error
    if not isinstance(data, dict):
        raise GroupIndexError(f"{manifest}: the group manifest is not a JSON object")
    return data.get("pipeline")


def training_allowed(path: Path, corner_dir: Path, index: dict[str, dict] | None) -> bool:
    """Whether a fixture may be trained on: never one in a held-out group, and a 10.22 group only once the index lists it
    as tuning (an unlisted one may still be held out later). Older groups (10.19 or no manifest) are allowed."""
    split = split_of(path, corner_dir, index)
    if split == HELDOUT:
        return False
    return split == TUNING or pipeline_of(path, corner_dir) != PIPELINE_10_22
