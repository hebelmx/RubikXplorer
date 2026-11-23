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

    private val converged = android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED
    private val searching = android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_SEARCHING
    private val window = CameraXFrameSource.SETTLE_WINDOW_MS

    /** One capture result at [atMs]: 20 ms exposure at [iso] unless told otherwise. */
    private fun SettleTracker.result(
        frame: Long,
        atMs: Long,
        iso: Int? = 125,
        exposureNs: Long? = 20_000_000,
        ae: Int? = converged,
    ) = onCaptureResult(frame, ae, exposureNs, iso, atMs)

    @Test
    fun `the settle signal waits for the future and a stable exposure window, capped`() {
        assertEquals(Settle.SETTLING, CameraXFrameSource.settleOutcome(false, window, 100))
        assertEquals(Settle.SETTLING, CameraXFrameSource.settleOutcome(true, window - 1, 100))
        assertEquals(Settle.SETTLED, CameraXFrameSource.settleOutcome(true, window, 100))
        // No exposure level known (the camera reports none): it settles only at the cap, as a timeout.
        assertEquals(Settle.SETTLING, CameraXFrameSource.settleOutcome(true, null, 100))
        assertEquals(Settle.TIMEOUT, CameraXFrameSource.settleOutcome(true, null, CameraXFrameSource.SETTLE_CAP_MS))
        assertEquals(Settle.SETTLING, CameraXFrameSource.settleOutcome(true, 0, CameraXFrameSource.SETTLE_CAP_MS - 1))
        assertEquals(Settle.TIMEOUT, CameraXFrameSource.settleOutcome(true, 0, CameraXFrameSource.SETTLE_CAP_MS))
        assertEquals(Settle.TIMEOUT, CameraXFrameSource.settleOutcome(false, null, CameraXFrameSource.SETTLE_CAP_MS))
    }

    @Test
    fun `the settle tracker counts only capture results after the request and the future, of the latest request`() {
        val tracker = SettleTracker()
        assertEquals(null, tracker.outcome(0))
        tracker.result(10, 900)
        val first = tracker.request(1000)
        tracker.onFutureDone(first)
        // Frame 10 came before the request: stale, it starts no exposure level.
        tracker.result(10, 1050)
        assertEquals("a result not newer than the request's floor is ignored", null, tracker.stableMs())
        tracker.result(11, 1100)
        tracker.result(12, 1100 + window - 1)
        assertEquals(Settle.SETTLING, tracker.outcome(1100 + window - 1))
        tracker.result(13, 1100 + window)
        assertEquals(Settle.SETTLED, tracker.outcome(1100 + window))
        // A new request resets both halves; the old request's future never completes it, and results that arrive
        // before the future completes do not count toward the window.
        val second = tracker.request(3000)
        tracker.onFutureDone(first)
        tracker.result(14, 3000)
        tracker.result(15, 3000 + window)
        assertEquals(Settle.SETTLING, tracker.outcome(3000 + window))
        tracker.onFutureDone(second)
        tracker.result(16, 3700)
        assertEquals(Settle.SETTLING, tracker.outcome(3700))
        tracker.result(17, 3700 + window)
        assertEquals(Settle.SETTLED, tracker.outcome(3700 + window))
        assertEquals(100L, tracker.elapsed(3100))
    }

    @Test
    fun `a converged AE state alone never settles, and a moving or searching exposure restarts the window`() {
        // No exposure time or ISO reported: converged or not, only the cap releases the wait.
        val unknown = SettleTracker()
        unknown.onFutureDone(unknown.request(0))
        unknown.result(1, 0, iso = null, exposureNs = null)
        unknown.result(2, 1000, iso = null, exposureNs = null)
        assertEquals(Settle.SETTLING, unknown.outcome(CameraXFrameSource.SETTLE_CAP_MS - 1))
        assertEquals(Settle.TIMEOUT, unknown.outcome(CameraXFrameSource.SETTLE_CAP_MS))

        // 5% inside one level holds the window; the 14% step to the other level restarts it.
        val moving = SettleTracker()
        moving.onFutureDone(moving.request(0))
        moving.result(1, 0, iso = 125)
        moving.result(2, 300, iso = 131)
        assertEquals(300L, moving.stableMs())
        moving.result(3, 500, iso = 144)
        assertEquals(0L, moving.stableMs())
        moving.result(4, 500 + window, iso = 147)
        assertEquals(Settle.SETTLED, moving.outcome(500 + window))

        // AE still searching: no level, whatever the exposure says.
        val hunting = SettleTracker()
        hunting.onFutureDone(hunting.request(0))
        hunting.result(1, 0)
        hunting.result(2, 400, ae = searching)
        assertEquals(null, hunting.stableMs())
        hunting.result(3, 500)
        hunting.result(4, 500 + window)
        assertEquals(Settle.SETTLED, hunting.outcome(500 + window))
    }

    private data class Exposure(
        val tMs: Long,
        val ae: Int?,
        val exposureNs: Long,
        val iso: Int,
    )

    /** The exposure series of still burst 1 (tablet and cube resting, 2026-11-22). */
    private fun stillBurst(): List<Exposure> =
        java.io
            .File("../docs/evidence/quick-scan-2026-11-22/bursts/20261122-114155-still.jsonl")
            .readLines()
            .filter { it.isNotBlank() }
            .map { line ->
                val json = org.json.JSONObject(line)
                val exposure = json.getJSONObject("exposure")
                Exposure(json.getLong("tMs"), exposure.optInt("ae"), exposure.getLong("exposureNs"), exposure.getInt("iso"))
            }

    /** [frames] as capture results into a tracker whose metering future completed at the first one; the settle per frame. */
    private fun settledFrames(frames: List<Exposure>): List<Pair<Exposure, Boolean>> {
        val tracker = SettleTracker()
        tracker.onFutureDone(tracker.request(frames.first().tMs))
        return frames.mapIndexed { index, frame ->
            tracker.onCaptureResult(index + 1L, frame.ae, frame.exposureNs, frame.iso, frame.tMs)
            frame to (CameraXFrameSource.settleOutcome(true, tracker.stableMs(), 0) == Settle.SETTLED)
        }
    }

    @Test
    fun `still burst 1's exposure swing is reported not settled throughout`() {
        val frames = stillBurst()
        val swing = 1500L..3300L
        // The swing: exposure alternates between 20 ms and 30 ms while most frames report AE converged (F2).
        val swinging = frames.filter { it.tMs in swing }
        assertEquals(setOf(20_000_000L, 30_000_000L), swinging.map { it.exposureNs }.toSet())
        assertTrue(swinging.count { it.ae == converged } * 2 > swinging.size)
        // Whenever the metering future completes, before or inside the swing, no frame of the swing reads settled.
        frames.indices.filter { frames[it].tMs <= swing.last }.forEach { start ->
            settledFrames(frames.drop(start)).forEach { (frame, settled) ->
                if (frame.tMs in swing) assertFalse("future done at ${frames[start].tMs}: settled at ${frame.tMs}", settled)
            }
        }
        // Before the swing the level holds long enough to settle: the lock taken then keeps that exposure.
        val before = settledFrames(frames).first { it.second }.first.tMs
        assertTrue("settled at $before", before <= frames.first().tMs + window + 100)
        // After the swing the exposure holds one level and settles a window later.
        val after = frames.filter { it.tMs > swing.last }
        val settledAt = settledFrames(after).first { it.second }.first.tMs
        assertTrue("settled at $settledAt", settledAt <= after.first().tMs + window + 200)
    }

    @Test
    fun `a camera reopen resets the settle tracker so restarted frame numbers count and old futures are dropped`() {
        val tracker = SettleTracker()
        tracker.result(500, 0)
        val before = tracker.request(0)
        tracker.reset()
        assertEquals("a reset drops the pending request", null, tracker.outcome(100))
        val after = tracker.request(1000)
        // The request before the reset completes late: it must not mark the new request done.
        tracker.onFutureDone(before)
        tracker.result(1, 1000)
        tracker.result(2, 1000 + window)
        assertEquals(Settle.SETTLING, tracker.outcome(1000 + window))
        tracker.onFutureDone(after)
        // The reopened camera numbers its results from 1 again: they count, not only those after frame 500.
        tracker.result(3, 1700)
        tracker.result(4, 1700 + window)
        assertEquals(Settle.SETTLED, tracker.outcome(1700 + window))
    }

    @Test
    fun `a request takes its generation before clearing the future, so the previous future never completes it`() {
        val tracker = SettleTracker()
        val first = tracker.request(0)
        val second = tracker.request(10)
        assertTrue(second != first)
        tracker.onFutureDone(first)
        tracker.result(1, 20)
        tracker.result(2, 20 + window)
        assertEquals(Settle.SETTLING, tracker.outcome(20 + window))
        tracker.onFutureDone(second)
        tracker.result(3, 700)
        tracker.result(4, 700 + window)
        assertEquals(Settle.SETTLED, tracker.outcome(700 + window))
    }

    @Test
    fun `exposure locks from settle to capture or re-meter, and hold 1's white balance is carried to hold 2`() {
        val none = ExposureLocks.NONE
        // Hold 1 meters AE and AWB; once settled both are locked.
        val hold1 = ExposureLocks.onSettled(ExposureLocks.onMeter(none, includeAwb = true), true, aeSupported = true, awbSupported = true)
        assertEquals(ExposureLocks(ae = true, awb = true), hold1)
        assertEquals("on+awb", ExposureLocks.label(hold1))
        // A re-meter during hold 1 frees both, so the metering can move them.
        assertEquals(none, ExposureLocks.onMeter(hold1, includeAwb = true))
        // The capture frees the exposure; the white balance stays for hold 2, whose metering leaves it alone.
        val captured = ExposureLocks.onCaptured(hold1)
        assertEquals(ExposureLocks(ae = false, awb = true), captured)
        val metering2 = ExposureLocks.onMeter(captured, includeAwb = false)
        assertEquals(ExposureLocks(ae = false, awb = true), metering2)
        assertEquals(
            ExposureLocks(ae = true, awb = true),
            ExposureLocks.onSettled(metering2, false, aeSupported = true, awbSupported = true),
        )
        // Hold 2 with no white balance carried (after a back-out) locks the exposure only.
        val alone = ExposureLocks.onSettled(none, false, aeSupported = true, awbSupported = true)
        assertEquals(ExposureLocks(ae = true, awb = false), alone)
        assertEquals("on", ExposureLocks.label(alone))
        // A camera that cannot lock its exposure behaves as before and says so.
        val unsupported = ExposureLocks.onSettled(none, true, aeSupported = false, awbSupported = false)
        assertEquals(none, unsupported)
        assertEquals(CameraXFrameSource.LOCK_UNSUPPORTED, ExposureLocks.label(unsupported))
        assertEquals(ExposureLocks(ae = true, awb = false), ExposureLocks.onSettled(none, true, aeSupported = true, awbSupported = false))
    }

    @Test
    fun `a torch change or a camera stop makes the locked white balance stale, and the next metering redoes it`() {
        val hold2 = ExposureLocks(ae = true, awb = true)
        // Torch toggled at hold 2: the metering that follows meters white balance although hold 2 does not ask for it,
        // releases the old lock to do so, and locks the new one once settled.
        val toggled = ExposureLocks.onTorchChanged(hold2)
        assertTrue(ExposureLocks.metersAwb(toggled, includeAwb = false))
        val metering = ExposureLocks.onMeter(toggled, includeAwb = false)
        assertEquals(ExposureLocks(ae = false, awb = false, awbStale = true), metering)
        assertEquals(
            ExposureLocks(ae = true, awb = true),
            ExposureLocks.onSettled(metering, includeAwb = true, aeSupported = true, awbSupported = true),
        )
        // Without a change hold 2 leaves white balance alone.
        assertFalse(ExposureLocks.metersAwb(hold2, includeAwb = false))
        // A stop (or the corner view ending) drops every lock; a white balance that was locked comes back stale.
        val stopped = ExposureLocks.cleared(hold2)
        assertEquals(ExposureLocks(ae = false, awb = false, awbStale = true), stopped)
        assertFalse(stopped.held)
        assertTrue(ExposureLocks.metersAwb(stopped, includeAwb = false))
        assertEquals(ExposureLocks.NONE, ExposureLocks.cleared(ExposureLocks(ae = true, awb = false)))
        // Stale survives a capture or a lost cube, until a metering that includes white balance settles.
        assertTrue(ExposureLocks.onCaptured(toggled).awbStale)
        assertTrue(ExposureLocks.onSettled(toggled, includeAwb = false, aeSupported = true, awbSupported = true).awbStale)
    }

    @Test
    fun `the settle tracker reports how long the metering future took`() {
        val tracker = SettleTracker()
        val request = tracker.request(1000)
        assertEquals(null, tracker.futureMs())
        tracker.onFutureDone(request, 1420)
        assertEquals(420L, tracker.futureMs())
        // A late completion of an older request changes nothing; a new request starts over.
        val next = tracker.request(3000)
        tracker.onFutureDone(request, 3100)
        assertEquals(null, tracker.futureMs())
        tracker.onFutureDone(next, 3250)
        assertEquals(250L, tracker.futureMs())
    }

    @Test
    fun `metering flags keep AWB for hold 1 only`() {
        val all = CameraXFrameSource.meteringFlags(includeAwb = true)
        val noAwb = CameraXFrameSource.meteringFlags(includeAwb = false)
        assertTrue(all and androidx.camera.core.FocusMeteringAction.FLAG_AWB != 0)
        assertEquals(0, noAwb and androidx.camera.core.FocusMeteringAction.FLAG_AWB)
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
