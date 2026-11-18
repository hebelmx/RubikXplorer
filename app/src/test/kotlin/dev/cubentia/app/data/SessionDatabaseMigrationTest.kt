package dev.cubentia.app.data

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

    @Test fun versionTwoToThreeKeepsRowsAndCreatesAttempt() =
        runBlocking {
            val path = Files.createTempFile("attempt-migration", ".db")
            Files.delete(path)
            val helper =
                MigrationTestHelper(
                    InstrumentationRegistry.getInstrumentation(),
                    path.toFile(),
                    AndroidSQLiteDriver(),
                    RubikDatabase::class,
                )
            try {
                helper.createDatabase(2).use { connection ->
                    connection.prepare("INSERT INTO session_snapshot VALUES ('LESSON', 'kept', 7, 1, 'snapshot')").use { it.step() }
                    connection.prepare("INSERT INTO checkpoint VALUES ('LESSON', 'checkpoint')").use { it.step() }
                }
                helper.runMigrationsAndValidate(3).use { connection ->
                    for ((table, payload) in listOf("session_snapshot" to "snapshot", "checkpoint" to "checkpoint")) {
                        connection.prepare("SELECT payload FROM $table").use {
                            assertEquals(true, it.step())
                            assertEquals(payload, it.getText(0))
                        }
                    }
                    connection.prepare("SELECT COUNT(attempt_id) FROM attempt").use {
                        assertEquals(true, it.step())
                        assertEquals(0L, it.getLong(0))
                    }
                }
            } finally {
                Files.deleteIfExists(path)
            }
        }

    @Test fun versionThreeToFourKeepsRowsAndCreatesPreferences() =
        runBlocking {
            val path = Files.createTempFile("attempt-migration", ".db")
            Files.delete(path)
            val helper =
                MigrationTestHelper(
                    InstrumentationRegistry.getInstrumentation(),
                    path.toFile(),
                    AndroidSQLiteDriver(),
                    RubikDatabase::class,
                )
            try {
                helper.createDatabase(3).use { connection ->
                    connection.prepare("INSERT INTO session_snapshot VALUES ('LESSON', 'kept', 7, 1, 'snapshot')").use { it.step() }
                    connection.prepare("INSERT INTO checkpoint VALUES ('LESSON', 'checkpoint')").use { it.step() }
                    connection
                        .prepare(
                            "INSERT INTO attempt (attempt_id, session_id, revision, event, objective_id, at_ms) " +
                                "VALUES ('attempt', 'session', 1, 'STARTED', 'objective', 0)",
                        ).use {
                            it.step()
                        }
                }
                helper.runMigrationsAndValidate(4).use { connection ->
                    for ((table, payload) in listOf("session_snapshot" to "snapshot", "checkpoint" to "checkpoint")) {
                        connection.prepare("SELECT payload FROM $table").use {
                            assertEquals(true, it.step())
                            assertEquals(payload, it.getText(0))
                        }
                    }
                    connection.prepare("SELECT attempt_id FROM attempt").use {
                        assertEquals(true, it.step())
                        assertEquals("attempt", it.getText(0))
                    }
                    connection.prepare("SELECT COUNT(id) FROM preferences").use {
                        assertEquals(true, it.step())
                        assertEquals(0L, it.getLong(0))
                    }
                }
            } finally {
                Files.deleteIfExists(path)
            }
        }

    @Test fun versionFourToFiveKeepsRowsAndCreatesProgressEvent() =
        runBlocking {
            val path = Files.createTempFile("progress-migration", ".db")
            Files.delete(path)
            val helper =
                MigrationTestHelper(
                    InstrumentationRegistry.getInstrumentation(),
                    path.toFile(),
                    AndroidSQLiteDriver(),
                    RubikDatabase::class,
                )
            try {
                helper.createDatabase(4).use { connection ->
                    connection.prepare("INSERT INTO session_snapshot VALUES ('LESSON', 'kept', 7, 1, 'snapshot')").use { it.step() }
                    connection.prepare("INSERT INTO checkpoint VALUES ('LESSON', 'checkpoint')").use { it.step() }
                    connection
                        .prepare(
                            "INSERT INTO attempt (attempt_id, session_id, revision, event, objective_id, at_ms) " +
                                "VALUES ('attempt', 'session', 1, 'STARTED', 'objective', 0)",
                        ).use {
                            it.step()
                        }
                    connection
                        .prepare(
                            "INSERT INTO preferences VALUES (1, 'DARK', 1, 'FAST', 0, 1, 'SHOW_ME')",
                        ).use {
                            it.step()
                        }
                }
                helper.runMigrationsAndValidate(5).use { connection ->
                    for ((table, payload) in listOf("session_snapshot" to "snapshot", "checkpoint" to "checkpoint")) {
                        connection.prepare("SELECT payload FROM $table").use {
                            assertEquals(true, it.step())
                            assertEquals(payload, it.getText(0))
                        }
                    }
                    connection.prepare("SELECT attempt_id FROM attempt").use {
                        assertEquals(true, it.step())
                        assertEquals("attempt", it.getText(0))
                    }
                    connection.prepare("SELECT theme FROM preferences WHERE id = 1").use {
                        assertEquals(true, it.step())
                        assertEquals("DARK", it.getText(0))
                    }
                    connection.prepare("SELECT COUNT(seq) FROM progress_event").use {
                        assertEquals(true, it.step())
                        assertEquals(0L, it.getLong(0))
                    }
                }
            } finally {
                Files.deleteIfExists(path)
            }
        }

    @Test fun versionFiveToSixKeepsRowsAndCreatesAlgorithmStatuses() =
        runBlocking {
            val path = Files.createTempFile("status-migration", ".db")
            Files.delete(path)
            val helper =
                MigrationTestHelper(
                    InstrumentationRegistry.getInstrumentation(),
                    path.toFile(),
                    AndroidSQLiteDriver(),
                    RubikDatabase::class,
                )
            try {
                helper.createDatabase(5).use { connection ->
                    connection.prepare("INSERT INTO session_snapshot VALUES ('LESSON', 'kept', 7, 1, 'payload')").use { it.step() }
                    connection
                        .prepare(
                            "INSERT INTO progress_event (session_id, revision, type, stage_id, lesson_id, epoch, at_ms) " +
                                "VALUES ('reset', 0, 'PROGRESS_RESET', '', '', 1, 0)",
                        ).use { it.step() }
                }
                helper.runMigrationsAndValidate(6).use { connection ->
                    for (table in listOf("session_snapshot", "progress_event", "algorithm_status_event")) {
                        connection.prepare("SELECT COUNT(*) FROM $table").use {
                            assertEquals(true, it.step())
                            assertEquals(if (table == "algorithm_status_event") 0L else 1L, it.getLong(0))
                        }
                    }
                }
            } finally {
                Files.deleteIfExists(path)
            }
        }

    @Test fun versionSixToSevenKeepsPreferencesAndDefaultsOnboardedToFalse() =
        runBlocking {
            val path = Files.createTempFile("onboarded-migration", ".db")
            Files.delete(path)
            val helper =
                MigrationTestHelper(
                    InstrumentationRegistry.getInstrumentation(),
                    path.toFile(),
                    AndroidSQLiteDriver(),
                    RubikDatabase::class,
                )
            try {
                helper.createDatabase(6).use { connection ->
                    connection
                        .prepare(
                            "INSERT INTO preferences (id, theme, color_blind_glyphs, animation_speed, haptics, reduce_motion, " +
                                "last_teaching_mode) VALUES (1, 'DARK', 1, 'FAST', 0, 1, 'SHOW_ME')",
                        ).use { it.step() }
                }
                helper.runMigrationsAndValidate(7).use { connection ->
                    connection.prepare("SELECT theme, onboarded FROM preferences WHERE id = 1").use {
                        assertEquals(true, it.step())
                        assertEquals("DARK", it.getText(0))
                        assertEquals(0L, it.getLong(1))
                    }
                }
            } finally {
                Files.deleteIfExists(path)
            }
        }

    @Test fun versionSevenToEightKeepsEveryTableAndCreatesEmptyCalibration() =
        runBlocking {
            val path = Files.createTempFile("calibration-migration", ".db")
            Files.delete(path)
            val helper =
                MigrationTestHelper(
                    InstrumentationRegistry.getInstrumentation(),
                    path.toFile(),
                    AndroidSQLiteDriver(),
                    RubikDatabase::class,
                )
            try {
                val inserts =
                    listOf(
                        "INSERT INTO session_snapshot VALUES ('LESSON', 'kept', 7, 1, 'snapshot')",
                        "INSERT INTO checkpoint VALUES ('LESSON', 'checkpoint')",
                        "INSERT INTO attempt (attempt_id, session_id, revision, event, objective_id, at_ms) " +
                            "VALUES ('attempt', 'session', 1, 'STARTED', 'objective', 0)",
                        "INSERT INTO preferences VALUES (1, 'DARK', 1, 'FAST', 0, 1, 'SHOW_ME', 1)",
                        "INSERT INTO progress_event (session_id, revision, type, stage_id, lesson_id, at_ms) " +
                            "VALUES ('kept', 1, 'LESSON_COMPLETED', '', 'lesson', 0)",
                        "INSERT INTO algorithm_status_event (session_id, revision, algorithm_id, status, epoch, at_ms) " +
                            "VALUES ('kept', 1, 'alg', 'LEARNING', 0, 0)",
                    )
                val tables = listOf("session_snapshot", "checkpoint", "attempt", "preferences", "progress_event", "algorithm_status_event")
                val before = mutableMapOf<String, List<String?>>()

                fun row(
                    connection: androidx.sqlite.SQLiteConnection,
                    table: String,
                ): List<String?> =
                    connection.prepare("SELECT * FROM $table").use { statement ->
                        assertEquals(true, statement.step())
                        (0 until statement.getColumnCount()).map { if (statement.isNull(it)) null else statement.getText(it) }
                    }
                helper.createDatabase(7).use { connection ->
                    inserts.forEach { sql -> connection.prepare(sql).use { it.step() } }
                    tables.forEach { before[it] = row(connection, it) }
                }
                helper.runMigrationsAndValidate(8).use { connection ->
                    tables.forEach { assertEquals(before[it], row(connection, it)) }
                    connection.prepare("SELECT COUNT(*) FROM color_calibration").use {
                        assertEquals(true, it.step())
                        assertEquals(0L, it.getLong(0))
                    }
                }
            } finally {
                Files.deleteIfExists(path)
            }
        }

    @Test fun versionEightToNineKeepsPreferencesAndLeavesPlaybackViewUnset() =
        runBlocking {
            val path = Files.createTempFile("playback-view-migration", ".db")
            Files.delete(path)
            val helper =
                MigrationTestHelper(
                    InstrumentationRegistry.getInstrumentation(),
                    path.toFile(),
                    AndroidSQLiteDriver(),
                    RubikDatabase::class,
                )
            try {
                helper.createDatabase(8).use { connection ->
                    connection.prepare("INSERT INTO preferences VALUES (1, 'DARK', 1, 'FAST', 0, 1, 'SHOW_ME', 1)").use { it.step() }
                }
                helper.runMigrationsAndValidate(9).use { connection ->
                    connection.prepare("SELECT * FROM preferences").use { row ->
                        assertEquals(true, row.step())
                        assertEquals(
                            listOf("1", "DARK", "1", "FAST", "0", "1", "SHOW_ME", "1"),
                            (0 until 8).map(row::getText),
                        )
                        assertEquals(true, row.isNull(8))
                        assertEquals(false, row.step())
                    }
                }
            } finally {
                Files.deleteIfExists(path)
            }
        }

    @Test fun versionNineToTenKeepsPreferencesAndDefaultsMovePicturesOn() =
        runBlocking {
            val path = Files.createTempFile("move-pictures-migration", ".db")
            Files.delete(path)
            val helper =
                MigrationTestHelper(
                    InstrumentationRegistry.getInstrumentation(),
                    path.toFile(),
                    AndroidSQLiteDriver(),
                    RubikDatabase::class,
                )
            try {
                helper.createDatabase(9).use { connection ->
                    connection
                        .prepare(
                            "INSERT INTO preferences VALUES (1, 'DARK', 1, 'FAST', 0, 1, 'SHOW_ME', 1, 'NET_2D')",
                        ).use { it.step() }
                }
                helper.runMigrationsAndValidate(10).use { connection ->
                    connection.prepare("SELECT * FROM preferences").use { row ->
                        assertEquals(true, row.step())
                        assertEquals(
                            listOf("1", "DARK", "1", "FAST", "0", "1", "SHOW_ME", "1"),
                            (0 until 8).map(row::getText),
                        )
                        assertEquals("NET_2D", row.getText(8))
                        assertEquals(1, row.getInt(9))
                        assertEquals(false, row.step())
                    }
                }
            } finally {
                Files.deleteIfExists(path)
            }
        }
}
