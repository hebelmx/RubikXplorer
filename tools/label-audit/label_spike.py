"""Label-cleansing spike (SCP 2026-11-23 pastel-plausibility, option A).

Per corner capture group (two holds = six faces = the whole cube):
  1. find each face with corner_keypoints.tflite (fallback: the recorded keypoints), unwarp, cut 3x3,
     inner 60% of each cell, median CIELAB from the photo itself;
  2. seed six clusters from the group's own six face centres (colour known from the face), refine with k-means
     on a shading-tolerant feature (a*, b*, 0.5 L*);
  3. margin = d(best) / d(second);
  4. cube count rule: 9 stickers of each colour;
  5. decide per sticker: auto-keep / auto-relabel proposal / quarantine (plus sample-mismatch when the stored
     app sample disagrees with the photo).
Writes artifacts/label-spike/{report.json, index.html, crops/}.
"""
import collections, html, json, sys
from pathlib import Path
import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "sticker-model"))
from sticker_common import REPO_ROOT, unwarp, rgb_to_lab, load_rgb, guide_quads, to_uint8  # noqa: E402
from assemble import CornerModel  # noqa: E402

CORNER = REPO_ROOT / "Fixtures/corner"
OUT = Path(__file__).resolve().parent / "artifacts/label-spike"
COLORS = ("WHITE", "YELLOW", "RED", "ORANGE", "BLUE", "GREEN")
SWATCH = {"WHITE": "#eee", "YELLOW": "#ffd500", "RED": "#c41e3a", "ORANGE": "#ff5800", "BLUE": "#0051ba", "GREEN": "#009e60"}
ROI = 0.60
SIDE = 120
MARGIN = 0.65          # d1/d2 below this = clear
MISMATCH_DE = 25.0     # photo vs stored app sample


def feat(lab):
    lab = np.asarray(lab, dtype=np.float64)
    return np.stack([lab[..., 1], lab[..., 2], 0.5 * lab[..., 0]], axis=-1)


def cells(crop):
    cell = SIDE / 3.0
    inset = (1 - ROI) / 2
    out = []
    for r in range(3):
        for c in range(3):
            x0, x1 = int((c + inset) * cell), int((c + 1 - inset) * cell)
            y0, y1 = int((r + inset) * cell), int((r + 1 - inset) * cell)
            px = crop[y0:y1, x0:x1].reshape(-1, 3)
            out.append(np.median(rgb_to_lab(px), axis=0))
    return out


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "crops").mkdir(exist_ok=True)
    detector = CornerModel()
    report = []
    for gdir in sorted(p for p in CORNER.iterdir() if p.is_dir()):
        holds = sorted(gdir.glob("corner-*.json"))
        stickers = []
        for path in holds:
            rec = json.loads(path.read_text())
            image = load_rgb(gdir / rec["frame"])
            quads, why = detector.locate(image)
            geom = "corner-model"
            if quads is None and rec.get("keypoints"):
                quads = guide_quads(np.asarray(rec["keypoints"], dtype=np.float64)[:, :2] if np.asarray(rec["keypoints"]).ndim == 2 else np.asarray(rec["keypoints"]).reshape(7, 2))
                geom = "recorded-keypoints (" + why + ")"
            for fi, ls in enumerate(rec["labeledSamples"]):
                if quads is None:
                    continue
                crop = unwarp(image, quads[fi], SIDE)
                if crop is None:
                    continue
                face_png = f"crops/{gdir.name}__{path.stem}__{fi}.png"
                Image.fromarray(to_uint8(crop)).save(OUT / face_png)
                labs = cells(crop)
                center = ls["sample"].get("centerColor") or ls["truth"][4]
                for i in range(9):
                    st = ls["sample"]["stickers"][i]
                    stored = (st["l"], st["a"], st["b"])
                    stickers.append(dict(group=gdir.name, hold=path.stem, photo=str(gdir / rec["frame"]), face=fi,
                                         faceName=ls["sample"]["face"], idx=i, label=ls["truth"][i].upper(),
                                         center=str(center).upper(), lab=[float(v) for v in labs[i]], stored=list(stored),
                                         faceCrop=face_png, geom=geom,
                                         source=(rec.get("truthSources") or [[""] * 9] * 3)[fi][i]))
        if not stickers:
            report.append(dict(group=gdir.name, skipped="no faces located"))
            continue
        # seeds from the six face centres (colour known); logo centres included, k-means fixes them
        seeds = {}
        for s in stickers:
            if s["idx"] == 4:
                seeds.setdefault(s["center"], []).append(feat(s["lab"]))
        X = feat(np.array([s["lab"] for s in stickers]))
        names = [c for c in COLORS if c in seeds]
        C = np.array([np.mean(seeds[c], axis=0) for c in names])
        for _ in range(20):
            d = np.linalg.norm(X[:, None, :] - C[None], axis=-1)
            a = d.argmin(1)
            newC = np.array([X[a == k].mean(0) if (a == k).any() else C[k] for k in range(len(names))])
            if np.allclose(newC, C):
                break
            C = newC
        d = np.linalg.norm(X[:, None, :] - C[None], axis=-1)
        order = d.argsort(1)
        label_counts = collections.Counter(s["label"] for s in stickers)
        full = len(stickers) == 54
        for k, s in enumerate(stickers):
            best, second = order[k, 0], order[k, 1]
            s["cluster"] = names[best]
            s["margin"] = float(d[k, best] / (d[k, second] + 1e-6))
            s["second"] = names[second]
            s["mismatchDE"] = float(np.linalg.norm(np.array(s["lab"]) - np.array(s["stored"])))
            if s["idx"] == 4:
                s["decision"] = "centre"
            elif s["cluster"] == s["label"] and s["margin"] < MARGIN:
                s["decision"] = "auto-keep"
            elif s["cluster"] != s["label"] and s["margin"] < MARGIN and full and \
                    label_counts[s["label"]] > 9 and label_counts[s["cluster"]] < 9:
                s["decision"] = "auto-relabel"
            else:
                s["decision"] = "quarantine"
            if s["decision"] != "centre" and s["mismatchDE"] > MISMATCH_DE:
                s["sampleMismatch"] = True
        report.append(dict(group=gdir.name, faces=len(stickers) // 9, counts=dict(label_counts),
                           countsOk=full and all(label_counts[c] == 9 for c in COLORS), stickers=stickers))
    (OUT / "report.json").write_text(json.dumps(report, indent=1))
    write_html(report)
    summarize(report)


