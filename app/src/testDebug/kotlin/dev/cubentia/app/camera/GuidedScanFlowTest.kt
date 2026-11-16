package dev.cubentia.app.camera

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.CellReading
import dev.cubentia.domain.recognition.Coaching
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.FrameStats
import dev.cubentia.domain.recognition.GuidedScanController
import dev.cubentia.domain.recognition.GuidedScanEffect
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.ScanReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Story 10.2: the Activity's pure permission and effect mappings, and the mini net's upright order. */
class GuidedScanFlowTest {
    private fun sample(
        stickers: List<Lab> = List(9) { Lab(it.toDouble(), 0.0, 0.0) },
        orientation: Int = 0,
    ) = FaceSample(
        face = Face.F,
        orientationDegrees = orientation,
        stickers = stickers,
        quality = CaptureQuality.ACCEPTABLE,
        centerColor = Color.GREEN,
        upNeighborColor = Color.WHITE,
    )

    @Test
    fun `the rationale comes first without the permission, the viewfinder with it`() {
        assertEquals(GuidedScanPhase.Rationale, GuidedScanFlow.initialPhase(hasPermission = false))
        assertEquals(GuidedScanPhase.Viewfinder(), GuidedScanFlow.initialPhase(hasPermission = true))
    }

    private fun denied(
        rationaleBefore: Boolean,
        rationaleAfter: Boolean,
        deniedBefore: Boolean,
    ) = GuidedScanFlow.afterPermission(false, rationaleBefore, rationaleAfter, deniedBefore)

    @Test
    fun `a grant opens the viewfinder`() {
        assertEquals(
            GuidedScanPhase.Viewfinder(),
            GuidedScanFlow.afterPermission(true, rationaleBefore = true, rationaleAfter = false, deniedBefore = true),
        )
    }

    @Test
    fun `a first-ever dismissal with no rationale before or after is not permanent (Android 11+)`() {
        assertEquals(GuidedScanPhase.Denied(false), denied(rationaleBefore = false, rationaleAfter = false, deniedBefore = false))
    }

    @Test
    fun `a denial after which the system still shows a rationale is not permanent`() {
        assertEquals(GuidedScanPhase.Denied(false), denied(rationaleBefore = false, rationaleAfter = true, deniedBefore = false))
        assertEquals(GuidedScanPhase.Denied(false), denied(rationaleBefore = true, rationaleAfter = true, deniedBefore = true))
    }

    @Test
    fun `a second denial that removes the rationale is permanent`() {
        assertEquals(GuidedScanPhase.Denied(true), denied(rationaleBefore = true, rationaleAfter = false, deniedBefore = false))
    }

    @Test
    fun `an already permanent denial in a later scan is permanent`() {
        assertEquals(GuidedScanPhase.Denied(true), denied(rationaleBefore = false, rationaleAfter = false, deniedBefore = true))
    }

    @Test
    fun `effects map to remetering and to the Captured result`() {
        val samples = List(6) { sample() }
        assertTrue(GuidedScanFlow.remeters(listOf(GuidedScanEffect.Capture(samples[0]), GuidedScanEffect.Remeter)))
        assertFalse(GuidedScanFlow.remeters(listOf(GuidedScanEffect.Capture(samples[0]), GuidedScanEffect.Done(samples))))
        assertNull(GuidedScanFlow.resultOf(listOf(GuidedScanEffect.Capture(samples[0]), GuidedScanEffect.Remeter)))
        assertEquals(
            ScanReview.fromSamples(samples),
            GuidedScanFlow.resultOf(listOf(GuidedScanEffect.Capture(samples[5]), GuidedScanEffect.Done(samples))),
        )
    }

    @Test
    fun `the viewfinder phase mirrors the controller`() {
        val controller = GuidedScanController()
        val cells = List(9) { CellReading(Lab(55.0, -60.0, 40.0), true) }
        controller.onFrame(FrameStats(cells, 120.0, 0.0, 100.0, 12), 0, 0)
        controller.onShutter(10)
        controller.onFrame(FrameStats(cells, 10.0, 0.0, 100.0, 12), 0, 20)
        val phase = GuidedScanFlow.phaseOf(controller)
        assertEquals(1, phase.stepIndex)
        assertEquals(controller.captured, phase.captured)
        assertEquals(Coaching.LOW_LIGHT, phase.coaching)
        assertFalse(phase.canUseAnyway)
    }

