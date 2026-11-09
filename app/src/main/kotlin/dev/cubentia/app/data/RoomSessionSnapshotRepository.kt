package dev.cubentia.app.data

import androidx.room3.withWriteTransaction
import dev.cubentia.application.port.SessionSnapshotRepository
import dev.cubentia.application.port.SnapshotLoad
import dev.cubentia.application.port.SnapshotSave
import dev.cubentia.application.session.AttemptRecord
import dev.cubentia.application.session.SessionSnapshot
import kotlinx.coroutines.CancellationException

class RoomSessionSnapshotRepository(
    private val database: RubikDatabase,
) : SessionSnapshotRepository {
    override suspend fun save(
        snapshot: SessionSnapshot,
        attempts: List<AttemptRecord>,
    ): SnapshotSave =
        try {
            database.withWriteTransaction {
                database.attempts().insert(attempts.map(AttemptEntity::of))
                val dao = database.snapshots()
                val stored = dao.find(snapshot.kind.name)
                if (stored != null && stored.sessionId == snapshot.sessionId && stored.revision > snapshot.revision) {
                    SnapshotSave.Stale
                } else {
                    dao.replace(
                        SessionSnapshotEntity(
                            snapshot.kind.name,
                            snapshot.sessionId,
                            snapshot.revision,
                            dao.sequence() + 1,
                            SnapshotPayload.encode(snapshot),
                        ),
                    )
                    SnapshotSave.Saved
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SnapshotSave.Failed
        }

    override suspend fun latest(): SnapshotLoad =
        try {
            val row = database.snapshots().latest()
            if (row == null) {
                SnapshotLoad.None
            } else {
                val snapshot = SnapshotPayload.decode(row.payload)
                require(snapshot.kind.name == row.kind && snapshot.sessionId == row.sessionId && snapshot.revision == row.revision)
                SnapshotLoad.Found(snapshot)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SnapshotLoad.Failed
        }
}
