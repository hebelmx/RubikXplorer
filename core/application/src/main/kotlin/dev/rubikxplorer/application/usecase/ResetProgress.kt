package dev.rubikxplorer.application.usecase

import dev.rubikxplorer.application.port.ProgressLoad
import dev.rubikxplorer.application.port.ProgressRecord
import dev.rubikxplorer.application.port.ProgressRepository
import dev.rubikxplorer.application.port.ProgressSave
import dev.rubikxplorer.application.port.ProgressWrite
import dev.rubikxplorer.domain.tutor.ProgressEvent
import dev.rubikxplorer.domain.tutor.StageProgress
import kotlin.time.Instant

/**
 * Single writer for [ProgressEvent.ProgressReset] (AD-10, AR-14, AR-21): appends a new epoch event
 * without deleting or updating existing `progress_event` rows.
 */
class ResetProgress(
    private val repository: ProgressRepository,
) {
    suspend operator fun invoke(
        at: Instant = Instant.fromEpochMilliseconds(0),
        sessionId: String = "",
        revision: Long = -1L,
    ): ProgressWrite {
        val loaded =
            when (val result = repository.all()) {
                is ProgressLoad.Found -> result
                ProgressLoad.Failed -> return ProgressWrite.Failed
            }
        val nextEpoch = StageProgress.currentEpoch(loaded.events) + 1L
        val event = ProgressEvent.ProgressReset(nextEpoch, at)
        val actualSessionId = sessionId.ifEmpty { "reset:epoch-$nextEpoch" }
        val actualRevision = if (revision >= 0L) revision else 0L
        return when (repository.append(listOf(ProgressRecord(actualSessionId, actualRevision, event)))) {
            ProgressSave.Saved -> ProgressWrite.Written(listOf(event))
            ProgressSave.Failed -> ProgressWrite.Failed
        }
    }
}
