package dev.cubentia.domain.tutor

import dev.cubentia.domain.content.Lesson
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Cubies
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.predicate.Piece
import dev.cubentia.domain.predicate.Predicate
import dev.cubentia.domain.predicate.WhitePieceSolved
import dev.cubentia.domain.text.Text

/**
 * What a Guided recovery must restore (FR-12, AD-5): the [pieces] that must again be among the
 * preserved pieces of the [keep] predicates. Built by [RecoveryPlanner.goal].
 */
data class RecoveryGoal(
    val keep: List<Predicate>,
    val pieces: Set<Piece>,
) {
    /** True when every one of [pieces] is preserved again at [state]. */
    fun isMet(state: CubeState): Boolean = missing(state).isEmpty()

    /** The [pieces] not preserved at [state]. */
    internal fun missing(state: CubeState): Set<Piece> {
        if (pieces.isEmpty()) return emptySet()
        val kept = keep.flatMapTo(mutableSetOf()) { it.preservedPieces(state) }
        return pieces - kept
    }
}

/** The outcome of [RecoveryPlanner.plan]. */
sealed interface RecoveryPlan {
    /** The shortest correction found: [moves] (empty when the goal already holds). */
    data class Found(
        val moves: List<Move>,
    ) : RecoveryPlan

    /** No correction of at most the search bound restores the goal. */
    data object NotFound : RecoveryPlan

    /** The caller asked the search to stop (a timeout or cancellation) before it finished. */
    data object Stopped : RecoveryPlan
}

/**
 * The Guided recovery planner (AD-5, AD-8): a pure, bounded IDA* over the configured face turns for
 * the shortest Move sequence that restores a [RecoveryGoal]. It never calls the Solver, and it runs
 * only inside the `PlanRecovery` Effect, never in the reducer.
 */
object RecoveryPlanner {
    /** "Finding the shortest way back…" while a plan is pending. */
    val PENDING: Text = Text("msg.recovery.pending")

    /** "I can't find a short way back. Undo, or start this step again." */
    val NOT_FOUND: Text = Text("msg.recovery.not-found")

    /**
     * How often, in visited nodes, [plan] asks `shouldContinue`: search mechanics fixed by AD-8, not a
     * tutor tuning value (the search bound, `recoveryMaxDepth`, is data in `tutor-config.json`).
     */
    val CHECK_INTERVAL: Int = 1024

    /**
     * The goal of [lesson]'s current Objective, judged at each of [references] (the Checkpoint's state
     * and the state before the diagnosed Move): for each reference `r`, `keep` gains `lesson.preserve`
     * and the `done` of every step effectively done at `r` ([Tutor.stepDone]), and `pieces` gains those
     * predicates' preserved pieces at `r`.
     */
    fun goal(
        lesson: Lesson,
        references: List<CubeState>,
    ): RecoveryGoal {
        val keep = mutableListOf<Predicate>()
        val pieces = mutableSetOf<Piece>()
        references.forEach { r ->
            val kept =
                listOf(lesson.preserve) +
                    lesson.steps.indices
                        .filter { Tutor.stepDone(lesson, it, r) }
                        .map { lesson.steps[it].done }
            kept.forEach { predicate ->
                if (predicate !in keep) keep += predicate
                pieces += predicate.preservedPieces(r)
            }
        }
        return RecoveryGoal(keep.toList(), pieces.toSet())
    }

    /**
     * The shortest sequence of at most [maxDepth] Moves of [moveSet] that takes [from] to a state
     * meeting [goal], by IDA* with `h = max(ceil(missingCorners / 4), ceil(missingEdges / 4))`. Two
     * turns of the same face never follow each other, and Moves are tried in [moveSet] order, so the
     * result is deterministic. [shouldContinue] is asked every [CHECK_INTERVAL] nodes; when it says
     * no the search returns [RecoveryPlan.Stopped].
     */
    fun plan(
        from: CubeState,
        goal: RecoveryGoal,
        maxDepth: Int,
        moveSet: List<Move>,
        shouldContinue: () -> Boolean = { true },
    ): RecoveryPlan {
        require(maxDepth >= 0) { "maxDepth must not be negative, was $maxDepth" }
        val start = heuristic(goal.missing(from))
        if (start == 0) return RecoveryPlan.Found(emptyList())
        val search =
            Search<CubeState>(
                moveSet = moveSet,
                shouldContinue = shouldContinue,
                step = { state, index -> state.apply(moveSet[index]) },
                estimate = { state, _ -> heuristic(goal.missing(state)) },
                accept = { _, _ -> true },
            )
        for (bound in start..maxDepth) {
            when (val result = search.run(from, bound)) {
                is RecoveryPlan.Found, RecoveryPlan.Stopped -> return result
                RecoveryPlan.NotFound -> Unit
            }
        }
        return RecoveryPlan.NotFound
    }

    /**
     * [plan]'s bounded IDA* for a goal whose [RecoveryGoal.keep] predicates preserve a piece exactly
     * when its cubie is home and untwisted (unflipped), as the first-two-layer predicates do. Same
     * bound loop, Move order, same-face rule, [CHECK_INTERVAL] polling and results as [plan], but it
     * tracks only the goal pieces and bounds by exact one-, two- and three-piece distances
     * ([PieceDistances]), so deep goals (a middle-layer edge) stay fast. A goal state is confirmed
     * with [RecoveryGoal.isMet] before it is reported. [moveSet] must hold face turns only, read in
     * [from]'s held frame.
     */
    fun planPieces(
        from: CubeState,
        goal: RecoveryGoal,
        maxDepth: Int,
        moveSet: List<Move>,
        shouldContinue: () -> Boolean = { true },
    ): RecoveryPlan {
        require(maxDepth >= 0) { "maxDepth must not be negative, was $maxDepth" }
        if (goal.isMet(from)) return RecoveryPlan.Found(emptyList())
        val search = PieceSearch(from, goal, moveSet, shouldContinue)
        for (bound in search.startBound..maxDepth) {
            when (val result = search.run(bound)) {
                is RecoveryPlan.Found, RecoveryPlan.Stopped -> return result
                RecoveryPlan.NotFound -> Unit
            }
        }
        return RecoveryPlan.NotFound
    }

