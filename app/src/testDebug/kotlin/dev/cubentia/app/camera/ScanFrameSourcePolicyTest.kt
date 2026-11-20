package dev.cubentia.app.camera

import android.app.Application
import android.util.Size
import androidx.camera.core.CameraState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** GH #158: which camera errors show the Unavailable card, and the ImageAnalysis target per viewport. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ScanFrameSourcePolicyTest {
    private fun state(
        type: CameraState.Type,
        code: Int? = null,
    ) = CameraState.create(type, code?.let { CameraState.StateError.create(it) })

    @Test
    fun `a recoverable error while opening keeps the viewfinder`() {
        listOf(CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE, CameraState.ERROR_OTHER_RECOVERABLE_ERROR)
            .forEach { code ->
                CameraState.Type.entries.forEach { type -> assertFalse("$type $code", cameraUnavailable(state(type, code))) }
            }
    }

    @Test
    fun `a critical error in any state shows Unavailable`() {
        listOf(
            CameraState.ERROR_CAMERA_DISABLED,
            CameraState.ERROR_CAMERA_FATAL_ERROR,
            CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED,
            CameraState.ERROR_STREAM_CONFIG,
            CameraState.ERROR_CAMERA_REMOVED,
        ).forEach { code ->
            CameraState.Type.entries.forEach { type -> assertTrue("$type $code", cameraUnavailable(state(type, code))) }
        }
    }

    @Test
    fun `no error is never unavailable`() {
        CameraState.Type.entries.forEach { assertFalse("$it", cameraUnavailable(state(it))) }
    }

    @Test
    fun `a viewport up to 4 to 3 keeps 640 by 480`() {
        val default = Size(640, 480)
        assertEquals(default, analysisTarget(1080, 1440))
        assertEquals(default, analysisTarget(1440, 1080))
        assertEquals(default, analysisTarget(800, 800))
        assertEquals(default, analysisTarget(0, 0))
        assertEquals(default, analysisTarget(640, 0))
    }

    @Test
    fun `a viewport longer than 4 to 3 targets 1280 by 960`() {
        val large = Size(1280, 960)
        assertEquals(large, analysisTarget(640, 203))
        assertEquals(large, analysisTarget(203, 640))
        assertEquals(large, analysisTarget(1081, 810))
        assertEquals(large, analysisTarget(1080, 1920))
    }
}
