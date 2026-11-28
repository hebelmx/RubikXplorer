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
    fun `the continuous Quick Scan targets the full-frame size whatever the pane`() {
        val full = Size(1280, 960)
        assertEquals(full, FULL_FRAME_ANALYSIS)
        for ((w, h) in listOf(1080 to 1440, 1440 to 1080, 800 to 800, 0 to 0, 640 to 203, 1600 to 1200)) {
            assertEquals(full, analysisTarget(w, h, continuous = true))
        }
        // Guided, calibration and corner keep the pane rule.
        assertEquals(Size(640, 480), analysisTarget(1080, 1440, continuous = false))
    }

    @Test
    fun `a viewport longer than 4 to 3 targets 1280 by 960`() {
        val large = Size(1280, 960)
        assertEquals(large, analysisTarget(640, 203))
        assertEquals(large, analysisTarget(203, 640))
        assertEquals(large, analysisTarget(1081, 810))
        assertEquals(large, analysisTarget(1080, 1920))
    }

    @Test
    fun `the torch is lit only when wanted, on a flash unit, in the foreground, in a view that may light it`() {
        fun lit(
            hasFlash: Boolean = true,
            wanted: Boolean = true,
            suspended: Boolean = false,
            corner: Boolean = false,
            calibrating: Boolean = false,
            continuous: Boolean = false,
        ) = CameraXFrameSource.torchLit(hasFlash, wanted, suspended, corner, calibrating, continuous)
        // Story 10.32: the continuous Quick Scan may light it; the Guided grid (no mode) never does.
        assertTrue(lit(continuous = true))
        assertFalse(lit(continuous = false))
        assertTrue(lit(corner = true))
        assertTrue(lit(calibrating = true))
        assertFalse(lit(continuous = true, hasFlash = false))
        assertFalse(lit(continuous = true, wanted = false))
        assertFalse(lit(continuous = true, suspended = true))
        assertFalse(lit(corner = true, calibrating = true, continuous = true, suspended = true))
    }

    @Test
    fun `a late stop from a closed scan releases nothing the next scan bound`() {
        // Scan B's bind already unbound A's use cases and bound its own.
        val bound = mutableSetOf("previewB", "analysisB")
        val unbound = mutableListOf<String>()
        val released =
            releaseOwnUseCases(listOf("previewA", "analysisA"), bound::contains) {
                unbound += it
                bound -= it.toSet()
            }
        assertFalse(released)
        assertTrue(unbound.isEmpty())
        assertEquals(setOf("previewB", "analysisB"), bound)
    }

    @Test
    fun `a normal stop releases this source's own use cases`() {
        val bound = mutableSetOf("preview", "analysis")
        val unbound = mutableListOf<String>()
        val released =
            releaseOwnUseCases(listOf("preview", "analysis"), bound::contains) {
                unbound += it
                bound -= it.toSet()
            }
        assertTrue(released)
        assertEquals(listOf("preview", "analysis"), unbound)
        assertTrue(bound.isEmpty())
    }

    @Test
    fun `a stop before any bind releases nothing`() {
        var calls = 0
        assertFalse(releaseOwnUseCases(emptyList<String>(), { true }) { calls++ })
        assertEquals(0, calls)
    }
}
