package dev.rubikxplorer.application.session

import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.content.ContentLoader
import dev.rubikxplorer.domain.content.ContentSource
import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.NotationResult
import dev.rubikxplorer.domain.predicate.WhitePieceSolved
import dev.rubikxplorer.domain.tutor.NextAction
import dev.rubikxplorer.domain.tutor.TeachingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Story 4.1 I/O matrix rows 8–13: the step card follows the cube through out-of-order steps, Undo
 * and Redo across a step boundary, and completion, in *Let me try* and *Test me*.
 */
class StepDerivationScenarioTest {
    private val lessons: List<Lesson> by lazy {
        val result = ContentLoader.load(ContentSource.SHIPPED)
        check(result is ContentLoadResult.Loaded) { "the shipped content does not load: $result" }
        result.bundle.stages.flatMap { it.lessons }
    }

    private fun lesson(id: String): Lesson = lessons.single { it.id.value == id }

    private fun moves(notation: String): List<Move> = (Notation.parse(notation) as NotationResult.Parsed).moves

    private fun SessionState.turning(notation: String): SessionState = after(*moves(notation).map { commit(it) }.toTypedArray())

    private fun start(
        id: String,
        mode: TeachingMode,
    ): SessionState = started(lesson(id)).after(modeChanged(mode))

    private fun SessionState.advanceFlag(): Boolean = checkNotNull(decision).advance

    @ParameterizedTest
    @EnumSource(value = TeachingMode::class, names = ["LET_ME_TRY", "TEST_ME"])
    fun `on the path mid-step the authored path decides the step`(mode: TeachingMode) {
        val lesson = lesson("lesson-03-04")
        var state = start(lesson.id.value, mode)
        lesson.steps.take(3).forEach { step -> state = state.turning(step.moves).after(stepAdvanced) }
        assertEquals(3, state.stepIndex)
        val lifted = state.turning("F'")
        assertFalse(lesson.steps[0].done.isSatisfied(lifted.virtual), "F' lifts step 1's corner")
        assertEquals(3, lifted.stepIndex)
        assertFalse(lifted.advanceFlag())
    }

    @ParameterizedTest
    @EnumSource(value = TeachingMode::class, names = ["LET_ME_TRY", "TEST_ME"])
    fun `out of order the card waits for the first step, then shows its Notice until Next`(mode: TeachingMode) {
        val second = start("lesson-02-05", mode).turning("R2")
        assertEquals(0, second.stepIndex)
        assertNull(second.noticeStep)
        assertFalse(second.advanceFlag())

        val both = second.after(reveal).turning("F2")
        assertEquals(0, both.stepIndex)
        assertEquals(0, both.noticeStep)
        assertTrue(both.advanceFlag())
        assertEquals(NextAction.NEXT_STEP, checkNotNull(both.decision).nextAction)
        assertNull(checkNotNull(both.decision).feedback, "Notice and Next speak once a step is done")

        val next = both.after(stepAdvanced)
        assertEquals(2, next.stepIndex, "the first step whose done is unmet")
        assertFalse(next.revealRequested)
        assertFalse(next.advanceFlag())
        assertEquals(both.revision, next.revision)
    }

    @ParameterizedTest
    @EnumSource(value = TeachingMode::class, names = ["LET_ME_TRY", "TEST_ME"])
    fun `undo across a step boundary returns the card, and redo brings the Notice back`(mode: TeachingMode) {
        val advanced = start("lesson-02-05", mode).turning("F2").after(stepAdvanced)
        assertEquals(1, advanced.stepIndex)

        val undone = advanced.after(reveal, undo)
        assertEquals(0, undone.stepIndex)
        assertNull(undone.noticeStep)
        assertFalse(undone.advanceFlag())
        assertFalse(undone.revealRequested, "the shown step changed")

        val redone = undone.after(redo)
        assertEquals(0, redone.stepIndex)
        assertEquals(0, redone.noticeStep)
        assertTrue(redone.advanceFlag())
    }

    @ParameterizedTest
    @EnumSource(value = TeachingMode::class, names = ["LET_ME_TRY", "TEST_ME"])
    fun `a complete Lesson shows the last step and finishes`(mode: TeachingMode) {
        val lesson = lesson("lesson-02-05")
        val done = start(lesson.id.value, mode).turning(lesson.demonstration)
        val decision = checkNotNull(done.decision)
        assertTrue(decision.satisfied)
        assertTrue(decision.advance)
        assertEquals(lesson.steps.lastIndex, done.stepIndex)
        assertEquals(NextAction.FINISH, decision.nextAction)
        assertEquals(done, done.after(stepAdvanced), "Next past the last step is the screen's to close")
    }

    @ParameterizedTest
    @EnumSource(value = TeachingMode::class, names = ["LET_ME_TRY", "TEST_ME"])
    fun `lesson 03-04 corners out of order`(mode: TeachingMode) {
        val lesson = lesson("lesson-03-04")
        // Step 2's Moves first: its corner sits, but step 1's does not, so the card stays on step 1.
        val second = start(lesson.id.value, mode).turning(lesson.steps[1].moves)
        assertTrue(WhitePieceSolved("white-red-blue").isSatisfied(second.virtual))
        assertFalse(lesson.steps[0].done.isSatisfied(second.virtual))
        assertEquals(0, second.stepIndex)
        assertFalse(second.advanceFlag())
        // Then step 1's corner, from the turned bottom layer: steps 1 and 2 done, the Notice waits for Next.
        val first = second.turning("D R' D' R")
        assertTrue(lesson.steps[1].done.isSatisfied(first.virtual))
        assertEquals(0, first.stepIndex)
        assertTrue(first.advanceFlag())
        assertEquals(2, first.after(stepAdvanced).stepIndex)
    }
}
