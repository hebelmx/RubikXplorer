#!/usr/bin/env python3
"""Offline color-only evaluation for explicitly labeled S3/S4 JPEG fixtures."""
import argparse
import json
import math
from pathlib import Path
from statistics import median

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "Fixtures/labeled/photo-evaluation.json"
RESULT = ROOT / "Fixtures/labeled/photo-evaluation-results.json"
OVERLAY = ROOT / "Fixtures/labeled/photo-evaluation-overlay.jpg"
CALIBRATION_OVERLAY = ROOT / "Fixtures/labeled/photo-calibration-overlay.jpg"
PATCH_RADIUS = 3
RING_FRACTION = 0.24


def rgb_to_lab(rgb):
    channels = [v / 255.0 for v in rgb]
    linear = [v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in channels]
    r, g, b = linear
    x = (r * 0.4124564 + g * 0.3575761 + b * 0.1804375) / 0.95047
    y = r * 0.2126729 + g * 0.7151522 + b * 0.0721750
    z = (r * 0.0193339 + g * 0.1191920 + b * 0.9503041) / 1.08883
    f = lambda v: v ** (1 / 3) if v > 0.0088564517 else 7.787037 * v + 16 / 116
    fx, fy, fz = f(x), f(y), f(z)
    return (max(0.0, min(100.0, 116 * fy - 16)), 500 * (fx - fy), 200 * (fy - fz))


def patch_rgb(image, x, y):
    px = image.convert("RGB").load()
    values = [[], [], []]
    cx, cy = round(x), round(y)
    for yy in range(max(0, cy - PATCH_RADIUS), min(image.height, cy + PATCH_RADIUS + 1)):
        for xx in range(max(0, cx - PATCH_RADIUS), min(image.width, cx + PATCH_RADIUS + 1)):
            for channel, value in enumerate(px[xx, yy]):
                values[channel].append(value)
    return tuple(median(v) for v in values)


def ring_lab(image, cx, cy, dx, dy):
    labs = []
    for sx, sy in ((-1,-1), (-1,1), (1,-1), (1,1)):
        rgb = patch_rgb(image, cx + sx * dx * RING_FRACTION, cy + sy * dy * RING_FRACTION)
        labs.append(rgb_to_lab(rgb))
    return tuple(median([lab[i] for lab in labs]) for i in range(3))


def face_centers(image, rect):
    left, top, right, bottom = rect
    dx = (right - left) * image.width / 3
    dy = (bottom - top) * image.height / 3
    return [(image.width * (left + (col + .5) * (right-left) / 3),
             image.height * (top + (row + .5) * (bottom-top) / 3), dx, dy)
            for row in range(3) for col in range(3)]


def load_rgb(path):
    with Image.open(path) as image:
        return image.convert("RGB").copy()


def validate_manifest(manifest):
    colors = {"WHITE", "YELLOW", "GREEN", "BLUE", "RED", "ORANGE"}
    if manifest.get("schemaVersion") != 1 or not isinstance(manifest.get("groups"), list):
        raise ValueError("manifest must have schemaVersion 1 and a groups list")
    for group in manifest["groups"]:
        name = group.get("cubeDevice", "<unnamed group>")
        calibrations = group.get("calibration", [])
        if len(calibrations) != 6 or {item.get("color") for item in calibrations} != colors:
            raise ValueError(f"{name}: expected one calibration image for each of six colors")
        if len(group.get("samples", [])) != 6:
            raise ValueError(f"{name}: expected six S4 scoring images")
        for item in calibrations:
            point = item.get("point")
            if item.get("color") not in colors or not isinstance(point, list) or len(point) != 2 or any(not 0 < v < 1 for v in point):
                raise ValueError(f"{item.get('image')}: invalid color or normalized center point")
            if not (ROOT / item["image"]).is_file():
                raise ValueError(f"{item['image']}: calibration image is missing")
        for item in group["samples"]:
            rect, truth = item.get("rect"), item.get("truth")
            if not isinstance(rect, list) or len(rect) != 4 or any(not 0 <= v <= 1 for v in rect) or rect[0] >= rect[2] or rect[1] >= rect[3]:
                raise ValueError(f"{item.get('image')}: invalid normalized grid rectangle")
            if not isinstance(truth, list) or len(truth) != 9 or any(color not in colors for color in truth):
                raise ValueError(f"{item.get('image')}: truth must contain nine explicit color labels")
            if not (ROOT / item["image"]).is_file():
                raise ValueError(f"{item['image']}: scoring image is missing")


