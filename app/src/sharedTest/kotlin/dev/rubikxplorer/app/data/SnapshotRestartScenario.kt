package dev.rubikxplorer.app.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.rubikxplorer.app.session.SessionHost
import dev.rubikxplorer.app.session.TickingClock
import dev.rubikxplorer.application.port.SnapshotLoad
import dev.rubikxplorer.application.session.SessionEvent
import dev.rubikxplorer.application.session.SessionSnapshot
import dev.rubikxplorer.application.session.SessionState
import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.content.ContentLoader
import dev.rubikxplorer.domain.content.ContentSource
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Turn
import dev.rubikxplorer.domain.tutor.TeachingMode
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import java.util.UUID

internal suspend fun snapshotRestartScenario(
    context: Context,
    playback: Boolean = false,
    changed: Boolean = false,
    missing: Boolean = false,
) {
    val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
    val lesson =
        bundle.stages
            .first()
            .lessons
            .first()
    val name = "snapshot-${UUID.randomUUID()}.db"

    fun database() = Room.databaseBuilder<RubikDatabase>(context, name).setDriver(AndroidSQLiteDriver()).build()
    var db = database()
    var writer = SerialWriter()
    try {
        val repo = RoomSessionSnapshotRepository(db)
        val host = SessionHost(TickingClock(), Dispatchers.Default.limitedParallelism(1), repo, writer)
        host.restore(bundle)
        if (playback) {
            host.dispatch { SessionEvent.SolvePlaybackStarted(CubeState.SOLVED, emptyList(), it) }
        } else {
            host.dispatch { SessionEvent.LessonStarted(lesson, bundle.tutorConfig, it, true) }
            host.dispatch { SessionEvent.MoveCommitted(FaceTurn(Face.R, Turn.CLOCKWISE), it) }
            host.dispatch { SessionEvent.MoveCommitted(FaceTurn(Face.U, Turn.CLOCKWISE), it) }
            host.dispatch { SessionEvent.Undo(it) }
            host.dispatch { SessionEvent.HintRequested(it) }
            host.dispatch { SessionEvent.TeachingModeChanged(TeachingMode.TEST_ME, it) }
        }
        val before = host.state.value
        writer.flush()
        val saved = (writer.read { repo.latest() } as SnapshotLoad.Found).snapshot
        writer.close()
        db.close()
        db = database()
        writer = SerialWriter()
        val restoredRepo = RoomSessionSnapshotRepository(db)
        val restored = SessionHost(TickingClock(), Dispatchers.Default.limitedParallelism(1), restoredRepo, writer)
        val content =
            if (changed) {
                bundle.copy(contentRevision = "changed")
            } else if (missing) {
                bundle.copy(stages = emptyList())
            } else {
                bundle
            }
        restored.restore(content)
        restored.awaitRestored()
        if (playback || changed || missing) {
            assertSame(SessionState.IDLE, restored.state.value)
        } else {
            val after = restored.state.value
            assertEquals(before.history, after.history)
            assertEquals(before.checkpoint, after.checkpoint)
            assertEquals(before.progress, after.progress)
            assertEquals(before.teachingMode, after.teachingMode)
            assertEquals(before.hintsUsed, after.hintsUsed)
            assertEquals(1L, after.revision)
            assertTrue(after.history.canUndo)
            assertTrue(after.history.canRedo)
            writer.flush()
            val resaved = (writer.read { restoredRepo.latest() } as SnapshotLoad.Found).snapshot
            assertNotEquals(saved.sessionId, resaved.sessionId)
            assertEquals(1L, resaved.revision)
            restored.restore(bundle) // Only once, even if catalog or shell is recreated.
            assertSame(after, restored.state.value)
        }
    } finally {
        writer.close()
        db.close()
        context.deleteDatabase(name)
    }
}
