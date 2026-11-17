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
import dev.cubentia.domain.recognition.GuidedScanPlan
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.ScanReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Story 10.2: the Activity's pure permission and effect mappings, and the mini net's upright order. */
class GuidedScanFlowTest {
    @Test fun `corrections overwrite only painted indices and select the first blank`() {
        val review = ScanReview(List(54) { null }, null)
        val corrected = GuidedScanFlow.withCorrections(review, mapOf(0 to Color.RED, 8 to Color.BLUE))
        assertEquals(Color.RED, corrected.stickers[0])
        assertEquals(Color.BLUE, corrected.stickers[8])
        assertEquals(1, corrected.selected)
        assertEquals(46, corrected.blanks.size)
    }

    @Test fun `swatch hold captures steadily resets for retry and ignores pending`() {
        val hold = CalibrationHold()
        val frame = FrameStats(List(9) { CellReading(Lab(55.0, -60.0, 40.0), true) }, 120.0, 0.0, 100.0, 12)
        val session =
            dev.cubentia.domain.recognition
                .CalibrationSession()

        fun feed(
            s: dev.cubentia.domain.recognition.CalibrationSession,
            start: Long,
            end: Long,
        ) = (start..end step 100).flatMap { hold.onFrame(s, frame, 0, it) }
        assertTrue(feed(session, 0, 2000).any { it is GuidedScanEffect.Capture })
        val pending = session.offer(CellReading(Lab(55.0, -60.0, 40.0), true))
        assertTrue(feed(pending, 2100, 4100).isEmpty())
        val retry = pending.retake()
        assertFalse(feed(retry, 4200, 4400).any { it is GuidedScanEffect.Capture })
        assertTrue(feed(retry, 4500, 6500).any { it is GuidedScanEffect.Capture })
    }

    private fun sample(
        stickers: List<Lab> = List(9) { Lab(it.toDouble(), 0.0, 0.0) },
        orientation: Int = 0,
    ) = FaceSample(
        face = Face.F,
        orientationDegrees = orientation,
        stickers = stickers,
        quality = CaptureQuality.ACCEPTABLE,
        centerColor = Color.GREEN,
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
        assertEquals(GuidedScanPlan.steps[1], phase.step)
        assertNull(phase.again)
    }

    @Test
    fun `the viewfinder phase mirrors the re-prompt step and its progress`() {
        val controller = GuidedScanController(reprompt = { listOf(Face.B, Face.U) })
        val colors =
            listOf(
                Lab(55.0, -60.0, 40.0),
                Lab(45.0, 65.0, 45.0),
                Lab(40.0, 10.0, -50.0),
                Lab(65.0, 45.0, 65.0),
                Lab(90.0, 0.0, 0.0),
                Lab(85.0, -5.0, 80.0),
            )
        colors.forEachIndexed { index, lab ->
            controller.onFrame(FrameStats(List(9) { CellReading(lab, true) }, 120.0, 0.0, 100.0, 12), 0, index * 10L)
            controller.onShutter(index * 10L + 1)
        }
        val phase = GuidedScanFlow.phaseOf(controller)
        assertEquals(GuidedScanPlan.reprompt(Face.B), phase.step)
        assertEquals(Color.WHITE, phase.step?.upNeighborColor)
        assertEquals(RepromptProgress(1, 2), phase.again)
        assertEquals(6, phase.captured.size)
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

    @Test fun `review flow passes saved centers into assembly and offer`() {
        val labs =
            mapOf(
                Color.GREEN to Lab(55.0, -60.0, 40.0),
                Color.RED to Lab(45.0, 65.0, 45.0),
                Color.BLUE to Lab(40.0, 10.0, -50.0),
                Color.ORANGE to Lab(65.0, 45.0, 65.0),
                Color.WHITE to Lab(90.0, 0.0, 0.0),
                Color.YELLOW to Lab(85.0, -5.0, 80.0),
            )
        val saved =
            dev.cubentia.domain.recognition
                .CalibrationCenters(labs)
        val samples =
            Face.entries.map { face ->
                val color = Color.home(face)
                FaceSample(
                    face = face,
                    orientationDegrees = 0,
                    stickers = List(9) { labs.getValue(color) },
                    quality = CaptureQuality.ACCEPTABLE,
                    centerColor = color,
                    centerPlain = color !in setOf(Color.WHITE, Color.RED),
                )
            }
        val effects = listOf(GuidedScanEffect.Done(samples))
        assertTrue(GuidedScanFlow.reviewOf(effects, null)!!.offerCalibration)
        val review = GuidedScanFlow.reviewOf(effects, saved)!!
        assertFalse(review.offerCalibration)
        assertTrue(review.review.blanks.isEmpty())
        assertEquals(review.review, GuidedScanFlow.resultOf(effects, saved))
    }

    @Test fun `calibration shutter coaches poor frames without offering a swatch`() {
        val phase = GuidedScanPhase.Calibration()
        val ring = CellReading(Lab(55.0, -60.0, 40.0), true)
        val good = FrameStats(List(9) { ring }, 120.0, 0.0, 100.0, 12)
        val dark = GuidedScanFlow.calibrationCapture(phase, good.copy(meanLuma = 1.0), ring)
        assertEquals(Coaching.LOW_LIGHT, dark.coaching)
        assertNull(dark.session.pending)
        assertFalse(dark.session.plainSticker)
        val accepted = GuidedScanFlow.calibrationCapture(dark, good, ring)
        assertEquals(ring.value, accepted.session.pending)
        assertNull(accepted.coaching)
        val unreadable = GuidedScanFlow.calibrationCapture(phase, good, null)
        assertTrue(unreadable.session.plainSticker)
        assertNull(unreadable.session.pending)
    }
}
