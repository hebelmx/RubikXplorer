package dev.rubikxplorer.application.port

import dev.rubikxplorer.application.session.SessionSnapshot

interface SessionSnapshotRepository {
    suspend fun save(snapshot: SessionSnapshot): SnapshotSave

    suspend fun latest(): SnapshotLoad
}

sealed interface SnapshotSave {
    data object Saved : SnapshotSave

    data object Stale : SnapshotSave

    data object Failed : SnapshotSave
}

sealed interface SnapshotLoad {
    data class Found(
        val snapshot: SessionSnapshot,
    ) : SnapshotLoad

    data object None : SnapshotLoad

    data object Failed : SnapshotLoad
}
