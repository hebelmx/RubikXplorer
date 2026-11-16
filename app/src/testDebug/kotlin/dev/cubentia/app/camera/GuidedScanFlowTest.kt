package dev.cubentia.app.camera

import dev.cubentia.app.scan.GuidedScanCapture
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
            GuidedScanCapture.Captured(samples),
            GuidedScanFlow.resultOf(listOf(GuidedScanEffect.Capture(samples[5]), GuidedScanEffect.Done(samples))),
        )
    }

    @Test
    fun `the viewfinder phase mirrors the controller`() {
        val controller = GuidedScanController()
        val cells = List(9) { CellReading(Lab(55.0, -60.0, 40.0), true) }
        controller.onFrame(FrameStats(cells, 120.0, 0.0, 100.0), 0, 0)
        controller.onShutter(10)
        controller.onFrame(FrameStats(cells, 10.0, 0.0, 100.0), 0, 20)
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
}
