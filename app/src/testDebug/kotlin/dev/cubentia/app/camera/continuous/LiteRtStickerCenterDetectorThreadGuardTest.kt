package dev.cubentia.app.camera.continuous

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import dev.cubentia.app.camera.StickerCenterDetection
import dev.cubentia.app.camera.YuvFrame
import dev.cubentia.app.camera.YuvPlane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/** Story 10.31 follow-up: a debuggable app refuses inference on the main thread; a release app does not check. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LiteRtStickerCenterDetectorThreadGuardTest {
    /** A zero-width crop: [LiteRtStickerCenterDetector.detect] returns empty before it loads the model. */
    private val degenerate =
        ByteBuffer.allocate(64 * 48).let { y ->
            val uv = ByteBuffer.allocate(32 * 24)
            YuvFrame(y = YuvPlane(y, 64, 1), u = YuvPlane(uv, 32, 1), v = YuvPlane(uv, 32, 1), left = 0, top = 0, right = 0, bottom = 48)
        }

    private fun detector(debuggable: Boolean): LiteRtStickerCenterDetector {
        val app = RuntimeEnvironment.getApplication()
        val info =
            ApplicationInfo(app.applicationInfo).apply {
                flags = if (debuggable) flags or ApplicationInfo.FLAG_DEBUGGABLE else flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
            }
        val context: Context =
            object : ContextWrapper(app) {
                override fun getApplicationInfo() = info
            }
        return LiteRtStickerCenterDetector(context)
    }

    @Test
    fun `a debuggable app throws on the main thread and detects from a background thread`() {
        val detector = detector(debuggable = true)
        assertThrows(IllegalStateException::class.java) { detector.detect(degenerate, 0) }
        val background = Executors.newSingleThreadExecutor()
        try {
            assertSame(StickerCenterDetection.EMPTY, background.submit<StickerCenterDetection> { detector.detect(degenerate, 0) }.get())
        } finally {
            background.shutdownNow()
        }
    }

    @Test
    fun `an app that is not debuggable does not check the thread`() {
        assertEquals(StickerCenterDetection.EMPTY, detector(debuggable = false).detect(degenerate, 0))
    }
}
