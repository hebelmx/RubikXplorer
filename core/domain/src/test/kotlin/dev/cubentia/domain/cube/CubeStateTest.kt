package dev.cubentia.domain.cube

import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CubeStateTest {
    private val faceMoves: List<FaceTurn> = Face.entries.flatMap { face -> Turn.entries.map { FaceTurn(face, it) } }
    private val arbMove: Arb<Move> = Arb.element(faceMoves)
    private val arbSequence: Arb<List<Move>> = Arb.list(arbMove, 0..40)
    private val arbState: Arb<CubeState> =
        Arb.bind(Arb.element(Orientation.ALL), arbSequence) { o, seq -> CubeState(Cubies.SOLVED, o).apply(seq) }

    private fun cw(face: Face) = FaceTurn(face, Turn.CLOCKWISE)

    private fun ccw(face: Face) = FaceTurn(face, Turn.COUNTERCLOCKWISE)

    private fun half(face: Face) = FaceTurn(face, Turn.HALF)

    @OptIn(ExperimentalKotest::class)
    private fun config(seed: Long) = PropTestConfig(seed = seed, iterations = 500)

    /** Repetitions of [seq] from solved until solved again. */
    private fun order(seq: List<Move>): Int {
        var state = CubeState.SOLVED.apply(seq)
        var n = 1
        while (!state.isSolved) {
            check(n < MAX_ORDER) { "$seq did not return to solved within $MAX_ORDER repetitions" }
            state = state.apply(seq)
            n++
        }
        return n
    }

    @Test
    fun `every face Move on solved returns a new unsolved state and leaves solved unchanged`() {
        val before = CubeState(Cubies.SOLVED, Orientation.HOME)
        faceMoves.forEach { move ->
            val after = CubeState.SOLVED.apply(move)
            assertNotEquals(CubeState.SOLVED, after, "$move")
            assertFalse(after.isSolved, "$move")
            assertEquals(Orientation.HOME, after.orientation, "$move")
        }
        assertEquals(before, CubeState.SOLVED)
        assertTrue(CubeState.SOLVED.isSolved)
    }

    @Test
    fun `quarter turn four times is identity`() =
        runBlocking<Unit> {
            checkAll(config(seed = 1_202L), arbState, arbMove) { s, m ->
                assertEquals(s, s.apply(m, m, m, m))
            }
        }

    @Test
    fun `move then inverse is identity`() =
        runBlocking<Unit> {
            checkAll(config(seed = 2_202L), arbState, arbMove) { s, m ->
                assertEquals(s, s.apply(m).apply(m.inverse()))
            }
        }

    @Test
    fun `sequence then inverse sequence is identity`() =
        runBlocking<Unit> {
            checkAll(config(seed = 3_202L), arbState, arbSequence) { s, seq ->
                assertEquals(s, s.apply(seq).apply(seq.inverse()))
            }
        }

    @Test
    fun `half turn equals two quarter turns`() =
        runBlocking<Unit> {
            checkAll(config(seed = 4_202L), arbState, Arb.element(Face.entries)) { s, f ->
                assertEquals(s.apply(cw(f), cw(f)), s.apply(half(f)))
                assertEquals(s.apply(ccw(f), ccw(f)), s.apply(half(f)))
                assertEquals(s.apply(cw(f), cw(f), cw(f)), s.apply(ccw(f)))
            }
        }

    @Test
    fun `random sequences keep real-cube invariants`() =
        runBlocking<Unit> {
            checkAll(config(seed = 5_202L), arbSequence) { seq ->
                val c = CubeState.SOLVED.apply(seq).cubies
                assertEquals(0, c.cornerOrientation.sum() % 3, "twist $seq")
                assertEquals(0, c.edgeOrientation.sum() % 2, "flip $seq")
                assertEquals(parity(c.cornerPermutation), parity(c.edgePermutation), "parity $seq")
            }
        }

    @Test
    fun `real-cube orders`() {
        assertEquals(105, order(listOf(cw(Face.R), cw(Face.U))))
        assertEquals(6, order(listOf(cw(Face.R), cw(Face.U), ccw(Face.R), ccw(Face.U))))
        assertEquals(6, order(listOf(half(Face.R), half(Face.U))))
    }

    @Test
    fun `every adjacent face pair has the real-cube orders`() {
        Face.entries.forEach { a ->
            Face.entries.filter { it != a && it != a.opposite }.forEach { b ->
                assertEquals(105, order(listOf(cw(a), cw(b))), "($a $b)")
                assertEquals(6, order(listOf(cw(a), cw(b), ccw(a), ccw(b))), "($a $b $a' $b')")
                assertEquals(6, order(listOf(half(a), half(b))), "(${a}2 ${b}2)")
            }
        }
    }

    @Test
    fun `opposite faces commute`() {
        Face.entries.forEach { a ->
            assertEquals(CubeState.SOLVED.apply(cw(a), cw(a.opposite)), CubeState.SOLVED.apply(cw(a.opposite), cw(a)))
            assertEquals(4, order(listOf(cw(a), cw(a.opposite))))
        }
    }

    @Test
    fun `state that differs only in orientation is solved`() {
        Orientation.ALL.forEach { assertTrue(CubeState(Cubies.SOLVED, it).isSolved, "$it") }
    }

    @Test
    fun `solved in another orientation is not equal to SOLVED`() {
        Orientation.ALL.filter { it != Orientation.HOME }.forEach {
            assertNotEquals(CubeState.SOLVED, CubeState(Cubies.SOLVED, it), "$it")
        }
    }

    @Test
    fun `face Moves act through orientation`() {
        val withRightOnTop = Orientation.ALL.filter { it.homeFaceAt(Face.U) == Face.R }
        assertEquals(4, withRightOnTop.size)
        withRightOnTop.forEach { o ->
            val after = CubeState(Cubies.SOLVED, o).apply(cw(Face.U))
            assertEquals(CubeState.SOLVED.apply(cw(Face.R)).cubies, after.cubies, "$o")
            assertEquals(o, after.orientation)
        }
        Orientation.ALL.forEach { o ->
            faceMoves.forEach { m ->
                val home = FaceTurn(o.homeFaceAt(m.face), m.turn)
                assertEquals(CubeState.SOLVED.apply(home).cubies, CubeState(Cubies.SOLVED, o).apply(m).cubies, "$o $m")
            }
        }
    }

    @Test
    fun `list and vararg apply agree with one-by-one apply`() {
        val seq = listOf(cw(Face.R), half(Face.U), ccw(Face.F))
        val oneByOne =
            CubeState.SOLVED
                .apply(seq[0])
                .apply(seq[1])
                .apply(seq[2])
        assertEquals(oneByOne, CubeState.SOLVED.apply(seq))
        assertEquals(oneByOne, CubeState.SOLVED.apply(seq[0], seq[1], seq[2]))
        assertEquals(CubeState.SOLVED, CubeState.SOLVED.apply(emptyList()))
    }

    private companion object {
        /** The largest order of any cube group element; a correct cube never needs more repetitions. */
        const val MAX_ORDER = 1260
    }

    private fun parity(permutation: List<Int>): Int {
        var inversions = 0
        for (i in permutation.indices) {
            for (j in i + 1 until permutation.size) if (permutation[i] > permutation[j]) inversions++
        }
        return inversions % 2
    }
}