    @Test
    fun `uprightNet turns pixel order clockwise by the orientation`() {
        val pixels = List(9) { Lab(it.toDouble(), 0.0, 0.0) }

        fun upright(orientation: Int) = uprightNet(sample(pixels, orientation)).map { it.l.toInt() }
        assertEquals((0..8).toList(), upright(0))
        assertEquals(listOf(6, 3, 0, 7, 4, 1, 8, 5, 2), upright(90))
        assertEquals((8 downTo 0).toList(), upright(180))
        assertEquals(listOf(2, 5, 8, 1, 4, 7, 0, 3, 6), upright(270))
        assertEquals(upright(270), upright(-90))
    }

    @Test
    fun `a capture freezes every capture decision for 400 ms, with the face already captured`() {
        val controller = GuidedScanController()
        val freeze = CaptureFreeze()

        fun face(lab: Lab) = FrameStats(List(9) { CellReading(lab, true) }, 120.0, 0.0, 100.0, 12)
        val green = face(Lab(55.0, -60.0, 40.0))
        val red = face(Lab(50.0, 70.0, 50.0))
        assertFalse(freeze.isFrozen(0))
        freeze.decide(0) { controller.onFrame(green, 0, 0) }
        assertTrue(freeze.decide(10) { controller.onShutter(10) }.first() is GuidedScanEffect.Capture)
        // The face is marked captured at once, while its frame stays frozen.
        assertEquals(1, controller.stepIndex)
        assertEquals(listOf(Face.F), GuidedScanFlow.phaseOf(controller).captured.map { it.face })
        assertTrue(freeze.isFrozen(10))
        assertTrue(freeze.isFrozen(10 + CaptureFreeze.FREEZE_MS - 1))
        // A new face held steady, the shutter and the override during the freeze: no decision runs.
        var decisions = 0
        (100L..400L step 50).forEach { now ->
            assertEquals(emptyList<GuidedScanEffect>(), freeze.decide(now) { decisions++.let { controller.onFrame(red, 0, now) } })
            assertEquals(emptyList<GuidedScanEffect>(), freeze.decide(now + 5) { decisions++.let { controller.onShutter(now + 5) } })
            assertEquals(emptyList<GuidedScanEffect>(), freeze.decide(now + 5) { decisions++.let { controller.onUseAnyway(now + 5) } })
        }
        assertEquals(0, decisions)
        assertEquals(1, controller.stepIndex)
        // Live view resumes after the freeze.
        assertFalse(freeze.isFrozen(10 + CaptureFreeze.FREEZE_MS))
        assertEquals(listOf(GuidedScanEffect.Remeter), freeze.decide(500) { controller.onFrame(red, 0, 500) })
        val next = freeze.decide(510) { controller.onShutter(510) }.first() as GuidedScanEffect.Capture
        assertEquals(Face.R, next.sample.face)
        assertTrue(freeze.isFrozen(510))
    }

    @Test
    fun `the finished scan waits for the last face's freeze to end`() {
        val vm = GuidedScanViewModel()
        val done = ScanReview.fromSamples(List(6) { sample() })
        vm.phase = GuidedScanPhase.Viewfinder()
        vm.frozen = FrozenCapture(6, Face.B, null)
        vm.publish(done)
        assertEquals(GuidedScanPhase.Viewfinder(), vm.phase)
        // A stale freeze end (an earlier capture) neither ends this freeze nor publishes.
        vm.endFreeze(5)
        assertEquals(GuidedScanPhase.Viewfinder(), vm.phase)
        vm.endFreeze(6)
        assertNull(vm.frozen)
        assertEquals(GuidedScanPhase.Review(done), vm.phase)
    }

    @Test
    fun `the finished scan is published at once when no capture is frozen`() {
        val vm = GuidedScanViewModel()
        val done = ScanReview.fromSamples(List(6) { sample() })
        vm.publish(done)
        assertEquals(GuidedScanPhase.Review(done), vm.phase)
    }

    @Test
    fun `a decision without a capture does not freeze`() {
        val freeze = CaptureFreeze()
        freeze.decide(0) { listOf(GuidedScanEffect.Remeter) }
        assertFalse(freeze.isFrozen(1))
    }
}
