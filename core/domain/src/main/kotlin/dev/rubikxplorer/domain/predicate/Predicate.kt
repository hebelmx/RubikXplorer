package dev.rubikxplorer.domain.predicate

import dev.rubikxplorer.domain.cube.CubeState
import kotlinx.serialization.Serializable

/**
 * A Completion Predicate (FR-5, AD-6): a pure, color-anchored judgment of a [CubeState] against a
 * goal. Every function finds faces by their center color and ignores `state.orientation` in effect.
 *
 * In content JSON a predicate is an object whose `"predicate"` member names its variant (the content
 * `Json` sets that class discriminator); parameters are flat members beside it. The closed registry:
 *
 * - `white-cross-solved` → [WhiteCrossSolved] (Stage 2)
 * - `white-corners-solved` → [WhiteCornersSolved] (Stage 3)
 * - `state-reached` (`moves`) → [StateReached] (Stage 1 Notation exercises)
 * - `allOf` (`predicates`) → [AllOf]
 * - `anyOf` (`predicates`) → [AnyOf]
 *
 * For example `{"predicate":"allOf","predicates":[{"predicate":"white-cross-solved"},{"predicate":"white-corners-solved"}]}`.
 */
@Serializable
sealed interface Predicate {
    /** True when the goal holds. */
    fun isSatisfied(state: CubeState): Boolean

    /**
     * The pieces already where the goal wants them, which a helping Move must not disturb. For leaf
     * predicates their count equals [progress]'s `done`; for combinators it need not ([AnyOf] scales
     * progress, and [AllOf] children may share pieces).
     */
    fun preservedPieces(state: CubeState): Set<Piece>

    /**
     * How far [state] is toward the goal. For leaf predicates and [AllOf], `done == total` exactly
     * when [isSatisfied]; that is the only guarantee tying progress to satisfaction.
     */
    fun progress(state: CubeState): ObjectiveProgress
}
