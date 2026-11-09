package dev.cubentia.application.usecase

import dev.cubentia.application.port.ProgressLoad
import dev.cubentia.application.port.ProgressRecord
import dev.cubentia.application.port.ProgressRepository
import dev.cubentia.application.port.ProgressSave
import dev.cubentia.application.port.ProgressWrite
import dev.cubentia.domain.content.Stage
import dev.cubentia.domain.content.StageId
import dev.cubentia.domain.tutor.ProgressEvent
import dev.cubentia.domain.tutor.StageProgress
import dev.cubentia.domain.tutor.StageStatus
import kotlin.time.Instant

/**
 * Single writer for [ProgressEvent.StageSkippedByPlacement] (FR-19, AD-10, AR-14): marks each
 * earlier Stage before [targetStageId] that is `LOCKED` or `IN_PROGRESS` as skipped by placement
 * (never `MASTERED`) and unlocks [targetStageId].
 */
class PlaceFromScan(
    private val repository: ProgressRepository,
) {
    suspend operator fun invoke(
        targetStageId: StageId,
        stages: List<Stage>,
        at: Instant = Instant.fromEpochMilliseconds(0),
        sessionId: String = "",
        revision: Long = -1L,
    ): ProgressWrite {
        val targetIndex = stages.indexOfFirst { it.id == targetStageId }
        if (targetIndex < 0) return ProgressWrite.UnknownStage(targetStageId)

        val loaded =
            when (val result = repository.all()) {
                is ProgressLoad.Found -> result
                ProgressLoad.Failed -> return ProgressWrite.Failed
            }
        val existing = loaded.events
        val epochEvents = StageProgress.currentEpochEvents(existing)
        val skippedEvents =
            stages
                .subList(0, targetIndex)
                .filter { earlier ->
                    val status = StageProgress.status(earlier, stages, existing)
                    status == StageStatus.LOCKED || status == StageStatus.IN_PROGRESS
                }.map { earlier -> ProgressEvent.StageSkippedByPlacement(earlier.id, at) }
        val targetStage = stages[targetIndex]
        val needsUnlock =
            StageProgress.status(targetStage, stages, existing) == StageStatus.LOCKED ||
                (targetIndex > 0 && epochEvents.none { it is ProgressEvent.StageUnlocked && it.stageId == targetStageId })
        val unlockedEvents =
            if (needsUnlock) {
                listOf(ProgressEvent.StageUnlocked(targetStageId, at))
            } else {
                emptyList()
            }
        val newEvents = skippedEvents + unlockedEvents
        if (newEvents.isEmpty()) return ProgressWrite.Written(emptyList())
        val epoch = StageProgress.currentEpoch(existing)
        val actualSessionId = sessionId.ifEmpty { "placement:${targetStageId.value}:epoch-$epoch" }
        val actualRevision = if (revision >= 0L) revision else 0L
        val records = newEvents.map { event -> ProgressRecord(actualSessionId, actualRevision, event) }
        return when (repository.append(records)) {
            ProgressSave.Saved -> ProgressWrite.Written(newEvents)
            ProgressSave.Failed -> ProgressWrite.Failed
        }
    }
}
