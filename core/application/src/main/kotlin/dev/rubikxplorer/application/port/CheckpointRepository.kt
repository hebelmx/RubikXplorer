package dev.rubikxplorer.application.port

import dev.rubikxplorer.application.session.Checkpoint
import dev.rubikxplorer.application.session.SessionKind

interface CheckpointRepository {
    suspend fun save(checkpoint: Checkpoint): CheckpointSave

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
