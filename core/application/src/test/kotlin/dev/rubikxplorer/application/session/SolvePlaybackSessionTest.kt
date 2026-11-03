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
    fun `starting while a Lesson is active leaves the Lesson, its history and tutor state unchanged`() {
        val lesson =
            started(twoStepLesson()).after(modeChanged(TeachingMode.TEST_ME), commit(U), stepAdvanced, reveal, commit(R), resetEvent)
        check(lesson.pendingReset != null && lesson.stepIndex == 1 && lesson.revealRequested && lesson.history.canUndo)
        val withFeedback = lesson.copy(lastMoveFeedback = MoveFeedback.NEUTRAL)
        assertEquals(Transition(withFeedback), reduce(withFeedback, playbackStarted()))
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
        val t = reduce(playback, SessionEvent.LessonStarted(whiteCrossLesson(), T0))
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
