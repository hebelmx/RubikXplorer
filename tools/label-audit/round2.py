"""Round 2: re-read every face with the PO's corrected outlines (decisions.json quad:*), relabels applied."""
import json, sys, collections
from pathlib import Path
import numpy as np
from PIL import Image
sys.path.insert(0, str(Path(__file__).resolve().parent))
from label_spike import cells, feat, COLORS, SIDE, OUT, CORNER  # noqa
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "sticker-model"))
from sticker_common import unwarp, load_rgb, to_uint8  # noqa

dec = json.loads((OUT / "decisions.json").read_text())
quads = json.loads((OUT / "quads.json").read_text())
faces_out = []
groups = {}
for gdir in sorted(p for p in CORNER.iterdir() if p.is_dir()):
    rows = []
    for path in sorted(gdir.glob("corner-*.json")):
        rec = json.loads(path.read_text())
        image = load_rgb(gdir / rec["frame"])
        q = quads.get(f"{gdir.name}|{path.stem}", {}).get("quads")
        for fi, ls in enumerate(rec["labeledSamples"]):
            fk = f"{gdir.name}|{path.stem}|{fi}"
            fq = (dec.get("quad:" + fk) or {}).get("quad") or (q[fi] if q else None)
            if fq is None:
                continue
            crop = unwarp(image, np.asarray(fq, dtype=np.float64), SIDE)
            labs = cells(crop)
            labels = []
            for i in range(9):
                d = (dec.get("face:" + fk) or {}).get("labels")
                if d:
                    labels.append(d[i]); continue
                sd = dec.get(f"{fk}|{i}")
                labels.append(sd["decision"][8:] if sd and sd["decision"].startswith("relabel:") else ls["truth"][i].upper())
            rows.append(dict(fk=fk, group=gdir.name, hold=path.stem, face=fi, faceName=ls["sample"]["face"],
                             quad=fq, corrected=("quad:" + fk) in dec, labels=labels, labs=[list(map(float, l)) for l in labs],
                             frame=f"/Fixtures/corner/{gdir.name}/{rec['frame']}", w=image.shape[1], h=image.shape[0],
                             othersQ=[qq for j, qq in enumerate(q or []) if j != fi]))
    if not rows:
        continue
    # centres: seeded from each face centre's label, refined by k-means over all 54 cells
    X = np.array([feat(l) for r in rows for l in r["labs"]])
    L = [lab for r in rows for lab in r["labels"]]
    names = [c for c in COLORS if c in L]
    C = np.array([X[[i for i, l in enumerate(L) if l == c]].mean(0) for c in names])
    for _ in range(20):
        a = np.linalg.norm(X[:, None] - C[None], axis=-1).argmin(1)
        C2 = np.array([X[a == k].mean(0) if (a == k).any() else C[k] for k in range(len(names))])
        if np.allclose(C2, C): break
        C = C2
    counts = collections.Counter(L)
    ok = len(rows) == 6 and all(counts[c] == 9 for c in COLORS)
    for r in rows:
        F = np.array([feat(l) for l in r["labs"]])
        d = np.linalg.norm(F[:, None] - C[None], axis=-1)
        o = d.argsort(1)
        r["reads"] = [names[o[i, 0]] for i in range(9)]
        r["margins"] = [float(d[i, o[i, 0]] / (d[i, o[i, 1]] + 1e-6)) for i in range(9)]
        r["disagree"] = sum(1 for i in range(9) if r["reads"][i] != r["labels"][i])
        r["countsOk"] = ok
        r["counts"] = dict(counts)
        r["centres"] = {n: list(map(float, C[k])) for k, n in enumerate(names)}
        faces_out.append(r)
(OUT / "round2.json").write_text(json.dumps(faces_out))
bad = sorted({r["group"] for r in faces_out if not r["countsOk"]})
print(json.dumps({"faces": len(faces_out), "facesWithDisagreement": sum(r["disagree"] > 0 for r in faces_out),
                  "stickerDisagreements": sum(r["disagree"] for r in faces_out), "groupsCountsWrong": bad}))
