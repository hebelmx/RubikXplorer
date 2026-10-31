package dev.rubikxplorer.domain.content

import dev.rubikxplorer.domain.cube.NotationError
import dev.rubikxplorer.domain.cube.ValidationResult

/** One reason a decoded [ContentBundle] is not valid content; see [ContentValidator]. */
sealed interface ContentProblem {
    /** Two Stages share [id]. */
    data class DuplicateStageId(
        val id: StageId,
    ) : ContentProblem

    /** Two Lessons share [id]. */
    data class DuplicateLessonId(
        val id: LessonId,
    ) : ContentProblem

    /** [id] is not of the form `stage-NN-<slug>`. */
    data class MalformedStageId(
        val id: StageId,
    ) : ContentProblem

    /** [id] is not of the form `lesson-NN-NN`. */
    data class MalformedLessonId(
        val id: LessonId,
    ) : ContentProblem

    /** Lesson [lesson] has a [recovery] reference not of the form `recovery-<slug>`. */
    data class MalformedRecoveryRef(
        val lesson: LessonId,
        val recovery: RecoveryRef,
    ) : ContentProblem

    /** Lesson [lesson] names [declared] as its Stage but sits in [containing]. */
    data class StageMismatch(
        val lesson: LessonId,
        val declared: StageId,
        val containing: StageId,
    ) : ContentProblem

    /** Lesson [lesson] requires [prerequisite], which no Stage defines. */
    data class UnknownPrerequisite(
        val lesson: LessonId,
        val prerequisite: LessonId,
    ) : ContentProblem

    /** The Notation in [field] of Lesson [lesson] does not parse. */
    data class BadNotation(
        val lesson: LessonId,
        val field: String,
        val error: NotationError,
    ) : ContentProblem

    /** The `entryState` of Lesson [lesson] is not a 54-letter facelet string. */
    data class MalformedEntryState(
        val lesson: LessonId,
        val error: FaceletStringResult,
    ) : ContentProblem

    /** The `entryState` of Lesson [lesson] spells a cube that cannot exist. */
    data class ImpossibleEntryState(
        val lesson: LessonId,
        val reason: ValidationResult.Invalid,
    ) : ContentProblem

    /** [key], owned by [owner], is not `<area>.<owner>.<field>[.<n>]`. */
    data class MalformedTextKey(
        val owner: String,
        val key: TextKey,
    ) : ContentProblem

    /** [key] has no message in `strings/en.json`. */
    data class MissingTextKey(
        val key: TextKey,
    ) : ContentProblem

    /** [key] is used by content, which passes no arguments, but its message takes [arguments]. */
    data class TextKeyTakesArguments(
        val key: TextKey,
        val arguments: Set<String>,
    ) : ContentProblem

    /** The message for [key] in `strings/en.json` is not valid ICU (unbalanced braces at [index]). */
    data class MalformedMessage(
        val key: String,
        val index: Int,
    ) : ContentProblem

    /** Lesson [lesson] has no steps, so it has no path to teach. */
    data class NoSteps(
        val lesson: LessonId,
    ) : ContentProblem

    /** Step [index] (0-based) of Lesson [lesson] has Moves that parse to nothing. */
    data class EmptyStep(
        val lesson: LessonId,
        val index: Int,
    ) : ContentProblem

    /**
     * Step [index] (0-based) of Lesson [lesson] ends on the same Cube State it started from, so
     * `Tutor.decide` would mark it done at once, before the Learner turns anything.
     */
    data class NoOpStep(
        val lesson: LessonId,
        val index: Int,
    ) : ContentProblem

    /** The `demonstration` of Lesson [lesson] is not the Moves of its steps in order. */
    data class DemonstrationMismatch(
        val lesson: LessonId,
    ) : ContentProblem
}
