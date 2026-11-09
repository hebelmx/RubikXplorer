package dev.cubentia.application.port

import dev.cubentia.domain.content.AlgorithmId
import dev.cubentia.domain.content.AlgorithmStatus
import dev.cubentia.domain.content.AlgorithmStatusEvent
import kotlinx.coroutines.flow.Flow

/**
 * An append-only `algorithm_status_event` row keyed by `([sessionId], [revision])` for idempotent
 * replay (AD-10). The key names one Learner command, so a replay of that command adds no row.
 */
data class AlgorithmStatusRecord(
    val sessionId: String,
    val revision: Long,
    val event: AlgorithmStatusEvent,
)

interface AlgorithmStatusRepository {
    fun statuses(): Flow<Map<AlgorithmId, AlgorithmStatus>>

    suspend fun append(record: AlgorithmStatusRecord): AlgorithmStatusSave
}

sealed interface AlgorithmStatusSave {
    data object Saved : AlgorithmStatusSave

    data object Failed : AlgorithmStatusSave
}

sealed interface AlgorithmStatusWrite {
    data class Written(
        val event: AlgorithmStatusEvent,
    ) : AlgorithmStatusWrite

    data object Failed : AlgorithmStatusWrite
}
