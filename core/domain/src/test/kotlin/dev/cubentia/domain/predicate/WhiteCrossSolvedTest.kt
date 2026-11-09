package dev.cubentia.domain.predicate

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.cube.Turn
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

/** AC 1–2: [WhiteCrossSolved] is color-anchored, orientation-invariant and tells helping from harmful Moves. */
class WhiteCrossSolvedTest {
    private val p = WhiteCrossSolved

    private val faceTurns: List<FaceTurn> = Turn.entries.flatMap { t -> Face.entries.map { FaceTurn(it, t) } }

    private fun moves(text: String): List<Move> = (Notation.parse(text) as NotationResult.Parsed).moves

    private fun progress(
        done: Int,
        total: Int = 4,
    ) = ObjectiveProgress(done, total)

    /** A fixed 25-Move Scramble, kept as explicit Moves. */
    private val scramble = moves("D2 B' R2 U F' L D' B2 R' U2 F L2 D B' R U' L' F2 D' R2 B U L2 F' D")

    private val crossOnly = CubeState.SOLVED.apply(moves("R' D R"))

    private val fixtures: Map<String, CubeState> =
        mapOf(
            "solved" to CubeState.SOLVED,
            "scrambled" to CubeState.SOLVED.apply(scramble),
            "partly solved (F)" to CubeState.SOLVED.apply(moves("F")),
            "cross only" to crossOnly,
            "misaligned (U)" to CubeState.SOLVED.apply(moves("U")),
            "one from the cross" to crossOnly.apply(moves("F")),
        )

    private fun assertSameInEveryOrientation(
        label: String,
        state: CubeState,
    ) {
        val satisfied = p.isSatisfied(state)
        val progress = p.progress(state)
        val preserved = p.preservedPieces(state)
        assertEquals(preserved.size, progress.done, label)
        assertEquals(satisfied, progress.done == 4, label)
        Orientation.ALL.forEach { o ->
            val rotated = state.copy(orientation = o)
            assertEquals(satisfied, p.isSatisfied(rotated), "$label at $o")
            assertEquals(progress, p.progress(rotated), "$label at $o")
            assertEquals(preserved, p.preservedPieces(rotated), "$label at $o")
        }
    }

    @Test
    fun `solved is satisfied with all four cross edges preserved`() {
        assertTrue(p.isSatisfied(CubeState.SOLVED))
        assertEquals(progress(4), p.progress(CubeState.SOLVED))
        assertEquals(
            setOf(Color.GREEN, Color.RED, Color.BLUE, Color.ORANGE).map { Piece(setOf(Color.WHITE, it)) }.toSet(),
            p.preservedPieces(CubeState.SOLVED),
        )
    }

    @Test
    fun `F breaks only the white-green edge`() {
        val state = CubeState.SOLVED.apply(moves("F"))
        assertFalse(p.isSatisfied(state))
        assertEquals(progress(3), p.progress(state))
        assertFalse(Piece(setOf(Color.WHITE, Color.GREEN)) in p.preservedPieces(state))
        assertEquals(3, p.preservedPieces(state).size)
    }

    @Test
    fun `a cross with the rest scrambled is satisfied though not solved`() {
        assertFalse(crossOnly.isSolved)
        assertTrue(p.isSatisfied(crossOnly))
        assertEquals(progress(4), p.progress(crossOnly))
    }

    @Test
    fun `a white top with misaligned sides is not the cross`() {
        val state = CubeState.SOLVED.apply(moves("U"))
        assertFalse(p.isSatisfied(state))
        assertEquals(progress(0), p.progress(state))
        assertTrue(p.preservedPieces(state).isEmpty())
    }

    @Test
    fun `the helping Move raises progress and a harmful Move does not`() {
        val fixture = crossOnly.apply(moves("F"))
        assertEquals(progress(3), p.progress(fixture))
        val helping = fixture.apply(moves("F'"))
        val helped = p.progress(helping)
        assertEquals(progress(4), helped)
        assertTrue(helped > p.progress(fixture))
        assertTrue(p.preservedPieces(helping).containsAll(p.preservedPieces(fixture)))
        val harming = fixture.apply(moves("R"))
        val harmed = p.progress(harming)
        assertTrue(harmed < p.progress(fixture), "R gave $harmed")
        assertTrue((p.preservedPieces(fixture) - p.preservedPieces(harming)).isNotEmpty())
    }

    @Test
    fun `the scramble fixture is a full-length Scramble`() {
        assertEquals(25, scramble.size)
    }

    @Test
    fun `every fixture gives the same result in all 24 orientations`() {
        fixtures.forEach { (label, state) -> assertSameInEveryOrientation(label, state) }
    }

    @Test
    fun `white is found by its center, wherever it is`() {
        // x2 puts WHITE on Learner D; the cross is still solved.
        val flipped = CubeState.SOLVED.apply(moves("x2"))
        assertEquals(Color.WHITE, flipped.toFacelets().colors[31])
        assertTrue(p.isSatisfied(flipped))
        // A face turn on the Learner face now holding WHITE breaks the alignment.
        assertEquals(progress(0), p.progress(flipped.apply(moves("D"))))
        assertEquals(progress(4), p.progress(flipped.apply(moves("U"))))
    }

    @Test
    fun `every face turn that breaks the cross is undone by its inverse`() {
        val breaking = faceTurns.filter { !p.isSatisfied(CubeState.SOLVED.apply(it)) }
        assertEquals(15, breaking.size, "all but the 3 D turns")
        breaking.forEach { m ->
            assertTrue(p.progress(CubeState.SOLVED.apply(m, m.inverse())) > p.progress(CubeState.SOLVED.apply(m)), "$m")
        }
    }

    @OptIn(ExperimentalKotest::class)
    @Test
    fun `random face-turn sequences give the same result in all 24 orientations`() =
        runBlocking<Unit> {
            checkAll(PropTestConfig(seed = 1_707L, iterations = 200), Arb.list(Arb.element(faceTurns), 0..30)) { seq ->
                assertSameInEveryOrientation(Notation.format(seq), CubeState.SOLVED.apply(seq))
            }
        }
}
