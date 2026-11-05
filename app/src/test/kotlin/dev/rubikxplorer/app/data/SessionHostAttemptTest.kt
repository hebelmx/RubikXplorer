package dev.rubikxplorer.app.data

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.rubikxplorer.app.session.SessionHost
import dev.rubikxplorer.app.session.shippedTutorConfig
import dev.rubikxplorer.app.session.whiteCrossLesson
import dev.rubikxplorer.application.port.AttemptLoad
import dev.rubikxplorer.application.port.Clock
import dev.rubikxplorer.application.session.AttemptEvent
import dev.rubikxplorer.application.session.AttemptOutcome
import dev.rubikxplorer.application.session.AttemptRecord
import dev.rubikxplorer.application.session.SessionEvent
import dev.rubikxplorer.application.session.SessionSnapshot
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.NotationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
            val host =
                SessionHost(
                    Clock { Instant.fromEpochMilliseconds(0) },
                    Dispatchers.Default.limitedParallelism(1),
                    RoomSessionSnapshotRepository(db),
                    writer,
                )
            val log = RoomAttemptRepository(db)
            val move = (Notation.parse("F'") as NotationResult.Parsed).moves.single()
            var previous = emptyList<AttemptRecord>()

            suspend fun checkPrefix(): List<AttemptRecord> {
                writer.flush()
                val rows = (log.all() as AttemptLoad.Found).records
                assertEquals(previous, rows.take(previous.size))
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
}
