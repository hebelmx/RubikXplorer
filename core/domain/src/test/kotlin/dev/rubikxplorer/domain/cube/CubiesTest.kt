package dev.rubikxplorer.domain.cube

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CubiesTest {
    private val solved = Cubies.SOLVED

    @Test
    fun `solved has every cubie home`() {
        assertEquals((0..7).toList(), solved.cornerPermutation)
        assertEquals(List(8) { 0 }, solved.cornerOrientation)
        assertEquals((0..11).toList(), solved.edgePermutation)
        assertEquals(List(12) { 0 }, solved.edgeOrientation)
    }

    @Test
    fun `wrong sizes are rejected`() {
        assertThrows<IllegalArgumentException> { solved.copy(cornerPermutation = (0..6).toList()) }
        assertThrows<IllegalArgumentException> { solved.copy(cornerOrientation = List(7) { 0 }) }
        assertThrows<IllegalArgumentException> { solved.copy(edgePermutation = (0..12).toList()) }
        assertThrows<IllegalArgumentException> { solved.copy(edgeOrientation = List(11) { 0 }) }
    }

    @Test
    fun `out-of-range values are rejected`() {
        assertThrows<IllegalArgumentException> { solved.copy(cornerOrientation = listOf(3, 0, 0, 0, 0, 0, 0, 0)) }
        assertThrows<IllegalArgumentException> { solved.copy(cornerOrientation = listOf(-1, 0, 0, 0, 0, 0, 0, 0)) }
        assertThrows<IllegalArgumentException> { solved.copy(edgeOrientation = List(11) { 0 } + 2) }
        assertThrows<IllegalArgumentException> { solved.copy(edgeOrientation = List(11) { 0 } + -1) }
    }

    @Test
    fun `non-permutations are rejected`() {
        assertThrows<IllegalArgumentException> { solved.copy(cornerPermutation = listOf(0, 0, 2, 3, 4, 5, 6, 7)) }
        assertThrows<IllegalArgumentException> { solved.copy(edgePermutation = (1..12).toList()) }
        assertThrows<IllegalArgumentException> { solved.copy(edgePermutation = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 10)) }
    }

    private fun afterClockwise(face: Face) = CubeState.SOLVED.apply(FaceTurn(face, Turn.CLOCKWISE)).cubies

    @Test
    fun `R clockwise has the exact real-cube effect`() {
        // Seen from the right, DFR rises into URF with its D sticker on F (twist 2); FR edge rises into UR.
        assertEquals(
            Cubies(
                listOf(4, 1, 2, 0, 7, 5, 6, 3),
                listOf(2, 0, 0, 1, 1, 0, 0, 2),
                listOf(8, 1, 2, 3, 11, 5, 6, 7, 4, 9, 10, 0),
                List(12) { 0 },
            ),
            afterClockwise(Face.R),
        )
    }

    @Test
    fun `U clockwise has the exact real-cube effect`() {
        // Seen from above, URF moves to UFL and UR to UF; U/D stickers stay up, so nothing twists or flips.
        assertEquals(
            Cubies(
                listOf(3, 0, 1, 2, 4, 5, 6, 7),
                List(8) { 0 },
                listOf(3, 0, 1, 2, 4, 5, 6, 7, 8, 9, 10, 11),
                List(12) { 0 },
            ),
            afterClockwise(Face.U),
        )
    }

    @Test
    fun `F clockwise has the exact real-cube effect`() {
        // Seen from the front, UFL moves to URF with its U sticker on R (twist 1), and all four F edges flip.
        assertEquals(
            Cubies(
                listOf(1, 5, 2, 3, 0, 4, 6, 7),
                listOf(1, 2, 0, 0, 2, 1, 0, 0),
                listOf(0, 9, 2, 3, 4, 8, 6, 7, 1, 5, 10, 11),
                listOf(0, 1, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0),
            ),
            afterClockwise(Face.F),
        )
    }

    @Test
    fun `D clockwise has the exact real-cube effect`() {
        // Seen from below, DLF moves to DFR and DF to DR; U/D stickers stay down, so nothing twists or flips.
        assertEquals(
            Cubies(
                listOf(0, 1, 2, 3, 5, 6, 7, 4),
                List(8) { 0 },
                listOf(0, 1, 2, 3, 5, 6, 7, 4, 8, 9, 10, 11),
                List(12) { 0 },
            ),
            afterClockwise(Face.D),
        )
    }

    @Test
    fun `L clockwise has the exact real-cube effect`() {
        // Seen from the left, ULB comes down into UFL with its U sticker on F (twist 1) and BL rises into UL;
        // L turns keep each edge's U/D or F/B reference sticker on a reference facet, so nothing flips.
        assertEquals(
            Cubies(
                listOf(0, 2, 6, 3, 4, 1, 5, 7),
                listOf(0, 1, 2, 0, 0, 2, 1, 0),
                listOf(0, 1, 10, 3, 4, 5, 9, 7, 8, 2, 6, 11),
                List(12) { 0 },
            ),
            afterClockwise(Face.L),
        )
    }

    @Test
    fun `B clockwise has the exact real-cube effect`() {
        // Seen from behind, UBR moves to ULB with its U sticker on L (twist 1), and all four B edges flip.
        assertEquals(
            Cubies(
                listOf(0, 1, 3, 7, 4, 5, 2, 6),
                listOf(0, 0, 1, 2, 0, 0, 2, 1),
                listOf(0, 1, 2, 11, 4, 5, 6, 10, 8, 9, 3, 7),
                listOf(0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 1),
            ),
            afterClockwise(Face.B),
        )
    }

    @Test
    fun `no legality check at construction`() {
        // A single twisted corner is impossible on a real cube but is CubeValidator's concern, not ours.
        val twisted = solved.copy(cornerOrientation = listOf(1, 0, 0, 0, 0, 0, 0, 0))
        assertEquals(1, twisted.cornerOrientation.sum())
    }
}
