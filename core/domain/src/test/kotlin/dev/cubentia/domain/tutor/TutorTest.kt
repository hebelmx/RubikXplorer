package dev.cubentia.domain.tutor

import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.tutor.TutorFixtures.fourEdges
import dev.cubentia.domain.tutor.TutorFixtures.oneStep
import dev.cubentia.domain.tutor.TutorFixtures.state
import dev.cubentia.domain.tutor.TutorFixtures.twoStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Story 4.1: the current step follows the Cube State, on the authored path and off it. */
class TutorTest {
    @Test
    fun `on the authored path the current step holds the next authored Move`() {
        val entry = state("F U'")
        assertEquals(0, Tutor.currentStep(twoStep, entry, entry))
        assertEquals(1, Tutor.currentStep(twoStep, entry, state("F U' U")))
        assertEquals(1, Tutor.currentStep(twoStep, entry, CubeState.SOLVED), "after the final Move: the last step")
    }

    @Test
    fun `off the path the current step is the first step whose done is unmet`() {
        val entry = state("L2 B2 R2 F2")
        assertEquals(0, Tutor.currentStep(fourEdges, entry, state("L2 B2 R2 F2 R2")))
        assertEquals(2, Tutor.currentStep(fourEdges, entry, state("L2 B2 R2 F2 R2 F2")))
        assertEquals(3, Tutor.currentStep(fourEdges, entry, state("L2 B2 R2 F2 R2 F2 B2")))
        // Every done met off the path: the last step.
        assertEquals(1, Tutor.currentStep(twoStep, state("F U'"), state("D")))
    }

    @Test
    fun `the path match wins over an unmet done mid-step`() {
        val long = TutorFixtures.lesson("F U'", "U F'" to TutorFixtures.twoStep.completion)
        assertEquals(0, Tutor.currentStep(long, state("F U'"), state("F U' U")))
    }

    @Test
    fun `the shown step is the last once complete, else the Notice step, else the current step`() {
        val entry = state("F U'")
        assertEquals(1, Tutor.shownStep(twoStep, entry, CubeState.SOLVED, noticeStep = 0))
        assertEquals(0, Tutor.shownStep(twoStep, entry, state("F U' U"), noticeStep = 0))
        assertEquals(1, Tutor.shownStep(twoStep, entry, state("F U' U"), noticeStep = null))
        assertEquals(0, Tutor.shownStep(oneStep, state("F"), state("F"), noticeStep = null))
    }

    @Test
    fun `the path state applies the first k steps and clamps past either end`() {
        val entry = state("F U'")
        assertEquals(entry, Tutor.pathState(twoStep, entry, 0))
        assertEquals(entry, Tutor.pathState(twoStep, entry, -1))
        assertEquals(state("F"), Tutor.pathState(twoStep, entry, 1))
        assertEquals(CubeState.SOLVED, Tutor.pathState(twoStep, entry, 2))
        assertEquals(CubeState.SOLVED, Tutor.pathState(twoStep, entry, 5))
    }

    @Test
    fun `invalid step notation is a programmer error`() {
        val broken = oneStep.copy(steps = listOf(oneStep.steps.single().copy(moves = "Q")))
        assertThrows<IllegalStateException> { Tutor.pathState(broken, CubeState.SOLVED, 1) }
        assertThrows<IllegalStateException> { Tutor.currentStep(broken, CubeState.SOLVED, CubeState.SOLVED) }
    }

    @Test
    fun `the modes are ordered show me, guide me, test me, let me try`() {
        assertEquals(
            listOf(TeachingMode.SHOW_ME, TeachingMode.GUIDE_ME, TeachingMode.TEST_ME, TeachingMode.LET_ME_TRY),
            TeachingMode.entries,
        )
    }

    @Test
    fun `guide me is the default mode`() {
        assertEquals(TeachingMode.GUIDE_ME, TeachingMode.DEFAULT)
    }

    @Test
    fun `reorient finds the fewest Cube Rotations between any two holds`() {
        Orientation.ALL.forEach { from ->
            Orientation.ALL.forEach { to ->
                val rotations = Tutor.reorient(from, to)
                assertTrue(rotations.size <= 2, "$from -> $to: $rotations")
                assertEquals(to, rotations.fold(from) { held, r -> held.rotated(r.axis, r.turn) }, "$from -> $to")
                assertEquals(from == to, rotations.isEmpty(), "$from -> $to")
            }
        }
        val turned = CubeState.SOLVED.apply(TutorFixtures.moves("y"))
        assertEquals(TutorFixtures.moves("y'"), Tutor.reorient(turned.orientation, CubeState.SOLVED.orientation))
    }
}
