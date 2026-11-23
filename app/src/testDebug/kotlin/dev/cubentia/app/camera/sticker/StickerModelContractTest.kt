package dev.cubentia.app.camera.sticker

import dev.cubentia.app.camera.YuvFrame
import dev.cubentia.app.camera.YuvPlane
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.recognition.StickerConfidence
import dev.cubentia.domain.recognition.StickerReading
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Story 10.23 (AC3): [StickerModelContract] equals `tools/sticker-model/model-card.json` and the committed model. */
class StickerModelContractTest {
    private val card = Json.parseToJsonElement(File("../tools/sticker-model/model-card.json").readText()) as JsonObject
    private val model = File("src/camera/debug/sticker/assets/${StickerModelContract.MODEL_ASSET}")

    @Test
    fun `the contract equals the model card`() {
        assertEquals(card.getValue("tau").jsonPrimitive.double, StickerModelContract.TAU.toDouble(), 1e-6)
        assertEquals(card.getValue("inputSize").jsonPrimitive.int, StickerModelContract.INPUT_SIZE)
        assertEquals(
            card.getValue("colorOrder").jsonArray.map { Color.valueOf(it.jsonPrimitive.content) },
            StickerModelContract.COLOR_ORDER,
        )
        assertEquals(listOf(1, 9, 6), card.getValue("output").jsonArray.map { it.jsonPrimitive.int })
        assertEquals(card.getValue("sha256").jsonPrimitive.content, StickerModelContract.MODEL_SHA256)
    }

    @Test
    fun `the committed model is the carded one and under 500 KB`() {
        val bytes = model.readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(card.getValue("sha256").jsonPrimitive.content, sha)
        assertEquals(card.getValue("bytes").jsonPrimitive.int, bytes.size)
        assertTrue(bytes.size < 500 * 1024, "${bytes.size} bytes")
    }

    @Test
    fun `probabilities map to categorical readings by tau in the model's color order`() {
        val probabilities =
            Array(9) { cell ->
                FloatArray(6).also {
                    // Cell i's top-1 is color index i % 6; even cells reach tau, odd cells fall just short.
                    it[cell % 6] = if (cell % 2 == 0) StickerModelContract.TAU else Math.nextDown(StickerModelContract.TAU)
                }
            }
        val readings = StickerModelContract.readings(probabilities)
        val expected =
            List(9) { cell ->
                StickerReading(
                    StickerModelContract.COLOR_ORDER[cell % 6],
                    if (cell % 2 == 0) StickerConfidence.CONFIDENT else StickerConfidence.UNCERTAIN,
                )
            }
        assertEquals(expected, readings)
        assertEquals(StickerConfidence.UNCERTAIN, StickerModelContract.readings(Array(9) { FloatArray(6) { Float.NaN } })[0].confidence)
        assertThrows(IllegalArgumentException::class.java) { StickerModelContract.readings(Array(8) { FloatArray(6) }) }
    }

    @Test
    fun `a crop becomes RGB input with the corner model's YUV formula`() {
        val side = StickerModelContract.INPUT_SIZE

        fun plane(
            width: Int,
            value: Int,
        ) = YuvPlane(ByteBuffer.wrap(ByteArray(width * width) { value.toByte() }), width, 1)
        // Y 128 with U 128 / V 200: red channel up, blue unchanged, green down.
        val crop = YuvFrame(plane(side, 128), plane(side / 2, 128), plane(side / 2, 200), 0, 0, side, side)
        val input = ByteBuffer.allocateDirect(StickerModelContract.INPUT_BYTES).order(ByteOrder.nativeOrder())
        StickerModelContract.fillInput(crop, input)
        val r = input.getFloat(0)
        val g = input.getFloat(4)
        val b = input.getFloat(8)
        assertEquals(((128 + 1.402f * 72) / 255f).coerceIn(0f, 1f), r, 1e-6f)
        assertEquals((128 - 0.714136f * 72) / 255f, g, 1e-6f)
        assertEquals(128 / 255f, b, 1e-6f)
        assertEquals(0, input.position())
    }
}
