package dev.rubikxplorer.domain.tutor

import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Cubies
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.predicate.Piece
import dev.rubikxplorer.domain.predicate.Predicate
import dev.rubikxplorer.domain.predicate.WhitePieceSolved
import dev.rubikxplorer.domain.text.Text

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
        val search = Search(goal, moveSet, shouldContinue)
        for (bound in start..maxDepth) {
            when (val result = search.run(from, bound)) {
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

    /** One depth-first pass of IDA* per bound, sharing the node count across passes. */
    private class Search(
        private val goal: RecoveryGoal,
        private val moveSet: List<Move>,
        private val shouldContinue: () -> Boolean,
    ) {
        private var nodes = 0L
        private var stopped = false
        private val path = ArrayDeque<Move>()

        fun run(
            from: CubeState,
            bound: Int,
        ): RecoveryPlan {
            path.clear()
            return when {
                dfs(from, 0, bound) -> RecoveryPlan.Found(path.toList())
                stopped -> RecoveryPlan.Stopped
                else -> RecoveryPlan.NotFound
            }
        }

        /** True when a goal state lies within [bound] of [state], reached at depth [g]; [path] then holds the way there. */
        private fun dfs(
            state: CubeState,
            g: Int,
            bound: Int,
        ): Boolean {
            if (++nodes % CHECK_INTERVAL == 0L && !shouldContinue()) stopped = true
            if (stopped) return false
            val h = heuristic(goal.missing(state))
            if (h == 0) return true
            if (g + h > bound) return false
            val last = path.lastOrNull()
            for (move in moveSet) {
                if (last != null && redundant(last, move)) continue
                path.addLast(move)
                if (dfs(state.apply(move), g + 1, bound)) return true
                path.removeLast()
                if (stopped) return false
            }
            return false
        }
    }
}
