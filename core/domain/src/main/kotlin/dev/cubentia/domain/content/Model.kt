package dev.cubentia.domain.content

import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.predicate.Predicate
import kotlinx.serialization.Serializable

/**
 * The content manifest, `content/bundle.json`: the [schemaVersion] it was written for, the Stage
 * files in teaching order and the [algorithms] files, as paths relative to `content/`.
 */
@Serializable
data class BundleManifest(
    val schemaVersion: Int,
    val stages: List<String>,
    val algorithms: List<String>,
    val twoLook: List<String> = emptyList(),
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
 * - [moveSet]: the Moves it tries, as Notation (also the Moves the `RecoveryPlanner` searches);
 * - [recoveryMaxDepth]: the longest Guided recovery the `RecoveryPlanner` searches for (1..8);
 * - [adaptive]: the thresholds of adaptive assistance (`AdaptivePolicy`, FR-13);
 * - [mastery]: the thresholds required to master a Stage (FR-4, FR-20, AD-6, UX Flow 7).
 */
@Serializable
data class TutorConfig(
    val maxDepth: Int,
    val moveSet: String,
    val recoveryMaxDepth: Int,
    val adaptive: AdaptiveThresholds,
    val mastery: MasteryThresholds,
) {
    /** [moveSet] parsed; empty when it is not valid Notation (which `ContentValidator` rejects). */
    val moves: List<Move>
        get() = (Notation.parse(moveSet) as? NotationResult.Parsed)?.moves.orEmpty()
}

/**
 * The thresholds required to mark a Stage mastered (FR-4, FR-20, AD-6, UX Flow 7): data in
 * `tutor-config.json`, validated by `ContentValidator`.
 *
 * - [solvesRequired]: completed attempts of the Stage Objective required (1..10);
 * - [reducedHelpRequired]: of those completed attempts, how many must use reduced assistance (0..10, <= [solvesRequired]).
 */
@Serializable
data class MasteryThresholds(
    val solvesRequired: Int,
    val reducedHelpRequired: Int,
)

/**
 * When adaptive assistance (`AdaptivePolicy`, FR-13) changes the help on the shown step: data in
 * `tutor-config.json`, each in 1..10, with [explainAfterMistakes] < [demonstrateAfterMistakes] <
 * [guideAfterMistakes] (`ContentValidator` checks them).
 *
 * - [explainAfterMistakes]: Harmful Moves on a step before the tutor explains and outlines the piece;
 * - [demonstrateAfterMistakes]: before it shows the step's Moves;
 * - [guideAfterMistakes]: before it guides one Move at a time;
 * - [reduceAfterCleanSteps]: steps in a row completed with no mistake and no Hint before it offers fewer Hints.
 */
@Serializable
data class AdaptiveThresholds(
    val explainAfterMistakes: Int,
    val demonstrateAfterMistakes: Int,
    val guideAfterMistakes: Int,
    val reduceAfterCleanSteps: Int,
)

/** The last-layer step a [Case] belongs to. */
@Serializable
enum class CaseSet { OLL, PLL }

/**
 * A last-layer Case: its [set], its [family] and the [name] the Library shows. The Case state is
 * derived, never stored here (AD-19).
 */
@Serializable
data class Case(
    val id: CaseId,
    val set: CaseSet,
    val family: FamilyId,
    val name: TextKey,
)

/** An Algorithm for the Case [caseId], its Moves written as [notation]. */
@Serializable
data class Algorithm(
    val id: AlgorithmId,
    val caseId: CaseId,
    val notation: String,
) {
    /** True for the `-a` Algorithm, the Case's primary one. */
    val isPrimary: Boolean get() = id.value.endsWith(PRIMARY_SUFFIX)

    private companion object {
        const val PRIMARY_SUFFIX = "-a"
    }
}

/** One file under `content/algorithms/`: its [cases] and their [algorithms]. */
@Serializable
data class AlgorithmFile(
    val cases: List<Case>,
    val algorithms: List<Algorithm>,
)

/**
 * Loaded, validated content: the manifest's [schemaVersion], a [contentRevision] hash of the exact
 * bytes read, the [stages] in manifest order, the English [strings] (`key → ICU message`), the
 * [tutorConfig], and the [cases] and [algorithms] of every Algorithm file in manifest order.
 */
data class ContentBundle(
    val schemaVersion: Int,
    val contentRevision: String,
    val stages: List<Stage>,
    val strings: Map<String, String>,
    val tutorConfig: TutorConfig,
    val cases: List<Case>,
    val algorithms: List<Algorithm>,
    val twoLookStages: List<Stage> = emptyList(),
    val lessonAlgorithms: Map<LessonId, List<AlgorithmId>> = emptyMap(),
)
