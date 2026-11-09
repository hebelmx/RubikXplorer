package dev.cubentia.solver

import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Turn

/**
 * Clean-room coordinate transitions based on https://kociemba.org/math/twophase.htm and
 * https://kociemba.org/math/coordlevel.htm. Tables contain successor indices, not additive offsets.
 * Moves are home-frame face turns; callers resolve held orientation before looking up a move.
 * Whole-cube rotations leave all coordinates unchanged. Wide/slice moves use their domain
 * face-turn equivalent parts, resolving orientation between parts.
 */
internal object MoveTables {
    val phase1Moves: List<FaceTurn> = Face.entries.flatMap { face -> Turn.entries.map { FaceTurn(face, it) } }
    val phase2Moves: List<FaceTurn> =
        phase1Moves.filter {
            it.face == Face.U || it.face == Face.D || it.turn == Turn.HALF
        }

    // Independent tables; cancelled builds are never published.
    private val tables = mutableMapOf<Coordinate, IntArray>()

    @Synchronized
    internal fun transitions(
        coordinate: Coordinate,
        checkCancellation: () -> Unit = {},
    ): IntArray {
        checkCancellation()
        return tables.getOrPut(coordinate) {
            val moves = moves(coordinate)
            val transforms = moves.map { CubeState.SOLVED.apply(it).cubies }
            val result = IntArray(coordinate.size * moves.size)
            for (value in 0 until coordinate.size) {
                if (value % 256 == 0) checkCancellation()
                val representative = coordinate.decode(value)
                for (column in moves.indices) {
                    result[value * moves.size + column] = coordinate.encode(representative.apply(transforms[column]))
                }
            }
            result
        }
    }

    fun moves(coordinate: Coordinate): List<FaceTurn> =
        when (coordinate) {
            Coordinate.TWIST, Coordinate.FLIP, Coordinate.SLICE_POSITION -> phase1Moves
            Coordinate.CORNER_PERMUTATION, Coordinate.EDGE_PERMUTATION, Coordinate.SLICE_PERMUTATION -> phase2Moves
        }

    /** Invalid indices or a move outside the coordinate's phase are programmer errors. */
    fun next(
        coordinate: Coordinate,
        value: Int,
        move: FaceTurn,
    ): Int {
        require(value in 0 until coordinate.size)
        val moves = moves(coordinate)
        val column = moves.indexOf(move)
        require(column >= 0)
        return transitions(coordinate)[value * moves.size + column]
    }
}
