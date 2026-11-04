package dev.rubikxplorer.application.session

import dev.rubikxplorer.domain.text.Text
import dev.rubikxplorer.domain.tutor.Certainty
import dev.rubikxplorer.domain.tutor.ErrorCategory
import dev.rubikxplorer.domain.tutor.MoveFeedback
import dev.rubikxplorer.domain.tutor.NextAction
import dev.rubikxplorer.domain.tutor.Recovery
import dev.rubikxplorer.domain.tutor.TeachingMode
import dev.rubikxplorer.domain.tutor.TutorDecision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The session rows of Story 2.5's I/O & edge-case matrix: the tutor fields and the derived decision. */
class TutorEventsTest {
    private fun decision(state: SessionState): TutorDecision = checkNotNull(state.decision)

    @Test
    fun `guide me start reveals nothing, cannot advance and is not satisfied`() {
        val s = started()
        assertEquals(TeachingMode.GUIDE_ME, s.teachingMode)
        assertEquals(0, s.stepIndex)
        assertFalse(s.revealRequested)
        assertEquals(
            TutorDecision(
                objectiveId = whiteCrossLesson().objective,
                satisfied = false,
                diagnosis = null,
                certainty = Certainty.VERIFIED,
                nextAction = NextAction.TURN,
                explanation = Text("lesson.lesson-01-01.goal.1"),
                recovery = Recovery.Unavailable,
                reveal = false,
                advance = false,
                feedback = null,
            ),
            s.decision,
        )
    }

    @Test
    fun `idle has no decision and ignores the tutor events`() {
        val idle = SessionState.IDLE
        assertNull(idle.decision)
        listOf(reveal, stepAdvanced, modeChanged(TeachingMode.SHOW_ME)).forEach { event ->
            assertEquals(Transition(idle), reduce(idle, event))
        }
    }

    @Test
    fun `a reveal request reveals without a revision bump`() {
        val s = started().after(commit(R), undo)
        val t = reduce(s, reveal)
        assertTrue(decision(t.state).reveal)
        assertTrue(t.state.revealRequested)
        assertEquals(s.revision, t.state.revision)
        assertEquals(s.history, t.state.history)
        assertTrue(t.state.history.canRedo)
        assertEquals(emptyList<Presentation>(), t.presentation)
        assertEquals(emptyList<Effect>(), t.effects)
    }

    @Test
    fun `show me reveals and leaves virtual, history and revision unchanged`() {
        val s = started().after(commit(R))
        val t = reduce(s, modeChanged(TeachingMode.SHOW_ME))
        assertEquals(TeachingMode.SHOW_ME, t.state.teachingMode)
        assertTrue(decision(t.state).reveal)
        assertEquals(s.virtual, t.state.virtual)
        assertEquals(s.history, t.state.history)
        assertEquals(s.revision, t.state.revision)
        assertEquals(t.state, reduce(t.state, modeChanged(TeachingMode.SHOW_ME)).state)
    }

    @Test
    fun `a mode switch keeps a pending reset current`() {
        val asked = started().after(commit(R), resetEvent)
        val pending = asked.pendingReset!!
        val switched = asked.after(modeChanged(TeachingMode.SHOW_ME), reveal)
        assertEquals(pending, switched.pendingReset)
        val confirmed = switched.after(pending.confirm())
        assertEquals(confirmed.entry, confirmed.virtual)
        assertNull(confirmed.lastDiscarded)
    }

    @Test
    fun `committing F prime meets the objective and allows advancing`() {
        val d = decision(started().after(commit(F_PRIME)))
        assertTrue(d.satisfied)
        assertTrue(d.advance)
    }

    @Test
    fun `an intermediate step advances on its path state and hides the Moves again`() {
        val atStep1 = started(twoStepLesson()).after(reveal, commit(U))
        val d = decision(atStep1)
        assertTrue(d.advance)
        assertFalse(d.satisfied)
        val next = reduce(atStep1, stepAdvanced).state
        assertEquals(1, next.stepIndex)
        assertFalse(next.revealRequested)
        assertEquals(atStep1.revision, next.revision)
        assertFalse(decision(next).advance)
        assertTrue(decision(next.after(commit(F_PRIME))).satisfied)
    }

