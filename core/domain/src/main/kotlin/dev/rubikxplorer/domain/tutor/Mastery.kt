package dev.rubikxplorer.domain.tutor

import dev.rubikxplorer.domain.content.MasteryThresholds
import dev.rubikxplorer.domain.content.Stage
import dev.rubikxplorer.domain.content.StageId

/**
 * Progress toward Stage mastery (FR-4, FR-20, AD-6, UX Flow 7).
 *
 * Evaluated purely over attempt records in the active epoch against [MasteryThresholds].
 */
data class StageMasteryProgress(
    val stageId: StageId,
    val solvesCompleted: Int,
    val solvesRequired: Int,
    val reducedHelpSolves: Int,
    val reducedHelpRequired: Int,
    val isMastered: Boolean,
)

/**
 * Pure evaluation of Stage mastery and attempt qualification (FR-4, FR-20, AD-6, AD-10, UX Flow 7).
 */
object Mastery {
    /**
     * Filters [attempts] to those belonging to the active epoch in [events].
     * If [events] contains a [ProgressEvent.ProgressReset], only attempts at or after
     * the latest reset's timestamp are considered.
     */
    fun currentEpochAttempts(
        attempts: List<MasteryAttempt>,
        events: List<ProgressEvent>,
    ): List<MasteryAttempt> {
        val lastReset = events.lastOrNull { it is ProgressEvent.ProgressReset } as? ProgressEvent.ProgressReset
        return if (lastReset == null) {
            attempts
        } else {
            attempts.filter { it.at > lastReset.at }
        }
    }

    /**
     * Checks if [attempt] counts toward [stage].
     * An attempt matches if its [MasteryAttempt.objectiveId] matches:
     * - `"practice-${stage.id.value}"`
     * - `stage.id.value`
     * - `stage.lessons.last().id.value` (when the Stage has authored Lessons)
     */
    fun matchesStage(
        attempt: MasteryAttempt,
        stage: Stage,
    ): Boolean {
        val stageIdVal = stage.id.value
        if (attempt.objectiveId == "practice-$stageIdVal") return true
        if (attempt.objectiveId == stageIdVal) return true
        if (stage.lessons.isNotEmpty() && attempt.objectiveId ==
            stage.lessons
                .last()
                .id.value
        ) {
            return true
        }
        return false
    }

    /**
     * Evaluates mastery progress for [stage] against [attempts] and [config].
     *
     * Note: [attempts] should already be filtered to the active epoch, or pass [events]
     * using the overload.
     */
    fun evaluate(
        stage: Stage,
        attempts: List<MasteryAttempt>,
        config: MasteryThresholds,
    ): StageMasteryProgress {
        val stageAttempts = attempts.filter { matchesStage(it, stage) }
        val completedAttempts = stageAttempts.filter { it.outcome == AttemptOutcome.COMPLETED }
        val solvesCompleted = completedAttempts.size
        val reducedHelpSolves =
            completedAttempts.count {
                val level = it.assistanceLevel
                level != null && level <= AssistanceLevel.REDUCED
            }
        val isMastered = solvesCompleted >= config.solvesRequired && reducedHelpSolves >= config.reducedHelpRequired
        return StageMasteryProgress(
            stageId = stage.id,
            solvesCompleted = solvesCompleted,
            solvesRequired = config.solvesRequired,
            reducedHelpSolves = reducedHelpSolves,
            reducedHelpRequired = config.reducedHelpRequired,
            isMastered = isMastered,
        )
    }

    /**
     * Evaluates mastery progress for [stage] using [attempts], [events] to determine active epoch, and [config].
     */
    fun evaluate(
        stage: Stage,
        attempts: List<MasteryAttempt>,
        events: List<ProgressEvent>,
        config: MasteryThresholds,
    ): StageMasteryProgress = evaluate(stage, currentEpochAttempts(attempts, events), config)

    /**
     * Derives the set of mastered Stage IDs for [stages] in the current epoch.
     */
    fun masteredStages(
        stages: List<Stage>,
        attempts: List<MasteryAttempt>,
        events: List<ProgressEvent>,
        config: MasteryThresholds,
    ): Set<StageId> {
        val epochAttempts = currentEpochAttempts(attempts, events)
        return stages
            .filter { stage -> evaluate(stage, epochAttempts, config).isMastered }
            .mapTo(mutableSetOf()) { it.id }
    }

    /**
     * Derives the set of mastered Stage IDs for [stages] from already-filtered [epochAttempts] and [config].
     */
    fun masteredStages(
        stages: List<Stage>,
        epochAttempts: List<MasteryAttempt>,
        config: MasteryThresholds,
    ): Set<StageId> =
        stages
            .filter { stage -> evaluate(stage, epochAttempts, config).isMastered }
            .mapTo(mutableSetOf()) { it.id }
}
