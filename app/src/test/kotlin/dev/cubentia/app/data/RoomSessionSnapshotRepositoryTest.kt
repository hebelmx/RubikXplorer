package dev.cubentia.app.data

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.cubentia.app.session.shippedTutorConfig
import dev.cubentia.app.session.whiteCrossLesson
import dev.cubentia.application.port.SnapshotLoad
import dev.cubentia.application.port.SnapshotSave
import dev.cubentia.application.session.CubeMode
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.application.session.SessionKind
import dev.cubentia.application.session.SessionSnapshot
import dev.cubentia.application.session.SessionState
import dev.cubentia.application.session.reduce
import dev.cubentia.domain.tutor.AssistanceLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RoomSessionSnapshotRepositoryTest {
    @Test fun conditionalWritesAndLatestSequence() =
        runBlocking {
            val db =
                Room
                    .inMemoryDatabaseBuilder<RubikDatabase>(RuntimeEnvironment.getApplication())
                    .setDriver(AndroidSQLiteDriver())
                    .build()
            val repo = RoomSessionSnapshotRepository(db)
            val state =
                reduce(
                    SessionState.IDLE,
                    SessionEvent.LessonStarted(whiteCrossLesson(), shippedTutorConfig, Instant.fromEpochMilliseconds(0)),
                ).state
            val saved = checkNotNull(SessionSnapshot.of(state, "a", "v1")).copy(revision = 9)
            try {
                assertEquals(SnapshotLoad.None, repo.latest())
                assertEquals(SnapshotSave.Saved, repo.save(saved))
                val sequence = db.snapshots().sequence()
                assertEquals(SnapshotSave.Stale, repo.save(saved.copy(revision = 8)))
                assertEquals(sequence, db.snapshots().sequence())
                assertEquals(SnapshotLoad.Found(saved), repo.latest())
                assertEquals(SnapshotSave.Saved, repo.save(saved.copy(sessionId = "b", revision = 1)))
                val playback =
                    saved.copy(
                        kind = SessionKind.SOLVE_PLAYBACK,
                        sessionId = "c",
                        revision = 1,
                        lessonId = null,
                        contentRevision = null,
                    )
                assertEquals(SnapshotSave.Saved, repo.save(playback))
                assertEquals(SnapshotLoad.Found(playback), repo.latest())
                val newer = saved.copy(sessionId = "d", revision = 1)
                repo.save(newer)
                assertEquals(SnapshotLoad.Found(newer), repo.latest())
                val previousSequence = db.snapshots().sequence()
                repo.save(newer.copy(hintsUsed = 2)) // Tutor-only transitions share a revision and must still save.
                assertTrue(db.snapshots().sequence() > previousSequence)
                assertEquals(2, (repo.latest() as SnapshotLoad.Found).snapshot.hintsUsed)
                // Every tutor collection survives the JSON payload, not only empty ones.
                val tutored =
                    newer.copy(
                        noticeStep = 1,
                        stepMistakes = mapOf(0 to 2, 1 to 1),
                        cleanSteps = 1,
                        cleanCredited = setOf(0),
                        stepHelped = setOf(1),
                        assistanceDismissed = mapOf(1 to AssistanceLevel.EXPLAIN),
                        attemptOpen = true,
                        attemptAssistance = AssistanceLevel.GUIDE,
                        attemptId = "attempt-1",
                    )
                repo.save(tutored)
                assertEquals(SnapshotLoad.Found(tutored), repo.latest())
                val legacy =
                    SnapshotPayload
                        .encode(
                            tutored,
                        ).replace(Regex("\"attemptOpen\":(?:true|false),"), "")
                        .replace(Regex("\"attemptAssistance\":\"[A-Z]+\","), "")
                        .replace(Regex("\"attemptId\":\"[^\"]*\","), "")
                        .replace(Regex("\"mode\":\"[^\"]*\","), "")
                assertEquals(
                    tutored.copy(attemptOpen = false, attemptAssistance = AssistanceLevel.USUAL, attemptId = null, mode = CubeMode.VIRTUAL),
                    SnapshotPayload.decode(legacy),
                )
                val row = checkNotNull(db.snapshots().latest())
                db.snapshots().replace(row.copy(payload = row.payload.replace("\"format\":1", "\"format\":99")))
                assertEquals(SnapshotLoad.Failed, repo.latest())
                db.snapshots().replace(row.copy(payload = row.payload.replace("\"format\":1,", "")))
                assertEquals(SnapshotLoad.Failed, repo.latest())
                db.snapshots().replace(row.copy(payload = "broken json"))
                assertEquals(SnapshotLoad.Failed, repo.latest())
            } finally {
                db.close()
            }
            assertEquals(SnapshotSave.Failed, repo.save(saved))
            assertEquals(SnapshotLoad.Failed, repo.latest())
        }
}