    @Test
    fun `step advanced is a no-op before the path state, once satisfied, and on the last step`() {
        val fresh = started(twoStepLesson())
        assertEquals(fresh, reduce(fresh, stepAdvanced).state)
        val onLast = fresh.after(commit(U), stepAdvanced)
        assertEquals(1, onLast.stepIndex)
        val done = onLast.after(commit(F_PRIME))
        assertTrue(decision(done).satisfied)
        assertEquals(done, reduce(done, stepAdvanced).state)
        val satisfiedOneStep = started().after(commit(F_PRIME))
        assertEquals(satisfiedOneStep, reduce(satisfiedOneStep, stepAdvanced).state)
    }

    @Test
    fun `an applied reset returns to the first step with the Moves hidden`() {
        val onStep2 = started(twoStepLesson()).after(commit(U), stepAdvanced, reveal)
        assertEquals(1, onStep2.stepIndex)
        val asked = onStep2.after(resetEvent)
        // Pending ask: the tutor fields wait for the answer.
        assertEquals(1, asked.stepIndex)
        assertTrue(asked.revealRequested)
        val declined = asked.after(asked.pendingReset!!.decline())
        assertEquals(1, declined.stepIndex)
        val confirmed = asked.after(asked.pendingReset!!.confirm())
        assertEquals(0, confirmed.stepIndex)
        assertFalse(confirmed.revealRequested)
        // Nothing unsaved: the reset applies at once and still returns to step 1.
        val undone = onStep2.after(undo)
        val reset = undone.after(resetEvent)
        assertNull(reset.pendingReset)
        assertEquals(0, reset.stepIndex)
        assertFalse(reset.revealRequested)
    }

    @Test
    fun `an applied reset and a new lesson clear a pending Notice`() {
        val pending = started(twoStepLesson()).after(commit(U))
        assertEquals(0, pending.noticeStep)
        assertTrue(decision(pending).advance)

        fun assertNoNotice(
            label: String,
            after: SessionState,
        ) {
            assertNull(after.noticeStep, label)
            assertFalse(decision(after).advance, label)
            assertEquals(NextAction.TURN, decision(after).nextAction, label)
        }
        val asked = pending.after(resetEvent)
        assertEquals(0, asked.noticeStep, "a reset that only asks keeps it")
        assertNoNotice("reset confirmed", asked.after(asked.pendingReset!!.confirm()))
        assertNoNotice("lesson started", pending.after(lessonStarted(twoStepLesson("lesson-01-02"))))
    }

    @Test
    fun `a new lesson keeps the teaching mode and starts at step 1`() {
        val s = started(twoStepLesson()).after(modeChanged(TeachingMode.SHOW_ME), commit(U), stepAdvanced)
        val next = s.after(lessonStarted(whiteCrossLesson()))
        assertEquals(TeachingMode.SHOW_ME, next.teachingMode)
        assertEquals(0, next.stepIndex)
        assertFalse(next.revealRequested)
    }

    // Story 2.6: Test me feedback and Let me try.

    private val testMe = started().after(modeChanged(TeachingMode.TEST_ME))

    @Test
    fun `test me neutral and harmful Moves, judged against the current step`() {
        val neutral = testMe.after(commit(D))
        assertEquals(MoveFeedback.NEUTRAL, neutral.lastMoveFeedback)
        assertEquals(MoveFeedback.NEUTRAL, decision(neutral).feedback)
        assertNull(decision(neutral).diagnosis)
        assertFalse(decision(neutral).reveal)
        // U lifts three cross edges, but no step has kept them yet: neutral, not harmful.
        assertEquals(MoveFeedback.NEUTRAL, decision(testMe.after(commit(U))).feedback)

        // Past step 1 of the two-step Lesson its pieces are preserved, so D disturbs them.
        val atStep2 = started(twoStepLesson()).after(modeChanged(TeachingMode.TEST_ME), commit(U), stepAdvanced)
        val harmed = atStep2.after(commit(D))
        assertEquals(MoveFeedback.HARMFUL, harmed.lastMoveFeedback)
        assertEquals(MoveFeedback.HARMFUL, decision(harmed).feedback)
        val diagnosis = checkNotNull(decision(harmed).diagnosis)
        assertEquals(ErrorCategory.WRONG_MOVE_OR_DIRECTION, diagnosis.category)
        assertEquals(diagnosis.explanation, decision(harmed).explanation)
        assertEquals(atStep2.virtual.apply(D), harmed.virtual, "the Learner's state is kept, never replaced")
    }

