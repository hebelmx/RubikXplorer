package dev.rubikxplorer.app.data

import android.app.Application
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SessionDatabaseMigrationTest {
    @Test fun exportedVersionOneCreatesAndValidates() =
        runBlocking {
            val path = Files.createTempFile("session-migration", ".db")
            Files.delete(path)
            val helper =
                MigrationTestHelper(
                    InstrumentationRegistry.getInstrumentation(),
                    path.toFile(),
                    AndroidSQLiteDriver(),
                    RubikDatabase::class,
                )
            try {
                helper.createDatabase(1).use { connection ->
                    connection.prepare("INSERT INTO session_snapshot VALUES ('LESSON', 'kept', 7, 1, 'payload')").use { it.step() }
                }
                helper.runMigrationsAndValidate(2).use { connection ->
                    connection
                        .prepare(
                            "SELECT COUNT(*) FROM session_snapshot WHERE session_id = 'kept' AND revision = 7 AND payload = 'payload'",
                        ).use {
                            assertEquals(true, it.step())
                            assertEquals(1L, it.getLong(0))
                        }
                }
            } finally {
                Files.deleteIfExists(path)
            }
        }
}
