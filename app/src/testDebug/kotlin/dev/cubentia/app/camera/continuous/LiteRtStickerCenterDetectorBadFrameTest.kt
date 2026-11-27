package dev.cubentia.app.camera.continuous

import android.app.Application
import dev.cubentia.app.ScanDiagnostics
import dev.cubentia.app.camera.StickerCenterDetection
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LiteRtStickerCenterDetectorBadFrameTest {
    @Test
    fun `only bad frames log their cumulative count at powers of two`() {
        val detector = CountingStickerCenterSession().detector
        val valid = makeBlankYuv(64, 48)
        val short = makeBlankYuv(64, 48).apply { y.buffer.limit(1) }
        ShadowLog.clear()

        fun logged() = ShadowLog.getLogsForTag(ScanDiagnostics.TAG).map { it.msg }

        repeat(4) { assertEquals(StickerCenterDetection.SKIPPED, detector.detect(short, 0)) }
        assertEquals(4, detector.failedFrames)
        val firstFour = listOf("quickScan frameFailed count=1", "quickScan frameFailed count=2", "quickScan frameFailed count=4")
        assertEquals(firstFour, logged())

        // A good frame right after a logged count reports nothing: a repeated report of 4 would log it again.
        assertEquals(valid.width, detector.detect(valid, 0).uprightWidth)
        assertEquals(4, detector.failedFrames)
        assertEquals(firstFour, logged())

        // Counts 5, 6 and 7 stay silent; 8 is the next line.
        repeat(4) { assertEquals(StickerCenterDetection.SKIPPED, detector.detect(short, 0)) }
        assertEquals(8, detector.failedFrames)
        assertEquals(firstFour + "quickScan frameFailed count=8", logged())
        // Fixed words and the count only: no exception rides along.
        assertEquals(List(4) { null }, ShadowLog.getLogsForTag(ScanDiagnostics.TAG).map { it.throwable })
    }

    @Test
    fun `a frame the pipeline could not sample reaches the same diagnostics count as a bad frame (GH #397)`() {
        val detector = CountingStickerCenterSession().detector
        val short = makeBlankYuv(64, 48).apply { y.buffer.limit(1) }
        ShadowLog.clear()

        fun logged() = ShadowLog.getLogsForTag(ScanDiagnostics.TAG).map { it.msg }

        detector.countFailedFrame()
        assertEquals(listOf("quickScan frameFailed count=1"), logged())
        assertEquals(StickerCenterDetection.SKIPPED, detector.detect(short, 0))
        assertEquals(listOf("quickScan frameFailed count=1", "quickScan frameFailed count=2"), logged())
        assertEquals(2, detector.failedFrames)
    }
}
