package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class CaseCaptureControllerTest {
    private val labs =
        mapOf(
            Color.GREEN to Lab(55.0, -60.0, 40.0),
            Color.RED to Lab(45.0, 65.0, 45.0),
            Color.BLUE to Lab(40.0, 10.0, -50.0),
            Color.ORANGE to Lab(65.0, 45.0, 65.0),
            Color.WHITE to Lab(90.0, 0.0, 0.0),
            Color.YELLOW to Lab(85.0, -5.0, 80.0),
        )
    private val calibrated = CalibrationCenters(labs)
    private val controller = CaseCaptureController(calibrated, 2)
    private val first = corner(Color.YELLOW, Color.GREEN, Color.ORANGE)
    private val second = corner(Color.YELLOW, Color.BLUE, Color.RED)

    @Test
    fun repromptsTheCurrentHoldAndAcceptsOnlyTheOppositeCorner() {
        fun reject(
            frames: List<FrameStats>,
            hold: Int,
            prompt: String,
        ) {
            assertTrue(shutter(frames).isEmpty())
            assertEquals(hold, controller.hold)
            assertEquals("msg.recognize.$prompt", controller.prompt.key)
        }

        reject(corner(Color.WHITE, Color.GREEN, Color.RED), 1, "turn-yellow-up")
        reject(corner(Color.YELLOW, Color.WHITE, Color.ORANGE), 1, "keep-yellow-up")
        reject(corner(Color.YELLOW, Color.GREEN, Color.BLUE), 1, "hold-first")
        assertEquals(listOf(CaseCaptureEffect.CornerCaptured(1)), shutter(first))
        assertEquals("msg.recognize.hold-second", controller.prompt.key)
        reject(first, 2, "same-corner")
        reject(corner(Color.YELLOW, Color.ORANGE, Color.BLUE), 2, "same-corner")
        reject(corner(Color.YELLOW, Color.BLUE, Color.GREEN), 2, "same-corner")
        reject(corner(Color.YELLOW, Color.BLUE, Color.YELLOW), 2, "keep-yellow-up")
        reject(corner(Color.WHITE, Color.BLUE, Color.ORANGE), 2, "turn-yellow-up")
        val done = shutter(second)
        assertEquals(CaseCaptureEffect.CornerCaptured(2), done.first())
        assertEquals(2, assertInstanceOf<CaseCaptureEffect.Done>(done.last()).views.size)
    }

    @Test
    fun waitsForChangeAfterACaptureAndAfterAReprompt() {
        assertEquals(listOf(CaseCaptureEffect.Remeter), controller.onFrame(true, first, 0))
        assertTrue(controller.onFrame(true, first, 1_000).isEmpty())
        assertEquals(listOf(CaseCaptureEffect.CornerCaptured(1)), controller.onFrame(true, first, 2_000))
        assertTrue(controller.onFrame(true, first, 3_000).isEmpty())
        val quarterTurn = corner(Color.YELLOW, Color.ORANGE, Color.BLUE)
        controller.onFrame(true, quarterTurn, 4_000)
        assertTrue(controller.onShutter().isEmpty())
        assertTrue(controller.onFrame(true, quarterTurn, 5_000).isEmpty())
        assertEquals(listOf(CaseCaptureEffect.Remeter), controller.onFrame(true, second, 6_000))
        assertTrue(controller.onFrame(true, second, 7_000).isEmpty())
        assertInstanceOf<CaseCaptureEffect.Done>(controller.onFrame(true, second, 8_000).last())
    }

    @Test
    fun uncertainNonCentersAreUnseenAndCentersKeepTheirNearestColor() {
        val center = Lab(55.5, 54.5, 54.5) // Closer to orange than red, with a margin below 10.
        val oranged = first.last().copy(cells = List(9) { CellReading(center, true) })
        val frames = first.dropLast(1) + oranged
        val single = CaseCaptureController(calibrated, 1)
        single.onFrame(true, frames, 0)
        val captured = assertInstanceOf<CaseCaptureEffect.Done>(single.onShutter().last()).views.single()
        assertEquals(Color.ORANGE, captured.right[4])
        assertNull(captured.right[0])
        assertNull(ScanAssembler.cornerView(frames.dropLast(1), calibrated))
        assertNull(ScanAssembler.cornerView(frames.dropLast(1) + null, calibrated))
    }

    // The four tests below pin the corner hold's behaviour as it was before Story 10.32 trimmed CornerHold to Case capture.

    @Test
    fun coachesLowLightThenGlareThenBlurAndClearsOnAGoodFrame() {
        fun coached(change: (FrameStats) -> FrameStats): Coaching? {
            controller.onFrame(true, first.dropLast(1) + change(first.last()), 0)
            return controller.coaching
        }
        assertEquals(Coaching.GLARE, coached { it.copy(glareFraction = 0.3) })
        assertEquals(Coaching.BLURRED, coached { it.copy(sharpness = 5.0) })
        assertEquals(Coaching.LOW_LIGHT, coached { it.copy(meanLuma = 5.0) })
        // Precedence: low light, then glare, then blur.
        assertEquals(Coaching.LOW_LIGHT, coached { it.copy(meanLuma = 5.0, glareFraction = 0.3, sharpness = 5.0) })
        assertEquals(Coaching.GLARE, coached { it.copy(glareFraction = 0.3, sharpness = 5.0) })
        assertNull(coached { it })
    }

    @Test
    fun aBadQualityFrameNeverCapturesAndRestartsTheSteadyHold() {
        val glare = first.dropLast(1) + first.last().copy(glareFraction = 0.3)
        assertEquals(listOf(CaseCaptureEffect.Remeter), controller.onFrame(true, first, 0))
        assertTrue(controller.onFrame(true, first, 600).isEmpty())
        assertTrue(controller.onFrame(true, glare, 1_500).isEmpty())
        // The steady second starts over on the next good frame.
        assertTrue(controller.onFrame(true, first, 1_700).isEmpty())
        assertTrue(controller.onFrame(true, first, 2_699).isEmpty())
        assertEquals(listOf(CaseCaptureEffect.CornerCaptured(1)), controller.onFrame(true, first, 2_700))
    }

    @Test
    fun coachesAPlainLightSurfaceAfterThreeSecondsUndetectedAndClearsWhenFound() {
        val none = List<FrameStats?>(3) { null }
        assertTrue(controller.onFrame(false, none, 0).isEmpty())
        assertNull(controller.coaching)
        controller.onFrame(false, none, 2_999)
        assertNull(controller.coaching)
        controller.onFrame(false, none, 3_000)
        assertEquals(Coaching.PLAIN_LIGHT_SURFACE, controller.coaching)
        assertEquals(listOf(CaseCaptureEffect.Remeter), controller.onFrame(true, first, 3_100))
        assertNull(controller.coaching)
        // A new undetected spell counts its three seconds from its own start.
        controller.onFrame(false, none, 3_200)
        controller.onFrame(false, none, 6_199)
        assertNull(controller.coaching)
        controller.onFrame(false, none, 6_200)
        assertEquals(Coaching.PLAIN_LIGHT_SURFACE, controller.coaching)
    }

    @Test
    fun aShortDetectionGapKeepsMeteringAndTheSteadyHoldAndALongerOneRestartsBoth() {
        val none = List<FrameStats?>(3) { null }
        assertEquals(listOf(CaseCaptureEffect.Remeter), controller.onFrame(true, first, 0))
        assertTrue(controller.onFrame(true, first, 600).isEmpty())
        // Undetected for 299 ms: below the lost threshold.
        assertTrue(controller.onFrame(false, none, 700).isEmpty())
        assertTrue(controller.onFrame(false, none, 999).isEmpty())
        assertTrue(controller.onFrame(true, first, 1_000).isEmpty())
        assertEquals(listOf(CaseCaptureEffect.CornerCaptured(1)), controller.onFrame(true, first, 1_600))

        val other = CaseCaptureController(calibrated, 1)
        assertEquals(listOf(CaseCaptureEffect.Remeter), other.onFrame(true, first, 0))
        assertTrue(other.onFrame(true, first, 600).isEmpty())
        // Undetected for 300 ms: the cube is lost.
        assertTrue(other.onFrame(false, none, 700).isEmpty())
        assertTrue(other.onFrame(false, none, 1_000).isEmpty())
        assertEquals(listOf(CaseCaptureEffect.Remeter), other.onFrame(true, first, 1_100))
        assertTrue(other.onFrame(true, first, 1_699).isEmpty())
        assertTrue(other.onFrame(true, first, 1_700).isEmpty())
        assertTrue(other.onFrame(true, first, 2_699).isEmpty())
        assertInstanceOf<CaseCaptureEffect.Done>(other.onFrame(true, first, 2_700).last())
    }

    private fun shutter(frames: List<FrameStats>): List<CaseCaptureEffect> {
        controller.onFrame(true, frames, 0)
        return controller.onShutter()
    }

    private fun corner(vararg colors: Color): List<FrameStats> =
        colors.map { color ->
            FrameStats(List(9) { CellReading(labs.getValue(color), true) }, 120.0, 0.0, 100.0, 12)
        }
}
