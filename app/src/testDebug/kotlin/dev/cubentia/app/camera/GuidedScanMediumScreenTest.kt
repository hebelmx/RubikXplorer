package dev.cubentia.app.camera

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.ui.a11y.assertNoTextOverflow
import dev.cubentia.app.ui.a11y.fontScale200
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

private val panelStrings =
    ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)

/** Shows the plain Guided viewfinder (with *Use this face anyway* and *Enter colors instead*), at 200% font if [big]. */
private fun ComposeContentTestRule.showPanel(
    big: Boolean = false,
    onClose: () -> Unit = {},
) = setContent {
    val screen = @androidx.compose.runtime.Composable {
        CubentiaTheme {
            GuidedScanScreen(
                GuidedScanPhase.Viewfinder(stepIndex = 0, coaching = Coaching.MISALIGNED, canUseAnyway = true),
                panelStrings,
                GuidedScanActions(onClose = onClose),
                offerEnterColors = true,
            )
        }
    }
    if (big) fontScale200(screen) else screen()
}

/** GH #313: in the wide panel *Close* sits in *Capture*'s row, beside the preview and never over it. */
private fun ComposeContentTestRule.assertCloseInPanel() {
    val root = onRoot().getUnclippedBoundsInRoot()
    val viewfinder = onNodeWithTag("scanViewfinder").assertIsDisplayed().getUnclippedBoundsInRoot()
    listOf("scanProgress", "scanClose", "scanCapture").forEach { tag ->
        val bounds = onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertFalse("$tag $bounds overlaps the preview $viewfinder", bounds.overlaps(viewfinder))
        assertTrue("$tag $bounds inside $root", bounds.inside(root))
    }
    assertCloseBesideCapture()
    val net = onNodeWithTag("scanNet").getUnclippedBoundsInRoot()
    assertTrue(onNodeWithTag("scanClose").getUnclippedBoundsInRoot().top >= net.bottom)
    val clickables = clickableTags()
    assertEquals("$clickables", "scanCapture", clickables.getOrNull(clickables.indexOf("scanClose") + 1))
    assertEquals(0, onAllNodes(hasScrollAction()).fetchSemanticsNodes().size)
}

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

    @Test
    fun `Close sits in the panel's Capture row and closes the scan`() {
        var closed = 0
        rule.showPanel(onClose = { closed++ })
        rule.assertCloseInPanel()
        rule.onNodeWithTag("scanClose").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun `at 200 percent font the panel keeps Close beside Capture without overflow`() {
        rule.showPanel(big = true)
        rule.assertNoTextOverflow("Guided Scan medium panel at 200%") { it.isSwatchGlyph() }
        rule.assertCloseInPanel()
    }
}

/** GH #313 device check: the viewfinder on the Lenovo TB128XU in landscape (smallest width 600 dp). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w1000dp-h600dp-land")
class GuidedScanLandscapeScreenTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `Close sits in the panel's Capture row, not alone in a far corner`() {
        var closed = 0
        rule.showPanel(onClose = { closed++ })
        rule.assertCloseInPanel()
        val close = rule.onNodeWithTag("scanClose").getUnclippedBoundsInRoot()
        val viewfinder = rule.onNodeWithTag("scanViewfinder").getUnclippedBoundsInRoot()
        assertTrue("scanClose $close right of the preview $viewfinder", close.left >= viewfinder.right)
        rule.onNodeWithTag("scanClose").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun `at 200 percent font the landscape panel keeps Close beside Capture without overflow`() {
        rule.showPanel(big = true)
        rule.assertNoTextOverflow("Guided Scan landscape panel at 200%") { it.isSwatchGlyph() }
        rule.assertCloseInPanel()
    }

    /** The spec's larger landscape window: the same panel rule holds. */
    @Test
    @Config(qualifiers = "w1333dp-h800dp-land")
    fun `on a w1333dp landscape window Close also sits in the panel's Capture row`() {
        rule.showPanel()
        rule.assertCloseInPanel()
        val close = rule.onNodeWithTag("scanClose").getUnclippedBoundsInRoot()
        val viewfinder = rule.onNodeWithTag("scanViewfinder").getUnclippedBoundsInRoot()
        assertTrue("scanClose $close right of the preview $viewfinder", close.left >= viewfinder.right)
    }
}
