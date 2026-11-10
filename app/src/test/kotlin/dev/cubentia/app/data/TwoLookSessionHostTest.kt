package dev.cubentia.app.data

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.session.TickingClock
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.application.session.SessionKind
import dev.cubentia.application.usecase.CompleteLesson
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
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
class TwoLookSessionHostTest {
    @Test fun twoLookResumesFromItsSnapshotAndRecordsCompletionWithoutCourseUnlocks() =
        runBlocking {
            val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
            val lesson =
                bundle.twoLookStages
                    .first()
                    .lessons
                    .first()
            val db =
                Room
                    .inMemoryDatabaseBuilder<RubikDatabase>(RuntimeEnvironment.getApplication())
                    .setDriver(AndroidSQLiteDriver())
                    .build()
            val writer = SerialWriter()
            val progress = RoomProgressRepository(db)

            fun host() =
                SessionHost(
                    clock = TickingClock(),
                    confined = Dispatchers.Default.limitedParallelism(1),
                    repository = RoomSessionSnapshotRepository(db),
                    writer = writer,
                    checkpoints = RoomCheckpointRepository(db),
                    completeLesson = CompleteLesson(progress),
                    progress = progress,
                )
            try {
                val original = host()
                original.restore(bundle)
                original.dispatch { SessionEvent.LessonStarted(lesson, bundle.tutorConfig, it, kind = SessionKind.TWO_LOOK) }
                val moves = (Notation.parse(lesson.demonstration) as NotationResult.Parsed).moves
                original.dispatch { SessionEvent.MoveCommitted(moves.first(), it) }
                writer.flush()
                val resumed = host()
                resumed.restore(bundle)
                assertEquals(SessionKind.TWO_LOOK, resumed.state.value.kind)
                assertEquals(
                    lesson.id,
                    resumed.state.value.lesson
                        ?.id,
                )
                assertEquals(original.state.value.virtual, resumed.state.value.virtual)
                moves.drop(1).forEach { move -> resumed.dispatch { SessionEvent.MoveCommitted(move, it) } }
                writer.flush()
                assertTrue(resumed.state.value.objectiveMet)
                assertEquals(
                    listOf(lesson.id),
                    resumed.progressEvents.value
                        .filterIsInstance<ProgressEvent.LessonCompleted>()
                        .map { it.lessonId },
                )
                assertFalse(resumed.progressEvents.value.any { it is ProgressEvent.StageUnlocked })
            } finally {
                writer.close()
                db.close()
            }
        }
}
