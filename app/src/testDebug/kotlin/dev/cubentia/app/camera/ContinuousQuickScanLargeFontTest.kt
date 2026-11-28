package dev.cubentia.app.camera

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import org.junit.Assert.assertEquals
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

    /**
     * The medium width class in portrait (the Lenovo; AD-17, GH #415): stacked like a phone, the preview on top at full
     * width and never taller than wide, the four actions stacked below.
     */
    @Test
    @Config(qualifiers = "w600dp-h960dp-port", fontScale = 2.0f)
    fun tabletAtDoubleFont() = fits(pairedLastRow = false)

    /** GH #415: a squarish portrait window (height = width) stacks too, with a camera pane no taller than wide. */
    @Test
    @Config(qualifiers = "w800dp-h800dp", fontScale = 1f)
    fun squarishPortraitWindow() = fits(pairedLastRow = false)

    /** Just under the medium width class a portrait window still stacks the preview above the panel. */
    @Test
    @Config(qualifiers = "w599dp-h960dp-port", fontScale = 2.0f)
    fun portraitJustUnderTheMediumWidthAtDoubleFont() = fits(pairedLastRow = false)

    @Test
    @Config(qualifiers = "w360dp-h640dp-port", fontScale = 1f)
    fun compactPhone() = fits()

    @Test
    @Config(qualifiers = "w960dp-h600dp-land")
    fun landscapeTablet() = fits(twoPane = true)

    /** A landscape phone (narrower than 600 dp tall): preview left, panel right, as in every landscape window. */
    @Test
    @Config(qualifiers = "w640dp-h360dp-land", fontScale = 1f)
    fun landscapePhone() = fits(twoPane = true, pairedLastRow = true)

    /** Exactly at the 480 dp switch the panel is not short yet: the four actions stay stacked. */
    @Test
    @Config(qualifiers = "w800dp-h480dp-land", fontScale = 1f)
    fun landscapeAtTheShortPanelSwitch() = fits(twoPane = true, pairedLastRow = false)

    /**
     * [twoPane]: whether the panel sits right of the preview (any landscape window) or below it (any portrait window,
     * GH #415; then the preview is never taller than wide). [pairedLastRow]: whether *Review and Correct* shares the last row with
     * *Enter colors instead* (the short landscape panel) or sits above it (every other layout); null leaves the
     * arrangement unchecked.
     */
    private fun fits(
        twoPane: Boolean = false,
        pairedLastRow: Boolean? = null,
    ) {
        var touch = 0f
        val twist = dev.cubentia.domain.recognition.continuous.ContinuousScanCue.LayerTwistDetected
        val state = mutableStateOf(ContinuousQuickScanFlow(snapshot = testSnapshot(testHandoff(Face.entries), twist)))
        rule.setContent {
            touch = with(LocalDensity.current) { 48.dp.toPx() }
            CubentiaTheme {
                GuidedScanScreen(
                    GuidedScanPhase.Continuous,
                    strings,
                    GuidedScanActions(),
                    continuous = state.value,
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
        // Six sides under a latched twist: *Review and Correct* is on the screen too, the fullest panel there is.
        val buttons = listOf("quickScan:reset", "quickScan:faceByFace", "quickScan:reviewAndCorrect", "scanEnterColors")
        val tags = listOf("quickScan:count", "quickScan:cue", "quickScan:net") + buttons
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
                assertTrue("$tag $bounds, camera $camera", if (twoPane) bounds.left >= camera.right else bounds.top >= camera.bottom)
            }
            if (tag in buttons + listOf("scanClose", "quickScan:torch")) {
                assertTrue("$tag touch height", bounds.height >= touch - 0.5f)
            }
        }

        fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        // The preview takes the whole first pane, and *Close* and the torch on it never overlap, however narrow the pane.
        assertTrue("camera $camera, root $root", if (twoPane) camera.height == root.height else camera.width == root.width)
        if (!twoPane) {
            assertEquals(root.top, camera.top)
            assertTrue("stacked camera $camera is never tall", camera.height <= camera.width)
        }
        assertTrue(
            "scanClose ${bounds("scanClose")}, torch ${bounds("quickScan:torch")}",
            bounds("scanClose").right <= bounds("quickScan:torch").left,
        )
        // Hidden (six sides, but only the six centres set), *Review and Correct* keeps its place: nothing else in the
        // panel moves when it goes or comes back, so every layout here fits the same way with and without it.
        val others = tags - "quickScan:reviewAndCorrect"
        val shown = others.associateWith(::bounds)
        rule.runOnIdle {
            state.value =
                ContinuousQuickScanFlow(snapshot = testSnapshot(testHandoff(Face.entries, centresOnly = true), twist))
        }
        rule.onNodeWithTag("quickScan:reviewAndCorrect").assertDoesNotExist()
        assertEquals(shown, others.associateWith(::bounds))
        rule.runOnIdle { state.value = ContinuousQuickScanFlow(snapshot = testSnapshot(testHandoff(Face.entries), twist)) }
        rule.onNodeWithTag("quickScan:reviewAndCorrect").assertIsDisplayed()
        assertEquals(shown, others.associateWith(::bounds))
        if (pairedLastRow == null) return

        val faceByFace = bounds("quickScan:faceByFace")
        val review = bounds("quickScan:reviewAndCorrect")
        val enterColors = bounds("scanEnterColors")
        val where = "faceByFace $faceByFace, reviewAndCorrect $review, enterColors $enterColors"
        assertTrue(where, review.top >= faceByFace.bottom && enterColors.top >= faceByFace.bottom)
        if (pairedLastRow) {
            // One row: *Review and Correct* on the left, *Enter colors instead* on the right, not overlapping.
            assertTrue(where, review.top < enterColors.bottom && enterColors.top < review.bottom)
            assertTrue(where, review.right <= enterColors.left && review.width > 0f && enterColors.width > 0f)
        } else {
            assertTrue(where, enterColors.top >= review.bottom)
            assertTrue(where, review.left == faceByFace.left && review.right == faceByFace.right)
        }
    }

    /** GH #390: the detector-unavailable card on the Lenovo at 200% font: *Close*, the message and both actions, unclipped. */
    @Test
    @Config(qualifiers = "w600dp-h960dp-port", fontScale = 2.0f)
    fun theDetectorUnavailableCardFitsOnTheTabletAtDoubleFont() {
        rule.setContent {
            CubentiaTheme { GuidedScanScreen(GuidedScanPhase.QuickScanUnavailable, strings, GuidedScanActions()) }
        }
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val message = "Quick Scan isn't available right now. You can scan face by face or enter your cube's colors by hand."
        val parts =
            listOf(
                "Close" to rule.onNodeWithTag("scanClose"),
                "message" to rule.onNodeWithText(message),
                "Scan face by face" to rule.onNodeWithText("Scan face by face"),
                "Enter colors instead" to rule.onNodeWithTag("scanEnterColors"),
            ).map { (name, node) ->
                val shown = node.assertIsDisplayed().fetchSemanticsNode()
                val bounds = shown.boundsInRoot
                assertTrue(
                    "$name $bounds (size ${shown.size}), root $root",
                    bounds.left >= root.left && bounds.top >= root.top && bounds.right <= root.right && bounds.bottom <= root.bottom &&
                        bounds.width >= shown.size.width - 0.5f && bounds.height >= shown.size.height - 0.5f && bounds.height > 0f,
                )
                name to bounds
            }
        // Top to bottom in the card pattern's order, nothing overlapping.
        parts.zipWithNext { (above, upper), (below, lower) ->
            assertTrue("$above $upper, $below $lower", lower.top >= upper.bottom)
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