    /**
     * The plan copy: "{count} moves — {layer} stays", where `layer` names the [pieces] the plan
     * restores: `cross` only for exactly the four white edges, `first_layer` only for exactly all
     * eight white-layer pieces, else `other` (a partial cross or layer is never called whole).
     */
    fun describe(
        moves: List<Move>,
        pieces: Set<Piece>,
    ): Text = Text("msg.recovery.plan", mapOf("count" to moves.size, "layer" to layerOf(pieces)))

    private val CROSS: Set<Piece> = WhitePieceSolved.EDGE_NAMES.mapNotNull(WhitePieceSolved::pieceNamed).toSet()

    private val FIRST_LAYER: Set<Piece> = WhitePieceSolved.NAMES.mapNotNull(WhitePieceSolved::pieceNamed).toSet()

    private fun layerOf(pieces: Set<Piece>): String =
        when (pieces) {
            CROSS -> "cross"
            FIRST_LAYER -> "first_layer"
            else -> "other"
        }

    private fun heuristic(missing: Set<Piece>): Int {
        val edges = missing.count(WhitePieceSolved::isEdge)
        val corners = missing.size - edges
        return maxOf(perTurn(corners), perTurn(edges))
    }

    /** The fewest face turns that can move [n] corners (or edges): `ceil(n / 4)`. */
    private fun perTurn(n: Int): Int = (n + Cubies.PER_FACE_TURN - 1) / Cubies.PER_FACE_TURN

    private fun redundant(
        previous: Move,
        next: Move,
    ): Boolean = previous == next || (previous is FaceTurn && next is FaceTurn && previous.face == next.face)

    /**
     * One depth-first pass of IDA* per bound over nodes of type [N], sharing the node count across
     * passes: [step] gives the node after `moveSet[i]`, [estimate] the admissible lower bound (0 at a
     * goal; it may stop early with any value above the budget it is given, the depth left), and [accept] confirms a node whose estimate is 0, given the path that reaches it.
     */
    private class Search<N>(
        private val moveSet: List<Move>,
        private val shouldContinue: () -> Boolean,
        private val step: (N, Int) -> N,
        private val estimate: (N, Int) -> Int,
        private val accept: (N, List<Move>) -> Boolean,
    ) {
        private var nodes = 0L
        private var stopped = false
        private val path = ArrayDeque<Move>()

        fun run(
            from: N,
            bound: Int,
        ): RecoveryPlan {
            path.clear()
            return when {
                dfs(from, 0, bound) -> RecoveryPlan.Found(path.toList())
                stopped -> RecoveryPlan.Stopped
                else -> RecoveryPlan.NotFound
            }
        }

        /** True when a goal node lies within [bound] of [node], reached at depth [g]; [path] then holds the way there. */
        private fun dfs(
            node: N,
            g: Int,
            bound: Int,
        ): Boolean {
            if (++nodes % CHECK_INTERVAL == 0L && !shouldContinue()) stopped = true
            if (stopped) return false
            val h = estimate(node, bound - g)
            if (h == 0 && accept(node, path)) return true
            if (g + h > bound) return false
            val last = path.lastOrNull()
            for (index in moveSet.indices) {
                val move = moveSet[index]
                if (last != null && redundant(last, move)) continue
                path.addLast(move)
                if (dfs(step(node, index), g + 1, bound)) return true
                path.removeLast()
                if (stopped) return false
            }
            return false
        }
    }

    /**
     * [planPieces]'s search from [from], one bound at a time: tracks only the goal pieces' (slot,
     * twist/flip) through per-cubie move tables and bounds by [PieceBound]. A goal node is confirmed
     * with [RecoveryGoal.isMet] on the real [CubeState] before it is reported.
     */
    internal class PieceSearch(
        private val from: CubeState,
        private val goal: RecoveryGoal,
        moveSet: List<Move>,
        shouldContinue: () -> Boolean,
    ) {
        private val pieces: IntArray =
            goal.pieces
                .map { piece -> checkNotNull(PieceDistances.idOf(piece)) { "no cubie has the colors of $piece" } }
                .sorted()
                .toIntArray()
        private val moves: IntArray =
            moveSet
                .map { move ->
                    require(move is FaceTurn) { "planPieces searches face turns only, not $move" }
                    PieceDistances.moveIndex(from.orientation.homeFaceAt(move.face), move.turn)
                }.toIntArray()
        private val start: IntArray = IntArray(pieces.size) { PieceDistances.positionOf(from.cubies, pieces[it]) }
        private val bound = PieceBound(pieces, BooleanArray(pieces.size) { start[it] != PieceDistances.home(pieces[it]) })
        private val search =
            Search<IntArray>(
                moveSet = moveSet,
                shouldContinue = shouldContinue,
                step = { positions, index ->
                    val move = moves[index]
                    IntArray(positions.size) { PieceDistances.next(pieces[it], positions[it], move) }
                },
                estimate = bound::of,
                accept = { _, path -> goal.isMet(from.apply(path)) },
            )

        /** The lower bound at [from]: no plan is shorter. */
        val startBound: Int = bound.of(start, Int.MAX_VALUE)

        /** One IDA* pass with [bound]. */
        fun run(bound: Int): RecoveryPlan = search.run(start, bound)
    }
}
