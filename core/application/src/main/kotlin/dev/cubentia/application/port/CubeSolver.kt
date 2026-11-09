package dev.cubentia.application.port

import dev.cubentia.domain.cube.Cubies
import dev.cubentia.domain.cube.FaceTurn

/** Offline, center-relative solving. Cancellation propagates without publishing a result. */
fun interface CubeSolver {
    suspend fun solve(cubies: Cubies): SolveResult
}

sealed interface SolveResult {
    data class Solved(
        val moves: List<FaceTurn>,
    ) : SolveResult

    data object Invalid : SolveResult

    data object TimedOut : SolveResult

    data object Failed : SolveResult
}
