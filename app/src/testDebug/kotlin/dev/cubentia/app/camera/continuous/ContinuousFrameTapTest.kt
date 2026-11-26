package dev.cubentia.app.camera.continuous

import dev.cubentia.app.camera.ContinuousFrameTap
import dev.cubentia.app.camera.StickerCenterDetection
import dev.cubentia.app.camera.StickerCenterDetector
import dev.cubentia.app.camera.YuvFrame
import dev.cubentia.app.camera.YuvPlane
import dev.cubentia.domain.recognition.continuous.ContinuousScanSnapshot
import dev.cubentia.domain.recognition.continuous.QuadObservation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/** Story 10.34: the neutral frame tap sees every frame of the engine the module provides; the Solve tab passes none. */
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
        val seen = mutableListOf<Int>()
        val inputs = mutableListOf<ByteArray?>()
        val snapshots = mutableListOf<ContinuousScanSnapshot>()

        override fun wantsModelInput(frameIndex: Int) = frameIndex % 3 == 0

        override fun onFrame(
            frameIndex: Int,
            observations: List<QuadObservation>,
            snapshot: ContinuousScanSnapshot,
            detection: StickerCenterDetection,
            modelInput: ByteArray?,
        ) {
            seen += frameIndex
            inputs += modelInput?.copyOf()
            snapshots += snapshot
            assertTrue(observations.isEmpty())
        }
    }

    private fun detector(copies: Boolean) =
        object : StickerCenterDetector {
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
    fun `the tap gets every frame with the model input on the frames it asks for`() {
        val tap = RecordingTap()
        val engine = ContinuousScanEngineModule.factory(detector(copies = true)).create(tap)
        repeat(4) { engine.processFrame(frame(), 0, 0, 0) }
        assertEquals(listOf(0, 1, 2, 3), tap.seen)
        assertEquals(StickerCenterModelContract.INPUT_BYTES, tap.inputs[0]?.size)
        assertEquals(7, tap.inputs[0]?.get(100)?.toInt())
        assertNull(tap.inputs[1])
        assertEquals(listOf(0, 1, 2, 3), tap.snapshots.map { it.frameIndex })
    }

    @Test
    fun `an invalid crop still reaches the tap as an empty entry, and a detector without a copy gives none`() {
        val tap = RecordingTap()
        val engine = ContinuousScanEngineModule.factory(detector(copies = false)).create(tap)
        engine.processFrame(frame(right = 0), 0, 0, 0)
        engine.processFrame(frame(), 0, 0, 0)
        engine.processFrame(frame(), 0, 0, 0)
        engine.processFrame(frame(), 0, 0, 0)
        assertEquals(listOf(0, 1, 2, 3), tap.seen)
        assertTrue(tap.inputs.all { it == null })
    }

    @Test
    fun `the module names the bundled model and the default create passes no tap`() {
        val factory = ContinuousScanEngineModule.factory(detector(copies = true))
        assertEquals(StickerCenterModelContract.MODEL_SHA256, factory.modelIdentity?.sha256)
        assertEquals(StickerCenterModelContract.MODEL_ASSET, factory.modelIdentity?.file)
        factory.create().processFrame(frame(), 0, 0, 0)
    }
}
