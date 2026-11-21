"""Train and quantize the tiny 7-keypoint LiteRT corner detector (`corner_keypoints.tflite`).

Usage:
    uv run --project tools/corner-model python tools/corner-model/train_corner_model.py
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import numpy as np
import tensorflow as tf
from PIL import Image

from render_cube import FixtureAssets, generate_dataset, load_live_fixtures

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT = REPO_ROOT / "app/src/camera/corner/assets/corner_keypoints.tflite"
DEFAULT_FIXTURES_LIVE = REPO_ROOT / "Fixtures/live"
DEFAULT_FIXTURES_CORNER = REPO_ROOT / "Fixtures/corner"


def _inverted_residual(x: tf.Tensor, out_ch: int, stride: int, expand: int, name: str) -> tf.Tensor:
    in_ch = int(x.shape[-1])
    mid_ch = in_ch * expand
    h = x
    if expand != 1:
        h = tf.keras.layers.Conv2D(mid_ch, 1, padding="same", use_bias=False, name=f"{name}_exp")(h)
        h = tf.keras.layers.BatchNormalization(name=f"{name}_exp_bn")(h)
        h = tf.keras.layers.ReLU(6.0, name=f"{name}_exp_act")(h)
    h = tf.keras.layers.DepthwiseConv2D(3, strides=stride, padding="same", use_bias=False, name=f"{name}_dw")(h)
    h = tf.keras.layers.BatchNormalization(name=f"{name}_dw_bn")(h)
    h = tf.keras.layers.ReLU(6.0, name=f"{name}_dw_act")(h)
    h = tf.keras.layers.Conv2D(out_ch, 1, padding="same", use_bias=False, name=f"{name}_proj")(h)
    h = tf.keras.layers.BatchNormalization(name=f"{name}_proj_bn")(h)
    if stride == 1 and in_ch == out_ch:
        h = tf.keras.layers.Add(name=f"{name}_add")([x, h])
    return h


def build_corner_net(img_size: int = 128) -> tf.keras.Model:
    """Compact depthwise-separable CNN with spatial-preserving 4x4 head (~150-350 KB FP16)."""
    inp = tf.keras.Input(shape=(img_size, img_size, 3), dtype=tf.float32, name="rgb_crop")

    # Stem: 128 -> 64
    x = tf.keras.layers.Conv2D(16, 3, strides=2, padding="same", use_bias=False, name="stem_conv")(inp)
    x = tf.keras.layers.BatchNormalization(name="stem_bn")(x)
    x = tf.keras.layers.ReLU(6.0, name="stem_act")(x)

    # Stage 1: 64 -> 32
    x = _inverted_residual(x, 24, stride=2, expand=2, name="s1_0")
    x = _inverted_residual(x, 24, stride=1, expand=2, name="s1_1")

    # Stage 2: 32 -> 16
    x = _inverted_residual(x, 40, stride=2, expand=3, name="s2_0")
    x = _inverted_residual(x, 40, stride=1, expand=3, name="s2_1")

    # Stage 3: 16 -> 8
    x = _inverted_residual(x, 64, stride=2, expand=3, name="s3_0")
    x = _inverted_residual(x, 64, stride=1, expand=3, name="s3_1")

    # Stage 4: 8 -> 4
    x = _inverted_residual(x, 96, stride=2, expand=3, name="s4_0")
    x = _inverted_residual(x, 96, stride=1, expand=3, name="s4_1")

    # Spatial-preserving head: keeps 4x4 grid coordinates intact instead of collapsing with GAP
    x = tf.keras.layers.Conv2D(32, 1, padding="same", use_bias=False, name="head_reduce")(x)
    x = tf.keras.layers.BatchNormalization(name="head_reduce_bn")(x)
    x = tf.keras.layers.ReLU(6.0, name="head_reduce_act")(x)
    x = tf.keras.layers.Flatten(name="head_flat")(x)  # 4 * 4 * 32 = 512
    x = tf.keras.layers.Dense(64, activation="relu6", name="head_fc1")(x)
    out = tf.keras.layers.Dense(15, activation="sigmoid", name="corner_output")(x)
    return tf.keras.Model(inputs=inp, outputs=out, name="cubentia_corner_keypoints_v1")


def corner_loss(y_true: tf.Tensor, y_pred: tf.Tensor) -> tf.Tensor:
    pres_true = y_true[:, 0]
    pres_pred = tf.clip_by_value(y_pred[:, 0], 1e-6, 1.0 - 1e-6)
    bce = -(pres_true * tf.math.log(pres_pred) + (1.0 - pres_true) * tf.math.log(1.0 - pres_pred))

    coord_true = y_true[:, 1:]
    coord_pred = y_pred[:, 1:]
    diff = tf.abs(coord_true - coord_pred)
    delta = 0.02
    huber = tf.where(diff <= delta, 0.5 * diff * diff / delta, diff - 0.5 * delta)
    coord_per_sample = tf.reduce_mean(huber, axis=-1)
    masked_coord = coord_per_sample * pres_true

    # Normalize coordinate loss by active positives in the batch for stable gradients
    pos_count = tf.maximum(tf.reduce_sum(pres_true), 1.0)
    coord_term = tf.reduce_sum(masked_coord) / pos_count
    return tf.reduce_mean(bce) + 35.0 * coord_term


def mean_pixel_error(y_true: tf.Tensor, y_pred: tf.Tensor) -> tf.Tensor:
    """Mean Euclidean keypoint error in 128px crop pixels over positive samples."""
    pres = y_true[:, 0]
    true_xy = tf.reshape(y_true[:, 1:], (-1, 7, 2)) * 128.0
    pred_xy = tf.reshape(y_pred[:, 1:], (-1, 7, 2)) * 128.0
    dist = tf.sqrt(tf.reduce_sum(tf.square(true_xy - pred_xy), axis=-1) + 1e-8)
    per_sample = tf.reduce_mean(dist, axis=-1) * pres
    return tf.reduce_sum(per_sample) / tf.maximum(tf.reduce_sum(pres), 1.0)


def presence_acc(y_true: tf.Tensor, y_pred: tf.Tensor) -> tf.Tensor:
    return tf.reduce_mean(tf.cast(tf.equal(y_true[:, 0] > 0.5, y_pred[:, 0] > 0.5), tf.float32))


KEYPOINT_FIXTURE_KIND = "corner-keypoints"


def is_keypoint_fixture(data: object) -> bool:
    """A keypoint fixture: the 10.19 recorder's `"kind": "corner-keypoints"` file, or a hand-written README-format file
    (`frame` + `label`). Color records (`corner-<hold>.json`, schema 3/4, with `labeledSamples`) and `group.json`
    manifests are not keypoint fixtures and are skipped."""
    if not isinstance(data, dict):
        return False
    if data.get("kind") == KEYPOINT_FIXTURE_KIND:
        return True
    return "kind" not in data and "label" in data and "labeledSamples" not in data and "pipeline" not in data


def load_corner_keypoint_fixtures(corner_dir: Path, img_size: int = 128) -> list[dict]:
    """Every keypoint fixture under `corner_dir` (contract in README.md) as `{"path", "device", "x", "y"}`.

    Each fixture names a `frame` image that is already the app's model input crop: square, upright, centred on the
    `CornerGuide` with half-size `guide.radius * 1.65` and rotation-corrected exactly as `CornerModelTransform`.
    `label` is `"positive"` (with 7 crop-normalized `keypoints` `[T, UL, LL, B, LR, UR, C]`, each `[u, v]` in
    [0, 1]) or `"negative"` (no keypoints). `detectorKeypoints` (diagnostics) are never used as labels. Color records
    and manifests are skipped; a malformed keypoint fixture stops the run instead of being skipped.
    """
    if not corner_dir.is_dir():
        return []
    loaded: list[dict] = []
    for meta in sorted(corner_dir.rglob("*.json")):
        def bad(reason: str) -> SystemExit:
            return SystemExit(f"Malformed corner fixture {meta}: {reason}")

        try:
            data = json.loads(meta.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
            raise bad(f"unreadable JSON ({error})") from error
        if not is_keypoint_fixture(data):
            continue
        img_name = data.get("frame")
        if not isinstance(img_name, str) or not img_name:
            raise bad("missing 'frame'")
        img_path = meta.parent / img_name
        if not img_path.is_file():
            raise bad(f"frame not found: {img_path}")
        label = data.get("label")
        kps = data.get("keypoints")
        target = np.zeros(15, dtype=np.float32)
        if label == "positive":
            if not isinstance(kps, list) or len(kps) != 7:
                raise bad("a positive needs 7 keypoints [T, UL, LL, B, LR, UR, C]")
            for idx, pt in enumerate(kps):
                if not isinstance(pt, list) or len(pt) != 2 or not all(isinstance(c, (int, float)) for c in pt):
                    raise bad(f"keypoint {idx} must be [u, v]")
                u, v = float(pt[0]), float(pt[1])
                if not (0.0 <= u <= 1.0 and 0.0 <= v <= 1.0):
                    raise bad(f"keypoint {idx} ({u}, {v}) is outside the crop; keypoints are crop-normalized")
                target[1 + idx * 2] = u
                target[2 + idx * 2] = v
            target[0] = 1.0
        elif label == "negative":
            if kps:
                raise bad("a negative must not carry keypoints")
        else:
            raise bad("'label' must be 'positive' or 'negative'")
        try:
            with Image.open(img_path) as im:
                if im.width != im.height:
                    raise bad(f"frame is {im.width}x{im.height}; it must be the square guide-centred upright crop")
                arr = np.asarray(im.convert("RGB").resize((img_size, img_size), Image.Resampling.BILINEAR), dtype=np.float32) / 255.0
        except OSError as error:
            raise bad(f"unreadable frame ({error})") from error
        device = data.get("device")
        model = device.get("model") if isinstance(device, dict) else None
        loaded.append({"path": str(meta), "device": model if isinstance(model, str) and model else "unknown", "x": arr, "y": target})
    return loaded


def load_optional_corner_fixtures(corner_dir: Path, img_size: int = 128) -> tuple[np.ndarray, np.ndarray] | None:
    """Optional fine-tune hook for real labeled `Fixtures/corner/` keypoint fixtures (see `load_corner_keypoint_fixtures`)."""
    loaded = load_corner_keypoint_fixtures(corner_dir, img_size)
    if not loaded:
        return None
    return np.stack([f["x"] for f in loaded], axis=0), np.stack([f["y"] for f in loaded], axis=0)


def export_tflite(model: tf.keras.Model, output_path: Path, quant: str = "fp16") -> int:
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    if quant == "fp16":
        converter.target_spec.supported_types = [tf.float16]
    tflite_bytes = converter.convert()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(tflite_bytes)
    return len(tflite_bytes)


def main() -> None:
    parser = argparse.ArgumentParser(description="Train Cubentia 7-keypoint corner LiteRT model.")
    parser.add_argument("--train-samples", type=int, default=24000)
    parser.add_argument("--val-samples", type=int, default=2400)
    parser.add_argument("--epochs", type=int, default=40)
    parser.add_argument("--batch-size", type=int, default=128)
    parser.add_argument("--seed", type=int, default=20261120)
    parser.add_argument("--quant", choices=("fp16", "int8_dynamic"), default="fp16")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--fixtures-live", type=Path, default=DEFAULT_FIXTURES_LIVE)
    parser.add_argument("--fixtures-corner", type=Path, default=DEFAULT_FIXTURES_CORNER)
    args = parser.parse_args()

    tf.keras.utils.set_random_seed(args.seed)
    gpus = tf.config.list_physical_devices("GPU")
    print(f"TensorFlow {tf.__version__} | GPUs: {gpus}")

    # Train on every Fixtures/live palette and face crop except two groups held out for out-of-distribution evaluation.
    train_assets = load_live_fixtures(
        args.fixtures_live,
        exclude_suffixes=("lenovo-tb128xu_stkb_studio-a", "sm-s721u1_stlb_studio-a"),
    )
    print(f"Loaded {len(train_assets.palettes)} train palettes, {len(train_assets.face_crops)} real face crops.")

    print(f"Generating {args.train_samples} training samples and {args.val_samples} validation samples...")
    x_train, y_train = generate_dataset(args.train_samples, seed=args.seed, assets=train_assets)
    x_val, y_val = generate_dataset(args.val_samples, seed=args.seed + 1, assets=train_assets)

    corner_real = load_optional_corner_fixtures(args.fixtures_corner)
    if corner_real is not None:
        rx, ry = corner_real
        print(f"Appending {len(rx)} real labeled Fixtures/corner/ frames to training set.")
        x_train = np.concatenate([x_train, rx], axis=0)
        y_train = np.concatenate([y_train, ry], axis=0)
    else:
        print(f"No labeled Fixtures/corner/ captures found at {args.fixtures_corner}; training on synthetic 3D + real Fixtures/live/ palettes/crops.")

    model = build_corner_net(128)
    print(f"Model parameters: {model.count_params():,}")

    steps_per_epoch = max(1, len(x_train) // args.batch_size)
    lr_schedule = tf.keras.optimizers.schedules.CosineDecay(
        initial_learning_rate=2.5e-3,
        decay_steps=args.epochs * steps_per_epoch,
        alpha=0.02,
    )
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=lr_schedule),
        loss=corner_loss,
        metrics=[presence_acc, mean_pixel_error],
    )

    artifacts_dir = Path(__file__).resolve().parent / "artifacts"
    artifacts_dir.mkdir(parents=True, exist_ok=True)
    best_weights = artifacts_dir / "best.weights.h5"

    callbacks = [
        tf.keras.callbacks.ModelCheckpoint(
            filepath=str(best_weights),
            monitor="val_mean_pixel_error",
            mode="min",
            save_best_only=True,
            save_weights_only=True,
            verbose=1,
        ),
    ]

    model.fit(
        x_train,
        y_train,
        validation_data=(x_val, y_val),
        epochs=args.epochs,
        batch_size=args.batch_size,
        callbacks=callbacks,
        verbose=2,
    )

    if best_weights.is_file():
        model.load_weights(str(best_weights))

    size_bytes = export_tflite(model, args.output, quant=args.quant)
    print(f"Exported {args.output} ({size_bytes:,} bytes = {size_bytes / 1024.0:.1f} KB, quant={args.quant})")


if __name__ == "__main__":
    main()
