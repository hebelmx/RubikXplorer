package dev.cubentia.app.ui.freecube

import android.app.Application
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import dev.cubentia.app.random.KotlinRandomSource
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.application.usecase.NewScramble
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.predicate.ObjectiveProgress
import dev.cubentia.domain.predicate.WhiteCrossSolved
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/** AC 3: the debug Objective overlay on the free cube, on the JVM through Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class ObjectiveOverlayTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var viewModel: FreeCubeViewModel

    private fun scrambler() = NewScramble(KotlinRandomSource(Random(SEED)))

    private fun launch(showDebugToggles: Boolean = true) {
        viewModel = FreeCubeViewModel(scramble = scrambler())
        rule.setContent {
            CubentiaTheme(darkTheme = false) {
                FreeCubeScreen(viewModel = viewModel, showDebugToggles = showDebugToggles)
            }
        }
    }

    private fun showOverlay() {
        rule
            .onNodeWithTag("debug:objective")
            .performScrollTo()
            .assertIsOff()
            .performClick()
            .assertIsOn()
        rule.waitForIdle()
    }

    private fun tap(token: String) {
        rule.onNodeWithTag("moveKey:$token").performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun toolbar(name: String) {
        rule.onNodeWithTag("toolbar:$name").performClick()
        rule.waitForIdle()
    }

    private fun line(name: String): SemanticsNodeInteraction = rule.onNodeWithTag("objective:$name")

    private fun assertOverlay(
        satisfied: String,
        progress: String,
        change: String,
    ) {
        line("satisfied").assertTextEquals("White cross: $satisfied")
        line("progress").assertTextEquals("Progress: $progress")
        line("change").assertTextEquals("Last Move: $change")
        assertSettledAndValid()
    }

    private fun assertSettledAndValid() {
        assertEquals(viewModel.history.state.toFacelets(), viewModel.renderer.renderedFacelets())
        line("valid").assertTextEquals("Validates: yes")
        line("sync").assertTextEquals("Rendered: in sync")
    }

    private fun assertMatchesPredicate() {
        val state = viewModel.history.state
        val progress = WhiteCrossSolved.progress(state)
        line("satisfied").assertTextEquals(if (WhiteCrossSolved.isSatisfied(state)) "White cross: solved" else "White cross: not solved")
        line("progress").assertTextEquals("Progress: ${progress.done}/${progress.total}")
        assertSettledAndValid()
    }

    @Test
    fun `the overlay is hidden until switched on`() {
        launch()
        rule.onNodeWithTag("debug:objectiveOverlay").assertDoesNotExist()
        showOverlay()
        rule.onNodeWithTag("debug:objectiveOverlay").assertExists()
        assertOverlay("solved", "4/4", "—")
        rule
            .onNodeWithTag("debug:objective")
            .performScrollTo()
            .performClick()
            .assertIsOff()
        rule.onNodeWithTag("debug:objectiveOverlay").assertDoesNotExist()
    }

    @Test
    fun `no debug controls without debug toggles`() {
        launch(showDebugToggles = false)
        rule.onNodeWithTag("debug:objective").assertDoesNotExist()
        rule.onNodeWithTag("debug:scramble").assertDoesNotExist()
        rule.onNodeWithTag("debug:objectiveOverlay").assertDoesNotExist()
    }

    @Test
    fun `the Scramble button is at least 48dp tall`() {
        launch()
        val bounds = rule.onNodeWithTag("debug:scramble").performScrollTo().getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top >= 48.dp)
    }

    @Test
    fun `a seeded Scramble applies, renders and validates with no mismatch`() {
        launch()
        showOverlay()
        var before = WhiteCrossSolved.progress(viewModel.history.state)
        rule.onNodeWithTag("debug:scramble").performScrollTo().performClick()
        rule.waitForIdle()
        val expected = scrambler()()
        assertEquals(25, expected.size)
        assertEquals(CubeState.SOLVED.apply(expected), viewModel.history.state)
        assertFalse(viewModel.history.state.isSolved)
        assertMatchesPredicate()
        assertChangeSince(before)

        // Then Moves: after each one the overlay follows the predicate and the net the engine.
        listOf("R", "U'", "F2", "L", "D").forEach {
            before = WhiteCrossSolved.progress(viewModel.history.state)
            tap(it)
            assertMatchesPredicate()
            assertChangeSince(before)
        }
    }

    private fun assertChangeSince(before: ObjectiveProgress) {
        val after = WhiteCrossSolved.progress(viewModel.history.state)
        val change =
            when {
                after > before -> "closer"
                after < before -> "further"
                else -> "no change"
            }
        line("change").assertTextEquals("Last Move: $change")
    }

    @Test(timeout = 120_000)
    fun `the sync line reads catching up until the net settles`() {
        launch()
        showOverlay()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("moveKey:R").performClick()
        rule.mainClock.advanceTimeByFrame()
        line("sync").assertTextEquals("Rendered: catching up")
        var elapsed = 0L
        while (viewModel.renderer.renderedFacelets() != viewModel.history.state.toFacelets()) {
            assertTrue("not settled within 1 s", elapsed <= 1_000L)
            rule.mainClock.advanceTimeByFrame()
            elapsed += 16L
        }
        rule.mainClock.advanceTimeByFrame()
        line("sync").assertTextEquals("Rendered: in sync")
    }

    @Test
    fun `helping and harmful Moves show as closer, further or no change`() {
        launch()
        showOverlay()
        tap("F")
        assertOverlay("not solved", "3/4", "further")
        tap("F'")
        assertOverlay("solved", "4/4", "closer")
        tap("D")
        assertOverlay("solved", "4/4", "no change")
        toolbar("undo")
        assertOverlay("solved", "4/4", "no change")
        toolbar("undo")
        assertOverlay("not solved", "3/4", "further")
        toolbar("redo")
        assertOverlay("solved", "4/4", "closer")
        toolbar("reset")
        assertOverlay("solved", "4/4", "no change")
    }

    @Test
    fun `a Scramble is undoable Move by Move`() {
        launch()
        showOverlay()
        tap("R")
        rule.onNodeWithTag("debug:scramble").performScrollTo().performClick()
        rule.waitForIdle()
        val scramble = scrambler()()
        for (k in scramble.size - 1 downTo 0) {
            toolbar("undo")
            assertEquals(CubeState.SOLVED.apply(scramble.take(k)), viewModel.history.state)
            assertMatchesPredicate()
        }
        // The Scramble reset the history: the earlier R is gone.
        assertFalse(viewModel.history.canUndo)
        assertEquals(CubeState.SOLVED, viewModel.history.state)
    }

    private companion object {
        const val SEED = 1_707
    }
}
