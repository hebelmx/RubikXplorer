package dev.rubikxplorer.domain.tutor

import dev.rubikxplorer.domain.content.Stage
import dev.rubikxplorer.domain.content.StageId

/**
 * Trend evaluation for a Stage across attempts in the active epoch (FR-19, FR-20, AD-10, UX-DR18, UX-DR28).
 *
 * Exposes the count of ended attempts and chronological hint counts for sparkline rendering.
 * Total hints is never exposed as a score.
 */
data class StageTrend(
    val stageId: StageId,
    val attemptsCount: Int,
    val hintCounts: List<Int>,
)

/**
 * Pure evaluation of Stage hint-use trends over attempts and progress events (FR-19, FR-20, AD-10, UX-DR18, UX-DR28).
 */
object StageTrendCalculator {
    /**
     * Checks if [attempt] counts toward [stage].
     * An attempt matches if its [MasteryAttempt.objectiveId] matches:
     * - `"practice-${stage.id.value}"`
     * - `stage.id.value`
     * - Any lesson ID in [stage.lessons]
     */
    fun matchesStage(
        attempt: MasteryAttempt,
        stage: Stage,
    ): Boolean {
        val stageIdVal = stage.id.value
        if (attempt.objectiveId == "practice-$stageIdVal") return true
        if (attempt.objectiveId == stageIdVal) return true
        if (stage.lessons.any { it.id.value == attempt.objectiveId }) return true
        return false
    }

    /**
     * Evaluates the hint trend for [stage] across [epochAttempts] that have ended.
     */
    fun evaluate(
        stage: Stage,
        epochAttempts: List<MasteryAttempt>,
    ): StageTrend {
        val matching =
            epochAttempts
                .filter { matchesStage(it, stage) && it.outcome != null }
                .sortedBy { it.at }
        return StageTrend(
            stageId = stage.id,
            attemptsCount = matching.size,
            hintCounts = matching.map { it.hints ?: 0 },
        )
    }

    /**
     * Evaluates the hint trend for [stage] using [attempts] filtered to the active epoch determined by [events].
     */
    fun evaluate(
        stage: Stage,
        attempts: List<MasteryAttempt>,
        events: List<ProgressEvent>,
    ): StageTrend = evaluate(stage, Mastery.currentEpochAttempts(attempts, events))
}
