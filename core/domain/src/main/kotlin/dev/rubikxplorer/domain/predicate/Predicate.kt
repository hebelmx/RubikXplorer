package dev.rubikxplorer.domain.predicate

import dev.rubikxplorer.domain.cube.CubeState

/**
 * A Completion Predicate (FR-5, AD-6): a pure, color-anchored judgment of a [CubeState] against a
 * goal. Every function finds faces by their center color and ignores `state.orientation` in effect.
 */
sealed interface Predicate {
    /** True when the goal holds. */
    fun isSatisfied(state: CubeState): Boolean

    /** The pieces already where the goal wants them, which a helping Move must not disturb. */
    fun preservedPieces(state: CubeState): Set<Piece>

    /** How far [state] is toward the goal. */
    fun progress(state: CubeState): ObjectiveProgress
}
