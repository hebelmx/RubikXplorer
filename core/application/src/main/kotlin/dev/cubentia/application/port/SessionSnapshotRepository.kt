package dev.cubentia.application.port

import dev.cubentia.application.session.AttemptRecord
import dev.cubentia.application.session.SessionSnapshot

interface SessionSnapshotRepository {
    suspend fun save(
        snapshot: SessionSnapshot,
        attempts: List<AttemptRecord> = emptyList(),
    ): SnapshotSave

    /** Reset progress only; ordinary session end saves an ended snapshot. */
    suspend fun clear(attempts: List<AttemptRecord> = emptyList()): SnapshotSave

    /** Reports an ended latest row as [SnapshotLoad.None]. */
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