def run():
    manifest = json.loads(MANIFEST.read_text())
    validate_manifest(manifest)
    output = {"schemaVersion": 1, "scope": "offline JPEG sticker color classification only; no face identity, orientation, state validity, lighting attribution, or live CameraX claim", "groups": []}
    overlay_tiles = []
    calibration_tiles = []
    for group in manifest["groups"]:
        centers = {}
        for item in group["calibration"]:
            image = load_rgb(ROOT / item["image"])
            x, y = item["point"]
            # Monochrome face center sticker. Four offset patches avoid printed logos and the sticker's edges.
            lab = ring_lab(image, x * image.width, y * image.height, image.width * .14, image.height * .14)
            centers[item["color"]] = lab
            tile = image.copy()
            draw = ImageDraw.Draw(tile)
            cx, cy = x * tile.width, y * tile.height
            for sx, sy in ((-1,-1), (-1,1), (1,-1), (1,1)):
                px = round(cx + sx * tile.width * .14 * RING_FRACTION)
                py = round(cy + sy * tile.height * .14 * RING_FRACTION)
                draw.ellipse((px-18, py-18, px+18, py+18), outline=(0,255,255), width=10)
            tile.thumbnail((220,165))
            calibration_tiles.append((item["image"], tile))
        if set(centers) != {"WHITE", "YELLOW", "GREEN", "BLUE", "RED", "ORANGE"}:
            raise ValueError(f"{group['cubeDevice']}: calibration must cover all six colors")
        correct = total = 0
        samples = []
        for item in group["samples"]:
            image = load_rgb(ROOT / item["image"])
            points = face_centers(image, item["rect"])
            predictions = []
            for x, y, dx, dy in points:
                lab = ring_lab(image, x, y, dx, dy)
                pred = min(centers, key=lambda color: sum((lab[i]-centers[color][i])**2 for i in range(3)))
                predictions.append(pred)
            matches = sum(pred == truth for pred, truth in zip(predictions, item["truth"]))
            correct += matches
            total += 9
            samples.append({"image": item["image"], "correct": matches, "total": 9, "predicted": predictions, "truth": item["truth"]})
            tile = image.copy()
            draw = ImageDraw.Draw(tile)
            l,t,r,b = item["rect"]
            draw.rectangle((round(l*tile.width),round(t*tile.height),round(r*tile.width),round(b*tile.height)),outline=(255,0,255),width=8)
            for (x,y,_,_), pred, truth in zip(points,predictions,item["truth"]):
                color=(0,220,0) if pred == truth else (255,30,30)
                rr=max(9,round(tile.width*.004))
                draw.ellipse((round(x)-rr,round(y)-rr,round(x)+rr,round(y)+rr),outline=color,width=8)
                for sx, sy in ((-1,-1), (-1,1), (1,-1), (1,1)):
                    px=round(x+sx*dx*RING_FRACTION); py=round(y+sy*dy*RING_FRACTION)
                    draw.ellipse((px-12,py-12,px+12,py+12),fill=(0,220,255))
            tile.thumbnail((440,330))
            overlay_tiles.append((item["image"],tile))
        output["groups"].append({"cubeDevice": group["cubeDevice"], "calibrationImages": [i["image"] for i in group["calibration"]], "calibrationCentersLab": {color: [round(v, 5) for v in lab] for color, lab in sorted(centers.items())}, "scoreImages": [s["image"] for s in samples], "excluded": group["excluded"], "correct": correct, "total": total, "accuracyPercent": round(correct * 100 / total, 4), "samples": samples})
    all_correct=sum(g["correct"] for g in output["groups"])
    all_total=sum(g["total"] for g in output["groups"])
    output["aggregate"]={"correct":all_correct,"total":all_total,"accuracyPercent":round(all_correct*100/all_total,4)}
    # Four columns by six rows for every scored image.
    sheet=Image.new("RGB",(900,1140),"white"); draw=ImageDraw.Draw(sheet)
    for i,(name,tile) in enumerate(overlay_tiles):
        tile.thumbnail((220,165)); x=(i%4)*225; y=(i//4)*190
        sheet.paste(tile,(x,y+20)); draw.text((x+2,y+2),Path(name).stem,fill="black")
    cal_sheet=Image.new("RGB",(900,1140),"white"); cal_draw=ImageDraw.Draw(cal_sheet)
    for i,(name,tile) in enumerate(calibration_tiles):
        x=(i%4)*225; y=(i//4)*190
        cal_sheet.paste(tile,(x,y+20)); cal_draw.text((x+2,y+2),Path(name).stem,fill="black")
    return output,sheet,cal_sheet


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--check",action="store_true")
    args=parser.parse_args()
    result,sheet,calibration_sheet=run()
    rendered=json.dumps(result,indent=2)+"\n"
    if args.check:
        if not RESULT.exists() or RESULT.read_text()!=rendered:
            raise SystemExit("photo evaluation result differs; regenerate without --check")
        print("photo evaluation output matches")
        return
    RESULT.write_text(rendered)
    sheet.save(OVERLAY,quality=92)
    calibration_sheet.save(CALIBRATION_OVERLAY,quality=92)
    print(f"wrote {RESULT.relative_to(ROOT)}, {OVERLAY.relative_to(ROOT)}, and {CALIBRATION_OVERLAY.relative_to(ROOT)}")
    print(json.dumps(result["aggregate"],sort_keys=True))

if __name__ == "__main__":
    main()
