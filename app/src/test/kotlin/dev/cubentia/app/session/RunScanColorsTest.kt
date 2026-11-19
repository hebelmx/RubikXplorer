package dev.cubentia.app.session

import dev.cubentia.application.session.Checkpoint
import dev.cubentia.application.session.CubeMode
import dev.cubentia.application.session.Effect
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.application.session.SessionState
import dev.cubentia.application.session.SyncState
import dev.cubentia.application.session.reduce
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.PhysicalObservation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/** GH #172: run-scan colors are set on an applied start scan and expire with the run, only inside the host's dispatch. */
class RunScanColorsTest {
    private val at = Instant.fromEpochMilliseconds(0)
    private val r = FaceTurn(Face.R, Turn.CLOCKWISE)
    private val colors = CalibrationCenters(Color.entries.associateWith { Lab(50.0, it.ordinal * 4.0, -it.ordinal * 2.0) })
    private val other = CalibrationCenters(Color.entries.associateWith { Lab(60.0, it.ordinal * 4.0, -it.ordinal * 2.0) })

    private fun lesson() = reduce(SessionState.IDLE, SessionEvent.LessonStarted(whiteCrossLesson(), shippedTutorConfig, at)).state

    private fun awaiting() = reduce(lesson(), SessionEvent.ModeSwitched(CubeMode.PHYSICAL, at)).state

    @Test fun `the pure rule sets only on an applied start scan out of awaiting confirmation`() {
        val before = awaiting()
        assertEquals(SyncState.PHYSICAL_AWAITING_CONFIRM, before.syncState)
        val entered = SessionEvent.EditorEntered(before.expected, at)
        val after = reduce(before, entered).state
        assertTrue(setsRunScanColors(before, entered, after))
        assertTrue(keepsRunScanColors(before, entered, after))
        // Ignored (virtual mode ignores the Editor's entry): nothing is set.
        val virtual = lesson()
        val ignored = SessionEvent.EditorEntered(virtual.expected, at)
        assertFalse(setsRunScanColors(virtual, ignored, reduce(virtual, ignored).state))
        // A move is not a start-scan event.
        val moved = SessionEvent.PhysicalConfirmed(r, at)
        assertFalse(setsRunScanColors(after, moved, reduce(after, moved).state))
        // A playback start sets only from an accepted scan.
        val scanned = SessionEvent.SolvePlaybackStarted(CubeState.SOLVED, emptyList(), at, shippedTutorConfig, fromAcceptedScan = true)
        val plain = scanned.copy(fromAcceptedScan = false)
        assertTrue(setsRunScanColors(SessionState.IDLE, scanned, reduce(SessionState.IDLE, scanned).state))
        assertFalse(setsRunScanColors(SessionState.IDLE, plain, reduce(SessionState.IDLE, plain).state))
        // ... but any playback start expires held colors.
        assertFalse(keepsRunScanColors(after, scanned, reduce(after, scanned).state))
    }

    @Test fun `the pure rule expires on every end, start, restore, re-confirm and switch back to virtual`() {
        val verified = reduce(awaiting(), SessionEvent.EditorEntered(awaiting().expected, at)).state
        val checkpoint =
            Checkpoint.of(
                Effect.PersistCheckpoint(1, 1, "lesson-01-01", "stage-01", verified.history, verified.teachingMode, false, at),
                "content",
            )
        listOf(
            SessionEvent.SessionEnded(at),
            SessionEvent.LessonStarted(whiteCrossLesson(), shippedTutorConfig, at),
            SessionEvent.PracticeStarted(whiteCrossLesson(), shippedTutorConfig, at),
            SessionEvent.ModeSwitched(CubeMode.VIRTUAL, at),
        ).forEach { event ->
            val after = reduce(verified, event).state
            assertTrue(after !== verified, "$event must apply")
            assertFalse(keepsRunScanColors(verified, event, after), event.toString())
        }
        // Applied restore and playback end expire them too.
        val restore = SessionEvent.CheckpointRestored(checkpoint, whiteCrossLesson(), shippedTutorConfig, "content", at)
        val restored = reduce(SessionState.IDLE, restore).state
        assertTrue(restored !== SessionState.IDLE)
        assertFalse(keepsRunScanColors(SessionState.IDLE, restore, restored))
        val playback =
            reduce(
                SessionState.IDLE,
                SessionEvent.SolvePlaybackStarted(CubeState.SOLVED, emptyList(), at, shippedTutorConfig, fromAcceptedScan = true),
            ).state
        val ended = SessionEvent.SolvePlaybackEnded(at)
        assertFalse(keepsRunScanColors(playback, ended, reduce(playback, ended).state))
        // Back to awaiting confirmation, or idle, expires them whatever the event.
        assertFalse(keepsRunScanColors(verified, SessionEvent.Undo(at), awaiting()))
        assertFalse(keepsRunScanColors(verified, SessionEvent.Undo(at), SessionState.IDLE))
    }

