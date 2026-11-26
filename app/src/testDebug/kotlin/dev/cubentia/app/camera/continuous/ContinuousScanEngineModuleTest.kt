package dev.cubentia.app.camera.continuous

import dev.cubentia.app.camera.StickerCenterDetection
import dev.cubentia.app.camera.StickerCenterDetector
import dev.cubentia.app.camera.YuvFrame
import dev.cubentia.app.camera.YuvPlane
import dev.cubentia.domain.recognition.continuous.ContinuousScanExport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/** Story 10.32: the production engine the module provides, run over a fake detector (route tests fake the engine). */
class ContinuousScanEngineModuleTest {
    private val frame =
        ByteBuffer.allocate(64 * 48).let { y ->
            val uv = ByteBuffer.allocate(32 * 24)
            YuvFrame(y = YuvPlane(y, 64, 1), u = YuvPlane(uv, 32, 1), v = YuvPlane(uv, 32, 1), left = 0, top = 0, right = 64, bottom = 48)
        }

    @Test
    fun `the engine numbers frames from zero, restarts at reset and then exports an empty unanchored hand-off`() {
        var detections = 0
        val detector =
            object : StickerCenterDetector {
                override fun detect(
                    frame: YuvFrame,
                    rotationDegrees: Int,
                    viewWidth: Int,
                    viewHeight: Int,
                ): StickerCenterDetection {
                    detections++
                    return StickerCenterDetection.EMPTY
                }
            }
        val engine = ContinuousScanEngineModule.factory(detector).create()

        assertEquals(listOf(0, 1, 2), List(3) { engine.processFrame(frame, 90, 1080, 1920).snapshot.frameIndex })
        assertEquals(3, detections)

        engine.reset()
        assertEquals(listOf(0, 1), List(2) { engine.processFrame(frame, 90, 1080, 1920).snapshot.frameIndex })

        engine.reset()
        val exported = engine.export()
        assertTrue(exported is ContinuousScanExport.Partial)
        assertTrue(exported.handoff.faces.isEmpty())
        assertFalse(exported.handoff.globalAnchored)
    }
}
