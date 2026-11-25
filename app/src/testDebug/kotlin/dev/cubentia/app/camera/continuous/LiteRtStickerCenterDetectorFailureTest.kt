package dev.cubentia.app.camera.continuous

import dev.cubentia.app.camera.YuvFrame
import dev.cubentia.app.camera.YuvPlane
import dev.cubentia.domain.recognition.continuous.ContinuousScanCue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.FileNotFoundException
import java.nio.ByteBuffer

/** Story 10.31 I/O matrix: missing asset, corrupt buffer, LinkageError, tiny crop, and post-close degrade safely to empty. */
class LiteRtStickerCenterDetectorFailureTest {
    private val validFrame = makeBlankYuv(64, 48)

    private fun makeBlankYuv(
        width: Int,
        height: Int,
        cropRight: Int = width,
        cropBottom: Int = height,
    ): YuvFrame {
        val y = ByteBuffer.allocate(maxOf(1, width * height))
        val uv = ByteBuffer.allocate(maxOf(1, (width / 2) * (height / 2)))
        return YuvFrame(
            y = YuvPlane(y, maxOf(1, width), 1),
            u = YuvPlane(uv, maxOf(1, width / 2), 1),
            v = YuvPlane(uv, maxOf(1, width / 2), 1),
            left = 0,
            top = 0,
            right = cropRight,
            bottom = cropBottom,
        )
    }

    @Test
    fun `missing model asset returns empty on every frame and attempts load only once`() {
        var loads = 0
        val detector =
            LiteRtStickerCenterDetector {
                loads++
                throw FileNotFoundException(StickerCenterModelContract.MODEL_ASSET)
            }
        repeat(3) {
            val result = detector.detect(validFrame, rotationDegrees = 90, viewWidth = 1080, viewHeight = 1920)
            assertTrue(result.peaks.isEmpty())
        }
        assertEquals(1, loads)
    }

    @Test
    fun `corrupt model buffer or LinkageError returns empty without crashing`() {
        val corrupt = LiteRtStickerCenterDetector { ByteBuffer.allocateDirect(16) }
        assertTrue(corrupt.detect(validFrame, 0).peaks.isEmpty())
        assertTrue(corrupt.detect(validFrame, 0).peaks.isEmpty())

        var linkageLoads = 0
        val unlinked =
            LiteRtStickerCenterDetector {
                linkageLoads++
                throw UnsatisfiedLinkError("libLiteRt.so missing")
            }
        repeat(2) { assertTrue(unlinked.detect(validFrame, 0).peaks.isEmpty()) }
        assertEquals(1, linkageLoads)
    }

    @Test
    fun `empty or degenerate crop dimensions return empty without touching model loader`() {
        var loads = 0
        val detector =
            LiteRtStickerCenterDetector {
                loads++
                ByteBuffer.allocateDirect(16)
            }
        val zeroWidth = makeBlankYuv(64, 48, cropRight = 0, cropBottom = 48)
        val zeroHeight = makeBlankYuv(64, 48, cropRight = 64, cropBottom = 0)
        val negLeft = YuvFrame(validFrame.y, validFrame.u, validFrame.v, left = -4, top = 0, right = 64, bottom = 48)
        val negTop = YuvFrame(validFrame.y, validFrame.u, validFrame.v, left = 0, top = -4, right = 64, bottom = 48)
        assertTrue(detector.detect(zeroWidth, 0).peaks.isEmpty())
        assertTrue(detector.detect(zeroHeight, 90).peaks.isEmpty())
        assertTrue(detector.detect(negLeft, 0).peaks.isEmpty())
        assertTrue(detector.detect(negTop, 90).peaks.isEmpty())
        assertTrue(detector.detectRgb(ByteArray(0), 0, 0).peaks.isEmpty())
        assertTrue(detector.detectRgb(ByteArray(16), 50_000, 50_000).peaks.isEmpty())
        assertEquals(0, loads)
    }

    @Test
    fun `closed detector returns empty safely and feeds cleanly through ContinuousScanPipeline`() {
        var loads = 0
        val detector =
            LiteRtStickerCenterDetector {
                loads++
                throw FileNotFoundException("should not be loaded after close")
            }
        detector.close()
        assertTrue(detector.detect(validFrame, 0).peaks.isEmpty())
        assertEquals(0, loads)

        val pipeline = ContinuousScanPipeline(detector)
        val out = pipeline.processFrame(frameIndex = 0, frame = validFrame, rotationDegrees = 90, viewWidth = 1080, viewHeight = 1920)
        assertTrue(out.fittedFaces.isEmpty())
        assertEquals(ContinuousScanCue.BringIntoView, out.snapshot.cue)
        assertEquals(0, out.snapshot.committedCount)
    }
}