    @Test fun `the pure rule keeps colors when the reducer ignores the event`() {
        val verified = reduce(awaiting(), SessionEvent.EditorEntered(awaiting().expected, at)).state
        val checkpoint =
            Checkpoint.of(
                Effect.PersistCheckpoint(1, 1, "lesson-01-01", "stage-01", verified.history, verified.teachingMode, false, at),
                "content",
            )
        // A restore is rejected while a session is active: nothing changes, so the run keeps its colors.
        val rejected = SessionEvent.CheckpointRestored(checkpoint, whiteCrossLesson(), shippedTutorConfig, "content", at)
        val after = reduce(verified, rejected).state
        assertTrue(after === verified)
        assertTrue(keepsRunScanColors(verified, rejected, after))
    }

    @Test fun `the pure rule keeps colors across physical moves, checks and undo`() {
        val verified = reduce(awaiting(), SessionEvent.EditorEntered(awaiting().expected, at)).state
        val confirmed = reduce(verified, SessionEvent.PhysicalConfirmed(r, at)).state
        assertTrue(keepsRunScanColors(verified, SessionEvent.PhysicalConfirmed(r, at), confirmed))
        val observed = SessionEvent.QuickCheckObserved(99, confirmed.revision, PhysicalObservation.Partial(emptyMap()), at)
        assertTrue(keepsRunScanColors(confirmed, observed, reduce(confirmed, observed).state))
        assertTrue(keepsRunScanColors(confirmed, SessionEvent.Undo(at), reduce(confirmed, SessionEvent.Undo(at)).state))
        // Physical to physical through the virtual playback baseline: a switch to physical keeps them.
        val playback =
            reduce(
                SessionState.IDLE,
                SessionEvent.SolvePlaybackStarted(CubeState.SOLVED, emptyList(), at, shippedTutorConfig, fromAcceptedScan = true),
            ).state
        val toPhysical = SessionEvent.ModeSwitched(CubeMode.PHYSICAL, at)
        assertTrue(keepsRunScanColors(playback, toPhysical, reduce(playback, toPhysical).state))
    }

    @Test fun `the host sets colors on an applied start scan and keeps them through the run`() =
        runBlocking {
            val host = SessionHost(TickingClock())
            host.dispatch { SessionEvent.LessonStarted(whiteCrossLesson(), shippedTutorConfig, it) }
            // Ignored: the start scan in virtual mode does not apply, so nothing is held.
            host.dispatch(colors) { SessionEvent.EditorEntered(host.state.value.expected, it) }
            assertNull(host.runScanColors())
            host.dispatch { SessionEvent.ModeSwitched(CubeMode.PHYSICAL, it) }
            host.dispatch(colors) { SessionEvent.EditorEntered(host.state.value.expected, it) }
            assertEquals(colors, host.runScanColors())
            host.dispatch { SessionEvent.PhysicalConfirmed(r, it) }
            host.dispatch { SessionEvent.QuickCheckObserved(99, host.state.value.revision, PhysicalObservation.Partial(emptyMap()), it) }
            host.dispatch { SessionEvent.Undo(it) }
            // An Editor entry without new colors keeps the run's; a new start scan replaces them.
            host.dispatch { SessionEvent.EditorEntered(host.state.value.expected, it) }
            assertEquals(colors, host.runScanColors())
            host.dispatch(other) { SessionEvent.EditorEntered(host.state.value.expected, it) }
            assertEquals(other, host.runScanColors())
            host.dispatch { SessionEvent.ModeSwitched(CubeMode.VIRTUAL, it) }
            assertNull(host.runScanColors())
        }

