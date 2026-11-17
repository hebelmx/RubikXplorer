package dev.cubentia.app.data

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.cubentia.application.port.CalibrationLoad
import dev.cubentia.application.port.CalibrationWrite
import dev.cubentia.application.preference.Preferences
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.Lab
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RoomCalibrationRepositoryTest {
    @Test fun roundTripReplaceRestartResetAndFailures() =
        runBlocking {
            val app = RuntimeEnvironment.getApplication()
            val name = "calibration-restart.db"
            app.deleteDatabase(name)

            fun open() = Room.databaseBuilder<RubikDatabase>(app, name).setDriver(AndroidSQLiteDriver()).build()
            val initial = CalibrationCenters(Color.entries.associateWith { Lab(60.0, it.ordinal.toDouble(), 12.0) })
            val replacement = CalibrationCenters(Color.entries.associateWith { Lab(70.0, it.ordinal.toDouble(), 15.0) })
            val first = open()
            try {
                val repo = RoomCalibrationRepository(first)
                assertEquals(CalibrationLoad.Loaded(null), repo.load())
                assertEquals(CalibrationWrite.Saved, repo.save(initial))
                assertEquals(CalibrationLoad.Loaded(initial), repo.load())
                assertEquals(CalibrationWrite.Saved, repo.save(replacement))
                first.preferences().save(PreferencesEntity.of(Preferences.DEFAULT))
                first.checkpoints().replace(CheckpointEntity("LESSON", "kept"))
                first.progress().insert(
                    listOf(ProgressEventEntity(sessionId = "kept", revision = 1, type = "LESSON_COMPLETED", lessonId = "lesson", atMs = 0)),
                )
            } finally {
                first.close()
            }
            val second = open()
            val repo = RoomCalibrationRepository(second)
            try {
                assertEquals(CalibrationLoad.Loaded(replacement), repo.load())
                assertEquals(CalibrationWrite.Saved, repo.reset())
                assertEquals(CalibrationLoad.Loaded(null), repo.load())
                assertEquals(PreferencesEntity.of(Preferences.DEFAULT), second.preferences().load())
                assertEquals("kept", second.checkpoints().find("LESSON")?.payload)
                assertEquals(
                    "kept",
                    second
                        .progress()
                        .all()
                        .single()
                        .sessionId,
                )
            } finally {
                second.close()
                app.deleteDatabase(name)
            }
            assertEquals(CalibrationLoad.Failed, repo.load())
            assertEquals(CalibrationWrite.Failed, repo.save(initial))
            assertEquals(CalibrationWrite.Failed, repo.reset())
        }
}
