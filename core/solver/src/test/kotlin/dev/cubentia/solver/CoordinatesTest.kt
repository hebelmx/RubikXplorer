package dev.cubentia.solver

import dev.cubentia.domain.cube.Axis
import dev.cubentia.domain.cube.CubeRotation
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.cube.ValidationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Clean-room checks of the coordinate definitions at https://kociemba.org/math/twophase.htm. */
class CoordinatesTest {
    @Test
    fun `every coordinate round trips and transitions match direct cubie composition`() {
        for (coordinate in Coordinate.entries) {
            val moves = MoveTables.moves(coordinate)
            for (value in 0 until coordinate.size) {
                val representative = coordinate.decode(value)
                assertEquals(value, coordinate.encode(representative), "$coordinate $value")
                val domain = legalState(coordinate, representative)
                assertEquals(value, coordinate.encode(domain.cubies))
                for (move in moves) {
                    assertEquals(
                        coordinate.encode(domain.apply(move).cubies),
                        MoveTables.next(coordinate, value, move),
                        "$coordinate $value $move",
                    )
                    assertEquals(value, MoveTables.next(coordinate, MoveTables.next(coordinate, value, move), move.inverse()))
                }
            }
        }
    }

    @Test
    fun `legal seeded cube walks agree with public domain application`() {
        val random = Random(3101)
        for (phase in listOf(MoveTables.phase1Moves, MoveTables.phase2Moves)) {
            var state = CubeState.SOLVED
            val coordinates = if (phase == MoveTables.phase1Moves) Coordinate.entries.take(3) else Coordinate.entries.drop(3)
            repeat(1000) {
                val move = phase[random.nextInt(phase.size)]
                val before = state
                state = state.apply(move)
                for (coordinate in coordinates) {
                    assertEquals(coordinate.encode(state.cubies), MoveTables.next(coordinate, coordinate.encode(before.cubies), move))
                }
            }
        }
    }

    @Test
    fun `identity coordinates and rotations are zero while face turns can change them`() {
        for (coordinate in Coordinate.entries) {
            assertEquals(0, coordinate.encode(CubeState.SOLVED.cubies))
            for (axis in Axis.entries) {
                for (turn in Turn.entries) {
                    assertEquals(0, coordinate.encode(CubeState.SOLVED.apply(CubeRotation(axis, turn)).cubies))
                }
            }
        }
        assertEquals(1494, Coordinate.TWIST.encode(CubeState.SOLVED.apply(FaceTurn(Face.R, Turn.CLOCKWISE)).cubies))
        assertEquals(21021, Coordinate.CORNER_PERMUTATION.encode(CubeState.SOLVED.apply(FaceTurn(Face.R, Turn.CLOCKWISE)).cubies))
    }

    @Test
    fun `invalid coordinate and phase inputs are programmer errors`() {
        for (coordinate in Coordinate.entries) {
            assertThrows(IllegalArgumentException::class.java) { coordinate.decode(-1) }
            assertThrows(IllegalArgumentException::class.java) { coordinate.decode(coordinate.size) }
            assertThrows(IllegalArgumentException::class.java) { MoveTables.next(coordinate, -1, MoveTables.moves(coordinate).first()) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            MoveTables.next(Coordinate.EDGE_PERMUTATION, 0, FaceTurn(Face.R, Turn.CLOCKWISE))
        }
    }

    // Test-only sticker assembly uses the documented URFDLB net and validates every representative.
    // Repair parity in a permutation ignored by the coordinate under test.
    private fun legalState(
        coordinate: Coordinate,
        representative: Representative,
    ): CubeState {
        var c = representative

        fun parity(p: List<Int>): Int = p.indices.sumOf { i -> (0 until i).count { p[it] > p[i] } } % 2
        if (parity(c.cp) != parity(c.ep)) {
            fun swap(p: List<Int>): List<Int> =
                p.toMutableList().also {
                    val first = it[0]
                    it[0] = it[1]
                    it[1] = first
                }
            c = if (coordinate == Coordinate.CORNER_PERMUTATION) c.copy(ep = swap(c.ep)) else c.copy(cp = swap(c.cp))
        }
        val corners =
            listOf(
                listOf(8, 9, 20),
                listOf(6, 18, 38),
                listOf(0, 36, 47),
                listOf(2, 45, 11),
                listOf(29, 26, 15),
                listOf(27, 44, 24),
                listOf(33, 53, 42),
                listOf(35, 17, 51),
            )
        val edges =
            listOf(
                listOf(5, 10),
                listOf(7, 19),
                listOf(3, 37),
                listOf(1, 46),
                listOf(32, 16),
                listOf(28, 25),
                listOf(30, 43),
                listOf(34, 52),
                listOf(23, 12),
                listOf(21, 41),
                listOf(50, 39),
                listOf(48, 14),
            )
        val home = CubeState.SOLVED.toFacelets().colors
        val colors = home.toMutableList()
        for (slot in corners.indices) {
            for (facet in 0..2) {
                colors[corners[slot][facet]] = home[corners[c.cp[slot]][(facet - c.co[slot] + 3) % 3]]
            }
        }
        for (slot in edges.indices) {
            for (facet in 0..1) {
                colors[edges[slot][facet]] = home[edges[c.ep[slot]][(facet + c.eo[slot]) % 2]]
            }
        }
        return (CubeValidator.validate(Facelets(colors)) as ValidationResult.Valid).state
    }
}
