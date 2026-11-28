package dev.cubentia.app.camera.continuous

import dev.cubentia.app.camera.DebugPeak
import dev.cubentia.app.camera.QuickScanDebugView
import dev.cubentia.app.camera.StickerCenterDetection
import dev.cubentia.app.camera.StickerCenterDetector
import dev.cubentia.app.camera.StickerCenterPeak
import dev.cubentia.app.camera.YuvFrame
import dev.cubentia.app.camera.YuvPlane
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.continuous.ContinuousScanExport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `the frame that disables detection preserves the last map and new engines see the failure immediately`() {
        var failInference = false
        val session =
            object : StickerCenterInferenceSession {
                override fun run(
                    input: ByteBuffer,
                    output: ByteBuffer,
                ) {
                    check(!failInference)
                }

                override fun close() = Unit
            }
        val detector = LiteRtStickerCenterDetector(loadModel = { ByteBuffer.allocateDirect(1) }, openSession = { session })
        val factory = ContinuousScanEngineModule.factory(detector)
        val engine = factory.create()
        assertFalse(engine.detectorUnavailable)
        val previous = engine.processFrame(frame, 90, 1080, 1920)
        val exported = engine.export()
        failInference = true
        repeat(2) {
            assertEquals(previous.snapshot, engine.processFrame(frame, 90, 1080, 1920).snapshot)
            assertEquals(exported, engine.export())
            assertTrue(engine.detectorUnavailable)
        }
        assertTrue(factory.create().detectorUnavailable)
    }

    @Test
    fun `the factory-built engine hands each frame's saved calibration and torch state to the mapper`() {
        // Story 10.37 E9.
        val detector =
            object : StickerCenterDetector {
                override val unavailable = false

                override fun detect(
                    frame: YuvFrame,
                    rotationDegrees: Int,
                    viewWidth: Int,
                    viewHeight: Int,
                ): StickerCenterDetection = StickerCenterDetection.EMPTY
            }
        val engine = ContinuousScanEngineModule.factory(detector).create()
        val mapper = (engine as PipelineEngine).pipeline.mapper
        val saved = CalibrationCenters(Color.entries.associateWith { Lab(50.0, it.ordinal * 10.0, 0.0) }, torchOn = true)
        engine.processFrame(frame, 90, 1080, 1920, saved, true)
        assertEquals(saved, mapper.frameCalibration)
        assertEquals(true, mapper.frameTorchLit)
        engine.processFrame(frame, 90, 1080, 1920, null, false)
        assertNull(mapper.frameCalibration)
        assertEquals(false, mapper.frameTorchLit)
    }

    @Test
    fun `over a detector that is already unavailable every engine reports it before any frame and reads no frame`() {
        var detections = 0
        val detector =
            object : StickerCenterDetector {
                override val unavailable = true

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
        val factory = ContinuousScanEngineModule.factory(detector)
        val engine = factory.create()
        assertTrue(engine.detectorUnavailable)
        val second = factory.create()
        assertTrue(second.detectorUnavailable)
        val exported = engine.export()
        // A frame handed to the engine all the same is not read: an empty scanning snapshot, and the map as it was.
        val snapshots = List(3) { engine.processFrame(frame, 90, 1080, 1920).snapshot }
        assertTrue(snapshots.all { it.mappedFacesCount == 0 && it.committedCount == 0 && it.trackedQuads.isEmpty() })
        assertEquals(0, detections)
        assertEquals(exported, engine.export())
        assertTrue(exported.handoff.faces.isEmpty())
        assertTrue(engine.detectorUnavailable)
    }

    /** GH #409: the engine hands the pipeline's debug overlay to the screen with the debug view on, and none with it off. */
    @Test
    fun `the engine forwards the pipeline's debug overlay only while the debug view is on`() {
        val detector =
            object : StickerCenterDetector {
                override val unavailable = false

                override fun detect(
                    frame: YuvFrame,
                    rotationDegrees: Int,
                    viewWidth: Int,
                    viewHeight: Int,
                ) = StickerCenterDetection(listOf(StickerCenterPeak(10.0, 20.0, 0.75f, 0.25, 0.5)), 48, 64)
            }
        try {
            QuickScanDebugView.set(false)
            val engine = ContinuousScanEngineModule.factory(detector).create()
            assertNull(engine.processFrame(frame, 90, 1080, 1920).debug)
            QuickScanDebugView.set(true)
            val debug = checkNotNull(engine.processFrame(frame, 90, 1080, 1920).debug)
            val expected = checkNotNull(ContinuousScanPipeline(detector, timings = null).processFrame(0, frame, 90, 1080, 1920).debug)
            assertEquals(listOf(DebugPeak(0.25, 0.5, 0.75f)), debug.peaks)
            assertEquals(expected.peaks, debug.peaks)
            assertEquals(expected.lattices, debug.lattices)
            // The engine's pipeline is timed in debug builds, so its line adds the stage timings after the same counts.
            assertTrue(debug.counts.startsWith(expected.counts), debug.counts)
            QuickScanDebugView.set(false)
            assertNull(engine.processFrame(frame, 90, 1080, 1920).debug)
        } finally {
            QuickScanDebugView.set(false)
        }
    }

    @Test
    fun `the engine numbers frames from zero, restarts at reset and then exports an empty unanchored hand-off`() {
        var detections = 0
        val detector =
            object : StickerCenterDetector {
                override val unavailable = false

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
