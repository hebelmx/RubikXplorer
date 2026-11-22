package dev.cubentia.domain.method

import dev.cubentia.domain.content.AlgorithmId
import dev.cubentia.domain.content.AlgorithmStatus
import dev.cubentia.domain.content.CaseId
import dev.cubentia.domain.content.ContentBundle
import dev.cubentia.domain.content.StageId
import dev.cubentia.domain.cube.CubeRotation
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Move
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

    /** The beginner first two layers, then the 2-look OLL and PLL Lessons' Algorithms (Story 15.2). */
    TWO_LOOK,

    /** The beginner first two layers, then one full OLL and one full PLL Algorithm per Case (Story 15.2). */
    FULL_OLL_PLL,
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

/**
 * One labeled part of a method-shaped solution: [moves] in the input's held frame, never empty. Beginner
 * segments hold only face turns; 2-look and full last-layer segments also hold the Library Algorithms'
 * wide, slice and rotation Moves, and the first of them may start with one Cube Rotation.
 */
data class MethodSegment(
    val tag: SegmentTag,
    val moves: List<Move>,
)

/** The outcome of [MethodSolve.solve]. */
sealed interface MethodSolveResult {
    /** [segments] in Course order; applied in order they solve the input. Empty for a solved input. */
    data class Solved(
        val segments: List<MethodSegment>,
    ) : MethodSolveResult

    /** A search bound was exhausted, or the content has no Stage or Algorithm the method needs. */
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
 *
 * 2-look and full OLL + PLL: the same first-two-layer segments, then last-layer segments built only from
 * Library Algorithms with `U` adjustments, one per application, tagged with the Algorithm's Case and
 * preferring the Learner's *Learning* or *Learned* Algorithms ([FasterLastLayer]).
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

    /** The Stage a 2-look lone closing `U` turn is tagged with. */
    val TWO_LOOK_LAST_LAYER_STAGE: StageId = StageId("stage-09-two-look-pll")

    /** The Stage a full OLL + PLL lone closing `U` turn is tagged with. */
    val FULL_LAST_LAYER_STAGE: StageId = StageId("stage-06-last-layer-completion")

    /**
     * A [method]-shaped solution of [state] from [bundle]'s Stages, Lessons, Algorithms and move set.
     * [statuses] are the Learner's Algorithm Statuses (absent = *Not started*); 2-look and full prefer
     * Algorithms marked *Learning* or *Learned*.
     */
    fun solve(
        state: CubeState,
        method: SolveMethod,
        bundle: ContentBundle,
        statuses: Map<AlgorithmId, AlgorithmStatus> = emptyMap(),
        shouldContinue: () -> Boolean = { true },
    ): MethodSolveResult =
        when (method) {
            SolveMethod.BEGINNER -> beginner(state, bundle, shouldContinue)
            SolveMethod.TWO_LOOK, SolveMethod.FULL_OLL_PLL -> faster(state, method, bundle, statuses, shouldContinue)
        }

    /** The Stage a lone closing `U` turn of [method]'s last layer is tagged with; null for the beginner method. */
    fun lastLayerStageOf(method: SolveMethod): StageId? =
        when (method) {
            SolveMethod.BEGINNER -> null
            SolveMethod.TWO_LOOK -> TWO_LOOK_LAST_LAYER_STAGE
            SolveMethod.FULL_OLL_PLL -> FULL_LAST_LAYER_STAGE
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
        val layers =
            when (val built = firstTwoLayers(state.reoriented(Orientation.HOME), completions, moveSet, shouldContinue)) {
                is Layers -> built
                is LayersFailed -> return built.result
            }
        var current = layers.state
        val segments = layers.segments.toMutableList()
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
        return MethodSolveResult.Solved(segments.map { held(state.orientation, it) })
    }

    /** 2-look or full OLL + PLL: the beginner first two layers, then [FasterLastLayer] in the held frame. */
    private fun faster(
        state: CubeState,
        method: SolveMethod,
        bundle: ContentBundle,
        statuses: Map<AlgorithmId, AlgorithmStatus>,
        shouldContinue: () -> Boolean,
    ): MethodSolveResult {
        val completions =
            FIRST_TWO_LAYER_STAGES.map { id ->
                bundle.stages
                    .firstOrNull { it.id == id }
                    ?.lessons
                    ?.lastOrNull()
                    ?.completion ?: return MethodSolveResult.NotFound
            }
        val moveSet = bundle.tutorConfig.moves.filterIsInstance<FaceTurn>()
        if (moveSet.isEmpty()) return MethodSolveResult.NotFound
        if (FasterLastLayer.looksOf(method, bundle, statuses) == null) return MethodSolveResult.NotFound
        val layers =
            when (val built = firstTwoLayers(state.reoriented(Orientation.HOME), completions, moveSet, shouldContinue)) {
                is Layers -> built
                is LayersFailed -> return built.result
            }
        val firstTwoLayers = layers.segments.map { held(state.orientation, it) }
        // FaceTurns never change the orientation, so after the held segments the cube is still held as the input was.
        val atLastLayer = layers.state.reoriented(state.orientation)
        val lastLayerStage = lastLayerStageOf(method) ?: return MethodSolveResult.NotFound
        return when (val built = FasterLastLayer.build(atLastLayer, method, bundle, statuses, lastLayerStage, shouldContinue)) {
            is LastLayerBuild.Done -> MethodSolveResult.Solved(firstTwoLayers + built.segments)
            LastLayerBuild.NotFound -> MethodSolveResult.NotFound
            LastLayerBuild.Stopped -> MethodSolveResult.Stopped
        }
    }

