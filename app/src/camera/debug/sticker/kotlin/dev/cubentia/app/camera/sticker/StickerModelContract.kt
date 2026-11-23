package dev.cubentia.app.camera.sticker

import dev.cubentia.app.camera.YuvFrame
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.recognition.StickerConfidence
import dev.cubentia.domain.recognition.StickerReading
import java.nio.ByteBuffer

/**
 * The shadow sticker reader's model contract (Story 10.23), equal to `tools/sticker-model/model-card.json` (checked by
 * `StickerModelContractTest`): input `[1, 96, 96, 3]` float32 RGB in [0, 1], the unwarped face; output `[1, 9, 6]`
 * softmax per sticker in Kotlin cell order (row-major), colours in [COLOR_ORDER]. Only categorical results leave the
 * reader (AD-9): a sticker is CONFIDENT when its top-1 probability is at least [TAU], else UNCERTAIN; the probabilities
 * themselves are never returned or logged.
 */
internal object StickerModelContract {
    const val MODEL_ASSET = "sticker_reader.tflite"
    const val INPUT_SIZE = 96
    const val CELLS = 9
    const val INPUT_BYTES = INPUT_SIZE * INPUT_SIZE * 3 * Float.SIZE_BYTES

    /** The model's output order; differs from [Color.entries]. */
    val COLOR_ORDER: List<Color> = listOf(Color.WHITE, Color.YELLOW, Color.RED, Color.ORANGE, Color.BLUE, Color.GREEN)

    /** The tune-split threshold from `model-card.json` (smallest on 0.50-0.99 with 0 confidently wrong tune stickers). */
    const val TAU = 0.99f

    /** SHA-256 of the committed `sticker_reader.tflite`, as `model-card.json` records it. */
    const val MODEL_SHA256 = "a6da207a1de63ca41aefedf14a3f024fa89963a039a0506bb1a597960ff35746"

    /** One categorical reading per sticker from the model's [probabilities] (`[9][6]`). */
    fun readings(probabilities: Array<FloatArray>): List<StickerReading> {
        require(probabilities.size == CELLS && probabilities.all { it.size == COLOR_ORDER.size }) { "Output is not [9, 6]" }
        return probabilities.map { cell ->
            var best = 0
            for (i in 1 until cell.size) if (cell[i] > cell[best]) best = i
            val confident = cell[best].isFinite() && cell[best] >= TAU
            StickerReading(COLOR_ORDER[best], if (confident) StickerConfidence.CONFIDENT else StickerConfidence.UNCERTAIN)
        }
    }

    /**
     * Writes [crop] (an unwarped face, normally exactly [INPUT_SIZE] square) as the model input, YUV to RGB with the
     * formula of `CornerModelTransform.fillRgbInput`; a crop of another size is sampled nearest. Repositions [output].
     */
    fun fillInput(
        crop: YuvFrame,
        output: ByteBuffer,
    ) {
        output.clear()
        val width = crop.width.coerceAtLeast(1)
        val height = crop.height.coerceAtLeast(1)
        for (row in 0 until INPUT_SIZE) {
            val py = (crop.top + row * height / INPUT_SIZE).coerceIn(crop.top, crop.bottom - 1)
            for (col in 0 until INPUT_SIZE) {
                val px = (crop.left + col * width / INPUT_SIZE).coerceIn(crop.left, crop.right - 1)
                val yy = crop.y.at(px, py).toFloat()
                val uu = crop.u.at(px shr 1, py shr 1).toFloat() - 128f
                val vv = crop.v.at(px shr 1, py shr 1).toFloat() - 128f
                output.putFloat(((yy + 1.402f * vv) / 255f).coerceIn(0f, 1f))
                output.putFloat(((yy - 0.344136f * uu - 0.714136f * vv) / 255f).coerceIn(0f, 1f))
                output.putFloat(((yy + 1.772f * uu) / 255f).coerceIn(0f, 1f))
            }
        }
        output.rewind()
    }
}
