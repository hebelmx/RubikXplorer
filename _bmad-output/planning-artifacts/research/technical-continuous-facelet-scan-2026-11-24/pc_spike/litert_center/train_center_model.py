"""Train and export Cubentia's clean-room LiteRT sticker-center heatmap detector (`sticker_centers.tflite`).

Architecture: Compact MobileNetV2 inverted-residual FPN (256x256x3 RGB -> 64x64x3 [hm_prob, dx, dy]).
Uses pure LiteRT built-in ops (Conv2D, DepthwiseConv2D, ResizeNearestNeighbor, Add, ReLU, Sigmoid) and
exports FP16 (~512 KB) + optional calibrated INT8 (~280 KB), fitting cleanly inside Cubentia's existing `litert:2.3.2` runtime.

Run with the repo's CUDA TensorFlow venv:
    /mnt/e/Dynamic/IndFun/RubikXplorer/tools/corner-model/.venv/bin/python train_center_model.py \
        --train-n 10000 --val-n 1000 --epochs 30 --out /tmp/sticker_centers.tflite
"""

from __future__ import annotations

import argparse
import hashlib
import os
import time
from pathlib import Path

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import numpy as np
import tensorflow as tf
from tensorflow import keras
from tensorflow.keras import layers

from synth_heatmap_data import HM_SIZE, IMG_SIZE, generate_dataset


def _inv_res(x, out_ch: int, stride: int, expand: int = 3, name: str = "ir"):
    in_ch = int(x.shape[-1])
    mid_ch = in_ch * expand
    y = layers.Conv2D(mid_ch, 1, padding="same", use_bias=False, name=f"{name}_exp")(x)
    y = layers.BatchNormalization(name=f"{name}_exp_bn")(y)
    y = layers.ReLU(name=f"{name}_exp_act")(y)
    y = layers.DepthwiseConv2D(3, strides=stride, padding="same", use_bias=False, name=f"{name}_dw")(y)
    y = layers.BatchNormalization(name=f"{name}_dw_bn")(y)
    y = layers.ReLU(name=f"{name}_dw_act")(y)
    y = layers.Conv2D(out_ch, 1, padding="same", use_bias=False, name=f"{name}_pw")(y)
    y = layers.BatchNormalization(name=f"{name}_pw_bn")(y)
    if stride == 1 and in_ch == out_ch:
        return layers.Add(name=f"{name}_add")([x, y])
    return y


def _sep_block(x, ch: int, name: str):
    y = layers.DepthwiseConv2D(3, padding="same", use_bias=False, name=f"{name}_dw")(x)
    y = layers.BatchNormalization(name=f"{name}_dw_bn")(y)
    y = layers.ReLU(name=f"{name}_dw_act")(y)
    y = layers.Conv2D(ch, 1, padding="same", use_bias=False, name=f"{name}_pw")(y)
    y = layers.BatchNormalization(name=f"{name}_pw_bn")(y)
    y = layers.ReLU(name=f"{name}_pw_act")(y)
    return y


