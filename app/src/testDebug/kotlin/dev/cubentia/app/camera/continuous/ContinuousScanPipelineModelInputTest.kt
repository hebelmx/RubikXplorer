package dev.cubentia.app.camera.continuous

import dev.cubentia.app.camera.ContinuousDebugOverlay
import dev.cubentia.app.camera.ModelInputThumbnail
import dev.cubentia.app.camera.StickerCenterDetection
import dev.cubentia.app.camera.StickerCenterDetector
import dev.cubentia.app.camera.YuvFrame
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/**
 * GH #414: with the debug view on, each read frame's overlay carries the exact model input the interpreter got (the
 * `fillYuvInput` bytes as ARGB, letterbox bars included), its peaks in model-input pixels and an info line; with the view
 * off the debug path never asks the detector for its input.
 */
class ContinuousScanPipelineModelInputTest {
    /** A fake session whose heatmap has a peak (score 0.9, no offset) at each heatmap cell of [cells]. */
    private class HeatmapSession : StickerCenterInferenceSession {
        var cells: List<Pair<Int, Int>> = emptyList()

        /** A copy of the bytes the latest inference was given. */
        var lastInput: ByteArray? = null

        val detector =
            LiteRtStickerCenterDetector(loadModel = { ByteBuffer.allocateDirect(16) }, openSession = { this })

        override fun run(
            input: ByteBuffer,
            output: ByteBuffer,
        ) {
            lastInput = ByteArray(input.remaining()).also { input.duplicate().get(it) }
            val floats = FloatArray(StickerCenterModelContract.OUTPUT_FLOATS)
            for ((gx, gy) in cells) floats[(gy * StickerCenterModelContract.HEATMAP_SIZE + gx) * 3] = 0.9f
            output.asFloatBuffer().put(floats)
        }

        override fun close() = Unit
    }

    /** Delegates to [inner], counting [copyModelInput] calls; [copy] replaces the copy when set. */
    private class CountingDetector(
        private val inner: StickerCenterDetector,
        private val copy: ((ByteArray) -> Boolean)? = null,
    ) : StickerCenterDetector by inner {
        var copies = 0

        override fun copyModelInput(into: ByteArray): Boolean {
            copies++
            return copy?.invoke(into) ?: inner.copyModelInput(into)
        }
    }

    @Test
    fun `at two rotations the thumbnail is the fillYuvInput output byte for byte with zero letterbox bars`() {
        for (rotation in listOf(0, 90)) {
            val session = HeatmapSession().apply { cells = listOf(10 to 20, 20 to 20, 10 to 40) }
            val pipeline = ContinuousScanPipeline(session.detector, timings = null, debugView = { true })
            val frame = patterned(1)
            val result = pipeline.processFrame(0, frame, rotation)
            val thumbnail = checkNotNull(checkNotNull(result.debug).thumbnail) { "rotation $rotation" }
            assertEquals(StickerCenterModelContract.INPUT_SIZE, thumbnail.size)
            assertArrayEquals(expectedPixels(frame, rotation), thumbnail.pixels, "rotation $rotation")
            // The bytes the interpreter session itself was given.
            assertArrayEquals(argb(checkNotNull(session.lastInput)), thumbnail.pixels, "rotation $rotation interpreter input")
            val letterbox =
                checkNotNull(StickerCenterModelTransform.letterbox(result.detection.uprightWidth, result.detection.uprightHeight))
            // The letterbox bars are part of the input: black, fully opaque.
            val size = StickerCenterModelContract.INPUT_SIZE
            for (i in thumbnail.pixels.indices) {
                val x = i % size
                val y = i / size
                val bar =
                    x < letterbox.padLeft ||
                        x >= letterbox.padLeft + letterbox.resizedWidth ||
                        y < letterbox.padTop ||
                        y >= letterbox.padTop + letterbox.resizedHeight
                if (bar) assertEquals(OPAQUE_BLACK, thumbnail.pixels[i], "rotation $rotation bar pixel $x,$y")
            }
            assertTrue(letterbox.padLeft > 0 || letterbox.padTop > 0, "rotation $rotation has bars")
            assertPeaksAtHeatmapCells(session.cells, thumbnail)
            assertEquals(
                "input=${letterbox.resizedWidth}x${letterbox.resizedHeight} crop=${frame.width}x${frame.height} spacing=10.0",
                thumbnail.info,
            )
        }
    }

    @Test
    fun `each read frame carries a thumbnail of its own bytes and peaks`() {
        val session = HeatmapSession()
        val pipeline = ContinuousScanPipeline(session.detector, timings = null, debugView = { true })
        session.cells = listOf(10 to 20, 20 to 20, 10 to 40)
        val first = checkNotNull(checkNotNull(pipeline.processFrame(0, patterned(1), 0).debug).thumbnail)
        session.cells = listOf(30 to 30)
        val second = checkNotNull(checkNotNull(pipeline.processFrame(1, patterned(7), 0).debug).thumbnail)
        assertArrayEquals(expectedPixels(patterned(1), 0), first.pixels)
        assertArrayEquals(expectedPixels(patterned(7), 0), second.pixels)
        assertFalse(first.pixels.contentEquals(second.pixels))
        assertPeaksAtHeatmapCells(listOf(10 to 20, 20 to 20, 10 to 40), first)
        assertPeaksAtHeatmapCells(listOf(30 to 30), second)
        assertTrue(first.info.endsWith(" spacing=10.0"), first.info)
        assertTrue(second.info.endsWith(" spacing=-"), second.info)
    }

