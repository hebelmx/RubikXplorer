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

/** Continuous viewfinder and controls fit without scrolling. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-port", fontScale = 2.0f)
class ContinuousQuickScanLargeFontTest {
    @get:Rule val rule = createComposeRule()

    private val strings = ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)

    @Test fun phoneAtDoubleFont() = fits()

    @Test
    @Config(qualifiers = "w600dp-h960dp-port", fontScale = 2.0f)
    fun tabletAtDoubleFont() = fits()

    @Test
    @Config(qualifiers = "w360dp-h640dp-port", fontScale = 1f)
    fun compactPhone() = fits()

    @Test
    @Config(qualifiers = "w960dp-h600dp-land")
    fun landscapeTablet() = fits(landscape = true)

    /** A landscape phone is narrower than 600 dp in neither direction a tablet is: it still gets preview left, panel right. */
    @Test
    @Config(qualifiers = "w640dp-h360dp-land", fontScale = 1f)
    fun landscapePhone() = fits(landscape = true)

    private fun fits(landscape: Boolean = false) {
        var touch = 0f
        rule.setContent {
            touch = with(LocalDensity.current) { 48.dp.toPx() }
            val snap =
                testSnapshot(testHandoff(Face.entries), dev.cubentia.domain.recognition.continuous.ContinuousScanCue.LayerTwistDetected)
            CubentiaTheme {
                GuidedScanScreen(
                    GuidedScanPhase.Continuous,
                    strings,
                    GuidedScanActions(),
                    continuous = ContinuousQuickScanFlow(snapshot = snap),
                    cornerTorch = false,
                )
            }
        }
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val camera =
            rule
                .onNodeWithTag("scanViewfinder")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val tags = listOf("quickScan:count", "quickScan:cue", "quickScan:net", "quickScan:reset", "quickScan:faceByFace", "scanEnterColors")
        // The net's cells never shrink to nothing: its cell is the smaller of a twelfth of its width and a ninth of its height.
        val net = rule.onNodeWithTag("quickScan:net").fetchSemanticsNode().boundsInRoot
        assertTrue("net cell in $net (8 dp = ${touch / 6} px)", minOf(net.width / 12, net.height / 9) >= touch / 6 - 0.5f)
        for (tag in tags + listOf("scanClose", "quickScan:torch")) {
            val bounds =
                rule
                    .onNodeWithTag(tag)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertTrue(
                "$tag $bounds, root $root",
                root.contains(bounds.topLeft) && bounds.right <= root.right && bounds.bottom <= root.bottom && bounds.height > 0f,
            )
            if (tag in
                tags
            ) {
                assertTrue("$tag $bounds, camera $camera", if (landscape) bounds.left >= camera.right else bounds.top >= camera.bottom)
            }
            if (tag in listOf("quickScan:reset", "quickScan:faceByFace", "scanEnterColors", "scanClose", "quickScan:torch")) {
                assertTrue("$tag touch height", bounds.height >= touch - 0.5f)
            }
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
