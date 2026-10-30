package dev.rubikxplorer.domain.tutor

import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.NotationResult

/** The tutor's pure rules (AD-5). */
object Tutor {
    /**
     * The [TutorDecision] for step [stepIndex] of [lesson], started at [entry] and now at [current],
     * taught in [mode]; [revealRequested] is true once the Learner asked to see the step's Moves.
     *
     * - `satisfied` is the Completion Predicate on [current];
     * - `reveal` is true in [TeachingMode.SHOW_ME] or once [revealRequested];
     * - `advance` is true when satisfied, or when [current] equals the path state after step
     *   [stepIndex] (an intermediate step is done when its state is reached, never by counting Moves).
     */
    fun decide(
        lesson: Lesson,
        entry: CubeState,
        current: CubeState,
        mode: TeachingMode,
        stepIndex: Int,
        revealRequested: Boolean,
    ): TutorDecision {
        val satisfied = lesson.completion.isSatisfied(current)
        return TutorDecision(
            objectiveId = lesson.objective,
            satisfied = satisfied,
            reveal = mode == TeachingMode.SHOW_ME || revealRequested,
            advance = satisfied || current == pathState(lesson, entry, stepIndex + 1),
        )
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
