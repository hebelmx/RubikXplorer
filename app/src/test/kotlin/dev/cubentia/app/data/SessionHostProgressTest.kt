package dev.cubentia.app.data

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.cubentia.app.content.twoLookMoves
import dev.cubentia.app.random.KotlinRandomSource
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.session.TickingClock
import dev.cubentia.application.port.AttemptLoad
import dev.cubentia.application.port.ProgressLoad
import dev.cubentia.application.session.CubeMode
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.application.session.SessionKind
import dev.cubentia.application.session.SyncState
import dev.cubentia.application.usecase.CompleteLesson
import dev.cubentia.application.usecase.TwoLookPractice
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.tutor.AttemptOutcome
import dev.cubentia.domain.tutor.ProgressEvent
import dev.cubentia.domain.tutor.StageProgress
import dev.cubentia.domain.tutor.StageStatus
import dev.cubentia.domain.tutor.TwoLookMastery
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
class SessionHostProgressTest {
    @Test fun completingStageOneLessonsViaSessionHostUnlocksStageTwoWhileMoveCountAloneDoesNot() =
        runBlocking {
            val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
            val stage1 = bundle.stages[0]
            val stage2 = bundle.stages[1]
            val stage3 = bundle.stages[2]
            val stage4 = bundle.stages[3]
            val stage5 = bundle.stages[4]
            val stage6 = bundle.stages[5]
            val stage7 = bundle.stages[6]
            val db =
                Room
                    .inMemoryDatabaseBuilder<RubikDatabase>(RuntimeEnvironment.getApplication())
                    .setDriver(AndroidSQLiteDriver())
                    .build()
            val writer = SerialWriter()
            val progressRepo = RoomProgressRepository(db)
            val host =
                SessionHost(
                    clock = TickingClock(startMillis = 1_000),
                    confined = Dispatchers.Default.limitedParallelism(1),
                    repository = RoomSessionSnapshotRepository(db),
                    writer = writer,
                    checkpoints = RoomCheckpointRepository(db),
                    completeLesson = CompleteLesson(progressRepo),
                    progress = progressRepo,
                )
            try {
                host.restore(bundle)
                host.awaitRestored()
                assertEquals(emptyList<ProgressEvent>(), host.progressEvents.value)

                // 10 Moves that do not satisfy lesson-01-01's Completion Predicate must record nothing.
                val firstLesson = stage1.lessons.first()
                host.dispatch { SessionEvent.LessonStarted(firstLesson, bundle.tutorConfig, it) }
                val nonSolvingMove = FaceTurn(Face.R, Turn.CLOCKWISE)
                repeat(10) {
                    host.dispatch { at -> SessionEvent.MoveCommitted(nonSolvingMove, at) }
                }
                assertFalse(host.state.value.objectiveMet)
                writer.flush()
                assertEquals(emptyList<ProgressEvent>(), (progressRepo.all() as ProgressLoad.Found).events)
                assertEquals(emptyList<ProgressEvent>(), host.progressEvents.value)

                // Completing all 3 Lessons of Stage 1 via their Completion Predicates records LessonCompleted and unlocks Stage 2.
                for (lesson in stage1.lessons) {
                    host.dispatch { SessionEvent.LessonStarted(lesson, bundle.tutorConfig, it) }
                    val moves = (Notation.parse(lesson.demonstration) as NotationResult.Parsed).moves
                    for (move in moves) {
                        host.dispatch { at -> SessionEvent.MoveCommitted(move, at) }
                    }
                    assertTrue(host.state.value.objectiveMet)
                }
                writer.flush()

                val events = (progressRepo.all() as ProgressLoad.Found).events
                assertEquals(events, host.progressEvents.value)
                assertEquals(
                    stage1.lessons.map { it.id } to listOf(stage2.id),
                    events.filterIsInstance<ProgressEvent.LessonCompleted>().map { it.lessonId } to
                        events.filterIsInstance<ProgressEvent.StageUnlocked>().map { it.stageId },
                )
                assertEquals(
                    mapOf(
                        stage1.id to StageStatus.COMPLETED,
                        stage2.id to StageStatus.IN_PROGRESS,
                        stage3.id to StageStatus.LOCKED,
                        stage4.id to StageStatus.LOCKED,
                        stage5.id to StageStatus.LOCKED,
                        stage6.id to StageStatus.LOCKED,
                        stage7.id to StageStatus.LOCKED,
                    ),
                    StageProgress.statuses(bundle.stages, events),
                )

                // Restoring a second SessionHost against the same database loads progressEvents on restore.
                val restoredHost =
                    SessionHost(
                        clock = TickingClock(startMillis = 5_000),
                        confined = Dispatchers.Default.limitedParallelism(1),
                        repository = RoomSessionSnapshotRepository(db),
                        writer = writer,
                        checkpoints = RoomCheckpointRepository(db),
                        completeLesson = CompleteLesson(progressRepo),
                        progress = progressRepo,
                    )
                restoredHost.restore(bundle)
                restoredHost.awaitRestored()
                assertEquals(events, restoredHost.progressEvents.value)

                // resetProgress appends a ProgressReset event and resets current-epoch stage statuses.
                val resetSuccess = restoredHost.resetProgress()
                assertTrue(resetSuccess)
                writer.flush()
                val postResetEvents = restoredHost.progressEvents.value
                assertTrue(postResetEvents.any { it is ProgressEvent.ProgressReset && it.epoch == 1L })
                val postResetStatuses = StageProgress.statuses(bundle.stages, postResetEvents)
                assertEquals(StageStatus.IN_PROGRESS, postResetStatuses[stage1.id])
                assertEquals(StageStatus.LOCKED, postResetStatuses[stage2.id])
                assertEquals(StageStatus.LOCKED, postResetStatuses[stage3.id])

                // Prior events remain intact in the Room database (append-only).
                val allInDb = (progressRepo.all() as ProgressLoad.Found).events
                assertTrue(allInDb.containsAll(events))
            } finally {
                writer.close()
                db.close()
            }
        }

    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
    private val lesson = bundle.stages[0].lessons.first()
    private val context = RuntimeEnvironment.getApplication()

