package dev.cubentia.domain.predicate

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.predicate.Fixtures.assertSameInEveryOrientation
import dev.cubentia.domain.predicate.Fixtures.faceTurns
import dev.cubentia.domain.predicate.Fixtures.moves
import dev.cubentia.domain.predicate.Fixtures.scramble
import dev.cubentia.domain.predicate.Fixtures.state
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** AC 1: [StateReached] judges the pattern, not the buttons, in every orientation. */
class StateReachedTest {
    private val turnR = StateReached("R")

    private fun progress(done: Int) = ObjectiveProgress(done, 20)

    private val fixtures: Map<String, CubeState> =
        mapOf(
            "solved" to CubeState.SOLVED,
            "scrambled" to CubeState.SOLVED.apply(scramble),
            "target (R)" to state("R"),
            "target held rotated (R y)" to state("R y"),
            "wrong face (L)" to state("L"),
            "white on D (x2 R)" to state("x2 R"),
        )

    @Test
    fun `solved is 12 of 20, the target is 20 of 20 and a rotation keeps it`() {
        assertFalse(turnR.isSatisfied(CubeState.SOLVED))
        assertEquals(progress(12), turnR.progress(CubeState.SOLVED))
        assertTrue(turnR.isSatisfied(state("R")))
        assertEquals(progress(20), turnR.progress(state("R")))
        assertTrue(turnR.isSatisfied(state("R y")))
        assertEquals(progress(20), turnR.progress(state("R y")))
    }

    @Test
    fun `the wrong face is not satisfied`() {
        assertFalse(turnR.isSatisfied(state("L")))
        assertEquals(progress(4), turnR.progress(state("L")))
    }

    @Test
    fun `any equivalent sequence counts`() {
        assertTrue(turnR.isSatisfied(state("R' R2")))
        assertTrue(turnR.isSatisfied(state("R U U'")))
        assertTrue(StateReached("L").isSatisfied(state("r")), "r = L x")
        assertTrue(StateReached("R U").isSatisfied(state("R U")))
        assertFalse(StateReached("R U").isSatisfied(state("U R")))
    }

    @Test
    fun `preserved pieces are the matching slots, named by their colors`() {
        val preserved = turnR.preservedPieces(CubeState.SOLVED)
        assertEquals(12, preserved.size)
        assertTrue(Piece(setOf(Color.WHITE, Color.GREEN, Color.ORANGE)) in preserved)
        assertFalse(Piece(setOf(Color.WHITE, Color.GREEN, Color.RED)) in preserved)
        assertFalse(Piece(setOf(Color.WHITE, Color.RED)) in preserved)
    }

    @Test
    fun `the helping Move raises progress and a harmful Move lowers it`() {
        val fixture = CubeState.SOLVED
        val helping = fixture.apply(moves("R"))
        assertTrue(turnR.progress(helping) > turnR.progress(fixture))
        assertTrue(turnR.preservedPieces(helping).containsAll(turnR.preservedPieces(fixture)))
        val harming = fixture.apply(moves("L"))
        assertTrue(turnR.progress(harming) < turnR.progress(fixture))
        assertTrue((turnR.preservedPieces(fixture) - turnR.preservedPieces(harming)).isNotEmpty())
    }

    @Test
    fun `white is found by its center, wherever it is`() {
        // x2 keeps Learner R on the red face; WHITE is now on Learner D.
        val flipped = state("x2")
        assertEquals(Color.WHITE, flipped.toFacelets().colors[31])
        assertTrue(turnR.isSatisfied(flipped.apply(moves("R"))))
        assertFalse(turnR.isSatisfied(flipped.apply(moves("L"))))
    }

    @Test
    fun `invalid Notation is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { StateReached("Q") }
        assertTrue(StateReached("").isSatisfied(CubeState.SOLVED))
    }

    @Test
    fun `every fixture gives the same result in all 24 orientations`() {
        fixtures.forEach { (label, s) -> assertSameInEveryOrientation(turnR, label, s) }
    }

    @OptIn(ExperimentalKotest::class)
    @Test
    fun `random face-turn sequences give the same result in all 24 orientations`() =
        runBlocking<Unit> {
            val target = StateReached("R U R' U'")
            checkAll(PropTestConfig(seed = 2_203L, iterations = 200), Arb.list(Arb.element(faceTurns), 0..30)) { seq ->
                assertSameInEveryOrientation(target, Notation.format(seq), CubeState.SOLVED.apply(seq))
            }
        }
}
