package dev.cubentia.application.freecube

import dev.cubentia.application.session.Presentation
import dev.cubentia.domain.cube.Axis
import dev.cubentia.domain.cube.CubeRotation
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.MoveHistory
import dev.cubentia.domain.cube.Slice
import dev.cubentia.domain.cube.SliceTurn
import dev.cubentia.domain.cube.Turn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ReduceFreeCubeTest {
    private val r = FaceTurn(Face.R, Turn.CLOCKWISE)
    private val u = FaceTurn(Face.U, Turn.CLOCKWISE)
    private val f = FaceTurn(Face.F, Turn.CLOCKWISE)

    @Test
    fun `commit undo and redo walk exact states and stamp animations`() {
        val initial = FreeCubeState()
        val committed = reduceFreeCube(initial, FreeCubeEvent.MoveCommitted(r))
        assertEquals(MoveHistory().commit(r), committed.state.history)
        assertEquals(Presentation.Animate(r, 1, CubeState.SOLVED.apply(r)), committed.presentation)
        val undone = reduceFreeCube(committed.state, FreeCubeEvent.Undo)
        assertEquals(CubeState.SOLVED, undone.state.history.state)
        assertEquals(listOf(r), undone.state.history.undone)
        assertEquals(Presentation.Animate(r.inverse(), 2, CubeState.SOLVED), undone.presentation)
        val redone = reduceFreeCube(undone.state, FreeCubeEvent.Redo)
        assertEquals(committed.state.history, redone.state.history)
        assertEquals(Presentation.Animate(r, 3, committed.state.history.state), redone.presentation)
        assertEquals(3L, redone.state.revision)
        assertEquals(FreeCubeState(), initial)
    }

    @Test
    fun `unavailable undo and redo preserve the exact state instance`() {
        val fresh = FreeCubeState(revision = 42)
        val committed = reduceFreeCube(fresh, FreeCubeEvent.MoveCommitted(r)).state
        val undone = reduceFreeCube(committed, FreeCubeEvent.Undo).state
        listOf(
            fresh to FreeCubeEvent.Undo,
            fresh to FreeCubeEvent.Redo,
            committed to FreeCubeEvent.Redo,
            undone to FreeCubeEvent.Undo,
        ).forEach { (state, event) ->
            val result = reduceFreeCube(state, event)
            assertSame(state, result.state)
            assertNull(result.presentation)
        }
    }

    @Test
    fun `new commit clears redo while preserving earlier moves`() {
        val original = FreeCubeState(MoveHistory().commit(r).commit(u).undo(), 9)
        val result = reduceFreeCube(original, FreeCubeEvent.MoveCommitted(f))
        assertEquals(listOf(r, f), result.state.history.done)
        assertFalse(result.state.history.canRedo)
        assertEquals(Presentation.Animate(f, 10, CubeState.SOLVED.apply(r, f)), result.presentation)
        assertEquals(listOf(u), original.history.undone)
    }

    @Test
    fun `reset always advances and clears both stacks even from solved`() {
        listOf(
            MoveHistory(),
            MoveHistory().commit(r),
            MoveHistory().commit(r).commit(u).undo(),
            MoveHistory().commit(r).undo(),
        ).forEach { history ->
            val result = reduceFreeCube(FreeCubeState(history, 12), FreeCubeEvent.Reset)
            assertEquals(FreeCubeState(MoveHistory(), 13), result.state)
            assertEquals(Presentation.Snap(13, CubeState.SOLVED), result.presentation)
        }
    }

    @Test
    fun `scramble replaces prior history in one snap and every scramble move is undoable`() {
        val original = FreeCubeState(MoveHistory().commit(f).commit(u).undo(), 20)
        listOf(listOf(r, u, f), emptyList()).forEach { moves ->
            val result = reduceFreeCube(original, FreeCubeEvent.Scrambled(moves))
            assertEquals(MoveHistory().let { moves.fold(it) { h, m -> h.commit(m) } }, result.state.history)
            assertEquals(Presentation.Snap(21, CubeState.SOLVED.apply(moves)), result.presentation)
            var state = result.state
            moves.asReversed().forEach { move ->
                val undone = reduceFreeCube(state, FreeCubeEvent.Undo)
                assertEquals(Presentation.Animate(move.inverse(), state.revision + 1, undone.state.history.state), undone.presentation)
                state = undone.state
            }
            assertEquals(CubeState.SOLVED, state.history.state)
            assertFalse(state.history.canUndo)
        }
    }

    @Test
    fun `mixed events are deterministic and revisions advance exactly once per presentation`() {
        var state = FreeCubeState(revision = 100)
        val events =
            listOf(
                FreeCubeEvent.Undo,
                FreeCubeEvent.Redo,
                FreeCubeEvent.MoveCommitted(r),
                FreeCubeEvent.MoveCommitted(u),
                FreeCubeEvent.Undo,
                FreeCubeEvent.Redo,
                FreeCubeEvent.Reset,
                FreeCubeEvent.Scrambled(listOf(f, u)),
                FreeCubeEvent.Scrambled(emptyList()),
            )
        repeat(20) {
            events.forEach { event ->
                val result = reduceFreeCube(state, event)
                assertEquals(result, reduceFreeCube(state, event))
                assertEquals(state.revision + if (result.presentation == null) 0 else 1, result.state.revision)
                result.presentation?.let {
                    assertEquals(result.state.revision, it.revision)
                    assertEquals(result.state.history.state, it.target)
                }
                state = result.state
            }
        }
    }

    @Test
    fun `reducer handles counterclockwise, half turns, slice turns and rotations`() {
        val rPrime = FaceTurn(Face.R, Turn.COUNTERCLOCKWISE)
        val u2 = FaceTurn(Face.U, Turn.HALF)
        val m = SliceTurn(Slice.M, Turn.CLOCKWISE)
        val y = CubeRotation(Axis.Y, Turn.CLOCKWISE)
        val moves = listOf(rPrime, u2, m, y)
        var state = FreeCubeState()
        moves.forEachIndexed { index, move ->
            val result = reduceFreeCube(state, FreeCubeEvent.MoveCommitted(move))
            assertEquals((index + 1).toLong(), result.state.revision)
            assertEquals(Presentation.Animate(move, (index + 1).toLong(), result.state.history.state), result.presentation)
            state = result.state
        }
        val undone = reduceFreeCube(state, FreeCubeEvent.Undo)
        assertEquals(Presentation.Animate(y.inverse(), 5L, undone.state.history.state), undone.presentation)
    }

    @Test
    fun `consecutive redos walk back through multiple undone moves until exhausted`() {
        var state = FreeCubeState()
        val moves = listOf(r, u, f)
        moves.forEach { state = reduceFreeCube(state, FreeCubeEvent.MoveCommitted(it)).state }
        assertEquals(3L, state.revision)
        repeat(3) { state = reduceFreeCube(state, FreeCubeEvent.Undo).state }
        assertEquals(6L, state.revision)
        assertEquals(CubeState.SOLVED, state.history.state)
        moves.forEachIndexed { index, expectedMove ->
            val redone = reduceFreeCube(state, FreeCubeEvent.Redo)
            assertEquals((7 + index).toLong(), redone.state.revision)
            assertEquals(Presentation.Animate(expectedMove, (7 + index).toLong(), redone.state.history.state), redone.presentation)
            state = redone.state
        }
        assertEquals(CubeState.SOLVED.apply(moves), state.history.state)
        assertFalse(state.history.canRedo)
        val exhausted = reduceFreeCube(state, FreeCubeEvent.Redo)
        assertSame(state, exhausted.state)
        assertNull(exhausted.presentation)
    }
}
