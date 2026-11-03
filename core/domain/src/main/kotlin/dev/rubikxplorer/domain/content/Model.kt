package dev.rubikxplorer.domain.content

import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.NotationResult
import dev.rubikxplorer.domain.predicate.Predicate
import kotlinx.serialization.Serializable

/**
 * The content manifest, `content/bundle.json`: the [schemaVersion] it was written for and the Stage
 * files, as paths relative to `content/`, in teaching order.
 */
@Serializable
data class BundleManifest(
    val schemaVersion: Int,
    val stages: List<String>,
)

/** A Stage: a titled, ordered group of Lessons (one file under `content/stages/`). */
@Serializable
data class Stage(
    val id: StageId,
    val title: TextKey,
    val lessons: List<Lesson>,
)

/**
 * A Lesson (FR-4): every field is required, so a Lesson without a Demonstration, a Completion
 * Predicate or a Recovery Path cannot load.
 *
 * [entryState] is a 54-letter facelet string (see [FaceletString]); [demonstration] is Notation.
 * Completion is decided by [completion], never by counting Moves; [preserve] names what must stay solved.
 */
@Serializable
data class Lesson(
    val id: LessonId,
    val stageId: StageId,
    val prerequisites: List<LessonId>,
    val objective: TextKey,
    val entryState: String,
    val demonstration: String,
    val learnerAction: TextKey,
    val completion: Predicate,
    val preserve: Predicate,
    val recovery: RecoveryRef,
    val steps: List<Step>,
)

/**
 * One explained step of a Demonstration: Goal, Keep ([preserve]), Watch, then Notice after the [moves].
 * [done] says when the step's goal holds (AD-6): the tutor derives the current step from it off the
 * authored path, so the Learner may reach it in any order. The last step's [done] equals the Lesson's
 * `completion`, which alone decides that the Lesson is complete.
 */
@Serializable
data class Step(
    val goal: TextKey,
    val preserve: TextKey,
    val watch: TextKey,
    val notice: TextKey,
    val moves: String,
    val done: Predicate,
)

/**
 * The tutor's tunable values (AR-22), `content/tutor-config.json`: data, never code constants, so
 * they change without a release of the rules. Decoded strictly, so an unknown or missing key does not
 * load; `ContentValidator` checks the values.
 *
 * - [maxDepth]: the longest Move sequence the `CandidateEnumerator` tries (1..3);
 * - [moveSet]: the Moves it tries, as Notation.
 */
@Serializable
data class TutorConfig(
    val maxDepth: Int,
    val moveSet: String,
) {
    /** [moveSet] parsed; empty when it is not valid Notation (which `ContentValidator` rejects). */
    val moves: List<Move>
        get() = (Notation.parse(moveSet) as? NotationResult.Parsed)?.moves.orEmpty()
}

/**
 * Loaded, validated content: the manifest's [schemaVersion], a [contentRevision] hash of the exact
 * bytes read, the [stages] in manifest order, the English [strings] (`key → ICU message`) and the
 * [tutorConfig].
 */
data class ContentBundle(
    val schemaVersion: Int,
    val contentRevision: String,
    val stages: List<Stage>,
    val strings: Map<String, String>,
    val tutorConfig: TutorConfig,
)
