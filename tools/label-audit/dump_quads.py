import json, sys
from pathlib import Path
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "sticker-model"))
from sticker_common import REPO_ROOT, load_rgb
from assemble import CornerModel
det = CornerModel(); out = {}
for g in sorted(p for p in (REPO_ROOT / "Fixtures/corner").iterdir() if p.is_dir()):
    for path in sorted(g.glob("corner-*.json")):
        rec = json.loads(path.read_text()); img = load_rgb(g / rec["frame"])
        quads, _ = det.locate(img)
        out[f"{g.name}|{path.stem}"] = {"w": img.shape[1], "h": img.shape[0], "frame": f"/Fixtures/corner/{g.name}/{rec['frame']}",
                                         "quads": None if quads is None else [q.tolist() for q in quads]}
(Path(__file__).resolve().parent / "artifacts/label-spike/quads.json").write_text(json.dumps(out))
print(len(out))