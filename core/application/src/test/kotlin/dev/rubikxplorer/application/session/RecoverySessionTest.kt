package dev.rubikxplorer.application.session

import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.content.ContentLoader
import dev.rubikxplorer.domain.content.ContentSource
import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.MoveHistory
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.NotationResult
import dev.rubikxplorer.domain.text.Text
import dev.rubikxplorer.domain.tutor.ErrorCategory
import dev.rubikxplorer.domain.tutor.HintRung
import dev.rubikxplorer.domain.tutor.MoveFeedback
import dev.rubikxplorer.domain.tutor.RecoveryOffer
import dev.rubikxplorer.domain.tutor.TeachingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Story 4.4 (FR-12, UX-DR11, UX-DR25): the Checkpoint follows the Objective boundaries only, and
 * *Try again* (`RetryRequested`) returns the history to it without restarting the Lesson.
 */
class RecoverySessionTest {
    private val lessons: List<Lesson> by lazy {
        val result = ContentLoader.load(ContentSource.SHIPPED)
        check(result is ContentLoadResult.Loaded) { "the shipped content does not load: $result" }
        result.bundle.stages.flatMap { it.lessons }
    }

    private fun lesson(id: String): Lesson = lessons.single { it.id.value == id }

    private fun move(notation: String): Move = (Notation.parse(notation) as NotationResult.Parsed).moves.single()

    private fun testMe(id: String): SessionState = started(lesson(id)).after(modeChanged(TeachingMode.TEST_ME))

    @Test
    fun `disturbing the cross in 03-01 is diagnosed, offers every recovery and keeps the entry Checkpoint`() {
        val before = testMe("lesson-03-01")
        assertEquals(MoveHistory(before.entry), before.checkpoint)
        val harmed = before.after(commit(move("R")))

        val decision = checkNotNull(harmed.decision)
        val diagnosis = checkNotNull(decision.diagnosis)
        assertEquals(ErrorCategory.COMPLETED_LAYER_DISTURBED, diagnosis.category)
        assertEquals(Text("msg.diagnosis.completed-layer-disturbed", mapOf("layer" to "cross")), diagnosis.explanation)
        assertEquals(Text("msg.recovery.guided-recovery"), diagnosis.recoveryPath.prompt)
        assertEquals(listOf(RecoveryOffer.UNDO, RecoveryOffer.GUIDE_ME_BACK, RecoveryOffer.TRY_AGAIN), diagnosis.recoveryPath.offers)
        assertTrue(diagnosis.disturbed.isNotEmpty(), "the disturbed cross pieces are named")
        assertEquals(MoveHistory(harmed.entry), harmed.checkpoint, "a disturbing Move never moves the Checkpoint")
        assertFalse(harmed.objectiveMet)
        assertFalse(decision.satisfied)
        assertFalse(decision.advance)
    }

    @Test
    fun `try again mid-Lesson returns to the last step boundary and keeps the attempt`() {
        val f2 = move("F2")
        val atStep2 = testMe("lesson-02-05").after(commit(f2))
        assertEquals(MoveHistory(atStep2.entry).commit(f2), atStep2.checkpoint, "a pending Notice is a boundary")
        val hinted = atStep2.after(stepAdvanced, hint)
        assertEquals(1, hinted.hintsUsed)
        val harmed = hinted.after(commit(move("F")))
        assertEquals(MoveFeedback.HARMFUL, harmed.lastMoveFeedback)
        assertNotNull(checkNotNull(harmed.decision).diagnosis)

        val transition = reduce(harmed, retry)
        val retried = transition.state
        assertEquals(harmed.entry.apply(f2), retried.virtual)
        assertEquals(listOf(f2), retried.history.done)
        assertFalse(retried.history.canRedo)
        assertEquals(1, retried.stepIndex)
        assertSame(harmed.lesson, retried.lesson)
        assertEquals(harmed.entry, retried.entry)
        assertEquals(TeachingMode.TEST_ME, retried.teachingMode)
        assertEquals(1, retried.hintsUsed, "Try again never resets the Hint count")
        assertEquals(HintRung.NONE, retried.hintRung)
        assertFalse(retried.revealRequested)
        assertNull(retried.noticeStep)
        assertEquals(harmed.revision + 1, retried.revision)
        assertNull(retried.lastDiagnosis)
        assertNull(retried.lastMoveFeedback)
        assertNull(checkNotNull(retried.decision).diagnosis)
        assertEquals(harmed.checkpoint, retried.checkpoint)
        assertEquals(listOf(Presentation.Snap(retried.revision, retried.virtual)), transition.presentation)
        assertTrue(transition.effects.isEmpty())

        // The restored history is a real one: Undo takes back F2.
        val undone = retried.after(undo)
        assertEquals(retried.entry, undone.virtual)
        assertTrue(undone.history.done.isEmpty())
    }

