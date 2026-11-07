package dev.rubikxplorer.app.data

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.rubikxplorer.app.session.SessionHost
import dev.rubikxplorer.app.session.TickingClock
import dev.rubikxplorer.application.port.ProgressLoad
import dev.rubikxplorer.application.session.SessionEvent
import dev.rubikxplorer.application.usecase.CompleteLesson
import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.content.ContentLoader
import dev.rubikxplorer.domain.content.ContentSource
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.NotationResult
import dev.rubikxplorer.domain.cube.Turn
import dev.rubikxplorer.domain.tutor.ProgressEvent
import dev.rubikxplorer.domain.tutor.StageProgress
import dev.rubikxplorer.domain.tutor.StageStatus
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
            } finally {
                writer.close()
                db.close()
            }
        }
}
