package dev.cubentia.application.session

import dev.cubentia.domain.content.LessonId
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.lastlayer.TwoLookSolve
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TwoLookPracticeSessionTest {
    private fun moves(text: String): List<Move> = (Notation.parse(text) as NotationResult.Parsed).moves

    private val lesson = twoStepLesson().copy(id = LessonId("practice-two-look"))
    private val algorithms = listOf(moves(lesson.demonstration))

    @Test fun `LL practice ends with off-path errors and excludes undone slips`() {
        var state = reduce(SessionState.IDLE, SessionEvent.PracticeStarted(lesson, TUTOR_CONFIG, T0, algorithms)).state
        state = reduce(state, commit(moves("D").single())).state
        state = reduce(state, SessionEvent.Undo(T0)).state
        var ending: Effect.AttemptEnded? = null
        moves(lesson.demonstration).forEach {
            val transition = reduce(state, commit(it))
            state = transition.state
            ending = transition.effects.filterIsInstance<Effect.AttemptEnded>().singleOrNull() ?: ending
        }
        assertTrue(state.objectiveMet)
        assertEquals(0, ending!!.errors)
        assertEquals(TwoLookSolve.offPathMoves(state.history.done, algorithms), ending!!.errors)
    }

    @Test fun `LL abandoned attempt counts off-path moves while Course keeps mistake count and clears algorithms`() {
        val started = reduce(SessionState.IDLE, SessionEvent.PracticeStarted(lesson, TUTOR_CONFIG, T0, algorithms)).state
        val slipped = reduce(started, commit(moves("D").single())).state
        val course = reduce(slipped, SessionEvent.PracticeStarted(twoStepLesson(), TUTOR_CONFIG, T0))
        assertEquals(
            1,
            course.effects
                .filterIsInstance<Effect.AttemptEnded>()
                .single()
                .errors,
        )
        assertTrue(course.state.twoLookAlgorithms.isEmpty())
        val moved = reduce(course.state, commit(moves("D").single())).state
        val ended = reduce(moved, SessionEvent.LessonStarted(lesson, TUTOR_CONFIG, T0))
        assertEquals(
            moved.stepMistakes.values.sum(),
            ended.effects
                .filterIsInstance<Effect.AttemptEnded>()
                .single()
                .errors,
        )
        assertTrue(ended.state.twoLookAlgorithms.isEmpty())
    }

    @Test fun `a mode switch that replaces the LL practice history counts as an error`() {
        val started = reduce(SessionState.IDLE, SessionEvent.PracticeStarted(lesson, TUTOR_CONFIG, T0, algorithms)).state
        val turned = reduce(started, commit(moves("U").single())).state
        assertEquals(0, turned.twoLookErrors)
        val switched = reduce(turned, SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0)).state
        assertEquals(1, switched.twoLookErrors)
        val ended = reduce(switched, SessionEvent.PracticeStarted(lesson, TUTOR_CONFIG, T0, algorithms))
        assertEquals(
            1,
            ended.effects
                .filterIsInstance<Effect.AttemptEnded>()
                .single()
                .errors,
        )
    }
}
