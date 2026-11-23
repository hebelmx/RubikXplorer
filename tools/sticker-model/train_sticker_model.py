"""Train, export and card the Story 10.23 shadow sticker reader (`sticker_reader.tflite`).

Usage:
    uv run --project tools/sticker-model python tools/sticker-model/train_sticker_model.py

Needs `assemble.py` first. Refuses to train when the committed `split.json` fails any check or is stale. Trains a small
seeded CNN on the training side only (real training faces, augmented, plus `synth_faces.py` faces), exports it fp16 to
`app/src/camera/debug/sticker/assets/sticker_reader.tflite` (fails at 500 KB or more), picks the confidence threshold
tau on the tune side, and writes `model-card.json` and `docs/evidence/10-23/model-provenance.md`.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import platform
import sys
from pathlib import Path

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import numpy as np
import PIL
import tensorflow as tf
from PIL import Image

import split as split_tool
import synth_faces
from sticker_common import (
    ARTIFACTS,
    COLOR_INDEX,
    COLOR_ORDER,
    DEFAULT_SEED,
    EVIDENCE,
    HOLDOUT_FILE,
    INPUT_SIZE,
    MODEL_CARD,
    MODEL_OUTPUT,
    MODEL_SIZE_LIMIT,
    REPO_ROOT,
    SPLIT_FILE,
    TAU_GRID,
    dump_json,
    rotate_labels,
    sha256_bytes,
)

DEFAULTS = {
    "synthetic": 36000,
    "real_copies": 120,
    "epochs": 24,
    "batch_size": 256,
    "learning_rate": 3e-3,
}


# --- data -----------------------------------------------------------------------------------------------------------


def load_manifest(artifacts: Path = ARTIFACTS) -> dict:
    path = artifacts / "manifest.json"
    if not path.is_file():
        raise SystemExit(f"{path} is missing: run assemble.py first")
    return json.loads(path.read_text())


def check_split(manifest: dict) -> None:
    """Refuses to train on a split that fails a check or does not match the manifest."""
    holdout = json.loads(HOLDOUT_FILE.read_text())
    committed = json.loads(SPLIT_FILE.read_text())
    failures = split_tool.verify(committed, holdout)
    if dump_json(split_tool.build(manifest, holdout)) != SPLIT_FILE.read_text():
        failures.append(f"{SPLIT_FILE.name} is stale against the manifest: re-run assemble.py")
    if failures:
        raise SystemExit("Split checks failed; refusing to train:\n  " + "\n  ".join(failures))


def real_faces(manifest: dict, side: str, artifacts: Path = ARTIFACTS, groups: set[str] | None = None) -> tuple[np.ndarray, np.ndarray, list[dict]]:
    records = [r for r in manifest["items"] if r["side"] == side and (groups is None or r["group"] in groups)]
    images = np.stack([np.asarray(Image.open(artifacts / r["crop"]).convert("RGB"), dtype=np.uint8) for r in records]) if records else np.empty((0, INPUT_SIZE, INPUT_SIZE, 3), np.uint8)
    labels = np.array([[COLOR_INDEX[c] for c in r["labels"]] for r in records], dtype=np.int64).reshape(-1, 9)
    return images, labels, records


def train_palettes(manifest: dict, groups: set[str]) -> list[dict[str, np.ndarray]]:
    """The default palette plus one per training group that recorded calibration centres (no tune/held-out palette)."""
    palettes = synth_faces.default_palettes()
    seen = set()
    for record in sorted(manifest["items"], key=lambda r: r["item"]):
        if record["group"] not in groups or record["group"] in seen or not record["source"].endswith(".jpg"):
            continue
        json_path = (REPO_ROOT / record["source"]).with_suffix(".json")
        if not json_path.is_file():
            continue
        centers = json.loads(json_path.read_text()).get("calibration", {}).get("centers")
        if centers and all(name in centers for name in COLOR_ORDER):
            palettes.append(synth_faces.palette_from_centers(centers))
            seen.add(record["group"])
    return palettes


def augment_real(images: np.ndarray, labels: np.ndarray, copies: int, seed: int) -> tuple[np.ndarray, np.ndarray]:
    """Seeded copies of the real training faces: quarter turns (labels turned with them), light, tint, noise."""
    rng = np.random.default_rng(seed)
    out_x = np.empty((len(images) * copies, INPUT_SIZE, INPUT_SIZE, 3), dtype=np.uint8)
    out_y = np.empty((len(images) * copies, 9), dtype=np.int64)
    n = 0
    for image, label in zip(images, labels):
        for _ in range(copies):
            turns = int(rng.integers(0, 4))
            arr = np.rot90(image.astype(np.float32), turns)
            gain = rng.uniform(0.45, 1.2)
            tint = np.array([rng.uniform(0.9, 1.1), 1.0, rng.uniform(0.88, 1.12)], dtype=np.float32)
            arr = arr * gain * tint + rng.uniform(-10, 10)
            if rng.random() < 0.5:
                dx, dy = (int(v) for v in rng.integers(-4, 5, size=2))
                arr = np.roll(arr, (dy, dx), axis=(0, 1))
            arr = arr + rng.normal(0.0, rng.uniform(1.0, 6.0), size=arr.shape)
            out_x[n] = np.clip(np.rint(arr), 0, 255).astype(np.uint8)
            out_y[n] = rotate_labels(label, turns)
            n += 1
    return out_x, out_y


# --- model ----------------------------------------------------------------------------------------------------------


def _block(x, out_ch: int, stride: int, expand: int, name: str):
    in_ch = int(x.shape[-1])
    h = x
    if expand != 1:
        h = tf.keras.layers.Conv2D(in_ch * expand, 1, use_bias=False, name=f"{name}_exp")(h)
        h = tf.keras.layers.BatchNormalization(name=f"{name}_exp_bn")(h)
        h = tf.keras.layers.ReLU(6.0, name=f"{name}_exp_act")(h)
    h = tf.keras.layers.DepthwiseConv2D(3, strides=stride, padding="same", use_bias=False, name=f"{name}_dw")(h)
    h = tf.keras.layers.BatchNormalization(name=f"{name}_dw_bn")(h)
    h = tf.keras.layers.ReLU(6.0, name=f"{name}_dw_act")(h)
    h = tf.keras.layers.Conv2D(out_ch, 1, use_bias=False, name=f"{name}_proj")(h)
    h = tf.keras.layers.BatchNormalization(name=f"{name}_proj_bn")(h)
    if stride == 1 and in_ch == out_ch:
        h = tf.keras.layers.Add(name=f"{name}_add")([x, h])
    return h


def build_model() -> tf.keras.Model:
    """96x96 RGB -> per-cell features on a 12x12 map pooled to the 3x3 grid, joined with a whole-face context vector
    (the face's light and white balance), -> [9, 6] softmax in Kotlin cell order."""
    inp = tf.keras.Input(shape=(INPUT_SIZE, INPUT_SIZE, 3), dtype=tf.float32, name="face_rgb")
    x = tf.keras.layers.Conv2D(16, 3, strides=2, padding="same", use_bias=False, name="stem")(inp)  # 48
    x = tf.keras.layers.BatchNormalization(name="stem_bn")(x)
    x = tf.keras.layers.ReLU(6.0, name="stem_act")(x)
    x = _block(x, 24, 2, 2, "b1")  # 24
    x = _block(x, 24, 1, 2, "b2")
    x = _block(x, 40, 2, 3, "b3")  # 12
    x = _block(x, 40, 1, 3, "b4")
    x = _block(x, 64, 1, 3, "b5")
    cells = tf.keras.layers.AveragePooling2D(4, name="cell_pool")(x)  # 3x3x64
    context = tf.keras.layers.GlobalAveragePooling2D(keepdims=True, name="face_pool")(x)  # 1x1x64
    context = tf.keras.layers.Conv2D(32, 1, activation="relu", name="face_context")(context)
    context = tf.keras.layers.UpSampling2D(3, name="face_broadcast")(context)  # 3x3x32
    h = tf.keras.layers.Concatenate(name="cell_and_face")([cells, context])
    h = tf.keras.layers.Conv2D(64, 1, activation="relu", name="cell_head")(h)
    h = tf.keras.layers.Dropout(0.1, name="cell_dropout")(h)
    h = tf.keras.layers.Conv2D(len(COLOR_ORDER), 1, name="cell_logits")(h)
    h = tf.keras.layers.Reshape((9, len(COLOR_ORDER)), name="cells")(h)
    out = tf.keras.layers.Softmax(axis=-1, name="sticker_probs")(h)
    return tf.keras.Model(inp, out, name="sticker_reader")


def fit(x_u8: np.ndarray, y: np.ndarray, seed: int, epochs: int, batch_size: int, learning_rate: float, verbose: int = 2) -> tf.keras.Model:
    tf.keras.utils.set_random_seed(seed)
    model = build_model()
    steps = max(1, math.ceil(len(x_u8) / batch_size))
    schedule = tf.keras.optimizers.schedules.CosineDecay(learning_rate, decay_steps=epochs * steps, alpha=0.02)
    model.compile(optimizer=tf.keras.optimizers.Adam(schedule), loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    data = (
        tf.data.Dataset.from_tensor_slices((x_u8, y))
        .shuffle(len(x_u8), seed=seed, reshuffle_each_iteration=True)
        .batch(batch_size)
        .map(lambda a, b: (tf.cast(a, tf.float32) / 255.0, b))
        .prefetch(2)
    )
    model.fit(data, epochs=epochs, verbose=verbose)
    return model


def training_set(manifest: dict, seed: int, synthetic: int, real_copies: int, groups: set[str] | None = None, artifacts: Path = ARTIFACTS):
    """Real training faces (all training groups, or only `groups`) augmented, plus a fixed synthetic set."""
    train_groups = {r["group"] for r in manifest["items"] if r["side"] == "train"}
    if groups is not None:
        train_groups &= groups
    rx, ry, records = real_faces(manifest, "train", artifacts, train_groups)
    ax, ay = augment_real(rx, ry, real_copies, seed + 1) if len(rx) else (rx, ry)
    # Synthetic palettes from every training group (held constant across learning-curve subsets).
    all_train = {r["group"] for r in manifest["items"] if r["side"] == "train"}
    sx, sy = synth_faces.generate(synthetic, seed + 2, train_palettes(manifest, all_train))
    x = np.concatenate([sx, ax]) if len(ax) else sx
    y = np.concatenate([sy, ay]) if len(ay) else sy
    return x, y, {"realFaces": len(records), "realGroups": sorted(train_groups), "realCopies": real_copies, "synthetic": synthetic}


# --- export, tau, card ----------------------------------------------------------------------------------------------


def export_bytes(model: tf.keras.Model, quant: str = "fp16") -> bytes:
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    if quant == "fp16":
        converter.target_spec.supported_types = [tf.float16]
    return converter.convert()


def check_size(size: int) -> None:
    if size >= MODEL_SIZE_LIMIT:
        raise SystemExit(f"sticker_reader.tflite would be {size} bytes; the limit is under {MODEL_SIZE_LIMIT} bytes (500 KB)")


def tflite_probs(model_bytes: bytes, images_u8: np.ndarray) -> np.ndarray:
    """[N, 9, 6] probabilities from the exported model, one face at a time as the app runs it."""
    interpreter = tf.lite.Interpreter(model_content=model_bytes, num_threads=1)
    interpreter.allocate_tensors()
    inp = interpreter.get_input_details()[0]
    out = interpreter.get_output_details()[0]
    probs = np.empty((len(images_u8), 9, len(COLOR_ORDER)), dtype=np.float32)
    for i, image in enumerate(images_u8):
        interpreter.set_tensor(inp["index"], (image.astype(np.float32) / 255.0)[None])
        interpreter.invoke()
        probs[i] = interpreter.get_tensor(out["index"])[0]
    return probs


def keras_probs(model: tf.keras.Model, images_u8: np.ndarray) -> np.ndarray:
    return model.predict(images_u8.astype(np.float32) / 255.0, verbose=0, batch_size=64)


def confidently_wrong(probs: np.ndarray, labels: np.ndarray, tau: float) -> int:
    top = probs.argmax(axis=-1)
    return int(((probs.max(axis=-1) >= tau) & (top != labels)).sum())


def choose_tau(probs: np.ndarray, labels: np.ndarray) -> tuple[float, dict]:
    """Smallest tau on the 0.50-0.99 grid with 0 confidently wrong tune stickers; 0.99 when none has."""
    for tau in TAU_GRID:
        if confidently_wrong(probs, labels, tau) == 0:
            return tau, {"zeroConfidentlyWrongReached": True}
    return TAU_GRID[-1], {"zeroConfidentlyWrongReached": False}


def tune_summary(probs: np.ndarray, labels: np.ndarray, tau: float) -> dict:
    top = probs.argmax(axis=-1)
    confident = probs.max(axis=-1) >= tau
    return {
        "stickers": int(labels.size),
        "confidentCorrect": int((confident & (top == labels)).sum()),
        "uncertain": int((~confident).sum()),
        "confidentlyWrong": int((confident & (top != labels)).sum()),
        "top1Correct": int((top == labels).sum()),
    }


def tool_versions() -> dict:
    return {
        "python": platform.python_version(),
        "tensorflow": tf.__version__,
        "converter": "tf.lite.TFLiteConverter.from_keras_model (bundled with TensorFlow)",
        "numpy": np.__version__,
        "pillow": PIL.__version__,
        "platform": f"{platform.system()} {platform.machine()}",
        "gpu": bool(tf.config.list_physical_devices("GPU")),
    }


def write_provenance(card: dict) -> None:
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    recipe = card["recipe"]
    tune = card["tune"]
    versions = card["tools"]
    lines = [
        "# Story 10.23: shadow sticker reader model provenance",
        "",
        "Generated by `tools/sticker-model/train_sticker_model.py` (re-run it; do not edit by hand). Same fields as the corner",
        "model's record (`docs/evidence/10-18/litert-model-evaluation.md`, Story 10.18).",
        "",
        f"- **Model artifact:** [`app/src/camera/debug/sticker/assets/sticker_reader.tflite`](../../../app/src/camera/debug/sticker/assets/sticker_reader.tflite) (debug camera source set only; never in release, benchmark or `cornerR8`).",
        f"- **Artifact identity:** SHA-256 `{card['sha256']}` ({card['bytes']:,} bytes = {card['bytes'] / 1024:.1f} KB, limit < 500 KB).",
        f"- **Contract:** input `[1, {card['inputSize']}, {card['inputSize']}, 3]` float32 RGB in [0, 1] (the unwarped face, YUV->RGB as `CornerModelTransform.fillRgbInput`); output `{card['output']}` softmax per sticker in Kotlin cell order (row-major); colour order {', '.join(card['colorOrder'])}.",
        f"- **Confidence rule:** CONFIDENT when the top-1 probability >= tau = **{card['tau']}**, else UNCERTAIN. tau is the smallest value on the 0.50-0.99 grid giving 0 confidently wrong stickers on the tune split"
        + (
            ""
            if card["tauRule"]["zeroConfidentlyWrongReached"]
            else " (none did, so the spec's fallback 0.99 applies: tau sits at the ceiling of its grid and the rule cannot raise it further)"
        )
        + f": tune {tune['stickers']} stickers, {tune['confidentCorrect']} confident correct, {tune['uncertain']} uncertain, {tune['confidentlyWrong']} confidently wrong.",
        f"- **Recipe:** seed `{card['seed']}`, quant `{card['quant']}` (`Optimize.DEFAULT` + float16 supported type), {card['parameters']:,} parameters; {recipe['epochs']} epochs, batch {recipe['batchSize']}, Adam with cosine decay from {recipe['learningRate']}; training side only: {recipe['realFaces']} real faces from {len(recipe['realGroups'])} groups x {recipe['realCopies']} seeded augmented copies (quarter turns with labels turned, light, tint, shift, noise) + {recipe['synthetic']:,} `synth_faces.py` faces (palettes from training groups only). Tune faces only choose tau; held-out faces are never read in training.",
        f"- **Tools:** Python {versions['python']}, TensorFlow {versions['tensorflow']} and its bundled `TFLiteConverter`, NumPy {versions['numpy']}, Pillow {versions['pillow']}, {versions['platform']}{' + CUDA GPU (`tensorflow[and-cuda]`)' if versions['gpu'] else ' (CPU)'}.",
        "- **Reproducibility:** dataset assembly is byte-identical across runs; training is seeded, but GPU training is not bit-exact, so a retrain reproduces the recipe and its metrics, not the SHA-256 above. Re-run `train_sticker_model.py` and then update `StickerModelContract` (tau, SHA-256) to match `model-card.json`; `StickerModelContractTest` fails until they match.",
        "- **Runtime:** `com.google.ai.edge.litert` (already in debug builds through the corner model), XNNPACK CPU, 4 threads, no GPU or NNAPI delegate, on the reader's own single-thread executor.",
        "",
    ]
    (EVIDENCE / "model-provenance.md").write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument("--synthetic", type=int, default=DEFAULTS["synthetic"])
    parser.add_argument("--real-copies", type=int, default=DEFAULTS["real_copies"])
    parser.add_argument("--epochs", type=int, default=DEFAULTS["epochs"])
    parser.add_argument("--batch-size", type=int, default=DEFAULTS["batch_size"])
    parser.add_argument("--learning-rate", type=float, default=DEFAULTS["learning_rate"])
    parser.add_argument("--quant", choices=("fp16",), default="fp16")
    parser.add_argument("--output", type=Path, default=MODEL_OUTPUT)
    parser.add_argument("--card", type=Path, default=MODEL_CARD)
    args = parser.parse_args()

    manifest = load_manifest()
    check_split(manifest)
    print(f"TensorFlow {tf.__version__} | GPUs: {tf.config.list_physical_devices('GPU')}")
    x, y, recipe = training_set(manifest, args.seed, args.synthetic, args.real_copies)
    print(f"Training on {len(x):,} faces ({recipe['realFaces']} real x {args.real_copies} + {args.synthetic:,} synthetic).")
    model = fit(x, y, args.seed, args.epochs, args.batch_size, args.learning_rate)
    model_bytes = export_bytes(model, args.quant)
    check_size(len(model_bytes))
    tx, ty, _ = real_faces(manifest, "tune")
    probs = tflite_probs(model_bytes, tx)
    tau, rule = choose_tau(probs, ty)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(model_bytes)
    card = {
        "schemaVersion": 1,
        "model": "sticker_reader.tflite",
        "story": "10.23",
        "inputSize": INPUT_SIZE,
        "input": [1, INPUT_SIZE, INPUT_SIZE, 3],
        "output": [1, 9, len(COLOR_ORDER)],
        "colorOrder": list(COLOR_ORDER),
        "tau": tau,
        "tauGrid": [TAU_GRID[0], TAU_GRID[-1], 0.01],
        "tauRule": rule,
        "tune": tune_summary(probs, ty, tau),
        "sha256": sha256_bytes(model_bytes),
        "bytes": len(model_bytes),
        "seed": args.seed,
        "quant": args.quant,
        "parameters": int(model.count_params()),
        "recipe": {**recipe, "epochs": args.epochs, "batchSize": args.batch_size, "learningRate": args.learning_rate},
        "tools": tool_versions(),
    }
    args.card.write_text(dump_json(card), encoding="utf-8")
    write_provenance(card)
    print(f"Exported {args.output} ({len(model_bytes):,} bytes), tau={tau}, tune={card['tune']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
