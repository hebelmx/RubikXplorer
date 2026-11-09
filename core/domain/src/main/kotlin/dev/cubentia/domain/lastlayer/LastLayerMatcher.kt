package dev.cubentia.domain.lastlayer

import dev.cubentia.domain.content.Algorithm
import dev.cubentia.domain.content.AlgorithmId
import dev.cubentia.domain.content.Case
import dev.cubentia.domain.content.CaseId
import dev.cubentia.domain.content.CaseSet
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationError
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.inverse

/**
 * The one definition of Case patterns and AUF (AD-19). Each pattern is derived from its Case's
 * primary Algorithm: OLL matches the orientation mask under pre-AUF `U^k`; PLL matches the
 * permutation under pre- and post-AUF, which also covers `y^k` viewing.
 */
class LastLayerMatcher private constructor(
    private val patterns: List<CasePattern>,
) {
    /** Every Case consistent with what [view] shows; all of them when nothing is seen. */
    fun candidates(view: LastLayerView): Set<CaseId> = patterns.filter { it.matches(view) }.map { it.id }.toSet()

    /** True when every sticker is seen and the LL is solved apart from a U-layer turn. */
    fun isSolvedUpToAuf(view: LastLayerView): Boolean {
        val seen = encode(view, CaseSet.PLL)
        return SOLVED_ORBIT.any { it == seen }
    }

    /** One Case's [orbit]: its pattern under every AUF of its [set]. */
    private class CasePattern(
        val id: CaseId,
        val set: CaseSet,
        val orbit: List<List<Int>>,
    ) {
        fun matches(view: LastLayerView): Boolean {
            val seen = encode(view, set)
            return orbit.any { pattern -> pattern.indices.all { seen[it] == null || seen[it] == pattern[it] } }
        }
    }

    companion object {
        /** Algorithm Notation assumes the home frame, so a derived Case has this color on its LL. */
        val CASE_LAST_LAYER: Color = Color.WHITE

        private const val SIDE_COUNT = 4
        private const val LAST_LAYER_CODE = SIDE_COUNT
        private const val OTHER_CODE = 0

        private val SOLVED_ORBIT: List<List<Int>> =
            orbit(
                (0 until SIDE_COUNT).flatMap { side -> listOf(LAST_LAYER_CODE, LAST_LAYER_CODE, side, side, side) },
                CaseSet.PLL,
            )

        /** Builds the matcher, or reports every Case whose pattern cannot be derived. */
        fun of(
            cases: List<Case>,
            algorithms: List<Algorithm>,
        ): LastLayerMatcherResult {
            val primaries = algorithms.filter { it.isPrimary }.associateBy { it.caseId }
            val problems = mutableListOf<LastLayerProblem>()
            val patterns =
                cases.mapNotNull { case ->
                    val primary = primaries[case.id]
                    if (primary == null) {
                        problems += LastLayerProblem.NoPrimaryAlgorithm(case.id)
                        null
                    } else {
                        patternOf(case, primary, problems)
                    }
                }
            return if (problems.isEmpty()) {
                LastLayerMatcherResult.Built(LastLayerMatcher(patterns))
            } else {
                LastLayerMatcherResult.Invalid(problems)
            }
        }

        /** The Case state of [algorithm]: its inverse applied to solved, in the home frame. */
        fun caseState(algorithm: Algorithm): CaseStateResult =
            when (val parsed = Notation.parse(algorithm.notation)) {
                is NotationResult.Invalid -> CaseStateResult.BadNotation(parsed.error)
                is NotationResult.Parsed -> CaseStateResult.Derived(CubeState.SOLVED.apply(parsed.moves.inverse()))
            }

        /** The color-neutral pattern of a fully seen [view]: the minimum encoding over the [set]'s AUF orbit. */
        internal fun signature(
            view: LastLayerView,
            set: CaseSet,
        ): String = orbit(encode(view, set).filterNotNull(), set).minOf { it.joinToString("") }

        private fun patternOf(
            case: Case,
            primary: Algorithm,
            problems: MutableList<LastLayerProblem>,
        ): CasePattern? =
            when (val derived = caseState(primary)) {
                is CaseStateResult.BadNotation -> {
                    problems += LastLayerProblem.BadAlgorithmNotation(primary.id, derived.error)
                    null
                }

                is CaseStateResult.Derived -> {
                    when (val reading = LastLayerView.of(derived.state, CASE_LAST_LAYER)) {
                        LastLayerReading.FirstTwoLayersUnsolved -> {
                            problems += LastLayerProblem.LeavesFirstTwoLayers(primary.id)
                            null
                        }

                        is LastLayerReading.View -> {
                            val pattern = encode(reading.view, case.set).filterNotNull()
                            CasePattern(case.id, case.set, orbit(pattern, case.set))
                        }
                    }
                }
            }

        // OLL sees only whether a sticker shows the LL color; PLL labels the rest by their ring index.
        private fun encode(
            view: LastLayerView,
            set: CaseSet,
        ): List<Int?> =
            view.stickers.map { color ->
                when {
                    color == null -> null
                    color == view.lastLayer -> LAST_LAYER_CODE
                    set == CaseSet.OLL -> OTHER_CODE
                    else -> view.ring.indexOf(color)
                }
            }

        // Pre-AUF shifts positions; post-AUF shifts labels; y^k is one of each.
        private fun orbit(
            pattern: List<Int>,
            set: CaseSet,
        ): List<List<Int>> {
            val shifted =
                (0 until SIDE_COUNT).map { k ->
                    val offset = k * LastLayerView.BLOCK
                    pattern.drop(offset) + pattern.take(offset)
                }
            val orbit = if (set == CaseSet.PLL) shifted.flatMap(::relabelled) else shifted
            return orbit.distinct()
        }

        private fun relabelled(pattern: List<Int>): List<List<Int>> =
            (0 until SIDE_COUNT).map { shift ->
                pattern.map { if (it == LAST_LAYER_CODE) it else (it + shift) % SIDE_COUNT }
            }
    }
}

/** The outcome of [LastLayerMatcher.of]. */
sealed interface LastLayerMatcherResult {
    data class Built(
        val matcher: LastLayerMatcher,
    ) : LastLayerMatcherResult

    data class Invalid(
        val problems: List<LastLayerProblem>,
    ) : LastLayerMatcherResult
}

/** The outcome of [LastLayerMatcher.caseState]. */
sealed interface CaseStateResult {
    data class Derived(
        val state: CubeState,
    ) : CaseStateResult

    data class BadNotation(
        val error: NotationError,
    ) : CaseStateResult
}

/** Why a Case pattern could not be derived. */
sealed interface LastLayerProblem {
    /** The Case [caseId] has no `-a` Algorithm. */
    data class NoPrimaryAlgorithm(
        val caseId: CaseId,
    ) : LastLayerProblem

    /** The Notation of [algorithmId] does not parse. */
    data class BadAlgorithmNotation(
        val algorithmId: AlgorithmId,
        val error: NotationError,
    ) : LastLayerProblem

    /** The inverse of [algorithmId] applied to solved disturbs the first two layers. */
    data class LeavesFirstTwoLayers(
        val algorithmId: AlgorithmId,
    ) : LastLayerProblem
}
