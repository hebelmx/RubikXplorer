package dev.rubikxplorer.app.data

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.rubikxplorer.app.session.SessionHost
import dev.rubikxplorer.app.session.TickingClock
import dev.rubikxplorer.application.port.CheckpointLoad
import dev.rubikxplorer.application.port.CheckpointRepository
import dev.rubikxplorer.application.port.CheckpointSave
import dev.rubikxplorer.application.session.Checkpoint
import dev.rubikxplorer.application.session.SessionEvent
import dev.rubikxplorer.application.session.SessionKind
import dev.rubikxplorer.application.session.SessionSnapshot
import dev.rubikxplorer.application.session.SessionState
import dev.rubikxplorer.application.session.reduce
import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.content.ContentLoader
import dev.rubikxplorer.domain.content.ContentSource
import dev.rubikxplorer.domain.content.FaceletString
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.NotationResult
import dev.rubikxplorer.domain.text.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SessionHostCheckpointTest {
    @Test fun boundaryAndRestartMatrix() =
        runBlocking {
            val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
            val lesson =
                bundle.stages
                    .first()
                    .lessons
                    .first()
            val context = RuntimeEnvironment.getApplication()
            for (scenario in listOf(
                "same",
                "revision",
                "stage",
                "missing",
                "missing-stage",
                "playback",
                "kept",
                "corrupt",
                "decoded-invalid-history",
            )) {
                val name = "checkpoint-${java.util.UUID.randomUUID()}.db"

                fun database() = Room.databaseBuilder<RubikDatabase>(context, name).setDriver(AndroidSQLiteDriver()).build()
                var db = database()
                var writer = SerialWriter()
                try {
                    var repo = RoomCheckpointRepository(db)
                    val host =
                        SessionHost(
                            TickingClock(),
                            Dispatchers.Default.limitedParallelism(1),
                            RoomSessionSnapshotRepository(db),
                            writer,
                            checkpoints = repo,
                        )
                    host.restore(bundle)
                    host.dispatch { SessionEvent.LessonStarted(lesson, bundle.tutorConfig, it, true) }
                    for (move in (
                        Notation.parse(
                            lesson.demonstration,
                        ) as NotationResult.Parsed
                    ).moves) {
                        host.dispatch { SessionEvent.MoveCommitted(move, it) }
                    }
                    writer.flush()
                    val saved = (repo.find(SessionKind.LESSON) as CheckpointLoad.Found).checkpoint
                    assertEquals(
                        FaceletString.encode(
                            host.state.value.checkpoint!!
                                .state
                                .toFacelets(),
                        ),
                        saved.cube,
                    )
                    assertEquals(CheckpointPayload.encode(saved), db.checkpoints().find("LESSON")!!.payload)
                    if (scenario == "stage") repo.save(saved.copy(stageId = "old-stage"))
                    // Keep the old snapshot for the revision-change case; otherwise force fallback by removing it.
                    // "kept" keeps a usable snapshot; "corrupt" keeps an unreadable one, so its read fails.
                    if (scenario != "revision" && scenario != "kept") {
                        val sql =
                            if (scenario ==
                                "corrupt"
                            ) {
                                "UPDATE session_snapshot SET payload = 'broken'"
                            } else {
                                "DELETE FROM session_snapshot"
                            }
                        writer.read {
                            AndroidSQLiteDriver().open(context.getDatabasePath(name).path).use { connection ->
                                connection.prepare(sql).use { it.step() }
                            }
                        }
                    }
                    if (scenario == "decoded-invalid-history" || scenario == "playback") {
                        val snapshot = checkNotNull(SessionSnapshot.of(host.state.value, "invalid-history", bundle.contentRevision))
                        writer.read { RoomSessionSnapshotRepository(db).save(snapshot.copy(done = "invalid")) }
                    }
                    if (scenario == "playback") {
                        val playback =
                            reduce(
                                SessionState.IDLE,
                                SessionEvent.SolvePlaybackStarted(CubeState.SOLVED, emptyList(), Instant.fromEpochMilliseconds(0)),
                            ).state
                        writer.read { RoomSessionSnapshotRepository(db).save(checkNotNull(SessionSnapshot.of(playback, "playback", null))) }
                    }
                    writer.close()
                    db.close()
                    db = database()
                    writer = SerialWriter()
                    repo = RoomCheckpointRepository(db)
                    var reads = 0
                    val events = java.util.concurrent.CopyOnWriteArrayList<SessionEvent>()
                    val counting =
                        object : CheckpointRepository {
                            override suspend fun save(checkpoint: Checkpoint) = repo.save(checkpoint)

                            override suspend fun find(kind: SessionKind): CheckpointLoad {
                                reads++
                                return repo.find(kind)
                            }
                        }
                    val restored =
                        SessionHost(
                            TickingClock(),
                            Dispatchers.Default.limitedParallelism(1),
                            RoomSessionSnapshotRepository(db),
                            writer,
                            checkpoints = counting,
                            reduced = { events.add(it) },
                        )
                    val content =
                        when (scenario) {
                            "revision" -> {
                                bundle.copy(contentRevision = "updated")
                            }

                            "missing" -> {
                                val stage = bundle.stages.first()
                                check(stage.lessons.size > 1) { "the first Stage needs a second Lesson to replace the removed one" }
                                bundle.copy(stages = listOf(stage.copy(lessons = stage.lessons.drop(1))) + bundle.stages.drop(1))
                            }

                            "missing-stage" -> {
                                bundle.copy(stages = emptyList())
                            }

                            else -> {
                                bundle
                            }
                        }
                    restored.restore(content)
                    restored.awaitRestored()
                    val state = restored.state.value
                    if (scenario == "missing-stage") {
                        assertSame(SessionState.IDLE, state)
                    } else {
                        assertEquals(saved.cube, FaceletString.encode(state.virtual.toFacelets()))
                        assertEquals(saved.teachingMode, state.teachingMode)
                        assertTrue(state.adaptiveAssistance)
                        assertFalse(state.history.canRedo)
                        if (scenario == "same" || scenario == "kept" || scenario == "corrupt" || scenario == "decoded-invalid-history" ||
                            scenario == "playback"
                        ) {
                            assertEquals(Text("msg.lesson.resumed"), state.restoreNote)
                            assertEquals(saved.history, Notation.format(state.history.done))
                        } else {
                            assertEquals(Text("msg.lesson.updated"), state.restoreNote)
                            assertTrue(state.history.done.isEmpty())
                            assertEquals(state.virtual, state.entry)
                            val expected =
                                if (scenario == "missing") {
                                    content.stages
                                        .first()
                                        .lessons
                                        .first()
                                        .id
                                } else {
                                    lesson.id
                                }
                            assertEquals(expected, state.lesson!!.id)
                            writer.flush()
                            assertEquals(
                                content.contentRevision,
                                (repo.find(SessionKind.LESSON) as CheckpointLoad.Found).checkpoint.contentRevision,
                            )
                        }
                    }
                    assertEquals(if (scenario == "kept") 0 else 1, reads)
                    assertEquals(
                        scenario == "kept" || scenario == "decoded-invalid-history",
                        events.any { it is SessionEvent.SessionRestored },
                    )
                    assertEquals(scenario == "corrupt", events.any { it is SessionEvent.SnapshotLoadFailed })
                } finally {
                    writer.close()
                    db.close()
                    context.deleteDatabase(name)
                }
            }
        }

    @Test fun endingSolveReadsQueuedCheckpointAndDropsLaterMoves() =
        runBlocking {
            val bundle =
                dev.rubikxplorer.app.session
                    .testBundle(
                        dev.rubikxplorer.app.session
                            .twoStepLesson(),
                    )
            val lesson =
                bundle.stages
                    .first()
                    .lessons
                    .first()
            val context = RuntimeEnvironment.getApplication()
            val db = Room.inMemoryDatabaseBuilder<RubikDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
            val writer = SerialWriter()
            try {
                val host =
                    SessionHost(
                        TickingClock(),
                        Dispatchers.Default.limitedParallelism(1),
                        RoomSessionSnapshotRepository(db),
                        writer,
                        checkpoints = RoomCheckpointRepository(db),
                    )
                host.restore(bundle)
                host.dispatch { SessionEvent.LessonStarted(lesson, bundle.tutorConfig, it) }
                val moves = (Notation.parse(lesson.demonstration) as NotationResult.Parsed).moves
                host.dispatch { SessionEvent.MoveCommitted(moves.first(), it) }
                val checkpoint = host.state.value.checkpoint!!
                host.dispatch { SessionEvent.MoveCommitted((Notation.parse("R") as NotationResult.Parsed).moves.single(), it) }
                host.dispatch { SessionEvent.SolvePlaybackStarted(CubeState.SOLVED, emptyList(), it) }
                host.endSolve(bundle)
                val resumed = host.state.value
                assertEquals(SessionKind.LESSON, resumed.kind)
                assertEquals(checkpoint, resumed.history)
                assertEquals(checkpoint.state, resumed.virtual)
                assertEquals(Text("msg.lesson.resumed"), resumed.restoreNote)
                assertTrue(resumed.attemptOpen)
                writer.flush()
                val rows = db.attempts().all()
                assertEquals(listOf("STARTED", "ENDED", "STARTED"), rows.map { it.event })
                assertEquals("ABANDONED", rows[1].outcome)
                assertEquals(rows[0].sessionId, rows[1].sessionId)
                assertFalse(rows[1].sessionId == rows[2].sessionId)
            } finally {
                writer.close()
                db.close()
            }
        }

    @Test fun endingSolveWithoutCheckpointStaysIdle() =
        runBlocking {
            val bundle =
                dev.rubikxplorer.app.session
                    .testBundle()
            val host = SessionHost(TickingClock(), Dispatchers.Default.limitedParallelism(1))
            host.dispatch { SessionEvent.SolvePlaybackStarted(CubeState.SOLVED, emptyList(), it) }
            host.endSolve(bundle)
            assertFalse(host.state.value.isActive)
        }

    @Test fun failedAndThrowingRepositoriesDispatchFailureEvents() =
        runBlocking {
            val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
            for (throws in listOf(false, true)) {
                val events = java.util.concurrent.CopyOnWriteArrayList<SessionEvent>()
                val repo =
                    object : CheckpointRepository {
                        override suspend fun save(checkpoint: Checkpoint): CheckpointSave {
                            if (throws) error("save")
                            return CheckpointSave.Failed
                        }

                        override suspend fun find(kind: SessionKind): CheckpointLoad {
                            if (throws) error("load")
                            return CheckpointLoad.Failed
                        }
                    }
                val writer = SerialWriter()
                try {
                    val host =
                        SessionHost(
                            TickingClock(),
                            Dispatchers.Default.limitedParallelism(1),
                            writer = writer,
                            reduced = { events.add(it) },
                            checkpoints = repo,
                        )
                    host.restore(bundle)
                    host.awaitRestored()
                    host.dispatch { SessionEvent.SolvePlaybackStarted(CubeState.SOLVED, emptyList(), it) }
                    events.clear()
                    host.endSolve(bundle)
                    assertFalse(host.state.value.isActive)
                    assertTrue(events.any { it is SessionEvent.CheckpointLoadFailed })
                    host.dispatch {
                        SessionEvent.LessonStarted(
                            bundle.stages
                                .first()
                                .lessons
                                .first(),
                            bundle.tutorConfig,
                            it,
                        )
                    }
                    writer.flush()
                    assertTrue(events.any { it is SessionEvent.CheckpointSaveFailed })
                    assertTrue(host.state.value.isActive)
                } finally {
                    writer.close()
                }
            }
        }
}