    @Test
    fun `test me on-path Moves, then completion hands over to Notice and Next`() {
        val s = started(oneLongStepLesson()).after(modeChanged(TeachingMode.TEST_ME), commit(U))
        assertEquals(MoveFeedback.ON_PATH, decision(s).feedback)
        assertNull(decision(s).diagnosis)
        assertFalse(decision(s).advance)
        val done = s.after(commit(F_PRIME))
        assertEquals(MoveFeedback.ON_PATH, done.lastMoveFeedback)
        assertTrue(decision(done).satisfied)
        assertTrue(decision(done).advance)
        assertNull(decision(done).feedback)
    }

    @Test
    fun `feedback and diagnosis clear on undo, redo, applied reset or step advance and a mode change, with no revision bump`() {
        val moved = testMe.after(commit(Y))
        check(moved.lastDiagnosis != null)

        fun assertCleared(
            label: String,
            after: SessionState,
            bumps: Long,
        ) {
            assertNull(after.lastMoveFeedback, label)
            assertNull(after.lastDiagnosis, label)
            assertNull(decision(after).feedback, label)
            assertNull(decision(after).diagnosis, label)
            assertEquals(moved.revision + bumps, after.revision, label)
        }
        assertCleared("undo", moved.after(undo), 1)
        assertCleared("redo", moved.after(undo, redo), 2)
        val asked = moved.after(resetEvent)
        assertEquals(MoveFeedback.NEUTRAL, asked.lastMoveFeedback, "a reset that only asks keeps it")
        assertEquals(moved.lastDiagnosis, asked.lastDiagnosis, "a reset that only asks keeps it")
        assertCleared("reset confirmed", asked.after(asked.pendingReset!!.confirm()), 1)
        assertCleared("mode change", moved.after(modeChanged(TeachingMode.GUIDE_ME)), 0)
        assertEquals(moved, moved.after(modeChanged(TeachingMode.TEST_ME)), "same mode: unchanged")
        assertCleared("lesson started", moved.after(lessonStarted(whiteCrossLesson("lesson-01-02"))), 1)

        val atStep1 = started(twoStepLesson()).after(modeChanged(TeachingMode.TEST_ME), commit(U))
        assertEquals(MoveFeedback.ON_PATH, atStep1.lastMoveFeedback)
        val advanced = atStep1.after(stepAdvanced)
        assertEquals(1, advanced.stepIndex)
        assertNull(advanced.lastMoveFeedback)
        assertEquals(atStep1.revision, advanced.revision)
        // A StepAdvanced that does not apply leaves it.
        val notDone = testMe.after(commit(D))
        assertEquals(notDone, notDone.after(stepAdvanced))
    }

    @Test
    fun `other modes record no feedback and no diagnosis, and a commit there clears a standing one`() {
        listOf(TeachingMode.SHOW_ME, TeachingMode.GUIDE_ME, TeachingMode.LET_ME_TRY).forEach { mode ->
            listOf(D, U, Y).forEach { move ->
                val s = started().after(modeChanged(mode), commit(move))
                assertNull(s.lastMoveFeedback, "$mode $move")
                assertNull(s.lastDiagnosis, "$mode $move")
                assertNull(decision(s).feedback, "$mode $move")
                assertNull(decision(s).diagnosis, "$mode $move")
            }
            val standing =
                started()
                    .after(
                        modeChanged(mode),
                    ).copy(lastMoveFeedback = MoveFeedback.NEUTRAL, lastDiagnosis = TURNED_DIAGNOSIS)
            val committed = standing.after(commit(D))
            assertNull(committed.lastMoveFeedback, "$mode")
            assertNull(committed.lastDiagnosis, "$mode")
        }
    }

    @Test
    fun `let me try reveals only after a hint request`() {
        val s = started().after(modeChanged(TeachingMode.LET_ME_TRY))
        assertFalse(decision(s).reveal)
        assertFalse(decision(s.after(commit(D))).reveal)
        assertTrue(decision(s.after(reveal)).reveal)
    }
}
