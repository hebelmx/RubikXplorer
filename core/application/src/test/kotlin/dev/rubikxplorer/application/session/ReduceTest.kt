package dev.rubikxplorer.application.session

import dev.rubikxplorer.domain.content.FaceletString
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Turn
import dev.rubikxplorer.domain.predicate.ObjectiveProgress
import dev.rubikxplorer.domain.predicate.WhiteCrossSolved
import dev.rubikxplorer.domain.text.Text
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The I/O & edge-case matrix of Story 2.3, row by row. */
class ReduceTest {
    private val entry = CubeState.SOLVED.apply(F)

    @Test
    fun `start sets virtual to the entry state at revision 1 with a snap and the predicate's progress`() {
        val t = reduce(SessionState.IDLE, lessonStarted(whiteCrossLesson()))
        val s = t.state
        assertEquals(SessionKind.LESSON, s.kind)
        assertEquals(whiteCrossLesson(), s.lesson)
        assertEquals(entry, s.entry)
        assertEquals(entry, s.virtual)
        assertEquals(1L, s.revision)
        assertEquals(listOf(Presentation.Snap(1, entry)), t.presentation)
        assertEquals(emptyList<Effect>(), t.effects.filterNot { it is Effect.PersistCheckpoint })
        assertFalse(s.objectiveMet)
        assertEquals(WhiteCrossSolved.progress(entry), s.progress)
        assertFalse(s.hasUnsavedSteps)
        assertTrue(s.isActive)
    }

    @Test
    fun `committing F prime animates it and meets the white-cross objective`() {
        val t = reduce(started(), commit(F_PRIME))
        assertEquals(2L, t.state.revision)
        assertEquals(listOf(Presentation.Animate(F_PRIME, 2, CubeState.SOLVED)), t.presentation)
        assertTrue(t.state.objectiveMet)
        assertEquals(ObjectiveProgress(4, 4), t.state.progress)
        assertTrue(t.state.hasUnsavedSteps)
    }

    @Test
    fun `undo animates the inverse back to entry and redo animates the Move again`() {
        val committed = started().after(commit(F_PRIME))
        val undone = reduce(committed, undo)
        assertEquals(listOf(Presentation.Animate(F, 3, entry)), undone.presentation)
        assertEquals(entry, undone.state.virtual)
        assertFalse(undone.state.objectiveMet)
        val redone = reduce(undone.state, redo)
        assertEquals(listOf(Presentation.Animate(F_PRIME, 4, CubeState.SOLVED)), redone.presentation)
        assertTrue(redone.state.objectiveMet)
    }

    @Test
    fun `undo and redo with nothing to walk are no-ops`() {
        val s = started()
        assertEquals(Transition(s), reduce(s, undo))
        assertEquals(Transition(s), reduce(s, redo))
    }

    @Test
    fun `a new Move clears redo`() {
        val s = started().after(commit(U), undo, commit(R))
        assertFalse(s.history.canRedo)
        assertEquals(entry.apply(R), s.virtual)
    }

    @Test
    fun `reset with nothing unsaved resets at once with a snap and no effect`() {
        val fresh = started()
        val t = reduce(fresh, resetEvent)
        assertEquals(listOf(Presentation.Snap(fresh.revision, entry)), t.presentation)
        assertEquals(emptyList<Effect>(), t.effects)
        assertEquals(fresh, t.state)

        val allUndone = started().after(commit(R), commit(U), undo, undo)
        val u = reduce(allUndone, resetEvent)
        assertEquals(allUndone.revision + 1, u.state.revision)
        assertEquals(listOf(Presentation.Snap(u.state.revision, entry)), u.presentation)
        assertEquals(emptyList<Effect>(), u.effects)
        assertFalse(u.state.history.canRedo)
        assertEquals(entry, u.state.virtual)
    }

    @Test
    fun `reset with unsaved steps only asks, at the current revision`() {
        val s = started().after(commit(R))
        val t = reduce(s, resetEvent)
        val effect = Effect.ConfirmReset(s.nextEffectId, s.revision, Text("msg.session.reset-unsaved"))
        assertEquals(listOf(effect), t.effects)
        assertEquals(emptyList<Presentation>(), t.presentation)
        assertEquals(s.copy(pendingReset = effect, nextEffectId = s.nextEffectId + 1), t.state)
    }