    @Test
    fun `no peak gives spacing dash`() {
        val session = HeatmapSession()
        val pipeline = ContinuousScanPipeline(session.detector, timings = null, debugView = { true })
        val thumbnail = checkNotNull(checkNotNull(pipeline.processFrame(0, patterned(3), 0).debug).thumbnail)
        assertTrue(thumbnail.peaks.isEmpty())
        assertEquals("input=256x192 crop=64x48 spacing=-", thumbnail.info)
    }

    @Test
    fun `a detector that exposes no input or throws leaves the overlay without a thumbnail`() {
        val refusing = CountingDetector(HeatmapSession().detector, copy = { false })
        val throwing = CountingDetector(HeatmapSession().detector, copy = { throw IllegalStateException("no input") })
        for (detector in listOf(refusing, throwing)) {
            val pipeline = ContinuousScanPipeline(detector, timings = null, debugView = { true })
            val debug = checkNotNull(pipeline.processFrame(0, patterned(1), 0).debug)
            assertNotEquals(ContinuousDebugOverlay.SKIPPED, debug)
            assertNull(debug.thumbnail)
            assertEquals(1, detector.copies)
        }
    }

    @Test
    fun `a skipped frame gets the skipped overlay with no thumbnail`() {
        val session = HeatmapSession()
        val pipeline = ContinuousScanPipeline(session.detector, timings = null, debugView = { true })
        val short = makeBlankYuv(64, 48).apply { y.buffer.limit(1) }
        val debug = pipeline.processFrame(0, short, 0).debug
        assertEquals(ContinuousDebugOverlay.SKIPPED, debug)
        assertNull(checkNotNull(debug).thumbnail)
    }

    @Test
    fun `with the view off no overlay leaves the pipeline and the detector is never asked for its input`() {
        val detector = CountingDetector(HeatmapSession().detector)
        val pipeline = ContinuousScanPipeline(detector, timings = null, debugView = { false })
        repeat(3) { assertNull(pipeline.processFrame(it, patterned(it), 0).debug) }
        assertEquals(0, detector.copies)
        val on = CountingDetector(HeatmapSession().detector)
        assertNotNull(ContinuousScanPipeline(on, timings = null, debugView = { true }).processFrame(0, patterned(1), 0).debug?.thumbnail)
        assertEquals(1, on.copies)
    }

    private companion object {
        const val OPAQUE_BLACK = -0x1000000

        /** A 64x48 frame whose luma and chroma vary with [seed], so two seeds give two different model inputs. */
        fun patterned(seed: Int): YuvFrame =
            makeBlankYuv(64, 48).apply {
                for (i in 0 until y.buffer.capacity()) y.buffer.put(i, ((i * (seed + 1)) % 200 + 30).toByte())
                for (i in 0 until u.buffer.capacity()) u.buffer.put(i, ((i * 3 + seed * 17) % 120 + 70).toByte())
                for (i in 0 until v.buffer.capacity()) v.buffer.put(i, ((i * 5 + seed * 29) % 120 + 70).toByte())
            }

        fun expectedPixels(
            frame: YuvFrame,
            rotation: Int,
        ): IntArray {
            val bytes = ByteArray(StickerCenterModelContract.INPUT_BYTES)
            checkNotNull(StickerCenterModelTransform.fillYuvInput(frame, rotation, bytes))
            return argb(bytes)
        }

        fun argb(bytes: ByteArray): IntArray {
            assertEquals(StickerCenterModelContract.INPUT_BYTES, bytes.size)
            return IntArray(bytes.size / 3) { i ->
                OPAQUE_BLACK or
                    ((bytes[3 * i].toInt() and 0xFF) shl 16) or
                    ((bytes[3 * i + 1].toInt() and 0xFF) shl 8) or
                    (bytes[3 * i + 2].toInt() and 0xFF)
            }
        }

        /** A heatmap peak with no offset decodes at the cell centre, `(g + 0.5) × stride` model-input px. */
        fun assertPeaksAtHeatmapCells(
            cells: List<Pair<Int, Int>>,
            thumbnail: ModelInputThumbnail,
        ) {
            val stride = StickerCenterModelContract.HEATMAP_STRIDE
            val expected =
                cells
                    .map { (gx, gy) ->
                        (gx + 0.5) * stride to (gy + 0.5) * stride
                    }.sortedWith(compareBy({ it.second }, { it.first }))
            val actual = thumbnail.peaks.map { it.x to it.y }.sortedWith(compareBy({ it.second }, { it.first }))
            assertEquals(expected.size, actual.size)
            expected.zip(actual).forEach { (e, a) ->
                assertEquals(e.first, a.first, 1e-9)
                assertEquals(e.second, a.second, 1e-9)
            }
        }
    }
}