    @Test fun `the host clears colors when the run ends or a new session starts`() =
        runBlocking {
            val host = SessionHost(TickingClock())

            suspend fun startScan() {
                host.dispatch { SessionEvent.LessonStarted(whiteCrossLesson(), shippedTutorConfig, it) }
                host.dispatch { SessionEvent.ModeSwitched(CubeMode.PHYSICAL, it) }
                host.dispatch(colors) { SessionEvent.EditorEntered(host.state.value.expected, it) }
                assertEquals(colors, host.runScanColors())
            }
            startScan()
            host.dispatch { SessionEvent.SessionEnded(it) }
            assertNull(host.runScanColors())
            startScan()
            host.dispatch { SessionEvent.LessonStarted(whiteCrossLesson(), shippedTutorConfig, it) }
            assertNull(host.runScanColors())
            startScan()
            host.dispatch { SessionEvent.SolvePlaybackStarted(CubeState.SOLVED, emptyList(), it, shippedTutorConfig) }
            assertNull(host.runScanColors())
            // A playback from an accepted scan holds its own colors until the playback ends.
            host.dispatch(other) { SessionEvent.SolvePlaybackStarted(CubeState.SOLVED, emptyList(), it, shippedTutorConfig, true) }
            assertEquals(other, host.runScanColors())
            host.dispatch { SessionEvent.ModeSwitched(CubeMode.PHYSICAL, it) }
            assertEquals(other, host.runScanColors())
            host.dispatch { SessionEvent.SolvePlaybackEnded(it) }
            assertNull(host.runScanColors())
        }

    @Test fun `the host writes the same snapshots with or without run-scan colors`() =
        runBlocking {
            suspend fun saved(withColors: Boolean): List<dev.cubentia.application.session.SessionSnapshot> {
                val snapshots = java.util.concurrent.CopyOnWriteArrayList<dev.cubentia.application.session.SessionSnapshot>()
                val repo =
                    object : dev.cubentia.application.port.SessionSnapshotRepository {
                        override suspend fun latest() = dev.cubentia.application.port.SnapshotLoad.None

                        override suspend fun save(
                            snapshot: dev.cubentia.application.session.SessionSnapshot,
                            attempts: List<dev.cubentia.application.session.AttemptRecord>,
                        ): dev.cubentia.application.port.SnapshotSave {
                            snapshots += snapshot.copy(sessionId = "", attemptId = null)
                            return dev.cubentia.application.port.SnapshotSave.Saved
                        }

                        override suspend fun clear(attempts: List<dev.cubentia.application.session.AttemptRecord>) =
                            dev.cubentia.application.port.SnapshotSave.Saved
                    }
                val writer =
                    dev.cubentia.app.data
                        .SerialWriter()
                try {
                    val host =
                        SessionHost(
                            TickingClock(),
                            kotlinx.coroutines.Dispatchers.Default
                                .limitedParallelism(1),
                            repo,
                            writer,
                        )
                    host.dispatch { SessionEvent.LessonStarted(whiteCrossLesson(), shippedTutorConfig, it) }
                    host.dispatch { SessionEvent.ModeSwitched(CubeMode.PHYSICAL, it) }
                    host.dispatch(if (withColors) colors else null) { SessionEvent.EditorEntered(host.state.value.expected, it) }
                    host.dispatch { SessionEvent.PhysicalConfirmed(r, it) }
                    writer.flush()
                } finally {
                    writer.close()
                }
                return snapshots
            }
            val plain = saved(false)
            assertTrue(plain.isNotEmpty())
            assertEquals(plain, saved(true))
        }
}
