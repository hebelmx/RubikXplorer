package dev.cubentia.domain.cube

import io.kotest.common.ExperimentalKotest
import io.kotest.property.PropTestConfig
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NotationTest {
    @OptIn(ExperimentalKotest::class)
    private fun config(seed: Long) = PropTestConfig(seed = seed, iterations = 500)

    private fun parsed(text: String): List<Move> {
        val result = Notation.parse(text)
        check(result is NotationResult.Parsed) { "$text -> $result" }
        return result.moves
    }

    private fun bad(
        token: String,
        index: Int,
    ) = NotationResult.Invalid(NotationError.BadToken(token, index))

    private val canonicalTokens: List<String> =
        listOf("U", "R", "F", "D", "L", "B", "u", "r", "f", "d", "l", "b", "M", "E", "S", "x", "y", "z")
            .flatMap { listOf(it, "$it'", "${it}2") }

    @Test
    fun `every canonical token parses to one Move and formats back`() {
        assertEquals(54, canonicalTokens.size)
        val moves = canonicalTokens.map { token -> parsed(token).single().also { assertEquals(token, Notation.format(it)) } }
        assertEquals(allMoves.toSet(), moves.toSet())
    }

    @Test
    fun `each letter maps to its Move type`() {
        assertEquals(listOf(FaceTurn(Face.R, Turn.CLOCKWISE)), parsed("R"))
        assertEquals(listOf(WideTurn(Face.R, Turn.COUNTERCLOCKWISE)), parsed("r'"))
        assertEquals(listOf(SliceTurn(Slice.E, Turn.HALF)), parsed("E2"))
        assertEquals(listOf(CubeRotation(Axis.Z, Turn.CLOCKWISE)), parsed("z"))
    }

    @Test
    fun `sequences split on any whitespace`() {
        val r = FaceTurn(Face.R, Turn.CLOCKWISE)
        val u = FaceTurn(Face.U, Turn.CLOCKWISE)
        assertEquals(listOf(r, u, r.inverse(), u.inverse()), parsed("R U R' U'"))
        assertEquals(
            listOf(SliceTurn(Slice.M, Turn.HALF), WideTurn(Face.R, Turn.CLOCKWISE), CubeRotation(Axis.Y, Turn.COUNTERCLOCKWISE)),
            parsed("  M2   r  y' "),
        )
        assertEquals(listOf(r, u), parsed("\tR\n U\r\n"))
        assertEquals(listOf(r, u), parsed("R\u00A0U"))
        assertEquals(listOf(r, u), parsed("R\u2003U"))
    }

    @Test
    fun `half turn primes are the same Move and format canonically`() {
        assertEquals(parsed("R2"), parsed("R2'"))
        assertEquals(parsed("x2"), parsed("x2'"))
        assertEquals(parsed("M2"), parsed("M2'"))
        assertEquals("R2", Notation.format(parsed("R2'")))
        assertEquals("x2", Notation.format(parsed("x2'")))
    }

    @Test
    fun `empty and blank text are no Moves`() {
        assertEquals(emptyList<Move>(), parsed(""))
        assertEquals(emptyList<Move>(), parsed("   "))
        assertEquals("", Notation.format(emptyList()))
    }

    @Test
    fun `bad tokens are returned with their text and index`() {
        assertEquals(bad("U3", 1), Notation.parse("R U3 F"))
        assertEquals(bad("Rw", 0), Notation.parse("Rw"))
        assertEquals(bad("R'2", 0), Notation.parse("R'2"))
        assertEquals(bad("X", 0), Notation.parse("X"))
        assertEquals(bad("RU", 0), Notation.parse("RU"))
        listOf("Q", "R3", "R''", "(R", "R)", "2", "'", "m", "Y").forEach { assertEquals(bad(it, 0), Notation.parse(it), it) }
    }

    @Test
    fun `the first bad token wins`() {
        assertEquals(bad("Q", 2), Notation.parse("  R U Q R3 "))
    }

    @Test
    fun `a sequence formats as tokens joined by one space`() {
        assertEquals("R U' M2 r x'", Notation.format(parsed("R   U'  M2' r x'")))
    }

    @Test
    fun `parse of format is the identity`() =
        runBlocking<Unit> {
            checkAll(config(seed = 1_303L), arbAnySequence) { seq ->
                assertEquals(NotationResult.Parsed(seq), Notation.parse(Notation.format(seq)))
            }
        }
}
