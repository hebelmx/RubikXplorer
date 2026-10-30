package dev.rubikxplorer.domain.predicate

import dev.rubikxplorer.domain.cube.CubeState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * At least one of [predicates] holds. Progress is the best child's, scaled to the LCM of the
 * children's totals so the total is fixed and progress stays comparable across states; the preserved
 * pieces are those of the first child with that best progress.
 */
@Serializable
@SerialName("anyOf")
data class AnyOf(
    val predicates: List<Predicate>,
) : Predicate {
    init {
        require(predicates.isNotEmpty()) { "anyOf needs at least one predicate" }
    }

    override fun isSatisfied(state: CubeState): Boolean = predicates.any { it.isSatisfied(state) }

    override fun preservedPieces(state: CubeState): Set<Piece> = best(state).preservedPieces(state)

    override fun progress(state: CubeState): ObjectiveProgress {
        val total = lcm(state)
        return ObjectiveProgress(predicates.maxOf { scaled(it.progress(state), total) }, total)
    }

    /** The first child with the highest scaled progress. */
    private fun best(state: CubeState): Predicate {
        val total = lcm(state)
        return predicates.maxBy { scaled(it.progress(state), total) }
    }

    private fun lcm(state: CubeState): Int = predicates.map { it.progress(state).total }.reduce(::lcm)

    private fun scaled(
        progress: ObjectiveProgress,
        total: Int,
    ): Int = progress.done * (total / progress.total)

    private companion object {
        fun lcm(
            a: Int,
            b: Int,
        ): Int = a / gcd(a, b) * b

        tailrec fun gcd(
            a: Int,
            b: Int,
        ): Int = if (b == 0) a else gcd(b, a % b)
    }
}
