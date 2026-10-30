package dev.rubikxplorer.domain.cube

import io.kotest.common.ExperimentalKotest
import io.kotest.property.PropTestConfig
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MoveHistoryTest {
    private val r = FaceTurn(Face.R, Turn.CLOCKWISE)
    private val u = FaceTurn(Face.U, Turn.CLOCKWISE)
    private val f = FaceTurn(Face.F, Turn.CLOCKWISE)

    @OptIn(ExperimentalKotest::class)
    private fun config(seed: Long) = PropTestConfig(seed = seed, iterations = 500)

    @Test
    fun `a fresh history is solved with nothing to undo or redo`() {
        val history = MoveHistory()
        assertEquals(CubeState.SOLVED, history.state)
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertNull(history.undoable)
        assertNull(history.redoable)
        assertEquals(history, history.undo())
        assertEquals(history, history.redo())
        assertEquals(MoveHistory().hashCode(), history.hashCode())
    }

    @Test
    fun `a history from a start state undoes back to it and resets to it`() {
        val entry = CubeState.SOLVED.apply(f)
        val history = MoveHistory(entry)
        assertEquals(entry, history.start)
        assertEquals(entry, history.state)
        assertFalse(history.canUndo)
        val walked = history.commit(r).commit(u)
        assertEquals(entry.apply(r, u), walked.state)
        assertEquals(entry, walked.undo().undo().state)
        assertEquals(history, walked.reset())
        assertEquals(history.hashCode(), walked.reset().hashCode())
        assertNotEquals(MoveHistory(), history)
        assertEquals(MoveHistory(), MoveHistory(CubeState.SOLVED))
        assertTrue(history.toString().contains("start="))
    }

    @Test
    fun `commit applies the Move and enables undo only`() {
        val history = MoveHistory().commit(r)
        assertEquals(CubeState.SOLVED.apply(r), history.state)
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
        assertEquals(r, history.undoable)
    }

    @Test
    fun `undo then redo walks the exact states`() {
        val afterRu = MoveHistory().commit(r).commit(u)
        val undone = afterRu.undo()
        assertEquals(CubeState.SOLVED.apply(r), undone.state)
        assertTrue(undone.canRedo)
        assertEquals(u, undone.redoable)
        val redone = undone.redo()
        assertEquals(CubeState.SOLVED.apply(r, u), redone.state)
        assertEquals(afterRu, redone)
        assertFalse(redone.canRedo)
    }

    @Test
    fun `a new Move clears redo`() {
        val history = MoveHistory().commit(r).undo().commit(f)
        assertEquals(CubeState.SOLVED.apply(f), history.state)
        assertFalse(history.canRedo)
        assertNull(history.redoable)
    }

    @Test
    fun `reset returns solved with both stacks empty`() {
        val history =
            MoveHistory()
                .commit(r)
                .commit(u)
                .undo()
                .reset()
        assertEquals(MoveHistory(), history)
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun `equality covers the stacks, not just the state`() {
        val viaUndo = MoveHistory().commit(r).undo()
        assertEquals(MoveHistory().state, viaUndo.state)
        assertNotEquals(MoveHistory(), viaUndo)
        assertFalse(MoveHistory().equals("MoveHistory"))
        assertTrue(viaUndo.toString().contains("undone=R"))
    }

    @Test
    fun `committing any sequence then undoing it all returns to solved`() =
        runBlocking<Unit> {
            checkAll(config(seed = 1_606L), arbAnySequence) { moves ->
                var history = moves.fold(MoveHistory()) { h, m -> h.commit(m) }
                assertEquals(CubeState.SOLVED.apply(moves), history.state)
                repeat(moves.size) { history = history.undo() }
                assertEquals(CubeState.SOLVED, history.state)
                assertFalse(history.canUndo)
                assertEquals(moves.isNotEmpty(), history.canRedo)
            }
        }

    @Test
    fun `undo then redo restores the same history`() =
        runBlocking<Unit> {
            checkAll(config(seed = 2_606L), arbAnySequence, arbAnyMove) { moves, last ->
                val history = (moves + last).fold(MoveHistory()) { h, m -> h.commit(m) }
                assertEquals(history, history.undo().redo())
                assertEquals(history.state, history.undo().redo().state)
            }
        }

    @Test
    fun `any commit after an undo clears redo`() =
        runBlocking<Unit> {
            checkAll(config(seed = 3_606L), arbAnySequence, arbAnyMove, arbAnyMove) { moves, last, next ->
                val history = (moves + last).fold(MoveHistory()) { h, m -> h.commit(m) }.undo().commit(next)
                assertFalse(history.canRedo)
            }
        }
}
