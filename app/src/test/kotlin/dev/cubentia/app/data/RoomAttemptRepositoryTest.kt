package dev.cubentia.app.data

import android.app.Application
import androidx.room3.Room
import androidx.room3.withWriteTransaction
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.cubentia.app.session.shippedTutorConfig
import dev.cubentia.app.session.whiteCrossLesson
import dev.cubentia.application.port.AttemptLoad
import dev.cubentia.application.port.SnapshotLoad
import dev.cubentia.application.port.SnapshotSave
import dev.cubentia.application.session.AttemptRecord
import dev.cubentia.application.session.Effect
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.application.session.SessionSnapshot
import dev.cubentia.application.session.SessionState
import dev.cubentia.application.session.reduce
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RoomAttemptRepositoryTest {
    @Test fun appendOnlyAtomicStaleAndFailedRead() =
        runBlocking {
            val db =
                Room
                    .inMemoryDatabaseBuilder<RubikDatabase>(RuntimeEnvironment.getApplication())
                    .setDriver(AndroidSQLiteDriver())
                    .build()
            val repo = RoomSessionSnapshotRepository(db)
            val log = RoomAttemptRepository(db)
            val start =
                reduce(
                    SessionState.IDLE,
                    SessionEvent.LessonStarted(whiteCrossLesson(), shippedTutorConfig, Instant.fromEpochMilliseconds(0)),
                )
            val snapshot = checkNotNull(SessionSnapshot.of(start.state, "s", "v1")).copy(revision = 9)
            val row = AttemptRecord.of(start.effects.filterIsInstance<Effect.AttemptStarted>().single(), "s", "a")
            try {
                assertEquals(SnapshotSave.Saved, repo.save(snapshot, listOf(row)))
                assertEquals(SnapshotLoad.Found(snapshot), repo.latest())
                assertEquals(SnapshotSave.Saved, repo.save(snapshot, listOf(row.copy(objectiveId = "changed"))))
                assertEquals(AttemptLoad.Found(listOf(row)), log.all())
                val older = row.copy(revision = 0)
                assertEquals(SnapshotSave.Stale, repo.save(snapshot.copy(revision = 8), listOf(older)))
                assertEquals(AttemptLoad.Found(listOf(row, older)), log.all())
                assertEquals(SnapshotLoad.Found(snapshot), repo.latest())
                db.withWriteTransaction {
                    usePrepared("DROP TABLE attempt") {
                        it.step()
                        Unit
                    }
                }
                assertEquals(SnapshotSave.Failed, repo.save(snapshot.copy(revision = 10), listOf(row.copy(revision = 10))))
                assertEquals(SnapshotLoad.Found(snapshot), repo.latest())
                assertEquals(AttemptLoad.Failed, log.all())
            } finally {
                db.close()
            }
        }

    @Test fun snapshotFailureRollsBackInsertedRows() =
        runBlocking {
            val db =
                Room
                    .inMemoryDatabaseBuilder<RubikDatabase>(RuntimeEnvironment.getApplication())
                    .setDriver(AndroidSQLiteDriver())
                    .build()
            try {
                val start =
                    reduce(
                        SessionState.IDLE,
                        SessionEvent.LessonStarted(whiteCrossLesson(), shippedTutorConfig, Instant.fromEpochMilliseconds(0)),
                    )
                val snapshot = checkNotNull(SessionSnapshot.of(start.state, "s", "v1"))
                val row = AttemptRecord.of(start.effects.filterIsInstance<Effect.AttemptStarted>().single(), "s", "a")
                db.withWriteTransaction {
                    usePrepared("DROP TABLE session_snapshot") {
                        it.step()
                        Unit
                    }
                }
                assertEquals(SnapshotSave.Failed, RoomSessionSnapshotRepository(db).save(snapshot, listOf(row)))
                assertEquals(AttemptLoad.Found(emptyList<AttemptRecord>()), RoomAttemptRepository(db).all())
            } finally {
                db.close()
            }
        }
}
