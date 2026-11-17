package dev.cubentia.app.camera

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.recognition.Coaching
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Story 10.2 device check (2026-11-16): the viewfinder on a medium-width portrait tablet (the Lenovo TB128XU). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w800dp-h1333dp-port")
class GuidedScanMediumScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val strings =
        ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)

    @Test
    fun `the grid is centered on the preview and the panel beside it never overlaps it`() {
        rule.setContent {
            CubentiaTheme {
                GuidedScanScreen(
                    GuidedScanPhase.Viewfinder(stepIndex = 0, coaching = Coaching.MISALIGNED, canUseAnyway = true),
                    strings,
                    GuidedScanActions(),
                )
            }
        }
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        val viewfinder = rule.onNodeWithTag("scanViewfinder").assertIsDisplayed().getUnclippedBoundsInRoot()
        val grid = rule.onNodeWithTag("scanGrid").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("grid $grid inside $viewfinder", grid.inside(viewfinder))
        val tolerance = 1.dp
        assertTrue(
            "grid $grid centered horizontally on $viewfinder",
            ((grid.left + grid.right) / 2 - (viewfinder.left + viewfinder.right) / 2).value.let { kotlin.math.abs(it) } <= tolerance.value,
        )
        assertTrue(
            "grid $grid centered vertically on $viewfinder",
            ((grid.top + grid.bottom) / 2 - (viewfinder.top + viewfinder.bottom) / 2).value.let { kotlin.math.abs(it) } <= tolerance.value,
        )
        listOf("scanPrompt", "scanCoaching", "scanNet", "scanUseAnyway", "scanCapture").forEach { tag ->
            val bounds = rule.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertFalse("$tag $bounds overlaps the preview $viewfinder", bounds.overlaps(viewfinder))
            assertTrue("$tag $bounds inside $root", bounds.inside(root))
        }
        assertTrue(viewfinder.inside(root))
        assertEquals(0, rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().size)
    }

    @Test
    fun `the wide Solve viewfinder shows Enter colors instead beside the preview`() {
        var entered = false
        rule.setContent {
            CubentiaTheme {
                GuidedScanScreen(
                    GuidedScanPhase.Viewfinder(stepIndex = 0, coaching = Coaching.MISALIGNED, canUseAnyway = true),
                    strings,
                    GuidedScanActions(onEnterColors = { entered = true }),
                    offerEnterColors = true,
                )
            }
        }
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        val viewfinder = rule.onNodeWithTag("scanViewfinder").assertIsDisplayed().getUnclippedBoundsInRoot()
        val button = rule.onNodeWithTag("scanEnterColors").assertIsDisplayed()
        val bounds = button.getUnclippedBoundsInRoot()
        assertFalse("scanEnterColors $bounds overlaps the preview $viewfinder", bounds.overlaps(viewfinder))
        assertTrue("scanEnterColors $bounds inside $root", bounds.inside(root))
        assertTrue(bounds.bottom - bounds.top >= 48.dp)
        button.performClick()
        assertTrue(entered)
    }
}
