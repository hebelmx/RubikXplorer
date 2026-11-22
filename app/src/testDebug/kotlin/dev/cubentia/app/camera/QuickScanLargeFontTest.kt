package dev.cubentia.app.camera

import android.app.Application
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.Coaching
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.GuidedScanPlan
import dev.cubentia.domain.recognition.Lab
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Story 10.17 AC6: Quick Scan's corner view and hand-over viewfinder fit a 411 dp portrait phone at 200 % font, unscrolled. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-port", fontScale = 2.0f)
class QuickScanLargeFontTest {
    @get:Rule val rule = createComposeRule()

    private val strings = ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)

    @Test
    fun faceByFaceAndEnterColorsFitWithoutScrollingAtTwoHundredPercentFont() =
        assertFits(
            GuidedScanPhase.CornerViewfinder(hold = 1, faceByFace = true),
            listOf("scanCapture", "quickScan:faceByFace", "scanEnterColors"),
        )

    @Test
    fun theCornerViewWithTheTorchToggleFitsWithoutScrollingAtTwoHundredPercentFont() {
        assertFits(
            GuidedScanPhase.CornerViewfinder(hold = 1, faceByFace = true),
            listOf("scanCapture", "quickScan:faceByFace", "scanEnterColors"),
            cornerTorch = false,
        )
        val camera = rule.onNodeWithTag("scanViewfinder").fetchSemanticsNode().boundsInRoot
        val torch =
            rule
                .onNodeWithTag("quickScan:torch")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        // The torch toggle sits wholly inside the viewfinder, so it never pushes the buttons below off screen.
        assertTrue("$torch in $camera", torch.top >= camera.top && torch.bottom <= camera.bottom && torch.right <= camera.right + 0.5f)
        assertTrue("$torch in $camera", torch.left >= camera.left)
    }

    @Test
    fun theCornerViewWithTheCalibrationOfferFitsWithoutScrollingAtTwoHundredPercentFont() =
        assertFits(
            GuidedScanPhase.CornerViewfinder(hold = 1, coaching = Coaching.SIDES_DARK, faceByFace = true, offerCalibration = true),
            listOf("quickScan:calibrate:offer", "scanCapture", "quickScan:faceByFace", "scanEnterColors"),
            cornerTorch = false,
        )

    /** Story 10.19: on a 600 dp tablet at 200 % font the panel beside the viewfinder holds the offer and every button. */
    @Test
    @Config(qualifiers = "w600dp-h960dp-port", fontScale = 2.0f)
    fun theCalibrationOfferFitsTheSidePanelOnSixHundredDpAtTwoHundredPercentFont() {
        var minTouch = 0f
        val phase = GuidedScanPhase.CornerViewfinder(hold = 1, coaching = Coaching.SIDES_DARK, faceByFace = true, offerCalibration = true)
        rule.setContent {
            minTouch = with(LocalDensity.current) { 48.dp.toPx() }
            CubentiaTheme { GuidedScanScreen(phase, strings, GuidedScanActions(), offerEnterColors = true, cornerTorch = false) }
        }
        val screen = rule.onRoot().fetchSemanticsNode().boundsInRoot
        listOf("quickScan:calibrate:offer", "scanCapture", "quickScan:faceByFace", "scanEnterColors").forEach { tag ->
            val bounds =
                rule
                    .onNodeWithTag(tag)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertTrue("$tag at $bounds in $screen", bounds.bottom <= screen.bottom && bounds.height >= minTouch - 0.5f)
        }
    }

    @Test
    fun theHandOverViewfinderFitsWithoutScrollingAtTwoHundredPercentFont() {
        val kept =
            listOf(Face.U, Face.F, Face.R).map {
                FaceSample(
                    face = it,
                    orientationDegrees = 0,
                    stickers = List(9) { Lab(50.0, 0.0, 0.0) },
                    quality = CaptureQuality.ACCEPTABLE,
                )
            }
        val step = GuidedScanPlan.steps.first { it.face == Face.B }
        assertFits(
            GuidedScanPhase.Viewfinder(step = step, total = 3, kept = kept, handOver = true),
            listOf("scanCapture", "scanEnterColors"),
        )
    }

    private fun assertFits(
        phase: GuidedScanPhase,
        tags: List<String>,
        cornerTorch: Boolean? = null,
    ) {
        var minTouch = 0f
        rule.setContent {
            minTouch = with(LocalDensity.current) { 48.dp.toPx() }
            CubentiaTheme { GuidedScanScreen(phase, strings, GuidedScanActions(), offerEnterColors = true, cornerTorch = cornerTorch) }
        }
        val camera = rule.onNodeWithTag("scanViewfinder").fetchSemanticsNode().boundsInRoot
        val screen = rule.onRoot().fetchSemanticsNode().boundsInRoot
        tags.forEach { tag ->
            rule.onNodeWithTag(tag).assertIsDisplayed()
            val bounds = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            // Fully on screen below the viewfinder, at least 48 dp tall: nothing clipped.
            assertTrue(
                "$tag at $bounds in $screen (48 dp = $minTouch px)",
                bounds.top >= camera.bottom && bounds.bottom <= screen.bottom && bounds.height >= minTouch - 0.5f,
            )
        }
    }
}
