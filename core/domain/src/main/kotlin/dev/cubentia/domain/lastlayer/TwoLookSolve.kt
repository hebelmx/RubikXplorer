package dev.cubentia.domain.lastlayer

import dev.cubentia.domain.cube.CubeRotation
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.MoveHistory

/**
 * Judges whether a last-layer solve used only 2-look Algorithms (FR-17): the fewest committed Moves not
 * covered by a split into free Moves (a Cube Rotation, or a `U` turn while the starting top layer is on U)
 * and runs whose cubie effect equals one of the [algorithms]' effects, started while the starting top layer
 * is on U. Comparing effects accepts any spelling of an Algorithm (`U U` for `U2`, `L x` for `r`).
 */
object TwoLookSolve {
    fun offPathMoves(
        moves: List<Move>,
        algorithms: List<List<Move>>,
    ): Int {
        val effects = algorithms.map { CubeState.SOLVED.apply(it).cubies }.toSet()
        val bound = 2 * (algorithms.maxOfOrNull { it.size } ?: 0)
        // Which layer is on top before each Move: rotations move another layer to U, so a U turn or an
        // Algorithm counts only while the top is still the layer the solve started with.
        val held = moves.runningFold(CubeState.SOLVED) { state, move -> state.apply(move) }
        val topIsStart = held.map { it.orientation.homeFaceAt(Face.U) == Face.U }
        val cost = IntArray(moves.size + 1) { it }
        for (start in moves.indices) {
            val move = moves[start]
            val free = move is CubeRotation || (move is FaceTurn && move.face == Face.U && topIsStart[start])
            cost[start + 1] = minOf(cost[start + 1], cost[start] + if (free) 0 else 1)
            if (!topIsStart[start]) continue
            var effect = CubeState.SOLVED
            for (end in start until minOf(moves.size, start + bound)) {
                effect = effect.apply(moves[end])
                if (effect.cubies in effects) cost[end + 1] = minOf(cost[end + 1], cost[start])
            }
        }
        return cost.last()
    }

    /**
     * [offPathMoves] over [history]'s committed Moves, plus one when [history] no longer starts at [entry]: a
     * mode switch or the Manual Cube Editor replaced it, so the Moves before that are no longer evidence.
     */
    fun offPathMoves(
        history: MoveHistory,
        entry: CubeState,
        algorithms: List<List<Move>>,
    ): Int = offPathMoves(history.done, algorithms) + if (history.start == entry) 0 else 1
}
