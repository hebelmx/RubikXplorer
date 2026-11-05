package dev.rubikxplorer.app.data

import dev.rubikxplorer.application.port.CheckpointLoad
import dev.rubikxplorer.application.port.CheckpointRepository
import dev.rubikxplorer.application.port.CheckpointSave
import dev.rubikxplorer.application.session.Checkpoint
import dev.rubikxplorer.application.session.SessionKind
import kotlinx.coroutines.CancellationException

class RoomCheckpointRepository(
    private val database: RubikDatabase,
) : CheckpointRepository {
    override suspend fun save(checkpoint: Checkpoint): CheckpointSave =
        try {
            database.checkpoints().replace(CheckpointEntity(checkpoint.kind.name, CheckpointPayload.encode(checkpoint)))
            CheckpointSave.Saved
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CheckpointSave.Failed
        }

    override suspend fun find(kind: SessionKind): CheckpointLoad =
        try {
            val row = database.checkpoints().find(kind.name)
            if (row == null) {
                CheckpointLoad.None
            } else {
                val checkpoint = CheckpointPayload.decode(row.payload)
                require(checkpoint.kind == kind)
                CheckpointLoad.Found(checkpoint)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CheckpointLoad.Failed
        }
}
