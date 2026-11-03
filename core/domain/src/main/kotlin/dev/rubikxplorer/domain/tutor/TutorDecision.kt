package dev.rubikxplorer.domain.tutor

import dev.rubikxplorer.domain.content.TextKey
import dev.rubikxplorer.domain.text.Text

/**
 * What the tutor decides for the shown step of a Lesson (AD-5), built only by [Assess.decide]:
 * - [objectiveId]: the Lesson's Objective;
 * - [satisfied]: the Completion Predicate holds on the current Cube State (it never reads the mode);
 * - [diagnosis]: what went wrong, if the tutor names a mistake;
 * - [certainty]: how sure the tutor is of the Cube State it judged;
 * - [nextAction]: what the Learner does next;
 * - [explanation]: the shown step's Notice once it is done, else its Goal;
 * - [recovery]: the Guided recovery on offer;
 * - [reveal]: the shown step's Moves may be shown;
 * - [advance]: the Learner may move on (the Lesson is complete, or a completed step's Notice is shown);
 * - [feedback]: in *Test me* only, how the last committed Move changed progress toward the Objective,
 *   until the step is done (then Notice and Next speak).
 */
data class TutorDecision(
    val objectiveId: TextKey,
    val satisfied: Boolean,
    val diagnosis: Diagnosis?,
    val certainty: Certainty,
    val nextAction: NextAction,
    val explanation: Text,
    val recovery: Recovery,
    val reveal: Boolean,
    val advance: Boolean,
    val feedback: MoveFeedback?,
)