    @Test
    fun `try again cancels a pending reset confirmation`() {
        val asked = started().after(commit(F_PRIME), commit(R), resetEvent)
        assertNotNull(asked.pendingReset)
        val retried = asked.after(retry)
        assertNull(retried.pendingReset)
        assertEquals(listOf<Move>(F_PRIME), retried.history.done)
    }

    @Test
    fun `nothing to retry leaves a fresh Lesson unchanged`() {
        val fresh = started()
        assertEquals(fresh, reduce(fresh, retry).state)
        assertTrue(reduce(fresh, retry).presentation.isEmpty())
    }

    @Test
    fun `idle and playback sessions ignore try again`() {
        assertEquals(SessionState.IDLE, SessionState.IDLE.after(retry))
        val playback = SessionState.IDLE.after(playbackStarted())
        assertNull(playback.checkpoint)
        assertEquals(playback, playback.after(retry))
        assertNull(SessionState.IDLE.checkpoint)
    }

    @Test
    fun `the Checkpoint follows boundaries only`() {
        val fresh = started()
        val entry = MoveHistory(fresh.entry)
        val crossed = fresh.after(commit(F_PRIME))
        assertEquals(entry.commit(F_PRIME), crossed.checkpoint)
        val disturbed = crossed.after(commit(R))
        assertEquals(entry.commit(F_PRIME), disturbed.checkpoint, "a disturbing Move leaves it")
        val recrossed = disturbed.after(commit(R_PRIME))
        assertEquals(entry.commit(F_PRIME).commit(R).commit(R_PRIME), recrossed.checkpoint)
        val undoneOnce = recrossed.after(undo)
        assertEquals(recrossed.checkpoint, undoneOnce.checkpoint, "an Undo off a boundary leaves it")
        val undoneTwice = undoneOnce.after(undo)
        assertEquals(entry.commit(F_PRIME), undoneTwice.checkpoint)
        assertFalse(checkNotNull(undoneTwice.checkpoint).canRedo, "a Checkpoint never holds redo")
        // Undoing below the boundary does not lower it.
        assertEquals(entry.commit(F_PRIME), undoneTwice.after(undo).checkpoint)
        // Tutor events never write it.
        listOf(hint, reveal, modeChanged(TeachingMode.SHOW_ME), stepAdvanced).forEach {
            assertEquals(disturbed.checkpoint, disturbed.after(it).checkpoint)
        }
    }

    @Test
    fun `a Redo onto a boundary makes it the Checkpoint, and Try again returns there`() {
        val fresh = started()
        val recrossedHistory = MoveHistory(fresh.entry).commit(F_PRIME).commit(R).commit(R_PRIME)
        val recrossed = fresh.after(commit(F_PRIME), commit(R), commit(R_PRIME))
        assertEquals(recrossedHistory, recrossed.checkpoint)
        val redone = recrossed.after(undo, undo, undo, redo, redo, redo)
        assertEquals(recrossedHistory, redone.checkpoint)
        assertFalse(checkNotNull(redone.checkpoint).canRedo)

        val retried = redone.after(commit(R), retry)
        assertEquals(listOf<Move>(F_PRIME, R, R_PRIME), retried.history.done)
        assertFalse(retried.history.canRedo)
    }

    @Test
    fun `an applied Reset or ResetConfirmed returns the Checkpoint to the entry`() {
        val crossed = started().after(commit(F_PRIME))
        val entry = MoveHistory(crossed.entry)
        assertEquals(entry, crossed.after(undo, resetEvent).checkpoint)
        val asked = crossed.after(resetEvent)
        assertEquals(crossed.checkpoint, asked.checkpoint, "a Reset that only asks leaves it")
        assertEquals(entry, asked.after(checkNotNull(asked.pendingReset).confirm()).checkpoint)
    }

    @Test
    fun `a new Lesson starts with its entry as the Checkpoint`() {
        val other = whiteCrossLesson("lesson-01-02")
        val restarted = started().after(commit(F_PRIME), lessonStarted(other))
        assertEquals(MoveHistory(restarted.entry), restarted.checkpoint)
    }
}