def build_center_model() -> keras.Model:
    inp = keras.Input(shape=(IMG_SIZE, IMG_SIZE, 3), dtype="float32", name="rgb_image")

    # Stem: 256 -> 128
    x = layers.Conv2D(24, 3, strides=2, padding="same", use_bias=False, name="stem_conv")(inp)
    x = layers.BatchNormalization(name="stem_bn")(x)
    x = layers.ReLU(name="stem_act")(x)

    # Stage 1: 128 -> 64 (skip s4)
    x = _inv_res(x, 32, stride=2, expand=3, name="s4_a")
    s4 = _inv_res(x, 32, stride=1, expand=3, name="s4_b")

    # Stage 2: 64 -> 32 (skip s8)
    x = _inv_res(s4, 64, stride=2, expand=3, name="s8_a")
    x = _inv_res(x, 64, stride=1, expand=3, name="s8_b")
    s8 = _inv_res(x, 64, stride=1, expand=3, name="s8_c")

    # Stage 3: 32 -> 16 (context bottleneck)
    x = _inv_res(s8, 96, stride=2, expand=3, name="s16_a")
    x = _inv_res(x, 96, stride=1, expand=3, name="s16_b")
    x = _inv_res(x, 96, stride=1, expand=3, name="s16_c")

    # Lightweight FPN Decoder: 12 -> 24 -> 48 (nearest upsampling stays pure fp16 under XLA and is faster on LiteRT)
    u8 = layers.UpSampling2D(size=2, interpolation="nearest", name="up_s8")(x)
    u8 = layers.Conv2D(64, 1, padding="same", use_bias=False, name="up_s8_proj")(u8)
    u8 = layers.BatchNormalization(name="up_s8_bn")(u8)
    p8 = layers.Add(name="fuse_s8")([u8, s8])
    p8 = _sep_block(p8, 64, name="dec_s8")

    u4 = layers.UpSampling2D(size=2, interpolation="nearest", name="up_s4")(p8)
    u4 = layers.Conv2D(32, 1, padding="same", use_bias=False, name="up_s4_proj")(u4)
    u4 = layers.BatchNormalization(name="up_s4_bn")(u4)
    p4 = layers.Add(name="fuse_s4")([u4, s4])
    p4 = _sep_block(p4, 48, name="dec_s4a")
    p4 = _sep_block(p4, 32, name="dec_s4b")

    # Output head: channel 0 = heatmap prob (sigmoid), channels 1..2 = sub-cell offset in [-0.5, 0.5]
    hm_logits = layers.Conv2D(
        1,
        1,
        padding="same",
        dtype="float32",
        bias_initializer=keras.initializers.Constant(-2.5),  # focal-loss prior p ~ 0.075
        name="hm_logit",
    )(p4)
    hm_prob = layers.Activation("sigmoid", dtype="float32", name="hm_prob")(hm_logits)
    off_raw = layers.Conv2D(2, 1, padding="same", dtype="float32", name="off_raw")(p4)
    off = layers.Activation("tanh", dtype="float32", name="off_tanh")(off_raw) * 0.55
    out = layers.Concatenate(axis=-1, dtype="float32", name="heatmap_and_offset")([hm_prob, off])
    return keras.Model(inputs=inp, outputs=out, name="cubentia_sticker_centers")


def centernet_loss(y_true: tf.Tensor, y_pred: tf.Tensor) -> tf.Tensor:
    """Penalty-reduced focal loss (CenterNet alpha=2, beta=4) + masked L1 sub-cell offset loss."""
    y_true = tf.cast(y_true, tf.float32)
    hm_t = y_true[:, :, :, 0]
    hm_p = tf.clip_by_value(y_pred[:, :, :, 0], 1e-5, 1.0 - 1e-5)

    pos_mask = tf.cast(tf.greater_equal(hm_t, 0.99), tf.float32)
    neg_mask = 1.0 - pos_mask
    neg_weights = tf.pow(1.0 - hm_t, 4.0)

    pos_loss = -tf.math.log(hm_p) * tf.pow(1.0 - hm_p, 2.0) * pos_mask
    neg_loss = -tf.math.log(1.0 - hm_p) * tf.pow(hm_p, 2.0) * neg_weights * neg_mask

    num_pos = tf.reduce_sum(pos_mask)
    focal = (tf.reduce_sum(pos_loss) + tf.reduce_sum(neg_loss)) / tf.maximum(num_pos, 1.0)

    # Offset L1 loss only on exact peak cells
    off_t = y_true[:, :, :, 1:3]
    off_p = y_pred[:, :, :, 1:3]
    off_err = tf.abs(off_t - off_p) * pos_mask[:, :, :, None]
    off_loss = tf.reduce_sum(off_err) / tf.maximum(num_pos * 2.0, 1.0)
    return focal + 2.0 * off_loss


