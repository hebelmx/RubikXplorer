package dev.rubikxplorer.app.data

import dev.rubikxplorer.application.port.ProgressLoad
import dev.rubikxplorer.application.port.ProgressRecord
import dev.rubikxplorer.application.port.ProgressRepository
import dev.rubikxplorer.application.port.ProgressSave
import kotlinx.coroutines.CancellationException

class RoomProgressRepository(
    private val database: RubikDatabase,
) : ProgressRepository {
    override suspend fun all(): ProgressLoad =
        try {
            val records = database.progress().all().map { it.record() ?: return ProgressLoad.Failed }
            ProgressLoad.Found(records.map { it.event }, records)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ProgressLoad.Failed
        }

    override suspend fun append(records: List<ProgressRecord>): ProgressSave =
        try {
            if (records.isNotEmpty()) {
                database.progress().insert(records.map(ProgressEventEntity::of))
            }
            ProgressSave.Saved
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ProgressSave.Failed
        }
}
