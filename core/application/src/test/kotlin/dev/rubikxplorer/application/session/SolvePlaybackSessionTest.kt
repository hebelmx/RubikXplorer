package dev.rubikxplorer.application.session

import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.tutor.MoveFeedback
import dev.rubikxplorer.domain.tutor.TeachingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Story 3.6 AC 2, session side: `SolvePlaybackStarted` starts a `SOLVE_PLAYBACK` session, row by row of the matrix. */
class SolvePlaybackSessionTest {
    @Test
    fun `starting from idle holds the start state and its Solution with one snap`() {
        val t = reduce(SessionState.IDLE, playbackStarted())
        val s = t.state
        assertEquals(SessionKind.SOLVE_PLAYBACK, s.kind)
        assertNull(s.lesson)
        assertEquals(SCRAMBLED, s.entry)
        assertEquals(SCRAMBLED, s.virtual)
        assertEquals(SOLUTION, s.solution)
        assertEquals(1L, s.revision)
        assertTrue(s.isActive)
        assertNull(s.decision)
        assertNull(s.progress)
        assertEquals(listOf(Presentation.Snap(1, SCRAMBLED)), t.presentation)
        assertEquals(emptyList<Effect>(), t.effects)
    }

    @Test
    fun `starting while a completed Lesson is active suspends it without an attempt end`() {
        val lesson =
            started(twoStepLesson()).after(modeChanged(TeachingMode.TEST_ME), commit(U), stepAdvanced, reveal, commit(F_PRIME), resetEvent)
        check(lesson.pendingReset != null && lesson.stepIndex == 1 && lesson.revealRequested && lesson.history.canUndo)
        val withFeedback = lesson.copy(lastMoveFeedback = MoveFeedback.NEUTRAL, lastDiagnosis = TURNED_DIAGNOSIS)
        val transition = reduce(withFeedback, playbackStarted())
        assertEquals(SessionKind.SOLVE_PLAYBACK, transition.state.kind)
        assertTrue(transition.effects.isEmpty())
    }

    @Test
    fun `suspension closes an open attempt with counters peak help and sequential ids`() {
        val before = started().after(hint, commit(R)).copy(stepMistakes = mapOf(0 to 3))
        val result = reduce(before, playbackStarted())
        val end = result.effects.single() as Effect.AttemptEnded
        assertEquals(AttemptOutcome.ABANDONED, end.outcome)
        assertEquals(3, end.errors)
        assertEquals(before.hintsUsed, end.hints)
        assertEquals(before.attemptAssistance, end.assistanceLevel)
        assertEquals(before.nextEffectId, end.effectId)
        assertEquals(result.state.revision, end.revision)
        assertEquals(end.effectId + 1, result.state.nextEffectId)
        assertNull(result.state.checkpoint)
    }

    @Test
    fun `ending playback empties the session preserving monotonic revision and effect ids`() {
        val playback = started().after(playbackStarted())
        val result = reduce(playback, SessionEvent.SolvePlaybackEnded(T0))
        assertEquals(Transition(SessionState.IDLE.copy(revision = playback.revision + 1, nextEffectId = playback.nextEffectId)), result)
        for (before in listOf(SessionState.IDLE, started())) {
            assertEquals(Transition(before), reduce(before, SessionEvent.SolvePlaybackEnded(T0)))
        }
    }

    @Test
    fun `an unverified Solution changes nothing`() {
        listOf(SessionState.IDLE, started()).forEach { before ->
            assertEquals(Transition(before), reduce(before, playbackStarted(solution = listOf(U_PRIME))))
            assertEquals(Transition(before), reduce(before, playbackStarted(solution = emptyList())))
        }
    }

    @Test
    fun `an already-solved state starts with an empty Solution`() {
        val s = SessionState.IDLE.after(playbackStarted(CubeState.SOLVED, emptyList()))
        assertEquals(SessionKind.SOLVE_PLAYBACK, s.kind)
        assertEquals(emptyList<Any>(), s.solution)
        assertEquals(CubeState.SOLVED, s.entry)
    }

    @Test
    fun `a playback session ignores every event but a new start`() {
        val playback = SessionState.IDLE.after(playbackStarted())
        val pending =
            Effect.ConfirmReset(
                1,
                playback.revision,
                dev.rubikxplorer.domain.text
                    .Text("msg.session.reset-unsaved"),
            )
        listOf(
            commit(R),
            undo,
            redo,
            resetEvent,
            pending.confirm(),
            pending.decline(),
            modeChanged(TeachingMode.SHOW_ME),
            reveal,
            stepAdvanced,
        ).forEach { event -> assertEquals(Transition(playback), reduce(playback, event), event.toString()) }
    }

    @Test
    fun `a Lesson start replaces a playback session and drops its Solution`() {
        val playback = SessionState.IDLE.after(playbackStarted())
        val t = reduce(playback, lessonStarted(whiteCrossLesson()))
        assertEquals(SessionKind.LESSON, t.state.kind)
        assertEquals(whiteCrossLesson(), t.state.lesson)
        assertEquals(emptyList<Any>(), t.state.solution)
        assertEquals(playback.revision + 1, t.state.revision)
        assertEquals(CubeState.SOLVED.apply(F), t.state.entry)
    }

    @Test
    fun `a new Solution replaces the playing one`() {
        val playback = SessionState.IDLE.after(playbackStarted())
        val other = CubeState.SOLVED.apply(F)
        val s = playback.after(playbackStarted(other, listOf(F_PRIME)))
        assertEquals(other, s.entry)
        assertEquals(listOf(F_PRIME), s.solution)
        assertEquals(playback.revision + 1, s.revision)
    }
}