def peak_recall_metric(y_true: tf.Tensor, y_pred: tf.Tensor) -> tf.Tensor:
    """Quick online recall of ground-truth peak cells at prob >= 0.35."""
    y_true = tf.cast(y_true, tf.float32)
    pos = tf.cast(tf.greater_equal(y_true[:, :, :, 0], 0.99), tf.float32)
    hit = tf.cast(tf.greater_equal(y_pred[:, :, :, 0], 0.35), tf.float32) * pos
    return tf.reduce_sum(hit) / tf.maximum(tf.reduce_sum(pos), 1.0)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--train-n", type=int, default=10000)
    parser.add_argument("--val-n", type=int, default=1000)
    parser.add_argument("--epochs", type=int, default=18)
    parser.add_argument("--batch", type=int, default=64)
    parser.add_argument("--seed", type=int, default=20261124)
    parser.add_argument("--bg-dir", type=Path, default=None, help="Optional dir of cube-free tuning room backgrounds")
    parser.add_argument("--out", type=Path, default=Path("/tmp/sticker_centers_v2.tflite"))
    parser.add_argument("--out-int8", type=Path, default=None, help="Optional calibrated INT8 LiteRT export path")
    args = parser.parse_args()

    tf.keras.utils.set_random_seed(args.seed)
    if tf.config.list_physical_devices("GPU"):
        keras.mixed_precision.set_global_policy("mixed_float16")

    t0 = time.perf_counter()
    print(f"Generating {args.train_n} train + {args.val_n} val synthetic 1-3 face samples ({IMG_SIZE}x{IMG_SIZE} -> {HM_SIZE}x{HM_SIZE}, seed={args.seed}, bg_dir={args.bg_dir})...")
    x_train, y_train = generate_dataset(args.train_n, seed=args.seed, chunks=12, bg_dir=args.bg_dir)
    x_val, y_val = generate_dataset(args.val_n, seed=args.seed + 999999, chunks=4, bg_dir=args.bg_dir)
    print(f"Dataset generated in {time.perf_counter() - t0:.1f}s")

    def prep(img_u8, tgt_f16):
        return tf.cast(img_u8, tf.float32) / 255.0, tf.cast(tgt_f16, tf.float32)

    ds_train = (
        tf.data.Dataset.from_tensor_slices((x_train, y_train))
        .shuffle(2048, seed=args.seed)
        .batch(args.batch)
        .map(prep, num_parallel_calls=tf.data.AUTOTUNE)
        .prefetch(tf.data.AUTOTUNE)
    )
    ds_val = (
        tf.data.Dataset.from_tensor_slices((x_val, y_val))
        .batch(args.batch)
        .map(prep, num_parallel_calls=tf.data.AUTOTUNE)
        .prefetch(tf.data.AUTOTUNE)
    )

    model = build_center_model()
    print(f"Model params: {model.count_params():,}")

    steps_per_epoch = max(1, args.train_n // args.batch)
    lr_sched = keras.optimizers.schedules.CosineDecay(
        initial_learning_rate=2.5e-3,
        decay_steps=args.epochs * steps_per_epoch,
        alpha=0.02,
    )
    model.compile(
        optimizer=keras.optimizers.AdamW(learning_rate=lr_sched, weight_decay=1e-4),
        loss=centernet_loss,
        metrics=[peak_recall_metric],
    )

    t1 = time.perf_counter()
    model.fit(ds_train, validation_data=ds_val, epochs=args.epochs, verbose=2)
    print(f"Training completed in {time.perf_counter() - t1:.1f}s")

    # Export FP16 LiteRT model (rebuild float32 inference graph so TFLiteConverter exports clean float32 I/O + fp16 weights)
    keras.mixed_precision.set_global_policy("float32")
    export_model = build_center_model()
    export_model.set_weights(model.get_weights())
    converter = tf.lite.TFLiteConverter.from_keras_model(export_model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]
    tflite_bytes = converter.convert()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(tflite_bytes)
    sha = hashlib.sha256(tflite_bytes).hexdigest()
    print(f"Exported FP16 {args.out} ({len(tflite_bytes):,} bytes = {len(tflite_bytes)/1024:.1f} KB, SHA-256={sha})")

    if args.out_int8 is not None:
        cal_n = min(256, len(x_val))

        def rep_dataset():
            for i in range(cal_n):
                yield [(x_val[i].astype(np.float32) / 255.0)[None]]

        c_int8 = tf.lite.TFLiteConverter.from_keras_model(export_model)
        c_int8.optimizations = [tf.lite.Optimize.DEFAULT]
        c_int8.representative_dataset = rep_dataset
        c_int8.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
        # Keep float32 output for smooth sub-cell offset precision while running all convs in INT8!
        c_int8.inference_input_type = tf.uint8
        c_int8.inference_output_type = tf.float32
        int8_bytes = c_int8.convert()
        args.out_int8.parent.mkdir(parents=True, exist_ok=True)
        args.out_int8.write_bytes(int8_bytes)
        sha8 = hashlib.sha256(int8_bytes).hexdigest()
        print(f"Exported INT8 {args.out_int8} ({len(int8_bytes):,} bytes = {len(int8_bytes)/1024:.1f} KB, SHA-256={sha8})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
