package dev.cubentia.app.data

import dev.cubentia.application.port.AlgorithmStatusLoad
import dev.cubentia.application.port.AlgorithmStatusRecord
import dev.cubentia.application.port.AlgorithmStatusRepository
import dev.cubentia.application.port.AlgorithmStatusSave
import dev.cubentia.domain.content.AlgorithmStatuses
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

class RoomAlgorithmStatusRepository(
    private val database: RubikDatabase,
) : AlgorithmStatusRepository {
    override fun statuses() =
        database
            .algorithmStatuses()
            .currentEpoch()
            .map<_, AlgorithmStatusLoad> { rows -> AlgorithmStatusLoad.Loaded(AlgorithmStatuses.current(rows.mapNotNull { it.event() })) }
            .retryWhen { failure, attempt ->
                if (failure is CancellationException) throw failure
                emit(AlgorithmStatusLoad.Failed)
                delay(100L * (1L shl attempt.coerceAtMost(5).toInt()))
                true
            }

    override suspend fun append(record: AlgorithmStatusRecord): AlgorithmStatusSave =
        try {
            val event = record.event
            database
                .algorithmStatuses()
                .insert(record.sessionId, record.revision, event.algorithmId.value, event.status.name, event.at.toEpochMilliseconds())
            AlgorithmStatusSave.Saved
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AlgorithmStatusSave.Failed
        }
}
