package dev.rubikxplorer.domain.tutor

import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.predicate.Predicate
import dev.rubikxplorer.domain.text.Text

/**
 * How a committed Move changed the Learner's progress toward the Objective (FR-11): *Test me* tells
 * the Learner which. Calm copy in every case; a Harmful Move is never shown in red.
 *
 * Story 4.2 replaces the [HARMFUL] placeholder copy with a Diagnosis.
 */
enum class MoveFeedback(
    val text: Text,
) {
    /** The Completion Predicate's progress rose. */
    PROGRESS(Text("msg.test-me.progress")),

    /** The progress stayed where it was. */
    NEUTRAL(Text("msg.test-me.neutral")),

    /** The progress fell. */
    HARMFUL(Text("msg.test-me.harmful")),
    ;

    companion object {
        /**
         * The feedback for a Move from [before] to [after], judged only by [completion]'s
         * `ObjectiveProgress` (it never reads the Teaching Mode): higher is [PROGRESS], lower is
         * [HARMFUL], equal is [NEUTRAL].
         */
        fun classify(
            completion: Predicate,
            before: CubeState,
            after: CubeState,
        ): MoveFeedback {
            val change = completion.progress(after).compareTo(completion.progress(before))
            return when {
                change > 0 -> PROGRESS
                change < 0 -> HARMFUL
                else -> NEUTRAL
            }
        }
    }
}
