package dev.cubentia.app.data

import dev.cubentia.application.port.AlgorithmStatusRepository
import dev.cubentia.application.port.AlgorithmStatusSave
import dev.cubentia.domain.content.AlgorithmStatusEvent
import dev.cubentia.domain.content.AlgorithmStatuses
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

class RoomAlgorithmStatusRepository(
    private val database: RubikDatabase,
) : AlgorithmStatusRepository {
    override fun statuses() =
        database
            .algorithmStatuses()
            .currentEpoch()
            .map { rows -> AlgorithmStatuses.current(rows.mapNotNull { it.event() }) }
            .catch { failure ->
                if (failure is CancellationException) throw failure
                emit(emptyMap())
            }

    override suspend fun append(event: AlgorithmStatusEvent): AlgorithmStatusSave =
        try {
            database.algorithmStatuses().insert(event.algorithmId.value, event.status.name, event.at.toEpochMilliseconds())
            AlgorithmStatusSave.Saved
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AlgorithmStatusSave.Failed
        }
}
