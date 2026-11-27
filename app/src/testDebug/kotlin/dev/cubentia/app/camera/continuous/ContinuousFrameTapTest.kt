package dev.cubentia.app.camera.continuous

import dev.cubentia.app.camera.ContinuousFrameTap
import dev.cubentia.app.camera.StickerCenterDetection
import dev.cubentia.app.camera.StickerCenterDetector
import dev.cubentia.app.camera.YuvFrame
import dev.cubentia.app.camera.YuvPlane
import dev.cubentia.domain.recognition.continuous.ContinuousScanSnapshot
import dev.cubentia.domain.recognition.continuous.QuadObservation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/**
 * Story 10.34: the neutral frame tap of the engine the module provides. [ContinuousScanEngine.record] attaches it; it then
 * sees every frame [ContinuousScanEngine.processFrame] returns (read or skipped) and its [ContinuousScanEngine.frameDone];
 * the Solve tab (no record) and [ContinuousScanEngine.reset] pass none.
 */
class ContinuousFrameTapTest {
    private fun frame(right: Int = 64) =
        ByteBuffer.allocate(64 * 48).let { y ->
            val uv = ByteBuffer.allocate(32 * 24)
            YuvFrame(
                y = YuvPlane(y, 64, 1),
                u = YuvPlane(uv, 32, 1),
                v = YuvPlane(uv, 32, 1),
                left = 0,
                top = 0,
                right = right,
                bottom = 48,
            )
        }

    private class RecordingTap : ContinuousFrameTap {
        val buffer = ByteArray(StickerCenterModelContract.INPUT_BYTES)
        val events = mutableListOf<String>()
        val filled = mutableListOf<Boolean>()
        val snapshots = mutableListOf<ContinuousScanSnapshot>()
        val analyzerNs = mutableListOf<Long>()

        override fun modelInputBuffer(frameIndex: Int) = buffer.takeIf { frameIndex % 3 == 0 }

        override fun onFrame(
            frameIndex: Int,
            observations: List<QuadObservation>,
            snapshot: ContinuousScanSnapshot,
            detection: StickerCenterDetection,
            modelInputFilled: Boolean,
        ) {
            events += "frame $frameIndex"
            filled += modelInputFilled
            snapshots += snapshot
            assertTrue(observations.isEmpty())
        }

        override fun onSkippedFrame(frameIndex: Int) {
            events += "skipped $frameIndex"
        }

        override fun frameDone(
            sensorTimestampNs: Long,
            analyzerNs: Long,
            rotation: Int,
            imageWidth: Int,
            imageHeight: Int,
            cropLeft: Int,
            cropTop: Int,
            cropRight: Int,
            cropBottom: Int,
        ) {
            events += "done $sensorTimestampNs"
            this.analyzerNs += analyzerNs
        }
    }

    private fun detector(copies: Boolean) =
        object : StickerCenterDetector {
            override val unavailable = false

            override fun detect(
                frame: YuvFrame,
                rotationDegrees: Int,
                viewWidth: Int,
                viewHeight: Int,
            ) = StickerCenterDetection.EMPTY

            override fun copyModelInput(into: ByteArray): Boolean {
                if (!copies) return false
                into.fill(7)
                return true
            }
        }

    @Test
    fun `a recording tap sees every frame, with the model input filled into its own buffer on the frames it asks for`() {
        val tap = RecordingTap()
        val engine = ContinuousScanEngineModule.factory(detector(copies = true)).create()
        engine.processFrame(frame(), 0, 0, 0)
        engine.record(tap)
        repeat(4) {
            engine.processFrame(frame(), 0, 0, 0)
            engine.frameDone(100L + it, 5L, 90, 64, 48, 0, 0, 64, 48)
        }
        // The frame before record is not seen, and record restarts the frame index (answer 3).
        assertEquals(listOf("frame 0", "done 100", "frame 1", "done 101", "frame 2", "done 102", "frame 3", "done 103"), tap.events)
        assertEquals(listOf(true, false, false, true), tap.filled)
        assertEquals(7, tap.buffer[100].toInt())
        assertEquals(listOf(0, 1, 2, 3), tap.snapshots.map { it.frameIndex })
        assertEquals(listOf(5L, 5L, 5L, 5L), tap.analyzerNs)
    }

    @Test
    fun `a frame the pipeline does not read is a skipped frame, never a stale snapshot`() {
        val tap = RecordingTap()
        val engine = ContinuousScanEngineModule.factory(detector(copies = false)).create()
        engine.record(tap)
        engine.processFrame(frame(), 0, 0, 0)
        engine.processFrame(frame(right = 0), 0, 0, 0)
        engine.processFrame(frame(), 0, 0, 0)
        assertEquals(listOf("frame 0", "skipped 1", "frame 2"), tap.events)
        // A detector that exposes no model input fills nothing.
        assertEquals(listOf(false, false), tap.filled)
    }

    @Test
    fun `reset detaches the tap and the default engine passes none`() {
        val tap = RecordingTap()
        val factory = ContinuousScanEngineModule.factory(detector(copies = true))
        val engine = factory.create()
        engine.record(tap)
        engine.processFrame(frame(), 0, 0, 0)
        engine.reset()
        engine.processFrame(frame(), 0, 0, 0)
        engine.frameDone(1, 1, 0, 64, 48, 0, 0, 64, 48)
        assertEquals(listOf("frame 0"), tap.events)
        val untapped = factory.create()
        untapped.processFrame(frame(), 0, 0, 0)
        untapped.frameDone(1, 1, 0, 64, 48, 0, 0, 64, 48)
        assertEquals(listOf("frame 0"), tap.events)
    }

    @Test
    fun `the module names the bundled model`() {
        val factory = ContinuousScanEngineModule.factory(detector(copies = true))
        assertEquals(StickerCenterModelContract.MODEL_SHA256, factory.modelIdentity?.sha256)
        assertEquals(StickerCenterModelContract.MODEL_ASSET, factory.modelIdentity?.file)
        assertSame(ContinuousScanEngineModule.MODEL_IDENTITY, factory.modelIdentity)
    }
}
