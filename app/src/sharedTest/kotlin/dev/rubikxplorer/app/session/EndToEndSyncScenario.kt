package dev.rubikxplorer.app.session

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.dp
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.rubikxplorer.app.data.RoomCheckpointRepository
import dev.rubikxplorer.app.data.RoomSessionSnapshotRepository
import dev.rubikxplorer.app.data.RubikDatabase
import dev.rubikxplorer.app.data.SerialWriter
import dev.rubikxplorer.app.render.Cube3DRenderer
import dev.rubikxplorer.app.render.Net2DRenderer
import dev.rubikxplorer.app.render.RenderStyle
import dev.rubikxplorer.application.session.CubeMode
import dev.rubikxplorer.application.session.SessionEvent
import dev.rubikxplorer.application.session.SyncState
import dev.rubikxplorer.application.session.Transition
import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.content.ContentLoader
import dev.rubikxplorer.domain.content.ContentSource
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Turn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.util.UUID
import kotlin.time.Instant

/**
 * End-to-end sync verification scenario (Story 8.6, AC 2, NFR-4).
 *
 * Verifies zero silent desynchronization between virtual state, expected physical state,
 * and rendered cube representations (both [Cube3DRenderer] and [Net2DRenderer]) across:
 * - Virtual moves, undo, and redo
 * - Physical mode toggle and initial match confirmation
 * - Physical move confirmations, undo, and redo
 * - Mode switch back to virtual with undo barrier enforcement
 * - Session pause, process kill via database closure, and session resumption
 * - Continued physical interactions on resumed session
 */
