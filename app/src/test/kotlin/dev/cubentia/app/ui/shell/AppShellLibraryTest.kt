package dev.cubentia.app.ui.shell

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.width
import dev.cubentia.app.content.LessonCatalog
import dev.cubentia.app.preferences.PreferenceStore
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.session.TickingClock
import dev.cubentia.app.session.testFlags
import dev.cubentia.app.ui.freecube.FreeCubeViewModel
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class AppShellLibraryTest {
    @get:Rule val rule = createComposeRule()
    private val loaded = ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded

    private fun launch(
        unlocked: Boolean = false,
        restoration: StateRestorationTester? = null,
    ) {
        val preferences = PreferenceStore()
        val host =
            SessionHost(
                TickingClock(),
                kotlinx.coroutines.Dispatchers.Default
                    .limitedParallelism(1),
                preferences = preferences,
            )
        if (unlocked) runBlocking { host.passSkipCheck(loaded.bundle.stages.map { it.id }, loaded.bundle.stages) }
        val catalog = LessonCatalog.of(loaded)
        val freeCube = FreeCubeViewModel()
        val setContent: (@Composable () -> Unit) -> Unit = restoration?.let { it::setContent } ?: rule::setContent
        setContent {
            CubentiaTheme {
                AppShell(host, catalog, freeCube, false, testFlags(), preferenceStore = preferences)
            }
        }
        rule.onNodeWithTag("nav:learn").assertExists()
        rule.onNodeWithTag("nav:solve").assertExists()
        rule.onNodeWithTag("nav:library").performClick()
        rule.onNodeWithTag("nav:you").assertExists()
    }

    private fun assertColumns(columns: Int) {
        val ids = listOf("01", "02", "03", "04")
        val bounds = ids.map { rule.onNodeWithTag("library:case:case-oll-$it").getUnclippedBoundsInRoot() }
        assertEquals(columns, bounds.map { it.left }.distinct().size)
        val widths = bounds.map { it.width.value }
        assertTrue("tiles fill their cells: $widths", widths.max() - widths.min() <= 1f)
        assertEquals(bounds[0].top, bounds[columns - 1].top)
        if (columns == 2) assertTrue(bounds[2].top > bounds[0].top)
    }

    @Test fun `phone has two columns and keeps filter and grid position across tabs`() {
        launch()
        assertColumns(2)
        rule.onNodeWithTag("library:family:pll-diagonal-swap").performScrollTo()
        val chipBefore = rule.onNodeWithTag("library:family:pll-diagonal-swap").getUnclippedBoundsInRoot()
        rule.onNodeWithTag("library:grid").performScrollToKey("case-pll-z")
        val before = rule.onNodeWithTag("library:case:case-pll-z").getUnclippedBoundsInRoot()
        rule.onNodeWithTag("nav:you").performClick()
        rule.onNodeWithTag("nav:library").performClick()
        assertEquals(before, rule.onNodeWithTag("library:case:case-pll-z").getUnclippedBoundsInRoot())
        assertEquals(chipBefore, rule.onNodeWithTag("library:family:pll-diagonal-swap").getUnclippedBoundsInRoot())
        rule.onNodeWithTag("library:status:LEARNING").performClick()
        rule.onNodeWithTag("nav:learn").performClick()
        rule.onNodeWithTag("nav:library").performClick()
        rule.onNodeWithTag("library:status:LEARNING").assertIsSelected()
        rule.onNodeWithTag("library:two-look").assertIsDisplayed()
        rule.onNodeWithText("No algorithms marked Learning yet.").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1000dp-h900dp")
    fun `expanded window uses four columns beside rail`() {
        launch()
        rule.onNodeWithTag("nav:rail").assertExists()
        assertColumns(4)
        loaded.bundle.cases.forEach { case ->
            rule.onNodeWithTag("library:grid").performScrollToKey(case.id.value)
            rule.onNodeWithTag("library:case:${case.id.value}").assertIsDisplayed()
        }
    }

    @Test
    @Config(qualifiers = "w700dp-h900dp")
    fun `medium window uses three columns beside rail`() {
        launch()
        rule.onNodeWithTag("nav:rail").assertExists()
        assertColumns(3)
    }

    @Test fun `tab and family filter survive state restoration`() {
        val restoration = StateRestorationTester(rule)
        launch(restoration = restoration)
        rule.onNodeWithTag("library:family:oll-line").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("library:family:oll-line").assertIsSelected()
        val line = loaded.bundle.cases.first { it.family.value == "oll-line" }
        val dot = loaded.bundle.cases.first { it.family.value == "oll-dot" }
        rule.onNodeWithTag("library:case:${line.id.value}").assertIsDisplayed()
        rule.onNodeWithTag("library:case:${dot.id.value}").assertDoesNotExist()
    }

    @Test fun `locked link returns from selected Stage to Stage list`() {
        launch()
        rule.onNodeWithTag("nav:learn").performClick()
        rule.onNodeWithTag("learn:stage:stage-01-fundamentals").performClick()
        rule.onNodeWithTag("stageDetail").assertExists()
        rule.onNodeWithTag("nav:library").performClick()
        rule.onNodeWithTag("library:status:LEARNING").performClick()
        rule.onNodeWithTag("library:two-look").performClick()
        rule.onNodeWithTag("learn").assertIsDisplayed()
        rule.onNodeWithTag("stageDetail").assertDoesNotExist()
        rule.onNodeWithTag("learn:fasterLastLayer").assertDoesNotExist()
    }

    @Test fun `unlocked link brings Faster last layer into view`() {
        launch(unlocked = true)
        rule.onNodeWithTag("library:status:LEARNED").performClick()
        rule.onNodeWithTag("library:two-look").performClick()
        rule.onNodeWithTag("learn:fasterLastLayer").assertIsDisplayed()
    }
}
