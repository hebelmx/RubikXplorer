package dev.rubikxplorer.domain.tutor

import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.NotationResult

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
        val owners = lesson.steps.flatMapIndexed { index, step -> stepMoves(lesson, step.moves).map { index to it } }
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
