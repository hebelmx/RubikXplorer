package dev.rubikxplorer.app.data

import android.app.Application
import dev.rubikxplorer.app.session.SessionHost
import dev.rubikxplorer.app.session.TickingClock
import dev.rubikxplorer.application.port.SessionSnapshotRepository
import dev.rubikxplorer.application.port.SnapshotLoad
import dev.rubikxplorer.application.port.SnapshotSave
import dev.rubikxplorer.application.session.SessionEvent
import dev.rubikxplorer.application.session.SessionSnapshot
import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.content.ContentLoader
import dev.rubikxplorer.domain.content.ContentSource
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Turn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
                    override suspend fun latest() = SnapshotLoad.Failed

                    override suspend fun save(snapshot: SessionSnapshot) = SnapshotSave.Failed
                }
            val writer = SerialWriter()
            val host =
                SessionHost(
                    TickingClock(),
                    Dispatchers.Default.limitedParallelism(1),
                    repository,
                    writer,
                )
            host.restore(bundle)
            host.awaitRestored()
            val lesson =
                bundle.stages
                    .first()
                    .lessons
                    .first()
            host.dispatch {
                SessionEvent.LessonStarted(lesson, bundle.tutorConfig, it)
            }
            writer.flush()
            assertTrue(host.state.value.isActive)
            writer.close()
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
                    override suspend fun latest() = SnapshotLoad.None

                    override suspend fun save(snapshot: SessionSnapshot): SnapshotSave {
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
