package dev.rubikxplorer.domain.cube

import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.element
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class MoveTypesTest {
    @OptIn(ExperimentalKotest::class)
    private fun config(seed: Long) = PropTestConfig(seed = seed, iterations = 300)

    private val rotations = allMoves.filterIsInstance<CubeRotation>()

    private fun rotation(axis: Axis) = CubeRotation(axis, Turn.CLOCKWISE)

    private fun moves(text: String): List<Move> = (Notation.parse(text) as NotationResult.Parsed).moves

    /** Each wide and slice token with every turn, against its equivalent from the spec. */
    private val equivalents: List<Pair<String, String>> =
        listOf(
            "r" to "L x",
            "l" to "R x'",
            "u" to "D y",
            "d" to "U y'",
            "f" to "B z",
            "b" to "F z'",
            "M" to "R L' x'",
            "E" to "U D' y'",
            "S" to "F' B z",
        ).flatMap { (m, parts) ->
            val prime = parts.split(" ").joinToString(" ") { if (it.endsWith("'")) it.dropLast(1) else "$it'" }
            val half = parts.split(" ").joinToString(" ") { it.removeSuffix("'") + "2" }
            listOf(m to parts, "$m'" to prime, "${m}2" to half)
        }

    @Test
    fun `a Cube Rotation changes only orientation`() =
        runBlocking<Unit> {
            checkAll(config(seed = 1_313L), arbAnyState, Arb.element(rotations)) { s, m ->
                val after = s.apply(m)
                assertEquals(s.cubies, after.cubies)
                assertNotEquals(s.orientation, after.orientation)
                assertEquals(s.isSolved, after.isSolved)
            }
        }

    @Test
    fun `rotations turn the way their named face does`() {
        assertEquals(
            Color.GREEN,
            CubeState.SOLVED
                .apply(moves("x"))
                .orientation
                .colorAt(Face.U),
        )
        assertEquals(
            Color.RED,
            CubeState.SOLVED
                .apply(moves("y"))
                .orientation
                .colorAt(Face.F),
        )
        assertEquals(
            Color.ORANGE,
            CubeState.SOLVED
                .apply(moves("z"))
                .orientation
                .colorAt(Face.U),
        )
        assertEquals(Orientation.of(Face.B, Face.U), CubeState.SOLVED.apply(moves("x'")).orientation)
        assertEquals(Orientation.of(Face.D, Face.B), CubeState.SOLVED.apply(moves("x2")).orientation)
    }

    @Test
    fun `rotations act from non-home orientations`() {
        val expected =
            mapOf(
                Orientation.of(Face.U, Face.R) to
                    listOf(Orientation.of(Face.R, Face.D), Orientation.of(Face.U, Face.B), Orientation.of(Face.F, Face.R)),
                Orientation.of(Face.F, Face.D) to
                    listOf(Orientation.of(Face.D, Face.B), Orientation.of(Face.F, Face.R), Orientation.of(Face.L, Face.D)),
            )
        expected.forEach { (start, results) ->
            Axis.entries.zip(results).forEach { (axis, result) ->
                val after = CubeState(Cubies.SOLVED, start).apply(rotation(axis))
                assertEquals(result, after.orientation, "$start $axis")
                assertEquals(Cubies.SOLVED, after.cubies, "$start $axis")
            }
        }
    }

    @Test
    fun `half turn equals two quarter turns for every new Move type`() =
        runBlocking<Unit> {
            checkAll(config(seed = 7_313L), arbAnyState) { s ->
                val pairs: List<Pair<Move, Move>> =
                    Face.entries.map { WideTurn(it, Turn.CLOCKWISE) to WideTurn(it, Turn.HALF) } +
                        Slice.entries.map { SliceTurn(it, Turn.CLOCKWISE) to SliceTurn(it, Turn.HALF) } +
                        Axis.entries.map { CubeRotation(it, Turn.CLOCKWISE) to CubeRotation(it, Turn.HALF) }
                pairs.forEach { (q, half) ->
                    assertEquals(s.apply(q, q), s.apply(half), "$q")
                }
            }
        }

    @Test
    fun `every wide and slice Move equals its parts`() =
        runBlocking<Unit> {
            assertEquals(27, equivalents.size)
            checkAll(config(seed = 2_313L), arbAnyState) { s ->
                equivalents.forEach { (m, parts) ->
                    assertEquals(s.apply(moves(parts)), s.apply(moves(m)), "$m = $parts")
                }
            }
        }

    @Test
    fun `wide equals face plus adjacent slice`() =
        runBlocking<Unit> {
            checkAll(config(seed = 3_313L), arbAnyState) { s ->
                assertEquals(s.apply(moves("R M'")), s.apply(moves("r")))
                assertEquals(s.apply(moves("L M")), s.apply(moves("l")))
                assertEquals(s.apply(moves("U E'")), s.apply(moves("u")))
                assertEquals(s.apply(moves("D E")), s.apply(moves("d")))
                assertEquals(s.apply(moves("F S")), s.apply(moves("f")))
                assertEquals(s.apply(moves("B S'")), s.apply(moves("b")))
            }
        }

    @Test
    fun `any Move four times is identity for every Move type`() =
        runBlocking<Unit> {
            checkAll(config(seed = 4_313L), arbAnyState, arbAnyMove) { s, m ->
                assertEquals(s, s.apply(m, m, m, m))
            }
        }

    @Test
    fun `move then inverse is identity for every Move type`() =
        runBlocking<Unit> {
            checkAll(config(seed = 5_313L), arbAnyState, arbAnyMove) { s, m ->
                assertEquals(s, s.apply(m).apply(m.inverse()))
            }
        }

    @Test
    fun `sequence then inverse is identity for every Move type`() =
        runBlocking<Unit> {
            checkAll(config(seed = 6_313L), arbAnyState, arbAnySequence) { s, seq ->
                assertEquals(s, s.apply(seq).apply(seq.inverse()))
            }
        }

    @Test
    fun `inverse of each new type keeps its type and flips the turn`() {
        Face.entries.forEach { f ->
            assertEquals(WideTurn(f, Turn.COUNTERCLOCKWISE), WideTurn(f, Turn.CLOCKWISE).inverse())
            assertEquals(WideTurn(f, Turn.HALF), WideTurn(f, Turn.HALF).inverse())
        }
        Slice.entries.forEach { sl ->
            assertEquals(SliceTurn(sl, Turn.CLOCKWISE), SliceTurn(sl, Turn.COUNTERCLOCKWISE).inverse())
            assertEquals(SliceTurn(sl, Turn.HALF), SliceTurn(sl, Turn.HALF).inverse())
        }
        Axis.entries.forEach { a ->
            assertEquals(CubeRotation(a, Turn.COUNTERCLOCKWISE), CubeRotation(a, Turn.CLOCKWISE).inverse())
            assertEquals(CubeRotation(a, Turn.HALF), CubeRotation(a, Turn.HALF).inverse())
        }
    }

    @Test
    fun `every wide and slice Move unsolves a solved cube`() {
        allMoves.filter { it is SliceTurn || it is WideTurn }.forEach { assertFalse(CubeState.SOLVED.apply(it).isSolved, "$it") }
    }
}
