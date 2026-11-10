package dev.cubentia.app.data

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.session.shippedTutorConfig
import dev.cubentia.app.session.whiteCrossLesson
import dev.cubentia.application.port.AttemptLoad
import dev.cubentia.application.port.Clock
import dev.cubentia.application.port.SessionSnapshotRepository
import dev.cubentia.application.port.SnapshotLoad
import dev.cubentia.application.port.SnapshotSave
import dev.cubentia.application.session.AttemptEvent
import dev.cubentia.application.session.AttemptOutcome
import dev.cubentia.application.session.AttemptRecord
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.application.session.SessionSnapshot
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SessionHostAttemptTest {
    @Test fun repeatedRunsAndAbandonPreserveEarlierRows() =
        runBlocking {
            val db =
                Room
                    .inMemoryDatabaseBuilder<RubikDatabase>(RuntimeEnvironment.getApplication())
                    .setDriver(AndroidSQLiteDriver())
                    .build()
            val writer = SerialWriter()
            val log = RoomAttemptRepository(db)
            val host =
                SessionHost(
                    clock = Clock { Instant.fromEpochMilliseconds(0) },
                    confined = Dispatchers.Default.limitedParallelism(1),
                    repository = RoomSessionSnapshotRepository(db),
                    writer = writer,
                    attempts = log,
                )
            val move = (Notation.parse("F'") as NotationResult.Parsed).moves.single()
            var previous = emptyList<AttemptRecord>()

            suspend fun checkPrefix(): List<AttemptRecord> {
                writer.flush()
                val rows = (log.all() as AttemptLoad.Found).records
                assertEquals(previous, rows.take(previous.size))
                assertEquals(rows, host.attemptRecords.value)
                previous = rows
                return rows
            }
            try {
                repeat(3) {
                    host.dispatch { SessionEvent.LessonStarted(whiteCrossLesson(), shippedTutorConfig, it) }
                    host.dispatch { SessionEvent.MoveCommitted(move, it) }
                    val rows = checkPrefix()
                    assertEquals((it + 1) * 2, rows.size)
                }
                assertEquals(3, previous.count { it.event == AttemptEvent.STARTED })
                assertEquals(3, previous.count { it.outcome == AttemptOutcome.COMPLETED })
                host.dispatch { SessionEvent.LessonStarted(whiteCrossLesson(), shippedTutorConfig, it) }
                checkPrefix()
                host.dispatch { SessionEvent.LessonStarted(whiteCrossLesson(), shippedTutorConfig, it) }
                val rows = checkPrefix()
                assertEquals(AttemptOutcome.ABANDONED, rows[7].outcome)
                assertEquals(rows[6].sessionId, rows[7].sessionId)
                assertNotEquals(rows[7].sessionId, rows[8].sessionId)
                assertEquals(rows.size, rows.map { it.sessionId to it.revision }.toSet().size)
            } finally {
                writer.close()
                db.close()
            }
        }

    @Test fun failedSaveKeepsAttemptRowsPendingUntilTheNextSave() =
        runBlocking {
            val db =
                Room
                    .inMemoryDatabaseBuilder<RubikDatabase>(RuntimeEnvironment.getApplication())
                    .setDriver(AndroidSQLiteDriver())
                    .build()
            val room = RoomSessionSnapshotRepository(db)
            var failures = 1
            val flaky =
                object : SessionSnapshotRepository {
                    override suspend fun clear(attempts: List<dev.cubentia.application.session.AttemptRecord>) =
                        dev.cubentia.application.port.SnapshotSave.Saved

                    override suspend fun save(
                        snapshot: SessionSnapshot,
                        attempts: List<AttemptRecord>,
                    ): SnapshotSave = if (failures-- > 0) SnapshotSave.Failed else room.save(snapshot, attempts)

                    override suspend fun latest(): SnapshotLoad = room.latest()
                }
            val writer = SerialWriter()
            val host =
                SessionHost(
                    Clock { Instant.fromEpochMilliseconds(0) },
                    Dispatchers.Default.limitedParallelism(1),
                    flaky,
                    writer,
                )
            val log = RoomAttemptRepository(db)
            val move = (Notation.parse("F'") as NotationResult.Parsed).moves.single()
            try {
                host.dispatch { SessionEvent.LessonStarted(whiteCrossLesson(), shippedTutorConfig, it) }
                writer.flush()
                assertEquals(AttemptLoad.Found(emptyList<AttemptRecord>()), log.all())
                host.dispatch { SessionEvent.MoveCommitted(move, it) }
                writer.flush()
                val rows = (log.all() as AttemptLoad.Found).records
                assertEquals(listOf(AttemptEvent.STARTED, AttemptEvent.ENDED), rows.map { it.event })
                assertEquals(rows[0].attemptId, rows[1].attemptId)
                assertEquals(AttemptOutcome.COMPLETED, rows[1].outcome)
                // A later save does not replay the recovered rows.
                host.dispatch { SessionEvent.LessonStarted(whiteCrossLesson(), shippedTutorConfig, it) }
                writer.flush()
                assertEquals(rows, (log.all() as AttemptLoad.Found).records.take(2))
                assertEquals(3, (log.all() as AttemptLoad.Found).records.size)
            } finally {
                writer.close()
                db.close()
            }
        }

    @Test fun checkpointFallbackAfterAnUnfinishedAttemptPairsRowsByAttemptId() =
        runBlocking {
            val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
            val lesson =
                bundle.stages
                    .first()
                    .lessons
                    .first()
            val db =
                Room
                    .inMemoryDatabaseBuilder<RubikDatabase>(RuntimeEnvironment.getApplication())
                    .setDriver(AndroidSQLiteDriver())
                    .build()
            val writer = SerialWriter()
            val room = RoomSessionSnapshotRepository(db)
            val checkpoints = RoomCheckpointRepository(db)
            val log = RoomAttemptRepository(db)
            try {
                val first =
                    SessionHost(
                        Clock { Instant.fromEpochMilliseconds(0) },
                        Dispatchers.Default.limitedParallelism(1),
                        room,
                        writer,
                        checkpoints = checkpoints,
                    )
                first.restore(bundle)
                first.dispatch { SessionEvent.LessonStarted(lesson, bundle.tutorConfig, it) }
                writer.flush()
                // The process dies with the attempt open; the next start cannot use the snapshot.
                val unusable =
                    object : SessionSnapshotRepository {
                        override suspend fun clear(attempts: List<dev.cubentia.application.session.AttemptRecord>) =
                            dev.cubentia.application.port.SnapshotSave.Saved

                        override suspend fun save(
                            snapshot: SessionSnapshot,
                            attempts: List<AttemptRecord>,
                        ): SnapshotSave = room.save(snapshot, attempts)

                        override suspend fun latest(): SnapshotLoad = SnapshotLoad.Failed
                    }
                val second =
                    SessionHost(
                        Clock { Instant.fromEpochMilliseconds(1) },
                        Dispatchers.Default.limitedParallelism(1),
                        unusable,
                        writer,
                        checkpoints = checkpoints,
                    )
                second.restore(bundle)
                second.awaitRestored()
                assertTrue(second.state.value.attemptOpen)
                for (move in (Notation.parse(lesson.demonstration) as NotationResult.Parsed).moves) {
                    second.dispatch { SessionEvent.MoveCommitted(move, it) }
                }
                assertTrue(second.state.value.objectiveMet)
                writer.flush()
                val rows = (log.all() as AttemptLoad.Found).records
                assertEquals(listOf(AttemptEvent.STARTED, AttemptEvent.STARTED, AttemptEvent.ENDED), rows.map { it.event })
                assertNotEquals(rows[0].attemptId, rows[1].attemptId)
                assertEquals(rows[1].attemptId, rows[2].attemptId)
                assertEquals(AttemptOutcome.COMPLETED, rows[2].outcome)
                assertTrue(rows.none { it.event == AttemptEvent.ENDED && it.attemptId == rows[0].attemptId })
            } finally {
                writer.close()
                db.close()
            }
        }
}
