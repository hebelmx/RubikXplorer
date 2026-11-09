package dev.cubentia.app.data

import android.app.Application
import androidx.room3.Room
import androidx.room3.withWriteTransaction
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.cubentia.application.port.PreferencesLoad
import dev.cubentia.application.port.ProgressLoad
import dev.cubentia.application.port.ProgressRecord
import dev.cubentia.application.port.ProgressSave
import dev.cubentia.application.preference.Preference
import dev.cubentia.application.preference.Preferences
import dev.cubentia.application.preference.ThemeChoice
import dev.cubentia.application.preference.UpdatePreference
import dev.cubentia.application.usecase.CompleteLesson
import dev.cubentia.application.usecase.PassSkipCheck
import dev.cubentia.application.usecase.PlaceFromScan
import dev.cubentia.application.usecase.ResetProgress
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.content.FaceletString
import dev.cubentia.domain.content.FaceletStringResult
import dev.cubentia.domain.content.Lesson
import dev.cubentia.domain.content.LessonId
import dev.cubentia.domain.content.StageId
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.ValidationResult
import dev.cubentia.domain.tutor.ProgressEvent
import dev.cubentia.domain.tutor.StageProgress
import dev.cubentia.domain.tutor.StageStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RoomProgressRepositoryTest {
    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
    private val stages = bundle.stages

    private fun solvedState(lesson: Lesson): CubeState {
        val facelets = (FaceletString.decode(lesson.entryState) as FaceletStringResult.Decoded).facelets
        val start = (CubeValidator.validate(facelets) as ValidationResult.Valid).state
        val moves = (Notation.parse(lesson.demonstration) as NotationResult.Parsed).moves
        return start.apply(moves)
    }

    @Test fun roundTripsAllFiveEventTypesIgnoresDuplicateKeysAndHandlesFailures() =
        runBlocking {
            val db =
                Room
                    .inMemoryDatabaseBuilder<RubikDatabase>(RuntimeEnvironment.getApplication())
                    .setDriver(AndroidSQLiteDriver())
                    .build()
            val repo = RoomProgressRepository(db)
            val t0 = Instant.fromEpochMilliseconds(5_000)
            val records =
                listOf(
                    ProgressRecord("s1", 1, ProgressEvent.LessonCompleted(LessonId("lesson-01-01"), t0)),
                    ProgressRecord("s1", 2, ProgressEvent.StageSkippedByCheck(StageId("stage-02-first-cross"), t0)),
                    ProgressRecord("s1", 3, ProgressEvent.StageSkippedByPlacement(StageId("stage-01-fundamentals"), t0)),
                    ProgressRecord("s1", 4, ProgressEvent.StageUnlocked(StageId("stage-03-first-layer-corners"), t0)),
                    ProgressRecord("s1", 5, ProgressEvent.ProgressReset(1L, t0)),
                )
            try {
                assertEquals(ProgressSave.Saved, repo.append(records))
                // Replaying the exact same records with slightly different timestamp is ignored by unique index.
                val replayed =
                    records.map { record ->
                        val later = Instant.fromEpochMilliseconds(9_999)
                        val event =
                            when (val e = record.event) {
                                is ProgressEvent.LessonCompleted -> e.copy(at = later)
                                is ProgressEvent.StageSkippedByCheck -> e.copy(at = later)
                                is ProgressEvent.StageSkippedByPlacement -> e.copy(at = later)
                                is ProgressEvent.StageUnlocked -> e.copy(at = later)
                                is ProgressEvent.ProgressReset -> e.copy(at = later)
                            }
                        record.copy(event = event)
                    }
                assertEquals(ProgressSave.Saved, repo.append(replayed))
                assertEquals(ProgressLoad.Found(records.map { it.event }, records), repo.all())

                db.withWriteTransaction {
                    usePrepared("DROP TABLE progress_event") {
                        it.step()
                        Unit
                    }
                }
                assertEquals(ProgressLoad.Failed, repo.all())
                assertEquals(ProgressSave.Failed, repo.append(records))
            } finally {
                db.close()
            }
        }

    @Test fun progressSurvivesDatabaseRestartAndResetLeavesPreferencesUntouched() =
        runBlocking {
            val app = RuntimeEnvironment.getApplication()
            val name = "progress-restart.db"
            app.deleteDatabase(name)

            fun open() = Room.databaseBuilder<RubikDatabase>(app, name).setDriver(AndroidSQLiteDriver()).build()

            val t0 = Instant.fromEpochMilliseconds(1_000)
            val first = open()
            try {
                val progressRepo = RoomProgressRepository(first)
                val prefsRepo = RoomPreferencesRepository(first)
                UpdatePreference(prefsRepo)(Preferences.DEFAULT, Preference.Theme(ThemeChoice.DARK))
                val completeLesson = CompleteLesson(progressRepo)
                for (lesson in stages[0].lessons) {
                    completeLesson(lesson, solvedState(lesson), stages, t0)
                }
                PassSkipCheck(progressRepo)(stages[1].id, stages, t0)
                PlaceFromScan(progressRepo)(stages[2].id, stages, t0)
            } finally {
                first.close()
            }

            val second = open()
            try {
                val progressRepo = RoomProgressRepository(second)
                val prefsRepo = RoomPreferencesRepository(second)
                val loadedBeforeReset = progressRepo.all() as ProgressLoad.Found
                assertEquals(StageStatus.COMPLETED, StageProgress.status(stages[0], stages, loadedBeforeReset.events))
                assertEquals(StageStatus.SKIPPED, StageProgress.status(stages[1], stages, loadedBeforeReset.events))
                assertEquals(StageStatus.IN_PROGRESS, StageProgress.status(stages[2], stages, loadedBeforeReset.events))

                ResetProgress(progressRepo)(t0)
                val loadedAfterReset = progressRepo.all() as ProgressLoad.Found
                assertEquals(loadedBeforeReset.records.size + 1, loadedAfterReset.records.size)
                assertEquals(StageStatus.IN_PROGRESS, StageProgress.status(stages[0], stages, loadedAfterReset.events))
                assertEquals(StageStatus.LOCKED, StageProgress.status(stages[1], stages, loadedAfterReset.events))
                assertEquals(StageStatus.LOCKED, StageProgress.status(stages[2], stages, loadedAfterReset.events))
                assertEquals(
                    PreferencesLoad.Found(Preferences.DEFAULT.copy(theme = ThemeChoice.DARK)),
                    prefsRepo.load(),
                )
            } finally {
                second.close()
                app.deleteDatabase(name)
            }
        }
}
