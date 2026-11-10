package dev.cubentia.app.data

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.session.TickingClock
import dev.cubentia.application.port.AttemptLoad
import dev.cubentia.application.port.CheckpointLoad
import dev.cubentia.application.port.SnapshotLoad
import dev.cubentia.application.session.AttemptEvent
import dev.cubentia.application.session.AttemptOutcome
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.application.session.SessionKind
import dev.cubentia.application.usecase.ResetProgress
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.tutor.ProgressEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SessionEndingTest {
    @Test fun `reset ends every resumable kind and clears snapshots and fallback checkpoint`() =
        runBlocking {
            val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
            for (kind in listOf(SessionKind.LESSON, SessionKind.TWO_LOOK, SessionKind.PRACTICE)) {
                val db =
                    Room
                        .inMemoryDatabaseBuilder<RubikDatabase>(RuntimeEnvironment.getApplication())
                        .setDriver(AndroidSQLiteDriver())
                        .build()
                val writer = SerialWriter()
                val snapshots = RoomSessionSnapshotRepository(db)
                val checkpoints = RoomCheckpointRepository(db)
                val progress = RoomProgressRepository(db)

                fun host() =
                    SessionHost(
                        TickingClock(),
                        Dispatchers.Default.limitedParallelism(1),
                        repository = snapshots,
                        writer = writer,
                        checkpoints = checkpoints,
                        progress = progress,
                        attempts = RoomAttemptRepository(db),
                        resetProgress = ResetProgress(progress),
                    )
                try {
                    val host = host()
                    host.restore(bundle)
                    val lesson = bundle.stages[1].lessons.first()
                    host.dispatch { SessionEvent.LessonStarted(lesson, bundle.tutorConfig, it) }
                    if (kind == SessionKind.PRACTICE) {
                        host.dispatch { SessionEvent.PracticeStarted(lesson, bundle.tutorConfig, it) }
                    }
                    if (kind == SessionKind.TWO_LOOK) {
                        host.dispatch { SessionEvent.LessonStarted(lesson, bundle.tutorConfig, it, kind = kind) }
                    }
                    writer.flush()
                    assertTrue(snapshots.latest() is SnapshotLoad.Found)
                    assertTrue(checkpoints.find(SessionKind.LESSON) is CheckpointLoad.Found)
                    val before = (RoomAttemptRepository(db).all() as AttemptLoad.Found).records
                    val endedIds = before.filter { it.event == AttemptEvent.ENDED }.map { it.attemptId }.toSet()
                    val live = before.single { it.event == AttemptEvent.STARTED && it.attemptId !in endedIds }
                    assertTrue(host.resetProgress())
                    writer.flush()
                    assertFalse(host.state.value.isActive)
                    assertEquals(SnapshotLoad.None, snapshots.latest())
                    assertEquals(CheckpointLoad.None, checkpoints.find(SessionKind.LESSON))
                    val cold = host()
                    cold.restore(bundle)
                    assertFalse(cold.state.value.isActive)
                    val rows = (RoomAttemptRepository(db).all() as AttemptLoad.Found).records
                    val reset =
                        host.progressEvents.value
                            .filterIsInstance<ProgressEvent.ProgressReset>()
                            .single()
                    val abandoned = (rows - before.toSet()).filter { it.outcome == AttemptOutcome.ABANDONED }
                    assertEquals(kind.toString(), 1, abandoned.size)
                    assertEquals(live.attemptId, abandoned.single().attemptId)
                    assertTrue(abandoned.single().at < reset.at)
                } finally {
                    writer.close()
                    db.close()
                }
            }
        }

    @Test fun `reset on an idle host still deletes a leftover snapshot row`() =
        runBlocking {
            val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
            val db =
                Room
                    .inMemoryDatabaseBuilder<RubikDatabase>(RuntimeEnvironment.getApplication())
                    .setDriver(AndroidSQLiteDriver())
                    .build()
            val writer = SerialWriter()
            val snapshots = RoomSessionSnapshotRepository(db)
            val progress = RoomProgressRepository(db)

            fun host() =
                SessionHost(
                    TickingClock(),
                    Dispatchers.Default.limitedParallelism(1),
                    repository = snapshots,
                    writer = writer,
                    checkpoints = RoomCheckpointRepository(db),
                    progress = progress,
                    attempts = RoomAttemptRepository(db),
                    resetProgress = ResetProgress(progress),
                )
            try {
                val first = host()
                first.restore(bundle)
                val lesson = bundle.stages[1].lessons.first()
                first.dispatch { SessionEvent.LessonStarted(lesson, bundle.tutorConfig, it) }
                writer.flush()
                assertTrue(snapshots.latest() is SnapshotLoad.Found)
                // A host that never restored the row (e.g. its load failed) is idle while the row survives.
                val idle = host()
                assertFalse(idle.state.value.isActive)
                assertTrue(idle.resetProgress())
                writer.flush()
                assertFalse(idle.state.value.isActive)
                assertEquals(SnapshotLoad.None, snapshots.latest())
                val cold = host()
                cold.restore(bundle)
                assertFalse(cold.state.value.isActive)
            } finally {
                writer.close()
                db.close()
            }
        }
}
