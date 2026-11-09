package dev.cubentia.domain.predicate

import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.predicate.Fixtures.state
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Story 4.1: [WhitePieceSolved] names one white-layer piece, color-anchored and orientation-invariant. */
class WhitePieceSolvedTest {
    private val edge = WhitePieceSolved("white-green")
    private val corner = WhitePieceSolved("white-green-red")

    @Test
    fun `a solved cube has every white-layer piece in place`() {
        listOf(
            "white-green",
            "white-red",
            "white-blue",
            "white-orange",
            "white-green-red",
            "white-red-blue",
            "white-blue-orange",
            "white-orange-green",
        ).forEach { assertTrue(WhitePieceSolved(it).isSatisfied(CubeState.SOLVED), it) }
    }

    @Test
    fun `a Move that lifts the piece unsets it and only it`() {
        // F turns the white-green edge and both white-green corners out of place; white-red-blue stays.
        val f = state("F")
        assertFalse(edge.isSatisfied(f))
        assertFalse(corner.isSatisfied(f))
        assertTrue(WhitePieceSolved("white-red-blue").isSatisfied(f))
        assertTrue(WhitePieceSolved("white-blue").isSatisfied(f))
        // R lifts the white-red edge but not the white-green edge.
        assertTrue(edge.isSatisfied(state("R")))
        assertFalse(WhitePieceSolved("white-red").isSatisfied(state("R")))
    }

    @Test
    fun `a twisted corner in its slot is not solved`() {
        // (R' D' R D) twice returns the white-green-red corner to its slot, twisted.
        val twisted = state("R' D' R D R' D' R D")
        assertFalse(corner.isSatisfied(twisted))
        assertTrue(edge.isSatisfied(twisted))
    }

    @Test
    fun `progress, preserved pieces and orientation invariance`() {
        val piece = Piece(setOf(Color.WHITE, Color.GREEN, Color.RED))
        assertEquals(ObjectiveProgress(1, 1), corner.progress(CubeState.SOLVED))
        assertEquals(setOf(piece), corner.preservedPieces(CubeState.SOLVED))
        assertEquals(ObjectiveProgress(0, 1), corner.progress(state("F")))
        assertEquals(emptySet<Piece>(), corner.preservedPieces(state("F")))
        listOf("", "F", "R' D R", "R' D' R D R' D' R D").forEach { moves ->
            Fixtures.assertSameInEveryOrientation(corner, "corner $moves", state(moves))
            Fixtures.assertSameInEveryOrientation(edge, "edge $moves", state(moves))
        }
        Fixtures.assertSameInEveryOrientation(corner, "scrambled", CubeState.SOLVED.apply(Fixtures.scramble))
    }

    @Test
    fun `a name that is not one of the 8 canonical names is rejected like invalid state-reached moves`() {
        listOf(
            "green",
            "white",
            "white-yellow",
            "white-green-blue",
            "white-red-orange",
            "white-green-green",
            "white-Green",
            "white-red-green",
            "white-green-orange",
            "yellow-green",
            "white-green-red-blue",
            "",
        ).forEach { name ->
            val failure = assertThrows(IllegalArgumentException::class.java, { WhitePieceSolved(name) }, name)
            assertTrue("not a white-layer edge or corner" in failure.message.orEmpty(), failure.message)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContentLoader.json.decodeFromString<Predicate>("""{"predicate":"white-piece-solved","piece":"white-yellow"}""")
        }
    }
}
