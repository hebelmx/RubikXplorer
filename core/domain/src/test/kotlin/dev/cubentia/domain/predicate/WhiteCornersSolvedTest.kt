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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** AC 1: [WhiteCornersSolved] is color-anchored, orientation-invariant and tells helping from harmful Moves. */
class WhiteCornersSolvedTest {
    private val p = WhiteCornersSolved

    private fun progress(done: Int) = ObjectiveProgress(done, 4)

    private fun corner(
        a: Color,
        b: Color,
    ) = Piece(setOf(Color.WHITE, a, b))

    private val fixtures: Map<String, CubeState> =
        mapOf(
            "solved" to CubeState.SOLVED,
            "scrambled" to CubeState.SOLVED.apply(scramble),
            "partly solved (R)" to state("R"),
            "corner popped (R U R')" to state("R U R'"),
            "misaligned (U)" to state("U"),
            "white on D (x2)" to state("x2"),
            "corners only (D-layer scramble)" to state("R' D R"),
        )

    @Test
    fun `solved is satisfied with all four white corners preserved`() {
        assertTrue(p.isSatisfied(CubeState.SOLVED))
        assertEquals(progress(4), p.progress(CubeState.SOLVED))
        assertEquals(
            setOf(
                corner(Color.GREEN, Color.RED),
                corner(Color.GREEN, Color.ORANGE),
                corner(Color.BLUE, Color.ORANGE),
                corner(Color.BLUE, Color.RED),
            ),
            p.preservedPieces(CubeState.SOLVED),
        )
    }

    @Test
    fun `a popped corner is not satisfied`() {
        val popped = state("R U R'")
        assertFalse(p.isSatisfied(popped))
        assertTrue(p.progress(popped) < progress(4))
    }

    @Test
    fun `R breaks the two right-hand corners only`() {
        val s = state("R")
        assertEquals(progress(2), p.progress(s))
        assertEquals(setOf(corner(Color.GREEN, Color.ORANGE), corner(Color.BLUE, Color.ORANGE)), p.preservedPieces(s))
    }

    @Test
    fun `a white top with misaligned sides has no corner`() {
        assertEquals(progress(0), p.progress(state("U")))
        assertTrue(p.preservedPieces(state("U")).isEmpty())
    }

    @Test
    fun `the helping Move raises progress and a harmful Move lowers it`() {
        val fixture = state("R")
        val helping = fixture.apply(moves("R'"))
        assertTrue(p.progress(helping) > p.progress(fixture))
        assertTrue(p.preservedPieces(helping).containsAll(p.preservedPieces(fixture)))
        val harming = fixture.apply(moves("L"))
        assertTrue(p.progress(harming) < p.progress(fixture), "L gave ${p.progress(harming)}")
        assertTrue((p.preservedPieces(fixture) - p.preservedPieces(harming)).isNotEmpty())
    }

    @Test
    fun `white is found by its center, wherever it is`() {
        val flipped = state("x2")
        assertEquals(Color.WHITE, flipped.toFacelets().colors[31])
        assertTrue(p.isSatisfied(flipped))
        assertEquals(progress(0), p.progress(flipped.apply(moves("D"))))
        assertEquals(progress(4), p.progress(flipped.apply(moves("U"))))
    }

    @Test
    fun `every face turn that breaks the corners is undone by its inverse`() {
        val breaking = faceTurns.filter { !p.isSatisfied(CubeState.SOLVED.apply(it)) }
        assertEquals(15, breaking.size, "all but the 3 D turns")
        breaking.forEach { m ->
            assertTrue(p.progress(CubeState.SOLVED.apply(m, m.inverse())) > p.progress(CubeState.SOLVED.apply(m)), "$m")
        }
    }

    @Test
    fun `every fixture gives the same result in all 24 orientations`() {
        fixtures.forEach { (label, s) -> assertSameInEveryOrientation(p, label, s) }
    }

    @OptIn(ExperimentalKotest::class)
    @Test
    fun `random face-turn sequences give the same result in all 24 orientations`() =
        runBlocking<Unit> {
            checkAll(PropTestConfig(seed = 2_202L, iterations = 200), Arb.list(Arb.element(faceTurns), 0..30)) { seq ->
                assertSameInEveryOrientation(p, Notation.format(seq), CubeState.SOLVED.apply(seq))
            }
        }
}
