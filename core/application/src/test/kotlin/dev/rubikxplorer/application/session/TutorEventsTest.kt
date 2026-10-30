package dev.rubikxplorer.application.session

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
        assertEquals(TutorDecision(whiteCrossLesson().objective, satisfied = false, reveal = false, advance = false), s.decision)
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
    fun `a new lesson keeps the teaching mode and starts at step 1`() {
        val s = started(twoStepLesson()).after(modeChanged(TeachingMode.SHOW_ME), commit(U), stepAdvanced)
        val next = s.after(SessionEvent.LessonStarted(whiteCrossLesson(), T0))
        assertEquals(TeachingMode.SHOW_ME, next.teachingMode)
        assertEquals(0, next.stepIndex)
        assertFalse(next.revealRequested)
    }
}
