package dev.cubentia.app.camera

import android.app.Application
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.Coaching
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.Lab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Story 10.2: the Guided Scan screen on a compact portrait phone, and its permission cards. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp-port")
class GuidedScanScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings
    private val strings = ContentStrings(bundle)

    private fun show(
        phase: GuidedScanPhase,
        actions: GuidedScanActions = GuidedScanActions(),
    ) = rule.setContent { CubentiaTheme { GuidedScanScreen(phase, strings, actions) } }

    @Test
    fun `the viewfinder, prompt, net and Capture fit on one compact portrait screen without scrolling`() {
        val captured =
            listOf(
                FaceSample(
                    face = Face.F,
                    orientationDegrees = 90,
                    stickers = List(9) { Lab(55.0, -60.0, 40.0) },
                    quality = CaptureQuality.ACCEPTABLE,
                    centerColor = Color.GREEN,
                    upNeighborColor = Color.WHITE,
                ),
            )
        show(GuidedScanPhase.Viewfinder(stepIndex = 1, captured = captured, coaching = Coaching.GLARE))
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        listOf("scanViewfinder", "scanPrompt", "scanNet", "scanCapture").forEach { tag ->
            rule.onNodeWithTag(tag).assertIsDisplayed()
            val bounds = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue("$tag $bounds inside $root", bounds.top >= root.top && bounds.bottom <= root.bottom && bounds.right <= root.right)
        }
        rule.onNodeWithText("Show the red center, with white on top.").assertIsDisplayed()
        rule.onNodeWithText("Too much glare — tilt the cube").assertIsDisplayed()
        rule.onNodeWithText("Face 2 of 6").assertIsDisplayed()
        assertEquals(0, rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().size)
        rule.onNodeWithTag("scanUseAnyway").assertDoesNotExist()
        assertEquals(
            LiveRegionMode.Polite,
            rule.onNodeWithTag("scanProgress").fetchSemanticsNode().config[SemanticsProperties.LiveRegion],
        )
        assertEquals(
            LiveRegionMode.Polite,
            rule.onNodeWithTag("scanCoaching").fetchSemanticsNode().config[SemanticsProperties.LiveRegion],
        )
    }

    @Test
    fun `a repeated face shows its coaching and, once offered, Use this face anyway on one screen`() {
        var used = 0
        var captured = 0
        show(
            GuidedScanPhase.Viewfinder(stepIndex = 1, coaching = Coaching.WRONG_FACE, canUseAnyway = true),
            GuidedScanActions(onUseAnyway = { used++ }, onCapture = { captured++ }),
        )
        rule.onNodeWithText(bundle.getValue("msg.scan.coach.wrong-face")).assertIsDisplayed()
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        listOf("scanUseAnyway", "scanCapture").forEach { tag ->
            val bounds = rule.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("$tag $bounds inside $root", bounds.bottom <= root.bottom)
        }
        rule.onNodeWithText("Use this face anyway").performClick()
        rule.onNodeWithTag("scanCapture").performClick()
        assertEquals(1, used)
        assertEquals(1, captured)
    }

    @Test
    fun `every coaching has its own content string`() {
        Coaching.entries.forEach { assertTrue(coachingKey(it), coachingKey(it) in bundle) }
        assertEquals("Hold the cube still", bundle[coachingKey(Coaching.BLURRED)])
    }

    @Test
    fun `the rationale card shows the approved copy and both actions`() {
        var continued = 0
        var fellBack = 0
        show(GuidedScanPhase.Rationale, GuidedScanActions(onContinue = { continued++ }, onEnterColors = { fellBack++ }))
        rule
            .onNodeWithText(
                "Cubentia uses the camera to read your cube's colors. It reads them on this device. Nothing is saved or sent.",
            ).assertIsDisplayed()
        rule.onNodeWithText("Continue").performClick()
        rule.onNodeWithText("Enter colors instead").performClick()
        assertEquals(1, continued)
        assertEquals(1, fellBack)
    }

    @Test
    fun `a denial shows Enter colors instead without Open settings`() {
        show(GuidedScanPhase.Denied(permanent = false))
        rule.onNodeWithText(bundle.getValue("msg.scan.camera-denied")).assertIsDisplayed()
        rule.onNodeWithText("Enter colors instead").assertIsDisplayed()
        rule.onNodeWithText("Open settings").assertDoesNotExist()
    }

    @Test
    fun `a permanent denial also offers Open settings`() {
        var opened = 0
        show(GuidedScanPhase.Denied(permanent = true), GuidedScanActions(onOpenSettings = { opened++ }))
        rule.onNodeWithText(bundle.getValue("msg.scan.camera-denied-permanently")).assertIsDisplayed()
        rule.onNodeWithText("Enter colors instead").assertIsDisplayed()
        rule.onNodeWithText("Open settings").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `an unavailable camera offers Enter colors instead and the close button cancels`() {
        var closed = 0
        show(GuidedScanPhase.Unavailable, GuidedScanActions(onClose = { closed++ }))
        rule.onNodeWithText(bundle.getValue("msg.scan.camera-unavailable")).assertIsDisplayed()
        rule.onNodeWithText("Enter colors instead").assertIsDisplayed()
        rule.onNodeWithTag("scanClose").performClick()
        assertEquals(1, closed)
    }
}