    /** A host and its writes on the database file [name], as a process would open them. */
    private inner class Process(
        name: String,
    ) {
        val db = Room.databaseBuilder<RubikDatabase>(context, name).setDriver(AndroidSQLiteDriver()).build()
        val writer = SerialWriter()
        val progress = RoomProgressRepository(db)
        val host =
            SessionHost(
                clock = TickingClock(startMillis = 1_000),
                confined = Dispatchers.Default.limitedParallelism(1),
                repository = RoomSessionSnapshotRepository(db),
                writer = writer,
                checkpoints = RoomCheckpointRepository(db),
                completeLesson = CompleteLesson(progress),
                progress = progress,
                attempts = RoomAttemptRepository(db),
            )

        suspend fun completions(): Int {
            writer.flush()
            return (progress.all() as ProgressLoad.Found).events.count { it is ProgressEvent.LessonCompleted }
        }

        suspend fun close() {
            writer.close()
            db.close()
        }
    }

    private suspend fun Process.meetObjectiveOnTheRealCube() {
        host.restore(bundle)
        host.awaitRestored()
        host.dispatch { SessionEvent.LessonStarted(lesson, bundle.tutorConfig, it) }
        host.dispatch { SessionEvent.ModeSwitched(CubeMode.PHYSICAL, it) }
        host.dispatch { SessionEvent.PhysicalConfirmed(null, it) }
        (Notation.parse(lesson.demonstration) as NotationResult.Parsed).moves.forEach { move ->
            host.dispatch { SessionEvent.PhysicalConfirmed(move, it) }
        }
        assertTrue(host.state.value.objectiveMet)
    }

    private fun database() = "progress-physical-${java.util.UUID.randomUUID()}.db"

    @Test fun physicalObjectiveIsNotRecordedUntilConfirmedAndThenExactlyOnce() =
        runBlocking {
            val process = Process(database())
            try {
                process.meetObjectiveOnTheRealCube()
                assertEquals(0, process.completions())
                assertTrue(process.host.state.value.physicalSolveUnconfirmed)

                process.host.dispatch { SessionEvent.PhysicalSolveConfirmed(it) }
                assertEquals(1, process.completions())
                assertEquals(
                    1,
                    process.host.progressEvents.value
                        .count { it is ProgressEvent.LessonCompleted },
                )

                process.host.dispatch { SessionEvent.PhysicalSolveConfirmed(it) }
                assertEquals(1, process.completions())
            } finally {
                process.close()
            }
        }

    @Test fun switchingToVirtualNeverRecordsAnUnconfirmedSolve() =
        runBlocking {
            val process = Process(database())
            try {
                process.meetObjectiveOnTheRealCube()
                process.host.dispatch { SessionEvent.ModeSwitched(CubeMode.VIRTUAL, it) }
                assertEquals(0, process.completions())
                assertTrue(process.host.state.value.physicalSolveUnconfirmed)
            } finally {
                process.close()
            }
        }

