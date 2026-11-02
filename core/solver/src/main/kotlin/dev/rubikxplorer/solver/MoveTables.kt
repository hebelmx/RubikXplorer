package dev.rubikxplorer.solver

import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Turn

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

    // Lazy tables are independent: querying one coordinate does not allocate the other five.
    private val tables =
        Coordinate.entries.associateWith { coordinate ->
            lazy {
                val moves = moves(coordinate)
                val transforms = moves.map { CubeState.SOLVED.apply(it).cubies }
                IntArray(coordinate.size * moves.size) { offset ->
                    coordinate.encode(coordinate.decode(offset / moves.size).apply(transforms[offset % moves.size]))
                }
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
        return tables.getValue(coordinate).value[value * moves.size + column]
    }
}
