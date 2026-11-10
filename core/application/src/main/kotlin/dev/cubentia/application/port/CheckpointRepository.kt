package dev.cubentia.application.port

import dev.cubentia.application.session.Checkpoint
import dev.cubentia.application.session.SessionKind

interface CheckpointRepository {
    suspend fun save(checkpoint: Checkpoint): CheckpointSave

    suspend fun clear(): CheckpointSave

    suspend fun find(kind: SessionKind): CheckpointLoad
}

sealed interface CheckpointSave {
    data object Saved : CheckpointSave

    data object Failed : CheckpointSave
}

sealed interface CheckpointLoad {
    data class Found(
        val checkpoint: Checkpoint,
    ) : CheckpointLoad

    data object None : CheckpointLoad

    data object Failed : CheckpointLoad
}
