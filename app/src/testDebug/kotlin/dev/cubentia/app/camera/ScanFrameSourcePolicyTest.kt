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

    @Test
    fun `the cube metering point covers the detected outline's bounding box`() {
        val quads =
            listOf(
                listOf(CornerPoint(200.0, 100.0), CornerPoint(300.0, 150.0), CornerPoint(200.0, 200.0), CornerPoint(100.0, 150.0)),
                listOf(CornerPoint(100.0, 150.0), CornerPoint(200.0, 200.0), CornerPoint(200.0, 340.0), CornerPoint(100.0, 290.0)),
            )
        val region = checkNotNull(CameraXFrameSource.cubeMeteringRegion(quads, 640, 480))
        assertEquals(200.0, region.x, 1e-9)
        assertEquals(220.0, region.y, 1e-9)
        assertEquals(240.0 / 640.0, region.size, 1e-9)
        // Points off the frame are clamped; no outline or no frame size means no cube region.
        val clamped =
            checkNotNull(
                CameraXFrameSource.cubeMeteringRegion(listOf(listOf(CornerPoint(-50.0, -50.0), CornerPoint(700.0, 100.0))), 640, 480),
            )
        assertEquals(320.0, clamped.x, 1e-9)
        assertEquals(null, CameraXFrameSource.cubeMeteringRegion(emptyList(), 640, 480))
        assertEquals(null, CameraXFrameSource.cubeMeteringRegion(quads, 0, 0))
    }

    @Test
    fun `unsupported cube metering falls back to the grid centre`() {
        val outline = MeteringRegion(200.0, 220.0, 0.4)
        val centre = MeteringRegion(320.0, 240.0, 0.5)
        assertEquals(outline to true, CameraXFrameSource.chooseMetering(outline, centre) { true })
        assertEquals(centre to false, CameraXFrameSource.chooseMetering(outline, centre) { it != outline })
        assertEquals(centre to true, CameraXFrameSource.chooseMetering(null, centre) { true })
    }

    @Test
    fun `the settle signal waits for the future and AE convergence, capped`() {
        val converged = android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED
        val searching = android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_SEARCHING
        assertEquals(Settle.SETTLING, CameraXFrameSource.settleOutcome(false, converged, 100))
        assertEquals(Settle.SETTLING, CameraXFrameSource.settleOutcome(true, searching, 100))
        assertEquals(Settle.SETTLED, CameraXFrameSource.settleOutcome(true, converged, 100))
        assertEquals(
            Settle.SETTLED,
            CameraXFrameSource.settleOutcome(true, android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_LOCKED, 100),
        )
        // An AE state still unknown settles only at the cap, as a timeout.
        assertEquals(Settle.SETTLING, CameraXFrameSource.settleOutcome(true, null, 100))
        assertEquals(Settle.TIMEOUT, CameraXFrameSource.settleOutcome(true, null, CameraXFrameSource.SETTLE_CAP_MS))
        assertEquals(Settle.SETTLING, CameraXFrameSource.settleOutcome(true, searching, CameraXFrameSource.SETTLE_CAP_MS - 1))
        assertEquals(Settle.TIMEOUT, CameraXFrameSource.settleOutcome(true, searching, CameraXFrameSource.SETTLE_CAP_MS))
        assertEquals(Settle.TIMEOUT, CameraXFrameSource.settleOutcome(false, null, CameraXFrameSource.SETTLE_CAP_MS))
    }

    @Test
    fun `the settle tracker counts only capture results after the request and only the latest request's future`() {
        val converged = android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED
        val searching = android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_SEARCHING
        val tracker = SettleTracker()
        assertEquals(null, tracker.outcome(0))
        tracker.onCaptureResult(10, converged)
        val first = tracker.request(1000)
        tracker.onFutureDone(first)
        // The converged state of frame 10 came before the request: stale, it does not settle.
        assertEquals(Settle.SETTLING, tracker.outcome(1100))
        tracker.onCaptureResult(10, converged)
        assertEquals("a result not newer than the request's floor is ignored", Settle.SETTLING, tracker.outcome(1100))
        tracker.onCaptureResult(11, searching)
        assertEquals(Settle.SETTLING, tracker.outcome(1200))
        tracker.onCaptureResult(12, converged)
        assertEquals(Settle.SETTLED, tracker.outcome(1300))
        // A new request resets both halves; the old request's future never completes it.
        val second = tracker.request(2000)
        tracker.onFutureDone(first)
        tracker.onCaptureResult(13, converged)
        assertEquals(Settle.SETTLING, tracker.outcome(2100))
        tracker.onFutureDone(second)
        assertEquals(Settle.SETTLED, tracker.outcome(2100))
        assertEquals(100L, tracker.elapsed(2100))
        val unknown = SettleTracker()
        val only = unknown.request(0)
        unknown.onFutureDone(only)
        unknown.onCaptureResult(1, null)
        assertEquals(Settle.SETTLING, unknown.outcome(CameraXFrameSource.SETTLE_CAP_MS - 1))
        assertEquals(Settle.TIMEOUT, unknown.outcome(CameraXFrameSource.SETTLE_CAP_MS))
    }

    @Test
    fun `a camera reopen resets the settle tracker so restarted frame numbers count and old futures are dropped`() {
        val converged = android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED
        val tracker = SettleTracker()
        tracker.onCaptureResult(500, converged)
        val before = tracker.request(0)
        tracker.reset()
        assertEquals("a reset drops the pending request", null, tracker.outcome(100))
        val after = tracker.request(1000)
        // The request before the reset completes late: it must not mark the new request done.
        tracker.onFutureDone(before)
        // The reopened camera numbers its results from 1 again: they count, not only those after frame 500.
        tracker.onCaptureResult(1, converged)
        assertEquals(Settle.SETTLING, tracker.outcome(1100))
        tracker.onFutureDone(after)
        assertEquals(Settle.SETTLED, tracker.outcome(1100))
    }

    @Test
    fun `a request takes its generation before clearing the future, so the previous future never completes it`() {
        val converged = android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED
        val tracker = SettleTracker()
        val first = tracker.request(0)
        val second = tracker.request(10)
        assertTrue(second != first)
        tracker.onFutureDone(first)
        tracker.onCaptureResult(1, converged)
        assertEquals(Settle.SETTLING, tracker.outcome(20))
        tracker.onFutureDone(second)
        assertEquals(Settle.SETTLED, tracker.outcome(20))
    }

    @Test
    fun `metering flags keep AWB for hold 1 only`() {
        val all = CameraXFrameSource.meteringFlags(includeAwb = true)
        val noAwb = CameraXFrameSource.meteringFlags(includeAwb = false)
        assertTrue(all and androidx.camera.core.FocusMeteringAction.FLAG_AWB != 0)
        assertEquals(0, noAwb and androidx.camera.core.FocusMeteringAction.FLAG_AWB)
    }
}