    /**
     * The first-two-layer Stage segments of [home] (home frame), one per Stage whose completion does not
     * already hold, and the state they leave; every earlier Stage's goal pieces must survive each Stage.
     */
    private fun firstTwoLayers(
        home: CubeState,
        completions: List<Predicate>,
        moveSet: List<FaceTurn>,
        shouldContinue: () -> Boolean,
    ): LayersBuild {
        var current = home
        val segments = mutableListOf<MethodSegment>()
        for (index in FIRST_TWO_LAYER_STAGES.indices) {
            val keep = completions.subList(0, index + 1)
            when (val built = firstTwoLayerSegment(current, keep, moveSet, shouldContinue)) {
                is Built -> {
                    val earlier = completions.subList(0, index).flatMapTo(mutableSetOf()) { it.preservedPieces(CubeState.SOLVED) }
                    val kept = keep.flatMapTo(mutableSetOf()) { it.preservedPieces(built.state) }
                    if (!kept.containsAll(earlier)) return LayersFailed(MethodSolveResult.NotFound)
                    current = built.state
                    if (built.moves.isNotEmpty()) segments += MethodSegment(SegmentTag.OfStage(FIRST_TWO_LAYER_STAGES[index]), built.moves)
                }

                Unbuilt.NotFound -> {
                    return LayersFailed(MethodSolveResult.NotFound)
                }

                Unbuilt.Stopped -> {
                    return LayersFailed(MethodSolveResult.Stopped)
                }
            }
        }
        return Layers(current, segments)
    }

    /**
     * True when [segments] keep to [method]'s shape (the re-verification of AD-21). Beginner: no Case
     * segment. 2-look and full: first-two-layer Stage segments, then last-layer segments that are either
     * one lone closing segment tagged [lastLayerStageOf] (an optional Cube Rotation and one `U` turn), or
     * Case segments each holding an optional `U` turn and the exact parsed Moves of an Algorithm of the
     * chosen set whose Case is the tag; the first may start with one Cube Rotation and the last may end
     * with one `U` turn. The chosen set follows [statuses] as [solve] does.
     */
    fun conforms(
        segments: List<MethodSegment>,
        method: SolveMethod,
        bundle: ContentBundle,
        statuses: Map<AlgorithmId, AlgorithmStatus> = emptyMap(),
    ): Boolean {
        if (method == SolveMethod.BEGINNER) return segments.none { it.tag is SegmentTag.OfCase }
        val set = FasterLastLayer.looksOf(method, bundle, statuses)?.flatMap { it.algorithms } ?: return false
        val firstTwoLayers = segments.takeWhile { (it.tag as? SegmentTag.OfStage)?.stageId in FIRST_TWO_LAYER_STAGES }
        val lastLayer = segments.drop(firstTwoLayers.size)
        if (lastLayer.isEmpty()) return true
        val lone = lastLayer.singleOrNull()
        if (lone != null && lone.tag == SegmentTag.OfStage(lastLayerStageOf(method) ?: return false)) {
            val turns = if (lone.moves.firstOrNull() is CubeRotation) lone.moves.drop(1) else lone.moves
            return turns.size == 1 && FasterLastLayer.isUTurn(turns.single())
        }
        return lastLayer.withIndex().all { (index, segment) ->
            val caseId = (segment.tag as? SegmentTag.OfCase)?.caseId ?: return false
            // The first segment may lead with the one Cube Rotation, or its Algorithm may itself start with one (PLL Aa, E).
            val shapes =
                if (index == 0 &&
                    segment.moves.firstOrNull() is CubeRotation
                ) {
                    listOf(segment.moves, segment.moves.drop(1))
                } else {
                    listOf(segment.moves)
                }
            val last = index == lastLayer.lastIndex
            set.filter { it.algorithm.caseId == caseId }.any { algorithm -> shapes.any { isApplication(it, algorithm.moves, last) } }
        }
    }

    // An optional U pre-turn, the Algorithm's exact Moves, and (on the last segment) an optional closing U turn.
    private fun isApplication(
        moves: List<Move>,
        algorithm: List<Move>,
        closingAllowed: Boolean,
    ): Boolean =
        (0..1).any { pre ->
            val end = pre + algorithm.size
            moves.size >= end &&
                (pre == 0 || FasterLastLayer.isUTurn(moves.first())) &&
                moves.subList(pre, end) == algorithm &&
                (moves.size == end || (closingAllowed && moves.size == end + 1 && FasterLastLayer.isUTurn(moves.last())))
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

    /** A home-frame face-turn [segment] in the held frame of [orientation]. */
    private fun held(
        orientation: Orientation,
        segment: MethodSegment,
    ): MethodSegment = segment.copy(moves = segment.moves.map { held(orientation, it as FaceTurn) })

    /** [move] on a home face, as the face the Learner holds there under [orientation]. */
    private fun held(
        orientation: Orientation,
        move: FaceTurn,
    ): FaceTurn = FaceTurn(Face.entries.first { orientation.homeFaceAt(it) == move.face }, move.turn)

    private const val STAGE_5 = 3
    private const val STAGE_6 = 4
}

/** How building the first two layers ended. */
private sealed interface LayersBuild

/** The first-two-layer Stage [segments] (home frame) and the [state] they leave. */
private class Layers(
    val state: CubeState,
    val segments: List<MethodSegment>,
) : LayersBuild

private class LayersFailed(
    val result: MethodSolveResult,
) : LayersBuild

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
