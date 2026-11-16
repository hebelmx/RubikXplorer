package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.recognition.RecognitionFixtures.CALIBRATION
import dev.cubentia.domain.recognition.RecognitionFixtures.LABS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuidedScanTest {
    private fun cells(
        center: Color,
        plain: Int = 9,
    ): List<CellReading?> = List(9) { CellReading(LABS.getValue(center), it < plain) }

    private fun stats(
        cells: List<CellReading?> = cells(Color.GREEN),
        luma: Double = 120.0,
        glare: Double = 0.0,
        sharpness: Double = 100.0,
    ) = FrameStats(cells, luma, glare, sharpness)

    private val good = stats()

    /** Feeds [frame] every 100 ms from [from] through [to] inclusive and returns every effect. */
    private fun GuidedScanController.feed(
        frame: FrameStats,
        from: Long,
        to: Long,
        orientation: Int = 0,
    ): List<GuidedScanEffect> = (from..to step 100).flatMap { onFrame(frame, orientation, it) }

    // FrameQuality

    @Test
    fun `quality precedence is low light, glare, blur, misaligned, acceptable`() {
        val bad = stats(cells = List(9) { null }, luma = 10.0, glare = 0.5, sharpness = 1.0)
        assertEquals(CaptureQuality.LOW_LIGHT, FrameQuality.assess(bad))
        assertEquals(CaptureQuality.GLARE, FrameQuality.assess(bad.copy(meanLuma = 40.0)))
        assertEquals(CaptureQuality.BLURRED, FrameQuality.assess(bad.copy(meanLuma = 40.0, glareFraction = 0.149)))
        assertEquals(CaptureQuality.MISALIGNED, FrameQuality.assess(bad.copy(meanLuma = 40.0, glareFraction = 0.0, sharpness = 60.0)))
        assertEquals(CaptureQuality.ACCEPTABLE, FrameQuality.assess(good))
    }

    @Test
    fun `thresholds sit at their named boundaries`() {
        assertEquals(CaptureQuality.LOW_LIGHT, FrameQuality.assess(stats(luma = 39.9)))
        assertEquals(CaptureQuality.GLARE, FrameQuality.assess(stats(glare = 0.15)))
        assertEquals(CaptureQuality.BLURRED, FrameQuality.assess(stats(sharpness = 59.9)))
    }

    @Test
    fun `a missing cell, fewer than seven plain cells or not nine cells is misaligned`() {
        assertEquals(
            CaptureQuality.MISALIGNED,
            FrameQuality.assess(stats(cells = cells(Color.GREEN).toMutableList().also { it[3] = null })),
        )
        assertEquals(CaptureQuality.MISALIGNED, FrameQuality.assess(stats(cells = cells(Color.GREEN, plain = 6))))
        assertEquals(CaptureQuality.ACCEPTABLE, FrameQuality.assess(stats(cells = cells(Color.GREEN, plain = 7))))
        assertEquals(CaptureQuality.MISALIGNED, FrameQuality.assess(stats(cells = cells(Color.GREEN).take(8))))
    }

    // Plan

    @Test
    fun `the plan is six steps in the documented order, each on its home face`() {
        assertEquals(
            listOf(
                Color.GREEN to Color.WHITE,
                Color.RED to Color.WHITE,
                Color.BLUE to Color.WHITE,
                Color.ORANGE to Color.WHITE,
                Color.WHITE to Color.BLUE,
                Color.YELLOW to Color.GREEN,
            ),
            GuidedScanPlan.steps.map { it.centerColor to it.upNeighborColor },
        )
        assertEquals(listOf(Face.F, Face.R, Face.B, Face.L, Face.U, Face.D), GuidedScanPlan.steps.map { it.face })
    }

    @Test
    fun `six faces of a scrambled cube shown per the plan assemble to that cube`() {
        val moves =
            listOf(
                FaceTurn(Face.R, Turn.CLOCKWISE),
                FaceTurn(Face.U, Turn.COUNTERCLOCKWISE),
                FaceTurn(Face.F, Turn.HALF),
                FaceTurn(Face.L, Turn.CLOCKWISE),
            )
        val cube = CubeState.SOLVED.apply(moves).toFacelets()
        val controller = GuidedScanController()
        var now = 0L
        var done: List<FaceSample>? = null
        GuidedScanPlan.steps.forEach { step ->
            val net = cube.colors.subList(step.face.ordinal * 9, step.face.ordinal * 9 + 9)
            // A sensor needing 90° clockwise delivers the upright view turned a quarter counter-clockwise.
            val pixels = ScanAssembler.rotateClockwise(net, 3)
            val frame = stats(cells = pixels.map { CellReading(LABS.getValue(it), true) })
            controller.onFrame(stats(cells = List(9) { null }), 90, now)
            val effects = controller.feed(frame, now + 100, now + 1100, orientation = 90)
            done = effects.filterIsInstance<GuidedScanEffect.Done>().singleOrNull()?.samples ?: done
            now += 2000
        }
        val samples = requireNotNull(done)
        assertEquals(GuidedScanPlan.steps.map { it.centerColor }, samples.map { it.centerColor })
        assertTrue(samples.all { it.orientationDegrees == 90 && it.quality == CaptureQuality.ACCEPTABLE && it.centerPlain == true })
        assertEquals(ScanResult.Full(cube, List(54) { StickerConfidence.CONFIDENT }), ScanAssembler.assemble(samples, CALIBRATION))
        val selfCalibrated = SelfCalibration.from(samples) as SelfCalibration.Calibrated
        assertEquals(
            ScanResult.Full(cube, List(54) { StickerConfidence.CONFIDENT }),
            ScanAssembler.assemble(samples, selfCalibrated.calibration),
        )
    }

    // Controller

    @Test
    fun `alignment found remeters once and auto-captures after settle plus 600 ms steady`() {
        val controller = GuidedScanController()
        assertEquals(listOf(GuidedScanEffect.Remeter), controller.onFrame(good, 0, 0))
        // Settle until 300 ms; the reference is taken at 300 ms, so the capture lands at 900 ms.
        assertEquals(emptyList<GuidedScanEffect>(), controller.feed(good, 100, 800))
        val effects = controller.onFrame(good, 0, 900)
        assertEquals(2, effects.size)
        val capture = effects[0] as GuidedScanEffect.Capture
        assertEquals(GuidedScanEffect.Remeter, effects[1])
        assertEquals(Face.F, capture.sample.face)
        assertEquals(Color.GREEN, capture.sample.centerColor)
        assertEquals(Color.WHITE, capture.sample.upNeighborColor)
        assertEquals(1, controller.stepIndex)
        assertEquals(GuidedScanPlan.steps[1], controller.step)
    }

    @Test
    fun `a cell moving 5 dE at 500 ms restarts the steady timer`() {
        val controller = GuidedScanController()
        controller.onFrame(good, 0, 0)
        controller.feed(good, 100, 400)
        val moved = cells(Color.GREEN).toMutableList().also { it[8] = CellReading(Lab(55.0, -60.0, 45.0), true) }
        assertEquals(emptyList<GuidedScanEffect>(), controller.onFrame(stats(cells = moved), 0, 500))
        // Steady from 500 ms against the moved reference: nothing until 1100 ms.
        assertEquals(emptyList<GuidedScanEffect>(), controller.feed(stats(cells = moved), 600, 1000))
        assertEquals(0, controller.stepIndex)
        assertTrue(controller.onFrame(stats(cells = moved), 0, 1100).first() is GuidedScanEffect.Capture)
    }

    @Test
    fun `losing alignment remeters again when it is found`() {
        val controller = GuidedScanController()
        assertEquals(listOf(GuidedScanEffect.Remeter), controller.onFrame(good, 0, 0))
        assertEquals(emptyList<GuidedScanEffect>(), controller.onFrame(good, 0, 100))
        assertEquals(emptyList<GuidedScanEffect>(), controller.onFrame(stats(cells = cells(Color.GREEN, plain = 3)), 0, 200))
        assertEquals(Coaching.MISALIGNED, controller.coaching)
        assertEquals(listOf(GuidedScanEffect.Remeter), controller.onFrame(good, 0, 300))
        assertNull(controller.coaching)
    }

    @Test
    fun `glare, blur, low light and misalignment coach and never capture`() {
        val cases =
            mapOf(
                stats(glare = 0.3) to Coaching.GLARE,
                stats(sharpness = 5.0) to Coaching.BLURRED,
                stats(luma = 5.0) to Coaching.LOW_LIGHT,
                stats(cells = cells(Color.GREEN, plain = 0)) to Coaching.MISALIGNED,
            )
        cases.forEach { (frame, coaching) ->
            val controller = GuidedScanController()
            assertEquals(emptyList<GuidedScanEffect>(), controller.feed(frame, 0, 3000))
            assertEquals(coaching, controller.coaching)
            assertEquals(emptyList<GuidedScanEffect>(), controller.onShutter(3000))
            assertEquals(coaching, controller.coaching)
            assertEquals(0, controller.stepIndex)
        }
    }

    @Test
    fun `the shutter captures the latest acceptable frame at once`() {
        val controller = GuidedScanController()
        assertEquals(Coaching.MISALIGNED, controller.onShutter(0).let { controller.coaching })
        controller.onFrame(good, 180, 10)
        val effects = controller.onShutter(20)
        assertEquals(180, (effects[0] as GuidedScanEffect.Capture).sample.orientationDegrees)
        assertEquals(GuidedScanEffect.Remeter, effects[1])
    }

    /** A controller that has captured green at step 1 and then saw the cube move on (a misaligned frame). */
    private fun afterGreen(): GuidedScanController =
        GuidedScanController().apply {
            onFrame(good, 0, 0)
            onShutter(10)
            onFrame(stats(cells = cells(Color.GREEN, plain = 0)), 0, 20)
        }

    // Still green: within 10 dE of the captured green center, but not the identical frame.
    private val nearGreen = stats(cells = List(9) { CellReading(Lab(57.0, -58.0, 42.0), true) })

    @Test
    fun `the same face again is rejected with WRONG_FACE and not stored`() {
        val controller = afterGreen()
        assertEquals(1, controller.stepIndex)
        assertEquals(listOf(GuidedScanEffect.Remeter), controller.onFrame(nearGreen, 0, 100))
        assertEquals(emptyList<GuidedScanEffect>(), controller.onShutter(110))
        assertEquals(Coaching.WRONG_FACE, controller.coaching)
        assertEquals(1, controller.stepIndex)
        assertEquals(1, controller.captured.size)
        assertFalse(controller.canUseAnyway)
    }

    @Test
    fun `auto-capture rejects a repeated face too and keeps waiting on the same step`() {
        val controller = afterGreen()
        // Remeter at 100, reference at 400, rejection at 1000.
        assertEquals(listOf(GuidedScanEffect.Remeter), controller.feed(nearGreen, 100, 1000))
        assertEquals(Coaching.WRONG_FACE, controller.coaching)
        assertEquals(1, controller.stepIndex)
        assertFalse(controller.canUseAnyway)
    }

    @Test
    fun `a second rejection at one step offers Use this face anyway, which stores the rejected frame`() {
        val controller = afterGreen()
        assertEquals(emptyList<GuidedScanEffect>(), controller.onUseAnyway(50))
        controller.feed(nearGreen, 100, 1000)
        assertFalse(controller.canUseAnyway)
        // The steady timer restarted at the first rejection (1000 ms); the second lands at 1600 ms.
        controller.feed(nearGreen, 1100, 1600)
        assertTrue(controller.canUseAnyway)
        assertEquals(1, controller.stepIndex)
        val effects = controller.onUseAnyway(1700)
        val sample = (effects[0] as GuidedScanEffect.Capture).sample
        assertEquals(GuidedScanEffect.Remeter, effects[1])
        assertEquals(Color.RED, sample.centerColor)
        assertEquals(Lab(57.0, -58.0, 42.0), sample.center)
        assertEquals(2, controller.stepIndex)
        assertFalse(controller.canUseAnyway)
        assertNull(controller.coaching)
    }

    @Test
    fun `the face just captured is ignored until the frame changes`() {
        val controller = GuidedScanController()
        controller.onFrame(good, 0, 0)
        assertTrue(controller.onShutter(10).first() is GuidedScanEffect.Capture)
        // The same green, or green within 15 dE in every cell: no capture, no coaching, for as long as it is held.
        assertEquals(emptyList<GuidedScanEffect>(), controller.feed(good, 100, 3000))
        assertEquals(emptyList<GuidedScanEffect>(), controller.feed(nearGreen, 3100, 6000))
        assertNull(controller.coaching)
        assertEquals(1, controller.stepIndex)
        // One cell 15 dE or more away is a change: alignment found again.
        val moved = cells(Color.GREEN).toMutableList().also { it[0] = CellReading(LABS.getValue(Color.RED), true) }
        assertEquals(listOf(GuidedScanEffect.Remeter), controller.onFrame(stats(cells = moved), 0, 6100))
    }

    @Test
    fun `the shutter does nothing while the face just captured is held`() {
        val controller = GuidedScanController()
        controller.onFrame(good, 0, 0)
        controller.onShutter(10)
        controller.onFrame(good, 0, 100)
        // A double tap while green is still held: no capture, no WRONG_FACE coaching, no rejection counted.
        assertEquals(emptyList<GuidedScanEffect>(), controller.onShutter(110))
        assertEquals(emptyList<GuidedScanEffect>(), controller.onShutter(120))
        assertNull(controller.coaching)
        assertFalse(controller.canUseAnyway)
        assertEquals(1, controller.stepIndex)
        // Once the cube moves on, the shutter works again.
        controller.onFrame(stats(cells = cells(Color.RED)), 0, 200)
        val sample = (controller.onShutter(210)[0] as GuidedScanEffect.Capture).sample
        assertEquals(LABS.getValue(Color.RED), sample.center)
    }

    /** [afterGreen] with two repeated-green rejections at the red step, so *Use this face anyway* is offered. */
    private fun overrideOffered(): GuidedScanController =
        afterGreen().apply {
            onFrame(nearGreen, 0, 100)
            onShutter(110)
            onShutter(120)
            assertTrue(canUseAnyway)
            assertEquals(Coaching.WRONG_FACE, coaching)
        }

    @Test
    fun `showing a different face clears the override and the WRONG_FACE coaching`() {
        val controller = overrideOffered()
        controller.onFrame(stats(cells = cells(Color.RED)), 0, 200)
        assertFalse(controller.canUseAnyway)
        assertNull(controller.coaching)
        assertEquals(emptyList<GuidedScanEffect>(), controller.onUseAnyway(210))
        // Green again needs two fresh rejections before the override returns.
        controller.onFrame(nearGreen, 0, 300)
        controller.onShutter(310)
        assertFalse(controller.canUseAnyway)
    }

    @Test
    fun `a misaligned blip keeps the override`() {
        val controller = overrideOffered()
        controller.onFrame(stats(cells = cells(Color.GREEN, plain = 0)), 0, 200)
        assertEquals(Coaching.MISALIGNED, controller.coaching)
        assertTrue(controller.canUseAnyway)
        controller.onFrame(nearGreen, 0, 300)
        assertTrue(controller.canUseAnyway)
        val sample = (controller.onUseAnyway(310)[0] as GuidedScanEffect.Capture).sample
        assertEquals(Color.RED, sample.centerColor)
    }

    @Test
    fun `a misaligned frame after a capture ends the hold and the next alignment remeters`() {
        val controller = GuidedScanController()
        controller.onFrame(good, 0, 0)
        controller.onShutter(10)
        assertEquals(emptyList<GuidedScanEffect>(), controller.onFrame(stats(luma = 5.0), 0, 100))
        assertEquals(Coaching.LOW_LIGHT, controller.coaching)
        assertEquals(listOf(GuidedScanEffect.Remeter), controller.onFrame(stats(cells = cells(Color.RED)), 0, 200))
    }

    @Test
    fun `a face not yet captured is stored under the step's labels`() {
        val controller = GuidedScanController()
        controller.onFrame(good, 0, 0)
        controller.onShutter(10)
        // Blue shown at the red step: stored as the red step, left to Story 10.3 validation.
        controller.onFrame(stats(cells = cells(Color.BLUE)), 0, 100)
        val sample = (controller.onShutter(110)[0] as GuidedScanEffect.Capture).sample
        assertEquals(Color.RED, sample.centerColor)
        assertEquals(Face.R, sample.face)
        assertEquals(LABS.getValue(Color.BLUE), sample.center)
    }

    @Test
    fun `the sixth capture emits Done with six samples in plan order, then ignores input`() {
        val controller = GuidedScanController()
        val colors = GuidedScanPlan.steps.map { it.centerColor }
        val effects =
            colors.flatMapIndexed { index, color ->
                controller.onFrame(stats(cells = cells(color)), 0, index * 10L)
                controller.onShutter(index * 10L + 1)
            }
        val done = effects.last() as GuidedScanEffect.Done
        assertEquals(colors, done.samples.map { it.centerColor })
        assertEquals(6, effects.count { it is GuidedScanEffect.Capture })
        assertTrue(controller.done)
        assertNull(controller.step)
        assertEquals(emptyList<GuidedScanEffect>(), controller.onFrame(good, 0, 1000))
        assertEquals(emptyList<GuidedScanEffect>(), controller.onShutter(1000))
    }

    @Test
    fun `coaching maps every capture quality`() {
        assertEquals(
            mapOf(
                CaptureQuality.ACCEPTABLE to null,
                CaptureQuality.GLARE to Coaching.GLARE,
                CaptureQuality.BLURRED to Coaching.BLURRED,
                CaptureQuality.LOW_LIGHT to Coaching.LOW_LIGHT,
                CaptureQuality.MISALIGNED to Coaching.MISALIGNED,
                CaptureQuality.INCOMPLETE to Coaching.MISALIGNED,
            ),
            CaptureQuality.entries.associateWith { GuidedScanController.coachingFor(it) },
        )
    }
}