    @Test
    fun `a current confirmation resets to entry and clears history and redo`() {
        val s = started().after(commit(R), commit(U), undo)
        val asked = reduce(s, resetEvent)
        val pending = asked.effects.single() as Effect.ConfirmReset
        val t = reduce(asked.state, pending.confirm())
        assertEquals(s.revision + 1, t.state.revision)
        assertEquals(entry, t.state.virtual)
        assertFalse(t.state.history.canUndo)
        assertFalse(t.state.history.canRedo)
        assertNull(t.state.pendingReset)
        assertEquals(listOf(Presentation.Snap(t.state.revision, entry)), t.presentation)
    }

    @Test
    fun `a current decline only clears the pending confirmation`() {
        val s = started().after(commit(R))
        val asked = reduce(s, resetEvent).state
        val t = reduce(asked, asked.pendingReset!!.decline())
        assertEquals(asked.copy(pendingReset = null), t.state)
        assertEquals(s.revision, t.state.revision)
        assertEquals(emptyList<Presentation>(), t.presentation)
        assertEquals(emptyList<Effect>(), t.effects)
    }

    @Test
    fun `before any Lesson every event but LessonStarted is a no-op`() {
        val idle = SessionState.IDLE
        val pending = Effect.ConfirmReset(1, 0, Text("msg.session.reset-unsaved"))
        listOf(undo, redo, resetEvent, commit(R), pending.confirm(), pending.decline()).forEach { event ->
            val t = reduce(idle, event)
            assertEquals(Transition(idle), t, event.toString())
            assertEquals(0L, t.state.revision)
        }
        assertFalse(idle.isActive)
        assertNull(idle.progress)
    }

    @Test
    fun `starting a Lesson replaces the session and keeps counting revisions and effect ids`() {
        val asked = started().after(commit(R), resetEvent)
        val other = whiteCrossLesson("lesson-01-02")
        val t = reduce(asked, lessonStarted(other))
        assertEquals(asked.revision + 1, t.state.revision)
        assertEquals(other, t.state.lesson)
        assertFalse(t.state.hasUnsavedSteps)
        assertNull(t.state.pendingReset)
        assertEquals(asked.nextEffectId + 1, t.state.nextEffectId)
    }

    @Test
    fun `Undo, Redo and a Move cancel a pending confirmation`() {
        val asked = started().after(commit(R), commit(U), resetEvent)
        listOf(undo, commit(F), redo).forEach { event ->
            val base = if (event == redo) asked.after(undo, resetEvent) else asked
            val t = reduce(base, event)
            assertNull(t.state.pendingReset, event.toString())
            assertEquals(base.revision + 1, t.state.revision, event.toString())
        }
    }

    @Test
    fun `an undecodable or impossible entry state is a programmer error`() {
        val malformed = whiteCrossLesson().copy(entryState = "W")
        assertThrows<IllegalStateException> { reduce(SessionState.IDLE, lessonStarted(malformed)) }
        val solved = CubeState.SOLVED.toFacelets().colors
        val twisted =
            solved.toMutableList().apply {
                this[0] = solved[9]
                this[9] = solved[0]
            }
        val impossible =
            whiteCrossLesson().copy(
                entryState =
                    FaceletString.encode(
                        dev.rubikxplorer.domain.cube
                            .Facelets(twisted),
                    ),
            )
        assertThrows<IllegalStateException> { reduce(SessionState.IDLE, lessonStarted(impossible)) }
    }

    @Test
    fun `revision never moves without a state change and rises by exactly one with one`() {
        val moves = listOf(F, R, U, FaceTurn(Face.D, Turn.HALF))
        var state = started()
        var effect: Effect.ConfirmReset? = null
        val events =
            List(400) { i ->
                when (i % 7) {
                    0, 1 -> commit(moves[i % moves.size])
                    2 -> undo
                    3 -> redo
                    4 -> resetEvent
                    5 -> null
                    else -> undo
                }
            }
        events.forEach { event ->
            val e = event ?: effect?.let { if (it.effectId % 2 == 0L) it.confirm() else it.decline() } ?: undo
            val t = reduce(state, e)
            val changed = t.state.history != state.history || t.state.lesson != state.lesson
            assertEquals(if (changed) state.revision + 1 else state.revision, t.state.revision, e.toString())
            t.presentation.forEach { assertEquals(t.state.revision, it.revision) }
            t.presentation.lastOrNull()?.let { assertEquals(t.state.virtual, it.target) }
            effect = (t.effects.filterIsInstance<Effect.ConfirmReset>().lastOrNull()) ?: effect
            state = t.state
        }
    }
}
