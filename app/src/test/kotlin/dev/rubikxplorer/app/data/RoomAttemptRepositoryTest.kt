package dev.rubikxplorer.app.data

import android.app.Application
import androidx.room3.Room
import androidx.room3.withWriteTransaction
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.rubikxplorer.app.session.shippedTutorConfig
import dev.rubikxplorer.app.session.whiteCrossLesson
import dev.rubikxplorer.application.port.AttemptLoad
import dev.rubikxplorer.application.port.SnapshotLoad
import dev.rubikxplorer.application.port.SnapshotSave
import dev.rubikxplorer.application.session.AttemptRecord
import dev.rubikxplorer.application.session.Effect
import dev.rubikxplorer.application.session.SessionEvent
import dev.rubikxplorer.application.session.SessionSnapshot
import dev.rubikxplorer.application.session.SessionState
import dev.rubikxplorer.application.session.reduce
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
            val row = AttemptRecord.of(start.effects.filterIsInstance<Effect.AttemptStarted>().single(), "s")
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
                val row = AttemptRecord.of(start.effects.filterIsInstance<Effect.AttemptStarted>().single(), "s")
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
