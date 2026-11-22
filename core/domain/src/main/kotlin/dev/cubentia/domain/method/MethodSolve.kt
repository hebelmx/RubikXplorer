package dev.cubentia.domain.method

import dev.cubentia.domain.content.CaseId
import dev.cubentia.domain.content.ContentBundle
import dev.cubentia.domain.content.StageId
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.cube.mergeSameFaceTurns
import dev.cubentia.domain.predicate.Predicate
import dev.cubentia.domain.tutor.PieceDistances
import dev.cubentia.domain.tutor.RecoveryGoal
import dev.cubentia.domain.tutor.RecoveryPlan
import dev.cubentia.domain.tutor.RecoveryPlanner

/** The solving method a method-shaped solution follows (FR-30). */
enum class SolveMethod {
    /** The Course's layer-by-layer beginner method: one segment per Stage 2–6. */
    BEGINNER,
}

/** What a [MethodSegment] practices: a Course Stage or a last-layer Case (AD-20, AD-21). */
sealed interface SegmentTag {
    data class OfStage(
        val stageId: StageId,
    ) : SegmentTag

    /** For 2-look and full OLL + PLL last layers (Story 15.2); the beginner method never emits it. */
    data class OfCase(
        val caseId: CaseId,
    ) : SegmentTag
}

/** One labeled part of a method-shaped solution: [moves] in the input's held frame, never empty. */
data class MethodSegment(
    val tag: SegmentTag,
    val moves: List<FaceTurn>,
)

/** The outcome of [MethodSolve.solve]. */
sealed interface MethodSolveResult {
    /** [segments] in Course order; applied in order they solve the input. Empty for a solved input. */
    data class Solved(
        val segments: List<MethodSegment>,
    ) : MethodSolveResult

    /** A search bound was exhausted, or the content has no beginner Stage or Algorithm. */
    data object NotFound : MethodSolveResult

    /** `shouldContinue` said no before the solution was built. */
    data object Stopped : MethodSolveResult
}

/**
 * Method-aware solutions from the Course, not the Solver (FR-30, AD-21). Pure and deterministic; it
 * never calls the Solver.
 *
 * Beginner: the first-two-layer Stages are placed one goal piece at a time by
 * [RecoveryPlanner.planPieces] against each Stage's completion predicate (its final Lesson's
 * `completion`, as `Placement` reads it), so no segment breaks a piece an earlier one placed. The
 * last-layer Stages are built from the Stages 5–6 Lessons' Algorithms, judged by `LastLayerMatcher`
 * ([BeginnerLastLayer]). A Stage whose predicate already holds gets no segment.
 */
object MethodSolve {
    /** The longest single-piece placement the first-two-layer search tries. */
    const val PIECE_MAX_DEPTH: Int = 12

    /** The most Algorithm applications a last-layer rung tries. */
    const val LL_MAX_APPLICATIONS: Int = 6

    /** The beginner first-two-layer Stages, in Course order. */
    val FIRST_TWO_LAYER_STAGES: List<StageId> =
        listOf(StageId("stage-02-first-cross"), StageId("stage-03-first-layer-corners"), StageId("stage-04-middle-layer"))

    /** The beginner last-layer Stages, in Course order. */
    val LAST_LAYER_STAGES: List<StageId> =
        listOf(StageId("stage-05-last-layer-orientation"), StageId("stage-06-last-layer-completion"))

    /** A [method]-shaped solution of [state] from [bundle]'s Stages, Lessons and move set. */
    fun solve(
        state: CubeState,
        method: SolveMethod,
        bundle: ContentBundle,
        shouldContinue: () -> Boolean = { true },
    ): MethodSolveResult =
        when (method) {
            SolveMethod.BEGINNER -> beginner(state, bundle, shouldContinue)
        }

