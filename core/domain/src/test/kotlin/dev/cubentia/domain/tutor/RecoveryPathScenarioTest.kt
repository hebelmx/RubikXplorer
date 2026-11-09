package dev.cubentia.domain.tutor

import dev.cubentia.domain.content.Lesson
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.ValidationResult
import dev.cubentia.domain.cube.inverse
import dev.cubentia.domain.predicate.WhitePieceSolved
import dev.cubentia.domain.tutor.TutorFixtures.CONFIG
import dev.cubentia.domain.tutor.TutorFixtures.entryOf
import dev.cubentia.domain.tutor.TutorFixtures.moves
import dev.cubentia.domain.tutor.TutorFixtures.shipped
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Story 4.5 AC 4 (FR-10, FR-12): for the scenario fixture of each Error Category (the ones
 * [DiagnosisScenarioTest] diagnoses), applying its Recovery Path leaves a state where the Lesson's
 * `preserve` predicate holds.
 */
class RecoveryPathScenarioTest {
    private fun at(
        lesson: Lesson,
        steps: Int,
    ): CubeState = Tutor.pathState(lesson, entryOf(lesson), steps)

    private fun diagnose(
        lesson: Lesson,
        expected: CubeState,
        observed: CubeState,
    ): Diagnosis = checkNotNull(Assess.diagnose(lesson, entryOf(lesson), expected, observed, CONFIG))

    private fun assertPreserved(
        lesson: Lesson,
        state: CubeState,
    ) = assertTrue(lesson.preserve.isSatisfied(state), "${lesson.id}: preserve does not hold after recovery")

    /** The Guided recovery from [observed]: the planner's plan for the goal at the Checkpoint ([entryOf]) and [expected]. */
    private fun guided(
        lesson: Lesson,
        expected: CubeState,
        observed: CubeState,
    ): CubeState {
        val goal = RecoveryPlanner.goal(lesson, listOf(entryOf(lesson), expected))
        val plan = RecoveryPlanner.plan(observed, goal, CONFIG.recoveryMaxDepth, CONFIG.moves)
        assertInstanceOf(RecoveryPlan.Found::class.java, plan)
        val recovered = observed.apply((plan as RecoveryPlan.Found).moves)
        assertTrue(goal.isMet(recovered))
        return recovered
    }

    @Test
    fun `wrong move or direction is undone back to the expected state`() {
        val lesson = shipped("lesson-02-04")
        val expected = at(lesson, 1)
        val observed = expected.apply(moves("F"))
        assertEquals(RecoveryPath.UNDO, diagnose(lesson, expected, observed).recoveryPath)
        val undone = observed.apply(moves("F").inverse())
        assertEquals(expected, undone)
        assertPreserved(lesson, undone)
    }

    @Test
    fun `wrong piece is undone back to the expected state`() {
        val lesson = shipped("lesson-02-05")
        val expected = at(lesson, 1)
        val observed = expected.apply(moves("F"))
        assertEquals(RecoveryPath.UNDO, diagnose(lesson, expected, observed).recoveryPath)
        val undone = observed.apply(moves("F'"))
        assertEquals(expected, undone)
        assertPreserved(lesson, undone)
    }

    @Test
    fun `wrong piece orientation is guided back`() {
        val corner = "white-green-red"
        val lesson = TutorFixtures.lesson("", "R' D' R" to WhitePieceSolved(corner), completion = WhitePieceSolved(corner))
        val expected = CubeState.SOLVED.apply(moves("R' D' R D R' D' R D R'"))
        val observed = expected.apply(moves("R"))
        assertEquals(RecoveryPath.GUIDED_RECOVERY, diagnose(lesson, expected, observed).recoveryPath)
        val recovered = guided(lesson, expected, observed)
        assertTrue(WhitePieceSolved(corner).isSatisfied(recovered))
        assertPreserved(lesson, recovered)
    }

    @Test
    fun `unexpected orientation is turned back by the reorient rotations`() {
        val lesson = shipped("lesson-02-01")
        val entry = entryOf(lesson)
        val observed = entry.apply(moves("y"))
        assertEquals(RecoveryPath.REORIENT, diagnose(lesson, entry, observed).recoveryPath)
        val back = observed.apply(Tutor.reorient(observed.orientation, entry.orientation))
        assertEquals(entry, back)
        assertPreserved(lesson, back)
    }

    @Test
    fun `completed layer disturbed is guided back by the planner's plan`() {
        val lesson = shipped("lesson-03-01")
        val entry = entryOf(lesson)
        val observed = entry.apply(moves("R"))
        assertEquals(RecoveryPath.GUIDED_RECOVERY, diagnose(lesson, entry, observed).recoveryPath)
        assertPreserved(lesson, guided(lesson, entry, observed))
    }

    @Test
    fun `invalid entered state is re-entered as a valid state`() {
        val lesson = shipped("lesson-03-01")
        val colors = entryOf(lesson).toFacelets().colors.toMutableList()
        // Twist the U-R-F corner: its three stickers cycle.
        val (a, b, c) = Triple(colors[8], colors[9], colors[20])
        colors[8] = c
        colors[9] = a
        colors[20] = b
        assertEquals(RecoveryPath.RE_ENTER, Assess.diagnoseEntry(Facelets(colors))?.recoveryPath)
        val reentered = CubeValidator.validate(entryOf(lesson).toFacelets())
        assertInstanceOf(ValidationResult.Valid::class.java, reentered)
        assertPreserved(lesson, (reentered as ValidationResult.Valid).state)
    }

    @Test
    fun `unknown discrepancy retries from the Checkpoint`() {
        val lesson = shipped("lesson-03-01")
        val entry = entryOf(lesson)
        val observed = entry.apply(moves("R U F"))
        assertEquals(RecoveryPath.RETRY, diagnose(lesson, entry, observed).recoveryPath)
        assertPreserved(lesson, entry)
    }

    @Test
    fun `every category has a scenario here`() {
        // One test per category above; the enum is closed, so a new category must add one.
        assertEquals(7, ErrorCategory.entries.size)
    }
}
