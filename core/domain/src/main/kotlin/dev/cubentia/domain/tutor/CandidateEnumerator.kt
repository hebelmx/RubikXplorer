package dev.cubentia.domain.tutor

import dev.cubentia.domain.content.TutorConfig
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Move

/** A Move sequence a [CandidateEnumerator] tried and the [state] it leaves the expected state in. */
data class Candidate(
    val moves: List<Move>,
    val state: CubeState,
)

/**
 * The one source of candidates the tutor weighs evidence against (AD-5): every Move sequence of
 * length 0..[maxDepth] over [moveSet] applied to [expected]. A Move is never followed by itself or by
 * another turn of the same face, since that pair is one shorter sequence. Bounded work: at most
 * `1 + n + n² + … + n^maxDepth` sequences for `n = |moveSet|` (289 for the 18 face turns at depth 2),
 * and `maxDepth` is capped by `tutor-config.json`'s validation.
 */
class CandidateEnumerator(
    val expected: CubeState,
    val maxDepth: Int,
    val moveSet: List<Move>,
) {
    init {
        require(maxDepth >= 0) { "maxDepth must not be negative, was $maxDepth" }
        require(moveSet.isNotEmpty()) { "moveSet must not be empty" }
    }

    private val all: List<Candidate> by lazy(LazyThreadSafetyMode.PUBLICATION) { enumerate() }

    /** Every candidate, breadth-first (shorter first), each length in [moveSet] order; the empty sequence first. */
    fun candidates(): List<Candidate> = all

    /** The candidates whose state equals [observed], in [candidates] order. */
    fun consistentWith(observed: CubeState): List<Candidate> = all.filter { it.state == observed }

    private fun enumerate(): List<Candidate> {
        val result = mutableListOf(Candidate(emptyList(), expected))
        var layer = result.toList()
        repeat(maxDepth) {
            layer =
                layer.flatMap { parent ->
                    val last = parent.moves.lastOrNull()
                    moveSet
                        .filter { last == null || !redundant(last, it) }
                        .map { Candidate(parent.moves + it, parent.state.apply(it)) }
                }
            result += layer
        }
        return result
    }

    companion object {
        /** The enumerator for [expected] with the depth and Move set of [config]. */
        fun of(
            expected: CubeState,
            config: TutorConfig,
        ): CandidateEnumerator = CandidateEnumerator(expected, config.maxDepth, config.moves)

        private fun redundant(
            previous: Move,
            next: Move,
        ): Boolean = previous == next || (previous is FaceTurn && next is FaceTurn && previous.face == next.face)
    }
}
