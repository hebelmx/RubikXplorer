"""Reproduce the nine-photo center-in-polygon check documented in facelets.md."""

import hashlib
import json
from concurrent.futures import ThreadPoolExecutor
from urllib.request import Request, urlopen

import cv2
import numpy as np
import onnxruntime as ort
import yaml

DATA_REV = "59141945cb3657f5478c4cb399a2734e1eec804e"
DATASET = "seandavidreed/rubiks_cube_segmentation"
DATA_ROOT = f"https://huggingface.co/datasets/{DATASET}"
MODEL_REV = "b2ed9246c3259dd684b69e36170292bc88a2e02c"
MODEL = (
    "https://raw.githubusercontent.com/Super-Morse/rubiks-visual-detector/"
    f"{MODEL_REV}/app/infrastructure/models/best.onnx"
)
MODEL_SHA = "f158df4560063686cf90483e21bc611790bc63f6fc2b343a7a4e1dd6b5063dd3"
COLORS = ("W", "Y", "G", "B", "R", "O")
TARGET = 640
THRESHOLD = 0.35


def download(url):
    request = Request(url, headers={"User-Agent": "facelet-research/1.0"})
    with urlopen(request, timeout=45) as response:
        return response.read()


def read_example(path, names):
    image_bytes = download(f"{DATA_ROOT}/resolve/{DATA_REV}/{path}")
    image = cv2.imdecode(np.frombuffer(image_bytes, np.uint8), cv2.IMREAD_COLOR)
    height, width = image.shape[:2]
    label_path = path.replace("/images/", "/labels/").replace(".jpg", ".txt")
    label_text = download(f"{DATA_ROOT}/raw/{DATA_REV}/{label_path}").decode()
    polygons = []
    for line in label_text.splitlines():
        category, *numbers = line.split()
        vertices = np.asarray(numbers, dtype=np.float32).reshape(-1, 2)
        vertices *= np.asarray([width, height], dtype=np.float32)
        color = names[int(category)].split("_")[0]
        polygons.append((color, vertices))
    return image, polygons


def predict(session, image):
    height, width = image.shape[:2]
    scale = min(TARGET / width, TARGET / height)
    new_width, new_height = int(width * scale), int(height * scale)
    pad_x, pad_y = (TARGET - new_width) / 2, (TARGET - new_height) / 2
    left, top = int(pad_x), int(pad_y)
    canvas = np.zeros((TARGET, TARGET, 3), dtype=np.uint8)
    canvas[top : top + new_height, left : left + new_width] = cv2.resize(
        image, (new_width, new_height), interpolation=cv2.INTER_LINEAR
    )
    rgb = cv2.cvtColor(canvas, cv2.COLOR_BGR2RGB)
    tensor = np.ascontiguousarray((rgb.astype(np.float32) / 255).transpose(2, 0, 1)[None])
    rows = session.run(None, {session.get_inputs()[0].name: tensor})[0].squeeze(0).T
    classes = rows[:, 4:].argmax(axis=1)
    scores = rows[:, 4:].max(axis=1)
    selected = np.flatnonzero((scores >= THRESHOLD) & (classes < len(COLORS)))
    if not len(selected):
        return []
    boxes = rows[selected, :4].copy()
    boxes[:, :2] -= boxes[:, 2:] / 2
    keep = cv2.dnn.NMSBoxes(boxes.tolist(), scores[selected].tolist(), THRESHOLD, THRESHOLD)
    indices = selected[np.asarray(keep, dtype=int).reshape(-1)]
    predictions = []
    for idx in sorted(indices, key=lambda k: -scores[k]):
        center = (float((rows[idx, 0] - pad_x) / scale), float((rows[idx, 1] - pad_y) / scale))
        predictions.append((COLORS[classes[idx]], center))
    return predictions


def evaluate(predictions, polygons):
    matched = set()
    correct = 0
    for predicted_color, center in predictions:
        hits = [
            index for index, (_, vertices) in enumerate(polygons)
            if cv2.pointPolygonTest(vertices, center, False) >= 0
        ]
        if len(hits) == 1 and hits[0] not in matched:
            index = hits[0]
            matched.add(index)
            correct += int(predicted_color == polygons[index][0])
    return np.asarray([len(polygons), len(predictions), len(matched), correct])


def main():
    weights = download(MODEL)
    if hashlib.sha256(weights).hexdigest() != MODEL_SHA:
        raise ValueError("Model checksum differs from the reviewed artifact")
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    session = ort.InferenceSession(weights, options, providers=["CPUExecutionProvider"])
    names = yaml.safe_load(download(f"{DATA_ROOT}/raw/{DATA_REV}/data.yaml"))["names"]
    listing = f"https://huggingface.co/api/datasets/{DATASET}/tree/{DATA_REV}/test/images"
    paths = sorted(entry["path"] for entry in json.loads(download(listing)))
    totals = np.zeros(4, dtype=int)
    with ThreadPoolExecutor(max_workers=4) as pool:
        examples = [pool.submit(read_example, path, names) for path in paths]
        for path, example in zip(paths, examples):
            image, polygons = example.result()
            counts = evaluate(predict(session, image), polygons)
            print(path.split("/")[-1].split(".rf.")[0], *counts)
            totals += counts
    print("total", *totals)
    print(f"ONNX Runtime {ort.__version__}, OpenCV {cv2.__version__}, NumPy {np.__version__}")


if __name__ == "__main__":
    main()
