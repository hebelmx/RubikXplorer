package dev.rubikxplorer.domain.predicate

import dev.rubikxplorer.domain.cube.Color
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.predicate.Fixtures.assertSameInEveryOrientation
import dev.rubikxplorer.domain.predicate.Fixtures.faceTurns
import dev.rubikxplorer.domain.predicate.Fixtures.moves
import dev.rubikxplorer.domain.predicate.Fixtures.scramble
import dev.rubikxplorer.domain.predicate.Fixtures.state
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

/** AC 1: [AllOf] and [AnyOf] combine children's judgments, progress and preserved pieces. */
class CombinatorsTest {
    private val stage3 = AllOf(listOf(WhiteCrossSolved, WhiteCornersSolved))
    private val eitherTurn = AnyOf(listOf(StateReached("R"), StateReached("U")))
    private val mixed = AnyOf(listOf(WhiteCrossSolved, StateReached("R")))

    private val fixtures: Map<String, CubeState> =
        mapOf(
            "solved" to CubeState.SOLVED,
            "scrambled" to CubeState.SOLVED.apply(scramble),
            "F" to state("F"),
            "U" to state("U"),
            "R U R'" to state("R U R'"),
            "white on D (x2)" to state("x2"),
        )

    @Test
    fun `the Stage 3 composite is 8 of 8 on solved`() {
        assertTrue(stage3.isSatisfied(CubeState.SOLVED))
        assertEquals(ObjectiveProgress(8, 8), stage3.progress(CubeState.SOLVED))
        assertEquals(8, stage3.preservedPieces(CubeState.SOLVED).size)
    }

    @Test
    fun `a Move that breaks the cross lowers progress and drops the cross edge while other corners stay`() {
        val broken = state("F")
        assertFalse(stage3.isSatisfied(broken))
        assertEquals(ObjectiveProgress(5, 8), stage3.progress(broken))
        val preserved = stage3.preservedPieces(broken)
        assertFalse(Piece(setOf(Color.WHITE, Color.GREEN)) in preserved)
        assertFalse(Piece(setOf(Color.WHITE, Color.GREEN, Color.RED)) in preserved)
        assertTrue(Piece(setOf(Color.WHITE, Color.BLUE, Color.RED)) in preserved)
        assertTrue(Piece(setOf(Color.WHITE, Color.BLUE, Color.ORANGE)) in preserved)
    }

    @Test
    fun `an edge cycle breaks the cross and drops its edges while every corner stays preserved`() {
        // A U-layer edge 3-cycle (U perm): the white corners never leave home.
        val cycled = state("R U' R U R U R U' R' U' R2")
        assertTrue(WhiteCornersSolved.isSatisfied(cycled))
        assertEquals(ObjectiveProgress(1, 4), WhiteCrossSolved.progress(cycled))
        assertFalse(stage3.isSatisfied(cycled))
        assertEquals(ObjectiveProgress(5, 8), stage3.progress(cycled))
        val preserved = stage3.preservedPieces(cycled)
        assertTrue(preserved.containsAll(WhiteCornersSolved.preservedPieces(CubeState.SOLVED)))
        assertEquals(3, (WhiteCrossSolved.preservedPieces(CubeState.SOLVED) - preserved).size)
    }

    @Test
    fun `the helping Move raises the composite and a harmful one lowers it`() {
        val fixture = state("F")
        val helping = fixture.apply(moves("F'"))
        assertTrue(stage3.progress(helping) > stage3.progress(fixture))
        assertTrue(stage3.preservedPieces(helping).containsAll(stage3.preservedPieces(fixture)))
        val harming = fixture.apply(moves("R"))
        assertTrue(stage3.progress(harming) < stage3.progress(fixture))
    }

    @Test
    fun `anyOf is satisfied by either child and takes the best child's progress`() {
        assertTrue(eitherTurn.isSatisfied(state("U")))
        assertEquals(ObjectiveProgress(20, 20), eitherTurn.progress(state("U")))
        assertEquals(StateReached("U").preservedPieces(state("U")), eitherTurn.preservedPieces(state("U")))
        assertFalse(eitherTurn.isSatisfied(CubeState.SOLVED))
        assertEquals(ObjectiveProgress(12, 20), eitherTurn.progress(CubeState.SOLVED))
        assertEquals(StateReached("R").preservedPieces(CubeState.SOLVED), eitherTurn.preservedPieces(CubeState.SOLVED))
    }

    @Test
    fun `anyOf scales children to the LCM of their totals`() {
        // Cross 3/4 scales to 15/20; state-reached R is 7/20 after F.
        val s = state("F")
        assertEquals(ObjectiveProgress(7, 20), StateReached("R").progress(s))
        assertEquals(ObjectiveProgress(15, 20), mixed.progress(s))
        assertEquals(WhiteCrossSolved.preservedPieces(s), mixed.preservedPieces(s))
        // Totals 4 and 8 share LCM 8: cross 3/4 is 6/8, the composite 5/8.
        assertEquals(ObjectiveProgress(6, 8), AnyOf(listOf(WhiteCrossSolved, stage3)).progress(s))
        assertTrue(mixed.progress(s.apply(moves("F'"))) > mixed.progress(s))
        assertTrue(mixed.progress(s.apply(moves("L"))) < mixed.progress(s))
    }

    @Test
    fun `combinators need at least one child`() {
        assertThrows(IllegalArgumentException::class.java) { AllOf(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { AnyOf(emptyList()) }
    }

    @Test
    fun `every fixture gives the same result in all 24 orientations`() {
        fixtures.forEach { (label, s) ->
            assertSameInEveryOrientation(stage3, "allOf $label", s)
            assertSameInEveryOrientation(eitherTurn, "anyOf $label", s, countsPieces = false)
            assertSameInEveryOrientation(mixed, "mixed anyOf $label", s, countsPieces = false)
        }
    }

    @OptIn(ExperimentalKotest::class)
    @Test
    fun `random face-turn sequences give the same result in all 24 orientations`() =
        runBlocking<Unit> {
            checkAll(PropTestConfig(seed = 2_204L, iterations = 200), Arb.list(Arb.element(faceTurns), 0..30)) { seq ->
                val s = CubeState.SOLVED.apply(seq)
                assertSameInEveryOrientation(stage3, Notation.format(seq), s)
                assertSameInEveryOrientation(mixed, Notation.format(seq), s, countsPieces = false)
            }
        }
}