def cell_crop(face_png, idx, tag):
    img = Image.open(OUT / face_png)
    cell = SIDE // 3
    r, c = divmod(idx, 3)
    box = img.crop((c * cell, r * cell, (c + 1) * cell, (r + 1) * cell)).resize((96, 96), Image.NEAREST)
    name = f"crops/{tag}.png"
    box.save(OUT / name)
    return name


def write_html(report):
    rows = []
    for g in report:
        for s in g.get("stickers", []):
            if s["decision"] in ("quarantine", "auto-relabel") or s.get("sampleMismatch"):
                tag = f"{s['group']}__{s['hold']}__{s['face']}__{s['idx']}"
                cc = cell_crop(s["faceCrop"], s["idx"], tag)
                L, A, B = s["lab"]
                rows.append(f"""<div class=card><div class=imgs><img src="{cc}"><img class=face src="{s['faceCrop']}" style="--r:{s['idx']//3};--c:{s['idx']%3}"></div>
<b>{html.escape(s['group'])}</b> {s['hold']} face {s['face']} ({s['faceName']}) sticker {s['idx']}<br>
label <span class=sw style="background:{SWATCH[s['label']]}"></span>{s['label']} &rarr; cluster <span class=sw style="background:{SWATCH[s['cluster']]}"></span>{s['cluster']}
(2nd {s['second']}, margin {s['margin']:.2f})<br>photo Lab {L:.0f}/{A:.0f}/{B:.0f} &middot; app-sample dE {s['mismatchDE']:.0f}{' &#9888; sample mismatch' if s.get('sampleMismatch') else ''}<br>
<i>{s['decision']}</i> &middot; source {html.escape(str(s['source']))} &middot; {html.escape(s['geom'])}<br><small>{html.escape(s['photo'])}</small></div>""")
    page = f"""<!doctype html><meta charset=utf-8><title>Label spike quarantine</title><style>
body{{font:13px system-ui;background:#1b1b1b;color:#ddd}} .card{{display:inline-block;vertical-align:top;width:330px;margin:6px;padding:8px;background:#2a2a2a;border-radius:6px}}
.imgs img{{width:96px;height:96px;image-rendering:pixelated;margin-right:6px}} .sw{{display:inline-block;width:10px;height:10px;border:1px solid #888;margin:0 3px}}
small{{color:#999;word-break:break-all}}</style><h2>Quarantine and relabel proposals ({len(rows)})</h2>{''.join(rows)}"""
    (OUT / "index.html").write_text(page)


def summarize(report):
    tot = collections.Counter()
    per = []
    for g in report:
        if "stickers" not in g:
            per.append(f"{g['group']}: {g['skipped']}")
            continue
        c = collections.Counter(s["decision"] for s in g["stickers"])
        mm = sum(1 for s in g["stickers"] if s.get("sampleMismatch"))
        tot.update(c)
        tot["sampleMismatch"] += mm
        geo = sorted({s["geom"][:12] for s in g["stickers"]})
        per.append(f"{g['group']}: faces {g['faces']} keep {c['auto-keep']} relabel {c['auto-relabel']} quarantine {c['quarantine']} mismatch {mm} countsOk {g['countsOk']} geom {geo}")
    print("\n".join(per))
    print("TOTAL", dict(tot))


if __name__ == "__main__":
    main()
