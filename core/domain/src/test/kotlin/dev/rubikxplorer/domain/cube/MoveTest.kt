package dev.rubikxplorer.domain.cube

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MoveTest {
    @Test
    fun `inverse of clockwise is counterclockwise and back`() {
        Face.entries.forEach { face ->
            assertEquals(FaceTurn(face, Turn.COUNTERCLOCKWISE), FaceTurn(face, Turn.CLOCKWISE).inverse())
            assertEquals(FaceTurn(face, Turn.CLOCKWISE), FaceTurn(face, Turn.COUNTERCLOCKWISE).inverse())
        }
    }

    @Test
    fun `inverse of half is itself`() {
        Face.entries.forEach { assertEquals(FaceTurn(it, Turn.HALF), FaceTurn(it, Turn.HALF).inverse()) }
    }

    @Test
    fun `sequence inverse is reversed with each Move inverted`() {
        val seq: List<Move> = listOf(FaceTurn(Face.R, Turn.CLOCKWISE), FaceTurn(Face.U, Turn.HALF), FaceTurn(Face.F, Turn.COUNTERCLOCKWISE))
        assertEquals(
            listOf(FaceTurn(Face.F, Turn.CLOCKWISE), FaceTurn(Face.U, Turn.HALF), FaceTurn(Face.R, Turn.COUNTERCLOCKWISE)),
            seq.inverse(),
        )
        assertEquals(emptyList<Move>(), emptyList<Move>().inverse())
    }

    @Test
    fun `the face, color and turn sets are closed and in order`() {
        assertEquals(listOf("U", "R", "F", "D", "L", "B"), Face.entries.map { it.name })
        assertEquals(listOf("WHITE", "YELLOW", "GREEN", "BLUE", "RED", "ORANGE"), Color.entries.map { it.name })
        assertEquals(listOf("CLOCKWISE", "HALF", "COUNTERCLOCKWISE"), Turn.entries.map { it.name })
    }

    @Test
    fun `opposite faces pair up`() {
        assertEquals(listOf(Face.D, Face.L, Face.B, Face.U, Face.R, Face.F), Face.entries.map { it.opposite })
    }
}
