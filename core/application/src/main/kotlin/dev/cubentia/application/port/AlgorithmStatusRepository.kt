package dev.cubentia.application.port

import dev.cubentia.domain.content.AlgorithmId
import dev.cubentia.domain.content.AlgorithmStatus
import dev.cubentia.domain.content.AlgorithmStatusEvent
import kotlinx.coroutines.flow.Flow

interface AlgorithmStatusRepository {
    fun statuses(): Flow<Map<AlgorithmId, AlgorithmStatus>>

    suspend fun append(event: AlgorithmStatusEvent): AlgorithmStatusSave
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