    private fun beginner(
        state: CubeState,
        bundle: ContentBundle,
        shouldContinue: () -> Boolean,
    ): MethodSolveResult {
        val ids = FIRST_TWO_LAYER_STAGES + LAST_LAYER_STAGES
        val stages = ids.map { id -> bundle.stages.firstOrNull { it.id == id } ?: return MethodSolveResult.NotFound }
        val completions = stages.map { stage -> stage.lessons.lastOrNull()?.completion ?: return MethodSolveResult.NotFound }
        val moveSet = bundle.tutorConfig.moves.filterIsInstance<FaceTurn>()
        if (moveSet.isEmpty()) return MethodSolveResult.NotFound
        val lastLayer =
            BeginnerLastLayer.of(stages[STAGE_5], stages[STAGE_6], completions[STAGE_5], completions[STAGE_6])
                ?: return MethodSolveResult.NotFound

        // Work in the home frame (cubies are center-relative); map to the held frame at the end.
        var current = state.reoriented(Orientation.HOME)
        val segments = mutableListOf<MethodSegment>()
        for (index in FIRST_TWO_LAYER_STAGES.indices) {
            val keep = completions.subList(0, index + 1)
            when (val built = firstTwoLayerSegment(current, keep, moveSet, shouldContinue)) {
                is Built -> {
                    val earlier = completions.subList(0, index).flatMapTo(mutableSetOf()) { it.preservedPieces(CubeState.SOLVED) }
                    val kept = keep.flatMapTo(mutableSetOf()) { it.preservedPieces(built.state) }
                    if (!kept.containsAll(earlier)) return MethodSolveResult.NotFound
                    current = built.state
                    if (built.moves.isNotEmpty()) segments += MethodSegment(SegmentTag.OfStage(ids[index]), built.moves)
                }

                Unbuilt.NotFound -> {
                    return MethodSolveResult.NotFound
                }

                Unbuilt.Stopped -> {
                    return MethodSolveResult.Stopped
                }
            }
        }
        val llStages = listOf(STAGE_5 to lastLayer::orientation, STAGE_6 to lastLayer::completion)
        for ((index, segment) in llStages) {
            when (val built = segment(current, shouldContinue)) {
                is Built -> {
                    current = built.state
                    if (built.moves.isNotEmpty()) segments += MethodSegment(SegmentTag.OfStage(ids[index]), built.moves)
                }

                Unbuilt.NotFound -> {
                    return MethodSolveResult.NotFound
                }

                Unbuilt.Stopped -> {
                    return MethodSolveResult.Stopped
                }
            }
        }
        if (!current.isSolved) return MethodSolveResult.NotFound
        return MethodSolveResult.Solved(segments.map { it.copy(moves = it.moves.map { move -> held(state.orientation, move) }) })
    }

    /**
     * Places the missing goal pieces of `keep.last()` one at a time, cheapest first: for each bound
     * `0..PIECE_MAX_DEPTH`, each missing piece in cubie order is tried with a goal that keeps every
     * goal piece already preserved; the first plan found is taken.
     */
    private fun firstTwoLayerSegment(
        start: CubeState,
        keep: List<Predicate>,
        moveSet: List<FaceTurn>,
        shouldContinue: () -> Boolean,
    ): SegmentBuild {
        val completion = keep.last()
        val goalPieces = completion.preservedPieces(CubeState.SOLVED)
        var current = start
        val moves = mutableListOf<FaceTurn>()
        while (!completion.isSatisfied(current)) {
            if (!shouldContinue()) return Unbuilt.Stopped
            val preserved = goalPieces.intersect(keep.flatMapTo(mutableSetOf()) { it.preservedPieces(current) })
            val missing = (goalPieces - preserved).sortedBy { PieceDistances.idOf(it) }
            if (missing.isEmpty()) return Unbuilt.NotFound
            val searches = missing.map { RecoveryPlanner.PieceSearch(current, RecoveryGoal(keep, preserved + it), moveSet, shouldContinue) }
            val plan = cheapest(searches) ?: return Unbuilt.NotFound
            if (plan is RecoveryPlan.Stopped) return Unbuilt.Stopped
            val found = (plan as RecoveryPlan.Found).moves.map { it as FaceTurn }
            current = current.apply(found)
            moves += found
        }
        return Built(current, moves.mergeSameFaceTurns())
    }

    /** The first plan found by bound, then by piece order: Found or Stopped, or null when none fits. */
    private fun cheapest(searches: List<RecoveryPlanner.PieceSearch>): RecoveryPlan? {
        for (bound in 0..PIECE_MAX_DEPTH) {
            for (search in searches) {
                if (search.startBound > bound) continue
                when (val result = search.run(bound)) {
                    is RecoveryPlan.Found, RecoveryPlan.Stopped -> return result
                    RecoveryPlan.NotFound -> Unit
                }
            }
        }
        return null
    }

    /** [move] on a home face, as the face the Learner holds there under [orientation]. */
    private fun held(
        orientation: Orientation,
        move: FaceTurn,
    ): FaceTurn = FaceTurn(Face.entries.first { orientation.homeFaceAt(it) == move.face }, move.turn)

    private const val STAGE_5 = 3
    private const val STAGE_6 = 4
}

/** How building one segment ended. */
internal sealed interface SegmentBuild

/** The segment's home-frame [moves] (possibly none) and the [state] they leave. */
internal data class Built(
    val state: CubeState,
    val moves: List<FaceTurn>,
) : SegmentBuild

internal sealed interface Unbuilt : SegmentBuild {
    data object NotFound : Unbuilt

    data object Stopped : Unbuilt
}
