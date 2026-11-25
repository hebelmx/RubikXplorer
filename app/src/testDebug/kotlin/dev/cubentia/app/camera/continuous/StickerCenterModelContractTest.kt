package dev.cubentia.app.camera.continuous

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest

/** Story 10.31 (AC3): [StickerCenterModelContract] equals `tools/center-model/model-card.json` and the committed model. */
class StickerCenterModelContractTest {
    private val card = Json.parseToJsonElement(File("../tools/center-model/model-card.json").readText()) as JsonObject
    private val model = File("src/camera/continuous/assets/${StickerCenterModelContract.MODEL_ASSET}")

    @Test
    fun `the contract equals the model card`() {
        assertEquals(card.getValue("model").jsonPrimitive.content, StickerCenterModelContract.MODEL_ASSET)
        assertEquals(card.getValue("bytes").jsonPrimitive.int, StickerCenterModelContract.MODEL_BYTES)
        assertEquals(card.getValue("sha256").jsonPrimitive.content, StickerCenterModelContract.MODEL_SHA256)
        assertEquals(card.getValue("inputSize").jsonPrimitive.int, StickerCenterModelContract.INPUT_SIZE)
        assertEquals(card.getValue("heatmapSize").jsonPrimitive.int, StickerCenterModelContract.HEATMAP_SIZE)
        assertEquals(card.getValue("heatmapStride").jsonPrimitive.int, StickerCenterModelContract.HEATMAP_STRIDE)
        assertEquals(card.getValue("defaultThreshold").jsonPrimitive.double, StickerCenterModelContract.DEFAULT_THRESHOLD.toDouble(), 1e-6)
        assertEquals(card.getValue("maxPeaks").jsonPrimitive.int, StickerCenterModelContract.MAX_PEAKS)
        assertEquals(card.getValue("numThreads").jsonPrimitive.int, StickerCenterModelContract.NUM_THREADS)
        assertEquals(listOf(1, 256, 256, 3), card.getValue("input").jsonArray.map { it.jsonPrimitive.int })
        assertEquals("uint8", card.getValue("inputDtype").jsonPrimitive.content)
        assertEquals(listOf(1, 64, 64, 3), card.getValue("output").jsonArray.map { it.jsonPrimitive.int })
        assertEquals("float32", card.getValue("outputDtype").jsonPrimitive.content)
    }

    @Test
    fun `the committed model is byte-exact and matches its 64-hex SHA-256`() {
        assertTrue(model.isFile, "Missing committed model at ${model.absolutePath}")
        val bytes = model.readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(StickerCenterModelContract.MODEL_BYTES, bytes.size)
        assertEquals(StickerCenterModelContract.MODEL_SHA256, sha)
        assertEquals(card.getValue("bytes").jsonPrimitive.int, bytes.size)
        assertEquals(card.getValue("sha256").jsonPrimitive.content, sha)
    }
}
