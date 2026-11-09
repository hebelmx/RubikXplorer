package dev.cubentia.domain.predicate

import dev.cubentia.domain.cube.CubeState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Every one of [predicates] holds, e.g. Stage 3's corners with the cross kept. Progress sums the
 * children's; the preserved pieces are the union of theirs.
 */
@Serializable
@SerialName("allOf")
data class AllOf(
    val predicates: List<Predicate>,
) : Predicate {
    init {
        require(predicates.isNotEmpty()) { "allOf needs at least one predicate" }
    }

    override fun isSatisfied(state: CubeState): Boolean = predicates.all { it.isSatisfied(state) }

    override fun preservedPieces(state: CubeState): Set<Piece> = predicates.flatMapTo(mutableSetOf()) { it.preservedPieces(state) }

    override fun progress(state: CubeState): ObjectiveProgress {
        val parts = predicates.map { it.progress(state) }
        return ObjectiveProgress(parts.sumOf { it.done }, parts.sumOf { it.total })
    }
}
