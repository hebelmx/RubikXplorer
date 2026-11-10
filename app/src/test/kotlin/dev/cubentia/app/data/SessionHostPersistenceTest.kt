package dev.cubentia.app.data

import android.app.Application
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.session.TickingClock
import dev.cubentia.application.port.SessionSnapshotRepository
import dev.cubentia.application.port.SnapshotLoad
import dev.cubentia.application.port.SnapshotSave
import dev.cubentia.application.session.AttemptRecord
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.application.session.SessionSnapshot
import dev.cubentia.application.session.SessionState
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Turn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SessionHostPersistenceTest {
    @Test fun lessonResumesAcrossFileDatabaseRestart() =
        runBlocking {
            snapshotRestartScenario(RuntimeEnvironment.getApplication())
        }

    @Test fun solvePlaybackDoesNotResume() =
        runBlocking {
            snapshotRestartScenario(RuntimeEnvironment.getApplication(), playback = true)
        }

    @Test fun changedContentDoesNotResume() =
        runBlocking {
            snapshotRestartScenario(RuntimeEnvironment.getApplication(), changed = true)
        }

    @Test fun unresolvedLessonDoesNotResume() =
        runBlocking {
            snapshotRestartScenario(RuntimeEnvironment.getApplication(), missing = true)
        }

    @Test fun failureCompletesRestorationAndWriterContinues() =
        runBlocking {
            val bundle =
                (
                    ContentLoader.load(ContentSource.SHIPPED)
                        as ContentLoadResult.Loaded
                ).bundle
            val repository =
                object : SessionSnapshotRepository {
                    override suspend fun clear(attempts: List<dev.cubentia.application.session.AttemptRecord>) =
                        dev.cubentia.application.port.SnapshotSave.Saved

                    override suspend fun latest() = SnapshotLoad.Failed

                    override suspend fun save(
                        snapshot: SessionSnapshot,
                        attempts: List<AttemptRecord>,
                    ) = SnapshotSave.Failed
                }
            val writer = SerialWriter()
            val events = CopyOnWriteArrayList<SessionEvent>()
            val host =
                SessionHost(
                    TickingClock(),
                    Dispatchers.Default.limitedParallelism(1),
                    repository,
                    writer,
                ) { events += it }
            host.restore(bundle)
            host.awaitRestored()
            // AD-16: the failed read is an event, and the Learner starts fresh.
            assertEquals(listOf(SessionEvent.SnapshotLoadFailed::class), events.map { it::class })
            assertEquals(SessionState.IDLE, host.state.value)
            val lesson =
                bundle.stages
                    .first()
                    .lessons
                    .first()
            host.dispatch {
                SessionEvent.LessonStarted(lesson, bundle.tutorConfig, it)
            }
            val started = host.state.value
            writer.flush()
            assertEquals(
                listOf(SessionEvent.SnapshotLoadFailed::class, SessionEvent.LessonStarted::class, SessionEvent.SnapshotSaveFailed::class),
                events.map { it::class },
            )
            // The failed write changes nothing; the session keeps running.
            assertTrue(started === host.state.value)
            assertTrue(host.state.value.isActive)
            writer.close()
        }

    @Test fun thrownLoadAndSaveBecomeFailureEvents() =
        runBlocking {
            val bundle =
                (
                    ContentLoader.load(ContentSource.SHIPPED)
                        as ContentLoadResult.Loaded
                ).bundle
            val repository =
                object : SessionSnapshotRepository {
                    override suspend fun clear(attempts: List<dev.cubentia.application.session.AttemptRecord>) =
                        dev.cubentia.application.port.SnapshotSave.Saved

                    override suspend fun latest(): SnapshotLoad = throw IllegalStateException("disk")

                    override suspend fun save(
                        snapshot: SessionSnapshot,
                        attempts: List<AttemptRecord>,
                    ): SnapshotSave = throw IllegalStateException("disk")
                }
            val writer = SerialWriter()
            val events = CopyOnWriteArrayList<SessionEvent>()
            val host =
                SessionHost(
                    TickingClock(),
                    Dispatchers.Default.limitedParallelism(1),
                    repository,
                    writer,
                ) { events += it }
            try {
                withTimeout(5_000) {
                    host.restore(bundle)
                    host.awaitRestored()
                }
                assertEquals(listOf(SessionEvent.SnapshotLoadFailed::class), events.map { it::class })
                assertEquals(SessionState.IDLE, host.state.value)
                val lesson =
                    bundle.stages
                        .first()
                        .lessons
                        .first()
                host.dispatch { SessionEvent.LessonStarted(lesson, bundle.tutorConfig, it) }
                host.dispatch { SessionEvent.MoveCommitted(FaceTurn(Face.R, Turn.CLOCKWISE), it) }
                writer.flush()
                assertEquals(2, events.count { it is SessionEvent.SnapshotSaveFailed })
                assertEquals(listOf(FaceTurn(Face.R, Turn.CLOCKWISE)), host.state.value.history.done)
            } finally {
                writer.close()
            }
        }

    @Test fun cancelledRestoreStaysOpenForTheNextCall() =
        runBlocking {
            val bundle =
                (
                    ContentLoader.load(ContentSource.SHIPPED)
                        as ContentLoadResult.Loaded
                ).bundle
            val lesson =
                bundle.stages
                    .first()
                    .lessons
                    .first()
            val source = SessionHost(TickingClock())
            source.dispatch { SessionEvent.LessonStarted(lesson, bundle.tutorConfig, it) }
            source.dispatch { SessionEvent.MoveCommitted(FaceTurn(Face.R, Turn.CLOCKWISE), it) }
            val saved = checkNotNull(SessionSnapshot.of(source.state.value, "saved", bundle.contentRevision))
            val reading = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val reads = AtomicInteger()
            val repository =
                object : SessionSnapshotRepository {
                    override suspend fun clear(attempts: List<dev.cubentia.application.session.AttemptRecord>) =
                        dev.cubentia.application.port.SnapshotSave.Saved

                    override suspend fun latest(): SnapshotLoad {
                        if (reads.getAndIncrement() == 0) {
                            reading.complete(Unit)
                            gate.await()
                        }
                        return SnapshotLoad.Found(saved)
                    }

                    override suspend fun save(
                        snapshot: SessionSnapshot,
                        attempts: List<AttemptRecord>,
                    ) = SnapshotSave.Saved
                }
            val writer = SerialWriter()
            val host =
                SessionHost(
                    TickingClock(),
                    Dispatchers.Default.limitedParallelism(1),
                    repository,
                    writer,
                )
            try {
                // The shell leaves composition mid-read (for example a rotation on cold start).
                val first = launch(Dispatchers.Default) { host.restore(bundle) }
                withTimeout(5_000) { reading.await() }
                first.cancelAndJoin()
                gate.complete(Unit)
                withTimeout(5_000) {
                    host.restore(bundle)
                    host.awaitRestored()
                }
                assertEquals(listOf(FaceTurn(Face.R, Turn.CLOCKWISE)), host.state.value.history.done)
            } finally {
                gate.complete(Unit)
                writer.close()
            }
        }

    @Test
    fun dispatchCapturesInCommitOrderWithoutWaitingForIo() =
        runBlocking {
            val bundle =
                (
                    ContentLoader.load(ContentSource.SHIPPED)
                        as ContentLoadResult.Loaded
                ).bundle
            val gate = CompletableDeferred<Unit>()
            val snapshots = mutableListOf<SessionSnapshot>()
            val repository =
                object : SessionSnapshotRepository {
                    override suspend fun clear(attempts: List<dev.cubentia.application.session.AttemptRecord>) =
                        dev.cubentia.application.port.SnapshotSave.Saved

                    override suspend fun latest() = SnapshotLoad.None

                    override suspend fun save(
                        snapshot: SessionSnapshot,
                        attempts: List<AttemptRecord>,
                    ): SnapshotSave {
                        gate.await()
                        snapshots += snapshot
                        return SnapshotSave.Saved
                    }
                }
            val writer = SerialWriter()
            val host =
                SessionHost(
                    TickingClock(),
                    Dispatchers.Default.limitedParallelism(1),
                    repository,
                    writer,
                )
            try {
                host.restore(bundle)
                val lesson =
                    bundle.stages
                        .first()
                        .lessons
                        .first()
                withTimeout(5_000) {
                    host.dispatch {
                        SessionEvent.LessonStarted(lesson, bundle.tutorConfig, it)
                    }
                    host.dispatch {
                        SessionEvent.MoveCommitted(
                            FaceTurn(
                                Face.R,
                                Turn.CLOCKWISE,
                            ),
                            it,
                        )
                    }
                    host.dispatch {
                        SessionEvent.HintRequested(it)
                    }
                    host.dispatch {
                        SessionEvent.LessonStarted(lesson, bundle.tutorConfig, it)
                    }
                }
                assertTrue(snapshots.isEmpty())
                gate.complete(Unit)
                writer.flush()
                assertEquals(listOf(1L, 2L, 2L, 3L), snapshots.map { it.revision })
                assertEquals(listOf("", "R", "R", ""), snapshots.map { it.done })
                assertEquals(listOf(0, 0, 1, 0), snapshots.map { it.hintsUsed })
                assertTrue(snapshots.all { it.contentRevision == bundle.contentRevision })
                assertEquals(snapshots[0].sessionId, snapshots[2].sessionId)
                assertNotEquals(snapshots[0].sessionId, snapshots[3].sessionId)
            } finally {
                gate.complete(Unit)
                writer.close()
            }
        }
}
