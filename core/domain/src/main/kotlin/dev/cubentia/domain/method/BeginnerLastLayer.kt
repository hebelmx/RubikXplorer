package dev.cubentia.domain.method

import dev.cubentia.domain.content.Stage
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Cubies
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.lastlayer.LastLayerStep
import dev.cubentia.domain.predicate.LastLayerStepReached
import dev.cubentia.domain.predicate.Predicate

/**
 * The beginner last layer (AD-21): Stage 5 and Stage 6 segments built only from those Stages'
 * Lesson Algorithms plus turns of the last-layer face, judged by `LastLayerMatcher` through
 * [LastLayerStepReached] (AD-19). Everything here is in the home frame: WHITE on U, so the last
 * layer is on home [LL_FACE].
 *
 * Each rung is a breadth-first search by fewest Algorithm applications (at most
 * [MethodSolve.LL_MAX_APPLICATIONS]); one application is an optional [LL_FACE] pre-turn and one
 * Algorithm. Stage 5 climbs `EDGES_ORIENTED` then `ORIENTED`; Stage 6 climbs `CORNERS_PERMUTED` then
 * `SOLVED_UP_TO_AUF`, then takes the closing [LL_FACE] turn that makes its completion hold.
 */
internal class BeginnerLastLayer private constructor(
    /** Stage 5's Algorithms, home frame. */
    val orientationAlgorithms: List<List<FaceTurn>>,
    /** Stage 6's Algorithms, home frame. */
    val completionAlgorithms: List<List<FaceTurn>>,
    private val orientationDone: Predicate,
    private val completionDone: Predicate,
) {
    /** The Stage 5 segment from [state] (none when its completion already holds). */
    fun orientation(
        state: CubeState,
        shouldContinue: () -> Boolean,
    ): SegmentBuild =
        climb(state, orientationAlgorithms, listOf(LastLayerStep.EDGES_ORIENTED, LastLayerStep.ORIENTED), orientationDone, shouldContinue)

    /** The Stage 6 segment from [state] (none when its completion already holds). */
    fun completion(
        state: CubeState,
        shouldContinue: () -> Boolean,
    ): SegmentBuild =
        climb(
            state,
            completionAlgorithms,
            listOf(LastLayerStep.CORNERS_PERMUTED, LastLayerStep.SOLVED_UP_TO_AUF),
            completionDone,
            shouldContinue,
        )

    private fun climb(
        start: CubeState,
        algorithms: List<List<FaceTurn>>,
        rungs: List<LastLayerStep>,
        done: Predicate,
        shouldContinue: () -> Boolean,
    ): SegmentBuild {
        if (done.isSatisfied(start)) return Built(start, emptyList())
        var state = start
        val moves = mutableListOf<FaceTurn>()
        for (step in rungs) {
            val reached = LastLayerStepReached(step)
            when (val built = rung(state, algorithms, reached, shouldContinue)) {
                is Built -> {
                    state = built.state
                    moves += built.moves
                }

                is Unbuilt -> {
                    return built
                }
            }
        }
        val index = CLOSING_TURNS.indexOfFirst { turn -> done.isSatisfied(if (turn == null) state else state.apply(turn)) }
        if (index < 0) return Unbuilt.NotFound
        val closing = CLOSING_TURNS[index]
        if (closing != null) {
            state = state.apply(closing)
            moves += closing
        }
        return Built(state, moves.toList())
    }

    // An Algorithm's whole effect as one Cubies, so applying it is one composition.
    private val effects: Map<List<FaceTurn>, Cubies> =
        (orientationAlgorithms + completionAlgorithms).associateWith { CubeState.SOLVED.apply(it).cubies }

    private fun effectOf(algorithm: List<FaceTurn>): Cubies = effects.getValue(algorithm)

    private class Node(
        val state: CubeState,
        val moves: List<FaceTurn>,
    )

    private fun rung(
        start: CubeState,
        algorithms: List<List<FaceTurn>>,
        reached: Predicate,
        shouldContinue: () -> Boolean,
    ): SegmentBuild {
        if (reached.isSatisfied(start)) return Built(start, emptyList())
        val visited = hashSetOf<Cubies>(start.cubies)
        var frontier = listOf(Node(start, emptyList()))
        repeat(MethodSolve.LL_MAX_APPLICATIONS) {
            val next = mutableListOf<Node>()
            for (node in frontier) {
                for (pre in CLOSING_TURNS) {
                    val turned = if (pre == null) node.state else node.state.apply(pre)
                    for (algorithm in algorithms) {
                        if (!shouldContinue()) return Unbuilt.Stopped
                        val after = turned.copy(cubies = turned.cubies.compose(effectOf(algorithm)))
                        if (!visited.add(after.cubies)) continue
                        val moves = node.moves + listOfNotNull(pre) + algorithm
                        if (reached.isSatisfied(after)) return Built(after, moves)
                        next += Node(after, moves)
                    }
                }
            }
            frontier = next
        }
        return Unbuilt.NotFound
    }

    companion object {
        /** The last-layer face in the home frame: opposite WHITE's home face U. */
        val LL_FACE: Face = Face.D

        /** No turn, then each turn of [LL_FACE]: the pre-turns and closing turns, in search order. */
        private val CLOSING_TURNS: List<FaceTurn?> = listOf(null) + Turn.entries.map { FaceTurn(LL_FACE, it) }

        /**
         * The beginner last layer of [orientationStage] and [completionStage] with their completion
         * predicates, or null when either Stage has no usable Algorithm.
         */
        fun of(
            orientationStage: Stage,
            completionStage: Stage,
            orientationDone: Predicate,
            completionDone: Predicate,
        ): BeginnerLastLayer? {
            val orientation = algorithmsOf(orientationStage)
            val completion = algorithmsOf(completionStage)
            if (orientation.isEmpty() || completion.isEmpty()) return null
            return BeginnerLastLayer(orientation, completion, orientationDone, completionDone)
        }

        /**
         * The distinct Algorithms of [stage]'s Lesson steps, in Lesson and step order. Two steps are
         * the same Algorithm when their cubie effects agree once leading and trailing [LL_FACE] turns are
         * stripped (the search adds those itself), and the stripped form is the Algorithm. Steps that do not
         * parse, hold non-face Moves or are only [LL_FACE] turns are skipped.
         */
        fun algorithmsOf(stage: Stage): List<List<FaceTurn>> {
            val seen = mutableSetOf<Cubies>()
            val algorithms = mutableListOf<List<FaceTurn>>()
            for (step in stage.lessons.flatMap { it.steps }) {
                val parsed = Notation.parse(step.moves) as? NotationResult.Parsed ?: continue
                val turns = parsed.moves.filterIsInstance<FaceTurn>()
                if (turns.size != parsed.moves.size) continue
                val core = turns.dropWhile { it.face == LL_FACE }.dropLastWhile { it.face == LL_FACE }
                if (core.isEmpty()) continue
                if (seen.add(CubeState.SOLVED.apply(core).cubies)) algorithms += core
            }
            return algorithms
        }
    }
}
