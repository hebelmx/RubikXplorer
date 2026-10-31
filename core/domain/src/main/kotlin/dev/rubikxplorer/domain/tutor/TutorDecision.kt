package dev.rubikxplorer.domain.tutor

import dev.rubikxplorer.domain.content.TextKey

/**
 * What the tutor decides for the current step of a Lesson (AD-5), made only by [Tutor.decide]:
 * - [objectiveId]: the Lesson's Objective;
 * - [satisfied]: the Completion Predicate holds on the current Cube State (it never reads the mode);
 * - [reveal]: the current step's Moves may be shown;
 * - [advance]: the Learner may move on (the Lesson is complete, or the step's path state is reached);
 * - [feedback]: in *Test me* only, how the last committed Move changed progress toward the Objective,
 *   until the step is done (then Notice and Next speak). Story 4.2 replaces the [MoveFeedback.HARMFUL]
 *   placeholder with a Diagnosis.
 *
 * The remaining AD-5 fields (`diagnosis`, `certainty`, `nextAction`, `explanation`, `recovery`)
 * arrive with Story 4.1, which owns `tutor/Assess` and Certainty.
 */
data class TutorDecision(
    val objectiveId: TextKey,
    val satisfied: Boolean,
    val reveal: Boolean,
    val advance: Boolean,
    val feedback: MoveFeedback?,
)
