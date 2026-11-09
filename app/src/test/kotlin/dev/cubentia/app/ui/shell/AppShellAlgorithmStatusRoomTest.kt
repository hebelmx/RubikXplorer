package dev.cubentia.app.ui.shell

import android.app.Application
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room3.Room
import androidx.room3.withReadTransaction
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.cubentia.app.content.LessonCatalog
import dev.cubentia.app.data.RoomAlgorithmStatusRepository
import dev.cubentia.app.data.RoomProgressRepository
import dev.cubentia.app.data.RubikDatabase
import dev.cubentia.app.data.SerialWriter
import dev.cubentia.app.preferences.PreferenceStore
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.session.TickingClock
import dev.cubentia.app.session.testFlags
import dev.cubentia.app.ui.freecube.FreeCubeViewModel
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Joins the two ends of Story 9.6: the shell's controls over the production Room path. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class AppShellAlgorithmStatusRoomTest {
    @get:Rule val rule = createComposeRule()
    private val loaded = ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded

    private fun count(
        db: RubikDatabase,
        sql: String,
    ): Long =
        runBlocking {
            db.withReadTransaction {
                usePrepared(sql) {
                    it.step()
                    it.getLong(0)
                }
            }
        }

    private fun labels() = rule.onAllNodes(hasTestTag("library:status-label"), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test fun `detail status reaches the tile through Room and reset appends without deleting`() {
        val db =
            Room
                .inMemoryDatabaseBuilder<RubikDatabase>(RuntimeEnvironment.getApplication())
                .setDriver(AndroidSQLiteDriver())
                .build()
        val writer = SerialWriter()
        try {
            val preferences = PreferenceStore()
            val host =
                SessionHost(
                    TickingClock(),
                    Dispatchers.Default.limitedParallelism(1),
                    writer = writer,
                    preferences = preferences,
                    progress = RoomProgressRepository(db),
                    algorithmStatusRepository = RoomAlgorithmStatusRepository(db),
                )
            val catalog = LessonCatalog.of(loaded)
            val cube = FreeCubeViewModel()
            rule.setContent {
                CubentiaTheme { AppShell(host, catalog, cube, false, testFlags(), preferenceStore = preferences) }
            }
            rule.onNodeWithTag("nav:library").performClick()
            rule.onNodeWithTag("library:case:case-oll-01").performClick()
            rule.onNodeWithTag("algorithmDetail:status:LEARNING").performClick()
            rule.waitUntil(5_000) { count(db, "SELECT COUNT(*) FROM algorithm_status_event") == 1L }
            rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("algorithmDetail:status-line")).fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithTag("algorithmDetail:status:LEARNING").assertIsSelected()
            rule.onNodeWithTag("algorithmDetail:back").performClick()
            rule.waitUntil(5_000) { labels() == 1 }
            rule.onNodeWithTag("library:status-label", useUnmergedTree = true).assertTextEquals("Learning")

            rule.onNodeWithTag("nav:you").performClick()
            rule.onNodeWithTag("you:settings").performClick()
            rule.onNodeWithTag("settings:resetProgress").performScrollTo().performClick()
            rule.onNodeWithTag("settings:resetProgress:confirm").performClick()
            rule.waitUntil(5_000) { count(db, "SELECT COUNT(*) FROM progress_event WHERE type = 'PROGRESS_RESET'") == 1L }
            assertEquals(1L, count(db, "SELECT COUNT(*) FROM algorithm_status_event"))
            rule.onNodeWithTag("settings:close").performClick()
            rule.onNodeWithTag("welcome:imNew").performClick()
            rule.onNodeWithTag("nav:library").performClick()
            rule.waitUntil(5_000) { labels() == 0 }
            assertEquals(1L, count(db, "SELECT COUNT(*) FROM algorithm_status_event"))
        } finally {
            runBlocking { writer.close() }
            db.close()
        }
    }
}
