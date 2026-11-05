package dev.rubikxplorer.app.data
import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.rubikxplorer.app.preferences.PreferenceStore
import dev.rubikxplorer.application.port.PreferenceSave
import dev.rubikxplorer.application.port.PreferencesLoad
import dev.rubikxplorer.application.preference.AnimationSpeed
import dev.rubikxplorer.application.preference.Preference
import dev.rubikxplorer.application.preference.Preferences
import dev.rubikxplorer.application.preference.ThemeChoice
import dev.rubikxplorer.application.preference.UpdatePreference
import dev.rubikxplorer.domain.tutor.TeachingMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RoomPreferencesRepositoryTest {
    /** A real restart: the database file is closed, then a new database, repository and store open it. */
    @Test fun preferencesSurviveClosingAndReopeningTheDatabaseFile() =
        runBlocking {
            val app = RuntimeEnvironment.getApplication()
            val name = "preferences-restart.db"
            app.deleteDatabase(name)

            fun open() = Room.databaseBuilder<RubikDatabase>(app, name).setDriver(AndroidSQLiteDriver()).build()
            val chosen =
                Preferences(ThemeChoice.LIGHT, true, AnimationSpeed.SLOW, false, true, TeachingMode.SHOW_ME)
            val first = open()
            val firstWriter = SerialWriter()
            try {
                val store = PreferenceStore(RoomPreferencesRepository(first), firstWriter)
                store.awaitLoaded()
                listOf(
                    Preference.Theme(ThemeChoice.LIGHT),
                    Preference.ColorBlindGlyphs(true),
                    Preference.Speed(AnimationSpeed.SLOW),
                    Preference.Haptics(false),
                    Preference.ReduceMotion(true),
                    Preference.LastTeachingMode(TeachingMode.SHOW_ME),
                ).forEach(store::update)
            } finally {
                firstWriter.close()
                first.close()
            }
            val second = open()
            val secondWriter = SerialWriter()
            try {
                val restarted = PreferenceStore(RoomPreferencesRepository(second), secondWriter)
                restarted.awaitLoaded()
                assertEquals(chosen, restarted.preferences.value)
            } finally {
                secondWriter.close()
                second.close()
                app.deleteDatabase(name)
            }
        }

    @Test fun restartTolerantDecodeResetAndFailures() =
        runBlocking {
            val db =
                Room
                    .inMemoryDatabaseBuilder<RubikDatabase>(RuntimeEnvironment.getApplication())
                    .setDriver(AndroidSQLiteDriver())
                    .build()
            val writer = SerialWriter()
            val repo = RoomPreferencesRepository(db)
            try {
                val store = PreferenceStore(repo, writer)
                store.awaitLoaded()
                assertEquals(Preferences.DEFAULT, store.preferences.value)
                val changes =
                    listOf(
                        Preference.Theme(ThemeChoice.DARK),
                        Preference.ColorBlindGlyphs(true),
                        Preference.Speed(AnimationSpeed.FAST),
                        Preference.Haptics(false),
                        Preference.ReduceMotion(true),
                        Preference.LastTeachingMode(TeachingMode.LET_ME_TRY),
                    )
                changes.forEach(store::update)
                writer.flush()
                val restarted = PreferenceStore(RoomPreferencesRepository(db), writer)
                restarted.awaitLoaded()
                assertEquals(store.preferences.value, restarted.preferences.value)
                val unknown =
                    PreferencesEntity.of(store.preferences.value).copy(
                        theme = "NEON",
                        animation_speed = "WARP",
                        last_teaching_mode = "OTHER",
                    )
                db.preferences().save(unknown)
                assertEquals(
                    PreferencesLoad.Found(
                        store.preferences.value.copy(
                            theme = ThemeChoice.SYSTEM,
                            animationSpeed = AnimationSpeed.NORMAL,
                            lastTeachingMode = TeachingMode.DEFAULT,
                        ),
                    ),
                    repo.load(),
                )
                db.checkpoints().replace(CheckpointEntity("LESSON", "kept"))
                val snapshot = SessionSnapshotEntity("LESSON", "session", 1, 1, "saved")
                val attempt =
                    AttemptEntity(
                        attemptId = "attempt",
                        sessionId = "session",
                        revision = 1,
                        event = "STARTED",
                        objectiveId = "objective",
                        outcome = null,
                        errors = null,
                        hints = null,
                        assistanceLevel = null,
                        atMs = 0,
                    )
                db.snapshots().replace(snapshot)
                db.attempts().insert(listOf(attempt))
                val attempts = db.attempts().all()
                UpdatePreference(repo).reset()
                assertEquals(snapshot, db.snapshots().find("LESSON"))
                assertEquals(attempts, db.attempts().all())
                assertEquals(PreferencesLoad.Found(Preferences.DEFAULT), repo.load())
                assertEquals("kept", db.checkpoints().find("LESSON")?.payload)
            } finally {
                writer.close()
                db.close()
            }
            assertEquals(PreferencesLoad.Failed, repo.load())
            assertEquals(PreferenceSave.Failed, UpdatePreference(repo).reset().result)
            assertEquals(PreferenceSave.Failed, UpdatePreference(repo)(Preferences.DEFAULT, Preference.Haptics(false)).result)
        }
}
