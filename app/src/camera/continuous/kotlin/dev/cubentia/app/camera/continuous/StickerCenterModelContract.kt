package dev.cubentia.app.camera.continuous

/**
 * Model contract for `app/src/camera/continuous/assets/sticker_centers_v2_int8.tflite` (Story 10.31, AC3/AC4).
 *
 * Mirrors `tools/center-model/model-card.json`; verified by `StickerCenterModelContractTest`.
 */
internal object StickerCenterModelContract {
    const val MODEL_ASSET = "sticker_centers_v2_int8.tflite"
    const val MODEL_BYTES = 378_288
    const val MODEL_SHA256 = "d18fb2c2fe2bb975cf646877f811718c16775ce6bcde907f12547d143174be7c"

    const val INPUT_SIZE = 256
    const val CHANNELS = 3
    const val INPUT_BYTES = INPUT_SIZE * INPUT_SIZE * CHANNELS

    const val HEATMAP_SIZE = 64
    const val HEATMAP_STRIDE = 4
    const val OUTPUT_CHANNELS = 3
    const val OUTPUT_FLOATS = HEATMAP_SIZE * HEATMAP_SIZE * OUTPUT_CHANNELS

    const val DEFAULT_THRESHOLD = 0.45f
    const val MAX_PEAKS = 36
    const val NUM_THREADS = 2
}
