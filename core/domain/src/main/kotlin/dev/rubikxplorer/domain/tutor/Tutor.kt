package dev.rubikxplorer.domain.tutor

import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.cube.Axis
import dev.rubikxplorer.domain.cube.CubeRotation
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.NotationResult
import dev.rubikxplorer.domain.cube.Orientation
import dev.rubikxplorer.domain.cube.SliceTurn
import dev.rubikxplorer.domain.cube.Turn
import dev.rubikxplorer.domain.cube.WideTurn

/** The tutor's pure step rules (AD-5, AD-6); [Assess] builds the decision from them. */
object Tutor {
    /**
     * The 0-based step of [lesson], started at [entry], that the Learner is on at [state]: a pure
     * function of the Cube State, never stored (AD-6).
     *
     * - On the authored path ([state] equals [entry] with the first `i` of the steps' flattened Moves
     *   applied, first match): the step holding Move `i + 1`, or the last step after the final Move.
     * - Off the path: the first step whose effective done ([stepDone]) is unmet, else the last step.
     */
    fun currentStep(
        lesson: Lesson,
        entry: CubeState,
        state: CubeState,
    ): Int {
        val owners = pathOwners(lesson)
        var path = entry
        owners.forEach { (index, move) ->
            if (path == state) return index
            path = path.apply(move)
        }
        val last = lesson.steps.lastIndex.coerceAtLeast(0)
        if (path == state) return last
        return lesson.steps.indices.firstOrNull { !stepDone(lesson, it, state) } ?: last
    }

    /**
     * Step [index]'s effective done on [state]: `allOf(lesson.preserve, done)` (AD-6). A step is not
     * done while a piece an earlier Lesson solved is broken.
     */
    fun stepDone(
        lesson: Lesson,
        index: Int,
        state: CubeState,
    ): Boolean = lesson.preserve.isSatisfied(state) && lesson.steps[index].done.isSatisfied(state)

    /**
     * The step the step card shows: the last step once [lesson]'s `completion` holds on [state], else
     * [noticeStep] (a completed step whose Notice waits for *Next*) or, without one, [currentStep].
     */
    fun shownStep(
        lesson: Lesson,
        entry: CubeState,
        state: CubeState,
        noticeStep: Int?,
    ): Int =
        if (lesson.completion.isSatisfied(state)) {
            lesson.steps.lastIndex.coerceAtLeast(0)
        } else {
            noticeStep ?: currentStep(lesson, entry, state)
        }

    /** [entry] with the Moves of the first [steps] steps of [lesson] applied (all of them when [steps] exceeds their count). */
    fun pathState(
        lesson: Lesson,
        entry: CubeState,
        steps: Int,
    ): CubeState = entry.apply(lesson.steps.take(steps.coerceAtLeast(0)).flatMap { stepMoves(lesson, it.moves) })

    /**
     * True when [before] is an authored path state of [lesson] (entered at [entry]) and [move] is the
     * next authored Move there, compared as canonical [Move] values. Either quarter of an authored
     * half turn counts, and so does the same quarter again from that half-done state. A state match,
     * never a counter.
     */
    internal fun isOnPath(
        lesson: Lesson,
        entry: CubeState,
        before: CubeState,
        move: Move,
    ): Boolean {
        var path = entry
        pathOwners(lesson).forEach { (_, next) ->
            val quarters = quartersOf(next)
            if (path == before && (move == next || move in quarters)) return true
            if (move in quarters && path.apply(move) == before) return true
            path = path.apply(next)
        }
        return false
    }

    /**
     * The fewest Cube Rotations that turn a cube held in [from] to the hold [to] (*Show me how* for a
     * turned cube, RecoveryPath.REORIENT): empty when they match, else at most two, since one rotation
     * reaches nine of the 24 holds and two reach the rest. Breadth-first over x, y, z, so bounded.
     */
    fun reorient(
        from: Orientation,
        to: Orientation,
    ): List<CubeRotation> {
        val rotations = Axis.entries.flatMap { axis -> Turn.entries.map { CubeRotation(axis, it) } }
        val seen = mutableMapOf(from to emptyList<CubeRotation>())
        var frontier = listOf(from)
        while (to !in seen) {
            frontier =
                frontier.flatMap { held ->
                    rotations.mapNotNull { rotation ->
                        val next = held.rotated(rotation.axis, rotation.turn)
                        if (next in seen) null else next.also { seen[it] = seen.getValue(held) + rotation }
                    }
                }
        }
        return seen.getValue(to)
    }

    /** The two quarter turns that make up the half turn [move], in either direction; empty unless [move] is a half turn. */
    private fun quartersOf(move: Move): List<Move> =
        when (move) {
            is FaceTurn -> if (move.turn == Turn.HALF) QUARTERS.map { FaceTurn(move.face, it) } else emptyList()
            is WideTurn -> if (move.turn == Turn.HALF) QUARTERS.map { WideTurn(move.face, it) } else emptyList()
            is SliceTurn -> if (move.turn == Turn.HALF) QUARTERS.map { SliceTurn(move.slice, it) } else emptyList()
            is CubeRotation -> if (move.turn == Turn.HALF) QUARTERS.map { CubeRotation(move.axis, it) } else emptyList()
        }

    private val QUARTERS = listOf(Turn.CLOCKWISE, Turn.COUNTERCLOCKWISE)

    /** The steps' flattened Moves, each paired with the 0-based step that owns it. */
    private fun pathOwners(lesson: Lesson): List<Pair<Int, Move>> =
        lesson.steps.flatMapIndexed { index, step -> stepMoves(lesson, step.moves).map { index to it } }

    /** Loaded content is validated (Story 2.1 checks `steps.N.moves`), so bad Notation is a programmer error. */
    private fun stepMoves(
        lesson: Lesson,
        notation: String,
    ): List<Move> {
        val parsed = Notation.parse(notation)
        check(parsed is NotationResult.Parsed) { "Lesson ${lesson.id} has a step with invalid Moves: $parsed" }
        return parsed.moves
    }
}
