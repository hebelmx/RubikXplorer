package dev.cubentia.application.session

import dev.cubentia.domain.content.LessonId
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.lastlayer.TwoLookSolve
import dev.cubentia.domain.tutor.AttemptOutcome
import dev.cubentia.domain.tutor.StageAttempt
import dev.cubentia.domain.tutor.TwoLookMastery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    /** A physical LL practice: switched to physical at the entry (no replaced evidence) and the cube confirmed. */
    private fun physicalPractice(): SessionState =
        SessionState.IDLE.after(
            SessionEvent.PracticeStarted(lesson, TUTOR_CONFIG, T0, algorithms),
            SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0),
            SessionEvent.PhysicalConfirmed(null, T0),
        )

    private fun restored(state: SessionState): SessionState {
        val snapshot = checkNotNull(SessionSnapshot.of(state, "session", "rev"))
        return reduce(SessionState.IDLE, SessionEvent.SessionRestored(snapshot, lesson, TUTOR_CONFIG, T0, algorithms)).state
    }

    private fun Transition.ending(): Effect.AttemptEnded? = effects.filterIsInstance<Effect.AttemptEnded>().singleOrNull()

    @Test fun `a physical LL practice completes only on the solve confirmation, also after leaving and resuming`() {
        var state = physicalPractice()
        moves(lesson.demonstration).forEach {
            val transition = reduce(state, SessionEvent.PhysicalConfirmed(it, T0))
            assertEquals(null, transition.ending())
            state = transition.state
        }
        assertTrue(state.objectiveMet)
        assertTrue(state.attemptOpen)
        assertTrue(state.physicalSolveUnconfirmed)

        // Leaving before "Yes, it's solved" records no COMPLETED attempt, so nothing can count toward mastery.
        val left = reduce(state, SessionEvent.PracticeStarted(lesson, TUTOR_CONFIG, T0, algorithms))
        assertEquals(AttemptOutcome.ABANDONED, left.ending()!!.outcome)

        // Resumed after process death, the attempt is still open and still unconfirmed.
        val resumed = restored(state)
        assertTrue(resumed.attemptOpen)
        assertTrue(resumed.physicalSolveUnconfirmed)
        assertFalse(resumed.physicalSolveConfirmed)
        val cubeConfirmed = reduce(resumed, SessionEvent.PhysicalConfirmed(null, T0))
        assertEquals(null, cubeConfirmed.ending())
        assertTrue(cubeConfirmed.state.attemptOpen)

        val confirmed = reduce(cubeConfirmed.state, SessionEvent.PhysicalSolveConfirmed(T0))
        val ending = confirmed.ending()!!
        assertEquals(AttemptOutcome.COMPLETED, ending.outcome)
        assertTrue(
            TwoLookMastery.qualifies(
                StageAttempt(
                    objectiveId = ending.objectiveId,
                    outcome = ending.outcome,
                    hints = ending.hints,
                    errors = ending.errors,
                ),
            ),
        )
        // The 2-look milestone plays on a confirmed physical solve (LessonScreen: physicalSolveConfirmed).
        assertTrue(confirmed.state.physicalSolveConfirmed)
        assertFalse(confirmed.state.physicalSolveUnconfirmed)
        assertFalse(confirmed.state.attemptOpen)
    }

    @Test fun `a restored physical LL practice keeps its Move evidence so a clean solve still qualifies`() {
        val (first, second) = moves(lesson.demonstration)
        val partway = reduce(physicalPractice(), SessionEvent.PhysicalConfirmed(first, T0)).state
        assertFalse(partway.objectiveMet)

        val resumed = restored(partway)
        assertEquals(SyncState.PHYSICAL_AWAITING_CONFIRM, resumed.syncState)
        assertEquals(partway.history.done, resumed.history.done)
        assertEquals(0, resumed.twoLookErrors)

        val solved =
            resumed.after(
                SessionEvent.PhysicalConfirmed(null, T0),
                SessionEvent.PhysicalConfirmed(second, T0),
            )
        assertTrue(solved.objectiveMet)
        val ending = reduce(solved, SessionEvent.PhysicalSolveConfirmed(T0)).ending()!!
        assertEquals(AttemptOutcome.COMPLETED, ending.outcome)
        assertEquals(0, ending.errors)
        assertEquals(0, ending.hints)
    }
}
