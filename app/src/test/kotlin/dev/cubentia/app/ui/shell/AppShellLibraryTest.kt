package dev.cubentia.app.ui.shell

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
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
    private lateinit var back: OnBackPressedDispatcher
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
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CubentiaTheme {
                AppShell(host, catalog, freeCube, false, testFlags(), preferenceStore = preferences)
            }
        }
        rule.onNodeWithTag("nav:learn").assertExists()
        rule.onNodeWithTag("nav:solve").assertExists()
        rule.onNodeWithTag("nav:library").performClick()
        rule.onNodeWithTag("nav:you").assertExists()
    }

    @Test fun `detail keeps grid filter and survives tabs and restoration`() {
        val restoration = StateRestorationTester(rule)
        launch(restoration = restoration)
        rule.onNodeWithTag("library:family:oll-line").performScrollTo().performClick()
        val case = loaded.bundle.cases.first { it.family.value == "oll-line" }
        val tag = "library:case:${case.id.value}"
        val before = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        rule.onNodeWithTag(tag).performClick()
        rule.onNodeWithTag("algorithmDetail:title").assertExists()
        rule.onNodeWithTag("nav:bar").assertIsDisplayed()
        rule.onNodeWithTag("playback:forward").performClick()
        rule.onNodeWithTag("nav:you").performClick()
        rule.onNodeWithTag("nav:library").performClick()
        rule
            .onNodeWithTag(
                "playback:counter",
            ).assertTextEquals("0 / ${loaded.bundle.algorithms.first { it.caseId == case.id }.notation.split(" ").size}")
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("algorithmDetail:title").assertExists()
        rule.onNodeWithTag("algorithmDetail:back").performClick()
        rule.onNodeWithTag("library:family:oll-line").assertIsSelected()
        assertEquals(before, rule.onNodeWithTag(tag).getUnclippedBoundsInRoot())
        rule.onNodeWithTag(tag).performClick()
        rule.runOnIdle { back.onBackPressed() }
        rule.onNodeWithTag("library:family:oll-line").assertIsSelected()
        rule.onNodeWithTag("nav:library").assertIsSelected()
        assertEquals(before, rule.onNodeWithTag(tag).getUnclippedBoundsInRoot())
    }

    @Test fun `back from detail keeps a scrolled grid position`() {
        launch()
        rule.onNodeWithTag("library:grid").performScrollToKey("case-pll-z")
        val tag = "library:case:case-pll-z"
        val before = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        rule.onNodeWithTag(tag).performClick()
        rule.onNodeWithTag("algorithmDetail:back").performClick()
        assertEquals(before, rule.onNodeWithTag(tag).getUnclippedBoundsInRoot())
        rule.onNodeWithTag(tag).performClick()
        rule.runOnIdle { back.onBackPressed() }
        rule.onNodeWithTag("nav:library").assertIsSelected()
        assertEquals(before, rule.onNodeWithTag(tag).getUnclippedBoundsInRoot())
    }

    @Test
    @Config(qualifiers = "w1000dp-h900dp")
    fun `detail opens beside rail`() {
        launch()
        rule.onNodeWithTag("library:case:case-oll-01").performClick()
        rule.onNodeWithTag("algorithmDetail:title").assertExists()
        rule.onNodeWithTag("nav:rail").assertIsDisplayed()
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

    @Test fun `locked link leaves selected Stage for the locked 2-look destination and back returns to Stage list`() {
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
        rule.onNodeWithTag("learn:twoLookLocked").assertIsDisplayed()
        rule.onNodeWithText("Faster last layer").assertIsDisplayed()
        rule.onNodeWithTag("learn:twoLookLocked:status").assertIsDisplayed()
        rule.onNodeWithText("Complete or skip every Stage of the Course to unlock 2-look.").assertIsDisplayed()
        rule.onNodeWithTag("learn:twoLookLocked:back").performClick()
        rule.onNodeWithTag("learn:twoLookLocked").assertDoesNotExist()
        rule.onNodeWithTag("learn:stage:stage-01-fundamentals").assertIsDisplayed()
    }

    @Test fun `locked 2-look destination survives state restoration`() {
        val restoration = StateRestorationTester(rule)
        launch(restoration = restoration)
        rule.onNodeWithTag("library:status:LEARNING").performClick()
        rule.onNodeWithTag("library:two-look").performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("learn:twoLookLocked").assertIsDisplayed()
    }

    @Test fun `unlocked link brings Faster last layer into view`() {
        launch(unlocked = true)
        rule.onNodeWithTag("library:status:LEARNED").performClick()
        rule.onNodeWithTag("library:two-look").performClick()
        rule.onNodeWithTag("learn:fasterLastLayer").assertIsDisplayed()
        rule.onNodeWithTag("learn:twoLookLocked").assertDoesNotExist()
    }
}
