package dev.cubentia.domain.predicate

import dev.cubentia.domain.cube.CubeState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Nothing to judge: always satisfied, preserving no pieces, with progress complete (1 of 1). A Lesson
 * with no earlier solved pieces to keep uses it as its `preserve` (AD-6).
 */
@Serializable
@SerialName("nothing")
data object AlwaysTrue : Predicate {
    override fun isSatisfied(state: CubeState): Boolean = true

    override fun preservedPieces(state: CubeState): Set<Piece> = emptySet()

    override fun progress(state: CubeState): ObjectiveProgress = ObjectiveProgress(1, 1)
}
