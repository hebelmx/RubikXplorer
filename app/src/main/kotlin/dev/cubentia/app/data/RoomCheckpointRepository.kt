package dev.cubentia.app.data

import dev.cubentia.application.port.CheckpointLoad
import dev.cubentia.application.port.CheckpointRepository
import dev.cubentia.application.port.CheckpointSave
import dev.cubentia.application.session.Checkpoint
import dev.cubentia.application.session.SessionKind
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

    override suspend fun clear(): CheckpointSave =
        try {
            database.checkpoints().clear()
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
