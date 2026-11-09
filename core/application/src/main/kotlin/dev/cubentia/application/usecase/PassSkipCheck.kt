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
 * Single writer for [ProgressEvent.StageSkippedByCheck] (FR-19, AD-10, AR-14): marks the passed
 * Stage(s) skipped (never `MASTERED`) and appends [ProgressEvent.StageUnlocked] for any newly
 * unlocked Stage, including the Stage immediately following a skipped Stage.
 */
class PassSkipCheck(
    private val repository: ProgressRepository,
) {
    suspend operator fun invoke(
        stageId: StageId,
        stages: List<Stage>,
        at: Instant = Instant.fromEpochMilliseconds(0),
        sessionId: String = "",
        revision: Long = -1L,
    ): ProgressWrite = invoke(listOf(stageId), stages, at, sessionId, revision)

    suspend operator fun invoke(
        stageIds: List<StageId>,
        stages: List<Stage>,
        at: Instant = Instant.fromEpochMilliseconds(0),
        sessionId: String = "",
        revision: Long = -1L,
    ): ProgressWrite {
        val knownIds = stages.mapTo(mutableSetOf()) { it.id }
        val unknown = stageIds.firstOrNull { it !in knownIds }
        if (unknown != null) return ProgressWrite.UnknownStage(unknown)
        if (stageIds.isEmpty()) return ProgressWrite.Written(emptyList())

        val loaded =
            when (val result = repository.all()) {
                is ProgressLoad.Found -> result
                ProgressLoad.Failed -> return ProgressWrite.Failed
            }
        val existing = loaded.events
        val skippedSet = stageIds.toSet()
        val skippedEvents = stageIds.distinct().map { ProgressEvent.StageSkippedByCheck(it, at) }
        val afterSkip = existing + skippedEvents
        val newlyUnlockedByPrerequisites = StageProgress.newlyUnlocked(stages, existing, afterSkip).toSet()
        val immediatelyFollowingLocked =
            stageIds
                .mapNotNull { id ->
                    val index = stages.indexOfFirst { it.id == id }
                    stages.getOrNull(index + 1)?.id
                }.filter { nextId ->
                    nextId !in skippedSet &&
                        StageProgress.status(stages.first { it.id == nextId }, stages, existing) == StageStatus.LOCKED
                }.toSet()
        val unlockedStageIds =
            stages
                .map { it.id }
                .filter { it in newlyUnlockedByPrerequisites || it in immediatelyFollowingLocked }
        val unlockedEvents = unlockedStageIds.map { ProgressEvent.StageUnlocked(it, at) }
        val newEvents = skippedEvents + unlockedEvents
        val epoch = StageProgress.currentEpoch(existing)
        val actualSessionId = sessionId.ifEmpty { "skip-check:${stageIds.joinToString(",") { it.value }}:epoch-$epoch" }
        val actualRevision = if (revision >= 0L) revision else 0L
        val records = newEvents.map { event -> ProgressRecord(actualSessionId, actualRevision, event) }
        return when (repository.append(records)) {
            ProgressSave.Saved -> ProgressWrite.Written(newEvents)
            ProgressSave.Failed -> ProgressWrite.Failed
        }
    }
}