internal suspend fun endToEndSyncScenario(
    context: Context,
    composeRule: ComposeContentTestRule,
) {
    val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
    val lesson =
        bundle.stages
            .first()
            .lessons
            .first()
    val dbName = "sync-e2e-${UUID.randomUUID()}.db"

    fun database() = Room.databaseBuilder<RubikDatabase>(context, dbName).setDriver(AndroidSQLiteDriver()).build()

    var db = database()
    var writer = SerialWriter()
    try {
        var snapshotRepo = RoomSessionSnapshotRepository(db)
        var checkpointRepo = RoomCheckpointRepository(db)

        var host =
            SessionHost(
                clock = TickingClock(),
                confined = Dispatchers.Default.limitedParallelism(1),
                repository = snapshotRepo,
                writer = writer,
                checkpoints = checkpointRepo,
            )
        host.restore(bundle)
        host.awaitRestored()

        var currentCube3D by mutableStateOf(Cube3DRenderer())
        var currentNet2D by mutableStateOf(Net2DRenderer())

        composeRule.setContent {
            Row {
                currentCube3D.Content(RenderStyle(), modifier = Modifier.size(100.dp))
                currentNet2D.Content(RenderStyle(), modifier = Modifier.size(100.dp))
            }
        }
        composeRule.waitForIdle()

        fun drain() {
            composeRule.waitForIdle()
            composeRule.mainClock.advanceTimeBy(500)
            composeRule.waitForIdle()
        }

        fun dispatch(event: (Instant) -> SessionEvent): Transition {
            val transition = runBlocking { host.dispatch(event) }
            val items = transition.presentation.toItems()
            currentCube3D.submit(items)
            currentNet2D.submit(items)
            drain()
            return transition
        }

        fun assertZeroDesync(step: String) {
            val state = host.state.value
            val isPhysical = state.syncState != SyncState.VIRTUAL
            val expectedCube = if (isPhysical) state.expected else state.virtual
            val expectedFacelets = expectedCube.toFacelets()

            if (isPhysical) {
                assertEquals("[$step] Physical mode: virtual and expected state desync", state.expected, state.virtual)
            }

            assertEquals("[$step] Cube3DRenderer desync from expected state", expectedFacelets, currentCube3D.renderedFacelets())
            assertEquals("[$step] Net2DRenderer desync from expected state", expectedFacelets, currentNet2D.renderedFacelets())
        }

        // 1. Start lesson in virtual mode
        dispatch { SessionEvent.LessonStarted(lesson, bundle.tutorConfig, it, true) }
        assertZeroDesync("LessonStarted virtual")

        // 2. Virtual moves, undo, and redo
        dispatch { SessionEvent.MoveCommitted(FaceTurn(Face.R, Turn.CLOCKWISE), it) }
        assertZeroDesync("Virtual move R")

        dispatch { SessionEvent.MoveCommitted(FaceTurn(Face.U, Turn.CLOCKWISE), it) }
        assertZeroDesync("Virtual move U")

        dispatch { SessionEvent.MoveCommitted(FaceTurn(Face.F, Turn.COUNTERCLOCKWISE), it) }
        assertZeroDesync("Virtual move F'")

        dispatch(SessionEvent::Undo)
        assertZeroDesync("Virtual undo F'")

        dispatch(SessionEvent::Redo)
        assertZeroDesync("Virtual redo F'")

        dispatch(SessionEvent::Undo)
        assertZeroDesync("Virtual undo F' again")

        // 3. Switch to physical mode and confirm match
        dispatch { SessionEvent.ModeSwitched(CubeMode.PHYSICAL, it) }
        assertEquals(SyncState.PHYSICAL_AWAITING_CONFIRM, host.state.value.syncState)
        assertZeroDesync("ModeSwitched to physical awaiting confirm")

        dispatch { SessionEvent.PhysicalConfirmed(null, it) }
        assertEquals(SyncState.PHYSICAL_PREDICTED, host.state.value.syncState)
        assertZeroDesync("Physical match confirmed")

        // 4. Physical moves, confirmations, undo, and redo
        dispatch { SessionEvent.PhysicalConfirmed(FaceTurn(Face.D, Turn.CLOCKWISE), it) }
        assertZeroDesync("Physical move D confirmed")

        dispatch { SessionEvent.PhysicalConfirmed(FaceTurn(Face.L, Turn.COUNTERCLOCKWISE), it) }
        assertZeroDesync("Physical move L' confirmed")

        dispatch(SessionEvent::Undo)
        assertZeroDesync("Physical undo L'")

        dispatch(SessionEvent::Redo)
        assertZeroDesync("Physical redo L'")

        // 5. Mode switch back to virtual (undo barrier enforcement)
        dispatch { SessionEvent.ModeSwitched(CubeMode.VIRTUAL, it) }
        assertEquals(SyncState.VIRTUAL, host.state.value.syncState)
        assertZeroDesync("ModeSwitched back to virtual")
        assertFalse("Undo barrier must clear undo history", host.state.value.history.canUndo)

        // Commit move across barrier, then undo back to barrier
        dispatch { SessionEvent.MoveCommitted(FaceTurn(Face.B, Turn.CLOCKWISE), it) }
        assertZeroDesync("Virtual move B across barrier")
        assertTrue(host.state.value.history.canUndo)

        dispatch(SessionEvent::Undo)
        assertZeroDesync("Virtual undo back to barrier")
        assertFalse("Undo must stop at the barrier", host.state.value.history.canUndo)

        // 6. Switch to physical mode again and advance before pause/kill
        dispatch { SessionEvent.ModeSwitched(CubeMode.PHYSICAL, it) }
        assertZeroDesync("ModeSwitched to physical again")
        dispatch { SessionEvent.PhysicalConfirmed(null, it) }
        assertZeroDesync("Physical match confirmed again")
        dispatch { SessionEvent.PhysicalConfirmed(FaceTurn(Face.U, Turn.CLOCKWISE), it) }
        assertZeroDesync("Physical move U before process kill")
        // Independent oracle for the resume check: the cube as it stood before the kill.
        val preKillExpected = host.state.value.expected
        val preKillVirtual = host.state.value.virtual

        // 7. Pause, process kill (database recreation), and session resumption
        writer.flush()
        writer.close()
        db.close()

        db = database()
        writer = SerialWriter()
        snapshotRepo = RoomSessionSnapshotRepository(db)
        checkpointRepo = RoomCheckpointRepository(db)
        host =
            SessionHost(
                clock = TickingClock(),
                confined = Dispatchers.Default.limitedParallelism(1),
                repository = snapshotRepo,
                writer = writer,
                checkpoints = checkpointRepo,
            )
        host.restore(bundle)
        host.awaitRestored()

        assertEquals(SyncState.PHYSICAL_AWAITING_CONFIRM, host.state.value.syncState)
        val restoredExpected = host.state.value.expected
        assertEquals("Resume must restore the pre-kill expected cube", preKillExpected, restoredExpected)
        assertEquals("Resume must restore the pre-kill virtual cube", preKillVirtual, host.state.value.virtual)

        currentCube3D = Cube3DRenderer(restoredExpected.toFacelets())
        currentNet2D = Net2DRenderer(restoredExpected.toFacelets())
        drain()
        assertZeroDesync("Resumed physical session awaiting confirm")

        // 8. Resumed physical interaction
        dispatch { SessionEvent.PhysicalConfirmed(null, it) }
        assertEquals(SyncState.PHYSICAL_PREDICTED, host.state.value.syncState)
        assertZeroDesync("Resumed match confirmed")

        dispatch { SessionEvent.PhysicalConfirmed(FaceTurn(Face.R, Turn.CLOCKWISE), it) }
        assertZeroDesync("Resumed physical move R confirmed")
    } finally {
        writer.close()
        db.close()
        context.deleteDatabase(dbName)
    }
}
