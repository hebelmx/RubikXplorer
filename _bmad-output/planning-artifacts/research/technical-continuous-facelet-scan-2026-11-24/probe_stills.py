"""Count sticker proposals on the repository's square corner photos. Run from repo root."""

import argparse
import ast
import csv
import hashlib
from pathlib import Path
import sys

import cv2
import onnxruntime as ort


def count_proposals(session, path, cube_class):
    image = cv2.imread(str(path))
    if image is None or image.shape[0] != image.shape[1]:
        raise ValueError(f"Expected a square photo at {path}")
    tensor = cv2.dnn.blobFromImage(image, 1 / 255.0, (640, 640), swapRB=True)
    predictions = session.run(["output0"], {"images": tensor})[0][0].T
    classes = predictions[:, 4:].argmax(axis=1)
    scores = predictions[:, 4:].max(axis=1)
    keep = (scores >= 0.35) & (classes != cube_class)
    boxes = predictions[keep, :4].copy()
    boxes[:, :2] -= boxes[:, 2:] / 2
    indices = cv2.dnn.NMSBoxes(boxes.tolist(), scores[keep].tolist(), 0.35, 0.35)
    return len(indices)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", type=Path)
    args = parser.parse_args()
    digest = hashlib.sha256(args.model.read_bytes()).hexdigest()
    if digest != "f158df4560063686cf90483e21bc611790bc63f6fc2b343a7a4e1dd6b5063dd3":
        raise ValueError("Expected the ONNX export pinned in the research report")
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    session = ort.InferenceSession(str(args.model), options, providers=["CPUExecutionProvider"])
    classes = ast.literal_eval(session.get_modelmeta().custom_metadata_map["names"])
    cube_class = next(i for i, name in classes.items() if name == "rubik_cube")
    photos = sorted(Path("Fixtures/corner").glob("*/corner-*.jpg"))
    if not photos:
        parser.error("No corner photos found. Run from the repository root.")
    writer = csv.writer(sys.stdout, lineterminator="\n")
    writer.writerow(["photo", "sticker_proposals"])
    for path in photos:
        writer.writerow([path, count_proposals(session, path, cube_class)])


if __name__ == "__main__":
    main()
