package dev.cubentia.app.data

import dev.cubentia.application.port.AttemptLoad
import dev.cubentia.application.port.AttemptRepository
import kotlinx.coroutines.CancellationException

class RoomAttemptRepository(
    private val database: RubikDatabase,
) : AttemptRepository {
    override suspend fun all(): AttemptLoad =
        try {
            AttemptLoad.Found(database.attempts().all().map { it.record() })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AttemptLoad.Failed
        }
}
