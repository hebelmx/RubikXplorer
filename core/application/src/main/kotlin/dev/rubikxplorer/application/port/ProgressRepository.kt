package dev.rubikxplorer.application.port

import dev.rubikxplorer.domain.content.StageId
import dev.rubikxplorer.domain.tutor.ProgressEvent

/**
 * An append-only `progress_event` row keyed by `([sessionId], [revision], type, stageId, lessonId)`
 * for idempotent replay (AD-10, AD-12, AR-14, AR-21).
 */
data class ProgressRecord(
    val sessionId: String,
    val revision: Long,
    val event: ProgressEvent,
)

/**
 * Port for the append-only `progress_event` log (AD-10, AR-14).
 *
 * Only `CompleteLesson`, `PassSkipCheck`, `PlaceFromScan`, and `ResetProgress` may call [append].
 */
interface ProgressRepository {
    suspend fun all(): ProgressLoad

    suspend fun append(records: List<ProgressRecord>): ProgressSave
}

sealed interface ProgressLoad {
    data class Found(
        val events: List<ProgressEvent>,
        val records: List<ProgressRecord> = emptyList(),
    ) : ProgressLoad

    data object Failed : ProgressLoad
}

sealed interface ProgressSave {
    data object Saved : ProgressSave

    data object Failed : ProgressSave
}

sealed interface ProgressWrite {
    data class Written(
        val events: List<ProgressEvent>,
    ) : ProgressWrite

    data object PredicateNotMet : ProgressWrite

    data class UnknownStage(
        val stageId: StageId,
    ) : ProgressWrite

    data object Failed : ProgressWrite
}
