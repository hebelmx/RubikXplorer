package dev.cubentia.app.camera

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp-port")
class QuickScanScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun cornerCounterAndGuidedFallbackFitInPortrait() {
        val strings = ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)
        val phase = mutableStateOf<GuidedScanPhase>(GuidedScanPhase.CornerViewfinder(hold = 1))
        rule.setContent { CubentiaTheme { GuidedScanScreen(phase.value, strings, GuidedScanActions(), offerEnterColors = true) } }
        rule.onNodeWithTag("quickScan:outline").assertIsDisplayed()
        rule
            .onNodeWithTag("quickScan:counter")
            .assertTextEquals("1 / 2")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        rule.onNodeWithText("Hold white on top, green and red facing you").assertIsDisplayed()
        rule.onNodeWithTag("scanEnterColors").assertIsDisplayed()
        rule.runOnIdle { phase.value = GuidedScanPhase.CornerViewfinder(hold = 2) }
        rule.onNodeWithTag("quickScan:counter").assertTextEquals("2 / 2")
        rule.onNodeWithText("Now yellow on top, blue and orange facing you").assertIsDisplayed()
        rule.runOnIdle {
            phase.value = GuidedScanPhase.Viewfinder(step = GuidedScanPlan.reprompt(Face.F), again = RepromptProgress(1, 2), retake = true)
        }
        rule.onNodeWithTag("quickScan:counter").assertDoesNotExist()
        rule.onNodeWithTag("scanGrid").assertIsDisplayed()
        rule.onNodeWithTag("scanProgress").assertTextEquals("Again: face 1 of 2")
        rule.onNodeWithText("Show the green face again, with white on top.").assertIsDisplayed()
        rule.onNodeWithTag("scanNet").assertIsDisplayed()
        rule.onNodeWithTag("scanEnterColors").assertIsDisplayed()
    }

    @Test
    fun scanFaceByFaceShowsAtBothHoldsAboveEnterColorsAndNowhereElse() {
        val strings = ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)
        var taps = 0
        val phase = mutableStateOf<GuidedScanPhase>(GuidedScanPhase.CornerViewfinder(hold = 1, faceByFace = true))
        rule.setContent {
            CubentiaTheme {
                GuidedScanScreen(phase.value, strings, GuidedScanActions(onFaceByFace = { taps++ }), offerEnterColors = true)
            }
        }
        rule
            .onNodeWithTag("quickScan:faceByFace")
            .assertIsDisplayed()
            .assertTextEquals("Scan face by face")
            .performClick()
        rule.runOnIdle { assertEquals(1, taps) }
        val button = rule.onNodeWithTag("quickScan:faceByFace").fetchSemanticsNode().boundsInRoot
        val enter = rule.onNodeWithTag("scanEnterColors").fetchSemanticsNode().boundsInRoot
        // Stacked directly above *Enter colors instead*, full width.
        org.junit.Assert.assertTrue(button.bottom <= enter.top)
        assertEquals(enter.width, button.width, 1f)
        rule.runOnIdle { phase.value = GuidedScanPhase.CornerViewfinder(hold = 2, faceByFace = true) }
        rule.onNodeWithTag("quickScan:faceByFace").assertIsDisplayed()
        // Not in the Guided viewfinder (already face by face), nor in Case capture's corner view.
        rule.runOnIdle { phase.value = GuidedScanPhase.Viewfinder(handOver = true) }
        rule.onNodeWithTag("quickScan:faceByFace").assertDoesNotExist()
        rule.runOnIdle { phase.value = GuidedScanPhase.CornerViewfinder(hold = 1) }
        rule.onNodeWithTag("quickScan:faceByFace").assertDoesNotExist()
    }

    @Test
    fun theHandOverViewfinderCountsOnlyTheAskedFacesAndShowsKeptFacesDone() {
        val strings = ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)
        val kept = listOf(Face.U, Face.F, Face.R).map { sample(it) }
        val steps = GuidedScanPlan.steps.filter { it.face in setOf(Face.B, Face.L, Face.D) }
        rule.setContent {
            CubentiaTheme {
                GuidedScanScreen(
                    GuidedScanPhase.Viewfinder(step = steps.first(), total = 3, kept = kept, handOver = true),
                    strings,
                    GuidedScanActions(),
                    offerEnterColors = true,
                )
            }
        }
        rule.onNodeWithTag("scanProgress").assertTextEquals("Face 1 of 3")
        rule.onNodeWithText("Show the blue center.").assertIsDisplayed()
        listOf(Face.U, Face.F, Face.R).forEach {
            rule.onNodeWithTag("scanNetFace:${it.name}").assert(SemanticsMatcher.expectValue(ScanNetFaceCaptured, true))
        }
        listOf(Face.B, Face.L, Face.D).forEach {
            rule.onNodeWithTag("scanNetFace:${it.name}").assert(SemanticsMatcher.expectValue(ScanNetFaceCaptured, false))
        }
        rule.onNodeWithTag("scanEnterColors").assertIsDisplayed()
    }

    @Test
    fun closeAsksDiscardThisScanAndCancelKeepsTheScan() {
        val strings = ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)
        val confirm = mutableStateOf(true)
        var discarded = 0
        rule.setContent {
            CubentiaTheme {
                GuidedScanScreen(
                    GuidedScanPhase.CornerViewfinder(hold = 2, captured = listOf(sample(Face.U)), faceByFace = true),
                    strings,
                    GuidedScanActions(onDiscardConfirm = { discarded++ }, onDiscardCancel = { confirm.value = false }),
                    confirmDiscard = confirm.value,
                )
            }
        }
        rule.onNodeWithTag("scan:discard").assertIsDisplayed()
        rule.onNodeWithText("Discard this scan?").assertIsDisplayed()
        rule.onNodeWithTag("scan:discard:confirm").assertTextEquals("Discard").performClick()
        rule.runOnIdle { assertEquals(1, discarded) }
        rule.onNodeWithTag("scan:discard:cancel").assertTextEquals("Cancel").performClick()
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTag("scan:discard").fetchSemanticsNodes().size)
        rule.onNodeWithTag("quickScan:counter").assertTextEquals("2 / 2")
    }

    private fun sample(face: Face) =
        FaceSample(face = face, orientationDegrees = 0, stickers = List(9) { Lab(50.0, 0.0, 0.0) }, quality = CaptureQuality.ACCEPTABLE)

    @Test
    fun detectedCornerOutlineThumbnailAndLightSurfaceCoachingShow() {
        val strings = ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)
        val quad = listOf(CornerPoint(0.5, 0.2), CornerPoint(0.7, 0.35), CornerPoint(0.5, 0.5), CornerPoint(0.3, 0.35))
        rule.setContent {
            CubentiaTheme {
                GuidedScanScreen(
                    GuidedScanPhase.CornerViewfinder(hold = 2, coaching = Coaching.PLAIN_LIGHT_SURFACE),
                    strings,
                    GuidedScanActions(),
                    detectedCornerQuads = listOf(quad),
                    cornerThumbnail = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImageBitmap(),
                )
            }
        }
        rule.onNodeWithTag("quickScan:detectedOutline").assertIsDisplayed()
        rule.onNodeWithTag("quickScan:thumbnail").assertIsDisplayed()
        rule.onNodeWithTag("scanCoaching").assertTextEquals("Try a plain, light surface.")
        rule.onNodeWithTag("quickScan:captureFlash").assertDoesNotExist()
    }

    @Test
    fun captureFlashOverlayAndViewfinderCoexistDuringCornerFreeze() {
        val strings = ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)
        val thumb = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImageBitmap()
        rule.setContent {
            CubentiaTheme {
                GuidedScanScreen(
                    GuidedScanPhase.CornerViewfinder(hold = 2),
                    strings,
                    GuidedScanActions(),
                    frozen = FrozenCapture(id = 1L, face = Face.U, image = thumb, shrink = false),
                    cornerThumbnail = thumb,
                )
            }
        }
        rule.onNodeWithTag("scanViewfinder").assertIsDisplayed()
        rule.onNodeWithTag("quickScan:captureFlash").assertIsDisplayed()
        rule.onNodeWithTag("quickScan:thumbnail").assertIsDisplayed()
        rule.onNodeWithTag("quickScan:counter").assertTextEquals("2 / 2")
    }

    @Test
    fun sidesDarkAndTorchGlareCoachingLinesShow() {
        val strings = ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)
        val phase =
            mutableStateOf<GuidedScanPhase>(GuidedScanPhase.CornerViewfinder(hold = 1, coaching = Coaching.SIDES_DARK, faceByFace = true))
        rule.setContent { CubentiaTheme { GuidedScanScreen(phase.value, strings, GuidedScanActions(), cornerTorch = true) } }
        rule.onNodeWithTag("scanCoaching").assertTextEquals("Turn so the light shines on the cube.")
        // Scan face by face stays available while the light hold coaches.
        rule.onNodeWithTag("quickScan:faceByFace").assertIsDisplayed()
        rule.runOnIdle { phase.value = GuidedScanPhase.CornerViewfinder(hold = 1, coaching = Coaching.TORCH_GLARE, faceByFace = true) }
        rule.onNodeWithTag("scanCoaching").assertTextEquals("Glare from the torch — turn the torch off.")
        rule.runOnIdle { phase.value = GuidedScanPhase.CornerViewfinder(hold = 1, coaching = Coaching.GLARE, faceByFace = true) }
        rule.onNodeWithTag("scanCoaching").assertTextEquals("Too much glare — tilt the cube")
    }

    @Test
    fun theTorchToggleShowsItsStateAsLabelAndTalkBackTextInTheViewfinderCorner() {
        val strings = ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)
        val torch = mutableStateOf(true)
        rule.setContent {
            CubentiaTheme {
                GuidedScanScreen(
                    GuidedScanPhase.CornerViewfinder(hold = 1, faceByFace = true),
                    strings,
                    GuidedScanActions(onToggleTorch = { torch.value = !torch.value }),
                    cornerTorch = torch.value,
                )
            }
        }
        rule
            .onNodeWithTag("quickScan:torch")
            .assertIsDisplayed()
            .assertTextEquals("Torch on")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, emptyList<String>()).not())
        val button = rule.onNodeWithTag("quickScan:torch").fetchSemanticsNode().boundsInRoot
        val camera = rule.onNodeWithTag("scanViewfinder").fetchSemanticsNode().boundsInRoot
        // Inside the viewfinder, in its top corner.
        org.junit.Assert.assertTrue("$button in $camera", button.top >= camera.top && button.right <= camera.right + 0.5f)
        org.junit.Assert.assertTrue("$button in $camera", button.top - camera.top < camera.height / 3 && button.left > camera.center.x)
        rule.onNodeWithTag("quickScan:torch").performClick()
        rule.onNodeWithTag("quickScan:torch").assertTextEquals("Torch off")
    }

    @Test
    fun noTorchControlWithoutAFlashUnitNorOutsideTheCornerView() {
        val strings = ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)
        val phase = mutableStateOf<GuidedScanPhase>(GuidedScanPhase.CornerViewfinder(hold = 2, faceByFace = true))
        val torch = mutableStateOf<Boolean?>(null)
        rule.setContent { CubentiaTheme { GuidedScanScreen(phase.value, strings, GuidedScanActions(), cornerTorch = torch.value) } }
        rule.onNodeWithTag("quickScan:torch").assertDoesNotExist()
        rule.onNodeWithText("Torch on").assertDoesNotExist()
        rule.onNodeWithText("Torch off").assertDoesNotExist()
        rule.runOnIdle {
            phase.value = GuidedScanPhase.Viewfinder(handOver = true)
            torch.value = true
        }
        rule.onNodeWithTag("quickScan:torch").assertDoesNotExist()
    }
}