    @Test fun processDeathBeforeConfirmingRestoresUnconfirmedAndConfirmingRecordsIt() =
        runBlocking {
            val name = database()
            val first = Process(name)
            try {
                first.meetObjectiveOnTheRealCube()
                first.writer.flush()
            } finally {
                first.close()
            }
            val second = Process(name)
            try {
                second.host.restore(bundle)
                second.host.awaitRestored()
                assertTrue(second.host.state.value.physicalSolveUnconfirmed)
                assertEquals(SyncState.PHYSICAL_AWAITING_CONFIRM, second.host.state.value.syncState)
                assertEquals(0, second.completions())

                // The restored cube has not been confirmed yet, so the Learner's word does not count.
                second.host.dispatch { SessionEvent.PhysicalSolveConfirmed(it) }
                assertEquals(0, second.completions())

                second.host.dispatch { SessionEvent.PhysicalConfirmed(null, it) }
                second.host.dispatch { SessionEvent.PhysicalSolveConfirmed(it) }
                assertTrue(second.host.state.value.physicalSolveConfirmed)
                assertEquals(1, second.completions())
            } finally {
                second.close()
            }
        }

    @Test fun processDeathAfterConfirmingRestoresConfirmedWithNoSecondWrite() =
        runBlocking {
            val name = database()
            val first = Process(name)
            try {
                first.meetObjectiveOnTheRealCube()
                first.host.dispatch { SessionEvent.PhysicalSolveConfirmed(it) }
                assertEquals(1, first.completions())
            } finally {
                first.close()
            }
            val second = Process(name)
            try {
                second.host.restore(bundle)
                second.host.awaitRestored()
                assertTrue(second.host.state.value.physicalSolveConfirmed)
                second.host.dispatch { SessionEvent.PhysicalConfirmed(null, it) }
                second.host.dispatch { SessionEvent.PhysicalSolveConfirmed(it) }
                assertEquals(1, second.completions())
            } finally {
                second.close()
            }
        }

    @Test fun editorReplacedLlPracticeRestoredFromColdStartEarnsNoMasteryCredit() =
        runBlocking {
            val name = database()
            val algorithms = bundle.twoLookMoves()
            val practice = TwoLookPractice(KotlinRandomSource()).lesson(algorithms)
            val oneTurnFromSolved = CubeState.SOLVED.apply(FaceTurn(Face.U, Turn.COUNTERCLOCKWISE))
            val first = Process(name)
            try {
                first.host.restore(bundle)
                first.host.awaitRestored()
                first.host.dispatch { SessionEvent.PracticeStarted(practice, bundle.tutorConfig, it, algorithms) }
                first.host.dispatch { SessionEvent.ModeSwitched(CubeMode.PHYSICAL, it) }
                first.host.dispatch { SessionEvent.PhysicalConfirmed(null, it) }
                // The Manual Cube Editor replaces the fresh scramble with a cube one U turn from solved.
                first.host.dispatch { SessionEvent.EditorEntered(oneTurnFromSolved, it) }
                assertEquals(1, first.host.state.value.twoLookErrors)
                first.writer.flush()
            } finally {
                first.close()
            }
            val second = Process(name)
            try {
                second.host.restore(bundle)
                second.host.awaitRestored()
                assertEquals(SessionKind.PRACTICE, second.host.state.value.kind)
                assertEquals(
                    practice.entryState,
                    second.host.state.value.lesson
                        ?.entryState,
                )
                assertEquals(oneTurnFromSolved, second.host.state.value.virtual)
                assertEquals(1, second.host.state.value.twoLookErrors)

                second.host.dispatch { SessionEvent.PhysicalConfirmed(null, it) }
                second.host.dispatch { SessionEvent.PhysicalConfirmed(FaceTurn(Face.U, Turn.CLOCKWISE), it) }
                assertTrue(second.host.state.value.objectiveMet)
                second.host.dispatch { SessionEvent.PhysicalSolveConfirmed(it) }
                second.writer.flush()

                val records = (RoomAttemptRepository(second.db).all() as AttemptLoad.Found).records
                val completed =
                    records.filter {
                        it.objectiveId == TwoLookMastery.PRACTICE_ID.value &&
                            it.outcome == AttemptOutcome.COMPLETED
                    }
                assertEquals(1, completed.size)
                assertTrue((completed.single().errors ?: 0) >= 1)
                assertTrue(records.none(TwoLookMastery::qualifies))
            } finally {
                second.close()
            }
        }
}
