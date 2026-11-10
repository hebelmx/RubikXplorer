package dev.cubentia.app.data

import android.app.Application
import androidx.room3.Room
import androidx.room3.withReadTransaction
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.session.TickingClock
import dev.cubentia.application.port.SessionSnapshotRepository
import dev.cubentia.application.port.SnapshotLoad
import dev.cubentia.application.port.SnapshotSave
import dev.cubentia.application.session.AttemptOutcome
import dev.cubentia.application.session.AttemptRecord
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.application.session.SessionKind
import dev.cubentia.application.session.SessionSnapshot
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DurableSessionEndTest {
    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle

    @Test fun `ordinary end retains rows and checkpoint resumes`() =
        runBlocking {
            for (kind in listOf(SessionKind.LESSON, SessionKind.PRACTICE, SessionKind.TWO_LOOK)) {
                fixture { db, writer, room ->
                    val checkpoints = RoomCheckpointRepository(db)
                    val host =
                        SessionHost(
                            TickingClock(),
                            Dispatchers.Default.limitedParallelism(1),
                            room,
                            writer,
                            checkpoints = checkpoints,
                        )
                    host.restore(bundle)
                    val lesson = bundle.stages[1].lessons.first()
                    host.dispatch { SessionEvent.LessonStarted(lesson, bundle.tutorConfig, it) }
                    if (kind == SessionKind.PRACTICE) host.dispatch { SessionEvent.PracticeStarted(lesson, bundle.tutorConfig, it) }
                    if (kind == SessionKind.TWO_LOOK) {
                        val twoLook =
                            bundle.twoLookStages
                                .first()
                                .lessons
                                .first()
                        host.dispatch { SessionEvent.LessonStarted(twoLook, bundle.tutorConfig, it, kind = kind) }
                    }
                    writer.flush()
                    val before = (room.latest() as SnapshotLoad.Found).snapshot
                    assertEquals(kind, before.kind)
                    val count = count(db)
                    host.endSession()
                    writer.flush()
                    assertEquals(count, count(db))
                    val ended = SnapshotPayload.decode(checkNotNull(db.snapshots().find(kind.name)).payload)
                    assertTrue(ended.ended)
                    assertEquals(before.sessionId, ended.sessionId)
                    assertEquals(before.revision + 1, ended.revision)
                    assertFalse(ended.attemptOpen)
                    assertNull(ended.attemptId)
                    assertEquals(SnapshotLoad.None, room.latest())
                    assertTrue(db.attempts().all().any { it.record().outcome == AttemptOutcome.ABANDONED })
                    val events = CopyOnWriteArrayList<SessionEvent>()
                    val cold =
                        SessionHost(
                            TickingClock(),
                            Dispatchers.Default.limitedParallelism(1),
                            room,
                            writer,
                            checkpoints = checkpoints,
                            reduced = { events.add(it) },
                        )
                    cold.restore(bundle)
                    assertTrue(events.none { it is SessionEvent.SessionRestored })
                    assertTrue(events.any { it is SessionEvent.CheckpointRestored })
                    assertEquals(SessionKind.LESSON, cold.state.value.kind)
                }
            }
        }

    @Test fun `end failures retry before writer callers and background barriers`() =
        runBlocking {
            for (throws in listOf(false, true)) {
                for (explicit in listOf(false, true)) {
                    fixture { db, writer, room ->
                        var failEnd = true
                        var endWrites = 0
                        val events = CopyOnWriteArrayList<SessionEvent>()
                        val repo =
                            object : SessionSnapshotRepository by room {
                                override suspend fun save(
                                    snapshot: SessionSnapshot,
                                    attempts: List<AttemptRecord>,
                                ): SnapshotSave {
                                    if (snapshot.ended) {
                                        endWrites++
                                        if (failEnd) {
                                            if (throws) error("write failed")
                                            return SnapshotSave.Failed
                                        }
                                    }
                                    return room.save(snapshot, attempts)
                                }
                            }
                        val host =
                            SessionHost(
                                TickingClock(),
                                Dispatchers.Default.limitedParallelism(1),
                                repo,
                                writer,
                                reduced = { events.add(it) },
                            )
                        host.restore(bundle)
                        host.dispatch { SessionEvent.PracticeStarted(bundle.stages[1].lessons.first(), bundle.tutorConfig, it) }
                        writer.flush()
                        // The live practice row is persisted, so only a saved end can stop it restoring.
                        assertEquals(SessionKind.PRACTICE, (room.latest() as SnapshotLoad.Found).snapshot.kind)
                        events.clear()
                        host.endSession()
                        writer.flush()
                        assertTrue(endWrites >= 2)
                        assertEquals(1, events.count { it is SessionEvent.SnapshotSaveFailed })
                        assertTrue(room.latest() is SnapshotLoad.Found)
                        failEnd = false
                        if (explicit) {
                            // Observed outside the writer: the background barrier alone must run the retry.
                            host.retryPendingEnd()
                            kotlinx.coroutines.withTimeout(5_000) {
                                while (room.latest() != SnapshotLoad.None) kotlinx.coroutines.delay(10)
                            }
                        } else {
                            writer.read { assertEquals(SnapshotLoad.None, room.latest()) }
                        }
                        writer.flush()
                        assertEquals(SnapshotLoad.None, room.latest())
                        assertEquals(1, db.attempts().all().count { it.record().outcome == AttemptOutcome.ABANDONED })
                        val rows = db.attempts().all().map { it.record() }
                        assertEquals(2, rows.size)
                        assertEquals(1, rows.map { it.attemptId }.distinct().size)
                        val cold =
                            SessionHost(
                                TickingClock(),
                                Dispatchers.Default.limitedParallelism(1),
                                room,
                                writer,
                            )
                        cold.restore(bundle)
                        assertFalse(cold.state.value.isActive)
                    }
                }
            }
        }

    @Test fun `new snapshot supersedes a pending end even when its retry fails`() =
        runBlocking {
            fixture { _, writer, room ->
                var endWrites = 0
                val repo =
                    object : SessionSnapshotRepository by room {
                        override suspend fun save(
                            snapshot: SessionSnapshot,
                            attempts: List<AttemptRecord>,
                        ): SnapshotSave {
                            if (snapshot.ended) {
                                endWrites++
                                return SnapshotSave.Failed
                            }
                            return room.save(snapshot, attempts)
                        }
                    }
                val host =
                    SessionHost(
                        TickingClock(),
                        Dispatchers.Default.limitedParallelism(1),
                        repo,
                        writer,
                    )
                host.restore(bundle)
                val lesson = bundle.stages[1].lessons.first()
                host.dispatch { SessionEvent.PracticeStarted(lesson, bundle.tutorConfig, it) }
                writer.flush()
                host.endSession()
                host.dispatch { SessionEvent.LessonStarted(lesson, bundle.tutorConfig, it) }
                writer.flush()
                val calls = endWrites
                host.retryPendingEnd()
                writer.flush()
                assertEquals(calls, endWrites)
                assertEquals(SessionKind.LESSON, (room.latest() as SnapshotLoad.Found).snapshot.kind)
                val cold =
                    SessionHost(
                        TickingClock(),
                        Dispatchers.Default.limitedParallelism(1),
                        room,
                        writer,
                    )
                cold.restore(bundle)
                assertEquals(SessionKind.LESSON, cold.state.value.kind)
            }
        }

    @Test fun `a pending end never replaces a newer session of the same kind`() =
        runBlocking {
            fixture { _, writer, room ->
                var failEnd = true
                var endWrites = 0
                val repo =
                    object : SessionSnapshotRepository by room {
                        override suspend fun save(
                            snapshot: SessionSnapshot,
                            attempts: List<AttemptRecord>,
                        ): SnapshotSave {
                            if (snapshot.ended) {
                                endWrites++
                                if (failEnd) return SnapshotSave.Failed
                            }
                            return room.save(snapshot, attempts)
                        }
                    }
                val host =
                    SessionHost(
                        TickingClock(),
                        Dispatchers.Default.limitedParallelism(1),
                        repo,
                        writer,
                    )
                host.restore(bundle)
                val lesson = bundle.stages[1].lessons.first()
                host.dispatch { SessionEvent.PracticeStarted(lesson, bundle.tutorConfig, it) }
                writer.flush()
                val first = (room.latest() as SnapshotLoad.Found).snapshot.sessionId
                host.endSession()
                host.dispatch { SessionEvent.PracticeStarted(lesson, bundle.tutorConfig, it) }
                writer.flush()
                val second = (room.latest() as SnapshotLoad.Found).snapshot
                assertEquals(SessionKind.PRACTICE, second.kind)
                assertTrue(second.sessionId != first)
                failEnd = false
                val calls = endWrites
                host.retryPendingEnd()
                writer.flush()
                assertEquals(calls, endWrites)
                assertEquals(second.sessionId, (room.latest() as SnapshotLoad.Found).snapshot.sessionId)
                val cold =
                    SessionHost(
                        TickingClock(),
                        Dispatchers.Default.limitedParallelism(1),
                        room,
                        writer,
                    )
                cold.restore(bundle)
                assertEquals(SessionKind.PRACTICE, cold.state.value.kind)
            }
        }

    @Test fun `successful reset drops pending end and includes its abandoned attempt`() =
        runBlocking {
            fixture { db, writer, room ->
                var endWrites = 0
                val repo =
                    object : SessionSnapshotRepository by room {
                        override suspend fun save(
                            snapshot: SessionSnapshot,
                            attempts: List<AttemptRecord>,
                        ): SnapshotSave {
                            if (snapshot.ended) {
                                endWrites++
                                return SnapshotSave.Failed
                            }
                            return room.save(snapshot, attempts)
                        }
                    }
                val host =
                    SessionHost(
                        TickingClock(),
                        Dispatchers.Default.limitedParallelism(1),
                        repo,
                        writer,
                    )
                host.restore(bundle)
                host.dispatch { SessionEvent.PracticeStarted(bundle.stages[1].lessons.first(), bundle.tutorConfig, it) }
                writer.flush()
                host.endSession()
                writer.flush()
                assertTrue(host.resetProgress())
                val calls = endWrites
                host.retryPendingEnd()
                writer.flush()
                assertEquals(calls, endWrites)
                assertEquals(0L, count(db))
                assertEquals(1, db.attempts().all().count { it.record().outcome == AttemptOutcome.ABANDONED })
            }
        }

    private suspend fun count(db: RubikDatabase): Long =
        db.withReadTransaction {
            usePrepared("SELECT COUNT(*) FROM session_snapshot") { statement ->
                statement.step()
                statement.getLong(0)
            }
        }

    private suspend fun fixture(block: suspend (RubikDatabase, SerialWriter, RoomSessionSnapshotRepository) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder<RubikDatabase>(RuntimeEnvironment.getApplication()).setDriver(AndroidSQLiteDriver()).build()
        val writer = SerialWriter()
        try {
            block(db, writer, RoomSessionSnapshotRepository(db))
        } finally {
            writer.close()
            db.close()
        }
    }
}
