package dev.cubentia.domain.tutor

import dev.cubentia.domain.cube.CubeRotation
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.SliceTurn
import dev.cubentia.domain.cube.WideTurn

/** Turned layers in the cube's home frame, rather than every displaced sticker. */
object Coverage {
    fun touched(
        before: CubeState,
        move: Move,
    ): Set<Face> =
        when (move) {
            is FaceTurn -> setOf(before.orientation.homeFaceAt(move.face))
            is CubeRotation -> emptySet()
            is WideTurn -> touched(before, CubeState.parts(move))
            is SliceTurn -> touched(before, CubeState.parts(move))
        }

    fun touched(
        before: CubeState,
        moves: List<Move>,
    ): Set<Face> {
        var state = before
        return buildSet {
            moves.forEach { move ->
                addAll(touched(state, move))
                state = state.apply(move)
            }
        }
    }
}
