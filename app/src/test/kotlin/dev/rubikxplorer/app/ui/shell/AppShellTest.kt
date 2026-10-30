package dev.rubikxplorer.app.ui.shell

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.rubikxplorer.app.content.LessonCatalog
import dev.rubikxplorer.app.render.StickerColorKey
import dev.rubikxplorer.app.session.STAGE_TITLE
import dev.rubikxplorer.app.session.SessionHost
import dev.rubikxplorer.app.session.TickingClock
import dev.rubikxplorer.app.session.testBundle
import dev.rubikxplorer.app.session.whiteCrossLesson
import dev.rubikxplorer.app.ui.freecube.FreeCubeViewModel
import dev.rubikxplorer.app.ui.theme.RubikXplorerTheme
import dev.rubikxplorer.application.session.SessionEvent
import dev.rubikxplorer.domain.appearance.StickerAppearance
import dev.rubikxplorer.domain.content.ContentLoadError
import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Facelets
import dev.rubikxplorer.domain.cube.Turn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** AC 1 and AC 5: the navigation shell, the Learn list, the Lesson task flow's layout and back. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class AppShellTest {
    @get:Rule
    val rule = createComposeRule()

    private val host = SessionHost(TickingClock())
    private lateinit var back: OnBackPressedDispatcher

    private fun launch(
        result: ContentLoadResult = ContentLoadResult.Loaded(testBundle()),
        restoration: StateRestorationTester? = null,
    ) {
        val catalog = LessonCatalog.of(result)
        val setContent: (@Composable () -> Unit) -> Unit = restoration?.let { it::setContent } ?: rule::setContent
        setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            RubikXplorerTheme {
                AppShell(host = host, catalog = catalog, freeCube = FreeCubeViewModel(), showDebugToggles = false)
            }
        }
    }

    private fun bounds(tag: String): DpRect = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    /** The Guide me net shows the session's Cube State, sticker for sticker. */
    private fun assertNetMatchesSession(step: String) {
        rule.waitForIdle()
        val expected =
            host.state.value.virtual
                .toFacelets()
                .colors
                .map { Color(StickerAppearance.of(it).fill) }
        val drawn = (0 until Facelets.SIZE).map { rule.onNodeWithTag("sticker:$it").fetchSemanticsNode().config[StickerColorKey] }
        assertEquals(step, expected, drawn)
    }

    private fun openWhiteCross() {
        rule.onNodeWithTag("learn:lesson:lesson-01-01").performClick()
        rule.waitUntil(5_000) {
            host.state.value.lesson
                ?.id == whiteCrossLesson().id
        }
        rule.waitForIdle()
    }

    @Test
    fun `Learn lists every Lesson in order with its Objective and Stage title, over a navigation bar`() {
        launch()
        rule.onNodeWithTag("nav:bar").assertExists()
        rule.onNodeWithTag("nav:rail").assertDoesNotExist()
        rule.onNodeWithTag("learn:lesson:lesson-01-01").assertTextContains("Objective of lesson-01-01").assertTextContains(STAGE_TITLE)
        rule.onNodeWithTag("learn:lesson:lesson-01-03").assertTextContains("Objective of lesson-01-03")
        assertTrue(bounds("learn:lesson:lesson-01-01").top < bounds("learn:lesson:lesson-01-03").top)
    }

    @Test
    fun `an empty bundle shows the empty line`() {
        launch(ContentLoadResult.Loaded(testBundle(*emptyArray())))
        rule.onNodeWithTag("learn:empty").assertTextContains("No lessons yet.")
        rule.onNodeWithTag("learn:error").assertDoesNotExist()
    }

    @Test
    fun `a failed load shows the error line`() {
        launch(ContentLoadResult.Failed(ContentLoadError.MissingResource("bundle.json")))
        rule.onNodeWithTag("learn:error").assertTextContains("Lessons could not be loaded.")
    }

    @Test
    fun `the Solve tab is the free cube`() {
        launch()
        rule.onNodeWithTag("nav:solve").performClick()
        rule.onNodeWithTag("toolbar:undo").assertExists()
        rule.onNodeWithTag("learn").assertDoesNotExist()
    }

    @Test
    fun `an open Lesson hides the bar and stacks the panel in order below the stage when compact`() {
        launch()
        openWhiteCross()
        rule.onNodeWithTag("nav:bar").assertDoesNotExist()
        rule.onNodeWithTag("lesson:close").assertExists()
        val objective = bounds("lesson:objective")
        val card = bounds("lesson:stepCard")
        val switcher = bounds("lesson:modeSwitcher")
        val hint = bounds("lesson:hint")
        val next = bounds("lesson:next")
        assertTrue(objective.bottom <= card.top)
        assertTrue(card.bottom <= switcher.top)
        assertTrue(switcher.bottom <= hint.top)
        assertEquals(hint.top, next.top)
        assertTrue(hint.right <= next.left)
        val stage = bounds("lesson:stage")
        assertTrue("panel below stage", bounds("lesson:panel").top >= stage.bottom)
        assertTrue("stage ${stage.height} at least 280dp", stage.height >= 280.dp)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `at expanded width the panel sits right of the stage, at most 560dp wide, beside a rail`() {
        launch()
        rule.onNodeWithTag("nav:rail").assertExists()
        rule.onNodeWithTag("nav:bar").assertDoesNotExist()
        openWhiteCross()
        rule.onNodeWithTag("nav:rail").assertDoesNotExist()
        val stage = bounds("lesson:stage")
        val panel = bounds("lesson:panel")
        assertTrue("panel right of stage", panel.left >= stage.right)
        assertTrue("panel ${panel.width} <= 560dp", panel.width <= 560.dp)
        assertTrue(bounds("lesson:objective").bottom <= bounds("lesson:stepCard").top)
    }

    @Test
    @Config(qualifiers = "w700dp-h900dp")
    fun `at medium width a rail shows and the open Lesson's panel sits right of the stage`() {
        launch()
        rule.onNodeWithTag("nav:rail").assertExists()
        rule.onNodeWithTag("nav:bar").assertDoesNotExist()
        openWhiteCross()
        assertTrue("panel right of stage", bounds("lesson:panel").left >= bounds("lesson:stage").right)
    }

    @Test
    fun `the open Lesson survives a saved-state restore without restarting`() {
        val restoration = StateRestorationTester(rule)
        launch(restoration = restoration)
        openWhiteCross()
        runBlocking { host.dispatch { SessionEvent.MoveCommitted(FaceTurn(Face.R, Turn.CLOCKWISE), it) } }
        rule.waitForIdle()
        val before = host.state.value
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNodeWithTag("lesson").assertExists()
        rule.onNodeWithTag("learn").assertDoesNotExist()
        assertEquals(before, host.state.value)
        assertNetMatchesSession("restored")
    }

    @Test
    fun `system back closes the Lesson at once, with no dialog`() {
        launch()
        openWhiteCross()
        // Progress that a Reset would ask about; back still asks nothing.
        runBlocking { host.dispatch { SessionEvent.MoveCommitted(FaceTurn(Face.R, Turn.CLOCKWISE), it) } }
        rule.runOnIdle { back.onBackPressed() }
        rule.onNodeWithTag("learn").assertExists()
        rule.onNodeWithTag("lesson").assertDoesNotExist()
        rule.onNodeWithTag("nav:bar").assertExists()
        assertEquals(null, ShadowDialog.getLatestDialog())
    }

    @Test
    fun `the close button closes the Lesson, and reopening it keeps its progress`() {
        launch()
        openWhiteCross()
        runBlocking { host.dispatch { SessionEvent.MoveCommitted(FaceTurn(Face.R, Turn.CLOCKWISE), it) } }
        val before = host.state.value
        rule.onNodeWithTag("lesson:close").performClick()
        rule.onNodeWithTag("learn").assertExists()
        rule.onNodeWithTag("learn:lesson:lesson-01-01").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("lesson").assertExists()
        assertEquals(before, host.state.value)
        assertNetMatchesSession("reopened")
    }

    @Test
    fun `system back on Solve returns to Learn first`() {
        launch()
        rule.onNodeWithTag("nav:solve").performClick()
        rule.onNodeWithTag("toolbar:undo").assertExists()
        rule.runOnIdle { back.onBackPressed() }
        rule.onNodeWithTag("learn").assertExists()
        rule.onNodeWithTag("toolbar:undo").assertDoesNotExist()
    }

    @Test
    fun `opening another Lesson starts it`() {
        launch()
        openWhiteCross()
        rule.onNodeWithTag("lesson:close").performClick()
        rule.onNodeWithTag("learn:lesson:lesson-01-03").performClick()
        rule.waitUntil(5_000) {
            host.state.value.lesson
                ?.id
                ?.value == "lesson-01-03"
        }
    }
}
