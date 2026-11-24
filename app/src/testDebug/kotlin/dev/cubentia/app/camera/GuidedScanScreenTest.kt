package dev.cubentia.app.camera

import android.app.Application
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.CalibrationSession
import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.CellReading
import dev.cubentia.domain.recognition.Coaching
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.GuidedScanPlan
import dev.cubentia.domain.recognition.Lab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

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
        offerEnterColors: Boolean = false,
    ) = rule.setContent { CubentiaTheme { GuidedScanScreen(phase, strings, actions, offerEnterColors = offerEnterColors) } }

    @Test fun solveViewfinderFitsManualEntryOnCompactPortrait() {
        var entered = false
        show(
            GuidedScanPhase.Viewfinder(stepIndex = 1, captured = greenCaptured, coaching = Coaching.GLARE),
            GuidedScanActions(onEnterColors = { entered = true }),
            offerEnterColors = true,
        )
        assertFitsOnOneScreen()
        val button = rule.onNodeWithTag("scanEnterColors").assertIsDisplayed()
        val bounds = button.getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom <= rule.onRoot().getUnclippedBoundsInRoot().bottom)
        assertTrue(bounds.bottom - bounds.top >= 48.dp)
        button.performClick()
        assertTrue(entered)
    }

    @Test fun viewfinderWithoutSolveOfferDoesNotAddManualEntry() {
        show(GuidedScanPhase.Viewfinder())
        rule.onNodeWithTag("scanEnterColors").assertDoesNotExist()
    }

    @Test fun solveCalibrationOffersManualEntryAtTheSwatch() {
        var entered = false
        val session =
            dev.cubentia.domain.recognition
                .CalibrationSession()
                .offer(
                    dev.cubentia.domain.recognition
                        .CellReading(Lab(55.0, -60.0, 40.0), true),
                )
        show(
            GuidedScanPhase.Calibration(session),
            GuidedScanActions(onEnterColors = { entered = true }),
            offerEnterColors = true,
        )
        rule.onNodeWithTag("calibration:swatch").assertIsDisplayed()
        rule.onNodeWithTag("scanEnterColors").assertIsDisplayed().performClick()
        assertTrue(entered)
    }

    /** GH #325: the plain Guided viewfinder asks "Discard the faces you captured?" with *Keep scanning* and *Discard*. */
    @Test fun guidedDiscardQuestionShowsItsOwnTitleAndActions() {
        var discarded = 0
        var kept = 0
        rule.setContent {
            CubentiaTheme {
                GuidedScanScreen(
                    GuidedScanPhase.Viewfinder(stepIndex = 1, captured = greenCaptured),
                    strings,
                    GuidedScanActions(onDiscardConfirm = { discarded++ }, onDiscardCancel = { kept++ }),
                    confirmDiscard = true,
                    discardKind = DiscardKind.GuidedFaces,
                )
            }
        }
        rule.onNodeWithTag("scan:discard").assertIsDisplayed()
        rule.onNodeWithText("Discard the faces you captured?").assertIsDisplayed()
        rule.onNodeWithText("Discard this scan?").assertDoesNotExist()
        rule.onNodeWithTag("scan:discard:confirm").assertTextEquals("Discard").performClick()
        rule.onNodeWithTag("scan:discard:cancel").assertTextEquals("Keep scanning").performClick()
        rule.runOnIdle {
            assertEquals(1, discarded)
            assertEquals(1, kept)
        }
    }

    /** GH #325 copy regression: Quick Scan's question keeps "Discard this scan?" / *Discard* / *Cancel*. */
    @Test fun quickScanDiscardQuestionKeepsItsCopy() {
        rule.setContent {
            CubentiaTheme {
                GuidedScanScreen(
                    GuidedScanPhase.CornerViewfinder(hold = 1, captured = greenCaptured),
                    strings,
                    GuidedScanActions(),
                    confirmDiscard = true,
                )
            }
        }
        rule.onNodeWithText("Discard this scan?").assertIsDisplayed()
        rule.onNodeWithText("Discard the faces you captured?").assertDoesNotExist()
        rule.onNodeWithTag("scan:discard:confirm").assertTextEquals("Discard")
        rule.onNodeWithTag("scan:discard:cancel").assertTextEquals("Cancel")
    }

    private val greenCaptured =
        listOf(
            FaceSample(
                face = Face.F,
                orientationDegrees = 90,
                stickers = List(9) { Lab(55.0, -60.0, 40.0) },
                quality = CaptureQuality.ACCEPTABLE,
                centerColor = Color.GREEN,
            ),
        )

    /** The viewfinder, prompt, coaching, net and Capture all sit on one screen, the prompt and coaching off the grid. */
    private fun assertFitsOnOneScreen() {
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        listOf("scanViewfinder", "scanPrompt", "scanCoaching", "scanNet", "scanCapture").forEach { tag ->
            rule.onNodeWithTag(tag).assertIsDisplayed()
            val bounds = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue("$tag $bounds inside $root", bounds.top >= root.top && bounds.bottom <= root.bottom && bounds.right <= root.right)
        }
        // Device check 2026-11-16: the prompt and the coaching line sit above and below the grid, never on it.
        val grid = rule.onNodeWithTag("scanGrid").getUnclippedBoundsInRoot()
        val viewfinder = rule.onNodeWithTag("scanViewfinder").getUnclippedBoundsInRoot()
        assertTrue("grid $grid inside $viewfinder", grid.inside(viewfinder))
        listOf("scanPrompt", "scanCoaching", "scanNet", "scanCapture").forEach { tag ->
            val bounds = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertFalse("$tag $bounds overlaps the grid $grid", bounds.overlaps(grid))
        }
        assertTrue(rule.onNodeWithTag("scanPrompt").getUnclippedBoundsInRoot().bottom <= grid.top)
        assertTrue(rule.onNodeWithTag("scanCoaching").getUnclippedBoundsInRoot().top >= grid.bottom)
        assertEquals(0, rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().size)
    }

    @Test
    fun `the viewfinder, prompt, net and Capture fit on one compact portrait screen without scrolling`() {
        show(GuidedScanPhase.Viewfinder(stepIndex = 1, captured = greenCaptured, coaching = Coaching.GLARE))
        assertFitsOnOneScreen()
        // Story 10.14: the first-round prompt names only the center, with its one swatch.
        assertEquals("Show the {center} center.", bundle.getValue("msg.scan.prompt"))
        rule.onNodeWithText("Show the red center.").assertIsDisplayed()
        rule.onNodeWithTag("scanSwatch:RED").assertIsDisplayed()
        rule.onNodeWithTag("scanSwatch:WHITE").assertDoesNotExist()
        rule.onNodeWithText("Too much glare — tilt the cube").assertIsDisplayed()
        rule.onNodeWithText("Face 2 of 6").assertIsDisplayed()
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
    fun `a two-face Quick Check counts its progress against its own plan`() {
        val controller =
            dev.cubentia.domain.recognition.GuidedScanController(
                plan = listOf(GuidedScanPlan.reprompt(Face.U), GuidedScanPlan.reprompt(Face.R)),
                reprompt = { emptyList() },
            )
        show(GuidedScanFlow.phaseOf(controller))
        rule.onNodeWithText("Face 1 of 2").assertIsDisplayed()
        rule.onNodeWithText("Face 1 of 6").assertDoesNotExist()
    }

    @Test
    fun `a re-prompt names the top with both swatches and still fits on one screen`() {
        show(
            GuidedScanPhase.Viewfinder(
                stepIndex = 6,
                captured = greenCaptured,
                coaching = Coaching.GLARE,
                step = GuidedScanPlan.reprompt(Face.F),
                again = RepromptProgress(1, 2),
            ),
        )
        assertFitsOnOneScreen()
        val text =
            bundle
                .getValue("msg.scan.prompt-with-top")
                .replace("{center}", "green")
                .replace("{up}", "white")
        assertEquals("Show the green center, with white on top.", text)
        rule.onNodeWithText(text).assertIsDisplayed()
        rule.onNodeWithTag("scanSwatch:GREEN").assertIsDisplayed()
        rule.onNodeWithTag("scanSwatch:WHITE").assertIsDisplayed()
        rule.onNodeWithText(bundle.getValue("msg.scan.progress-again").replace("{n}", "1").replace("{total}", "2")).assertIsDisplayed()
        rule.onNodeWithText("Again: face 1 of 2").assertIsDisplayed()
    }

    @Test
    fun `a Quick Check retake names the face again with its top`() {
        show(
            GuidedScanPhase.Viewfinder(
                stepIndex = 2,
                step = GuidedScanPlan.reprompt(Face.R),
                again = RepromptProgress(1, 1),
                total = 2,
                retake = true,
            ),
        )
        assertFitsOnOneScreen()
        val text =
            bundle
                .getValue("msg.scan.prompt-again-with-top")
                .replace("{center}", "red")
                .replace("{up}", "white")
        assertEquals("Show the red face again, with white on top.", text)
        rule.onNodeWithText(text).assertIsDisplayed()
        rule.onNodeWithText("Again: face 1 of 1").assertIsDisplayed()
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

    private val ticks = mutableListOf<HapticFeedbackType>()
    private val ended = mutableListOf<Long>()

    private fun showFrozen(animatorScale: Float) {
        Settings.Global.putFloat(
            ApplicationProvider.getApplicationContext<Application>().contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            animatorScale,
        )
        rule.mainClock.autoAdvance = false
        val haptics =
            object : HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                    ticks += hapticFeedbackType
                }
            }
        rule.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                CubentiaTheme {
                    GuidedScanScreen(
                        GuidedScanPhase.Viewfinder(stepIndex = 1),
                        strings,
                        GuidedScanActions(onFreezeEnd = { ended += it }),
                        FrozenCapture(7, Face.F, Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImageBitmap()),
                    )
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
    }

    @Test
    fun `a capture freezes its frame with a haptic tick, then shrinks it into the net before live view resumes`() {
        showFrozen(animatorScale = 1f)
        rule.onNodeWithTag("scanFrozen").assertIsDisplayed()
        assertEquals(listOf(HapticFeedbackType.KeyboardTap), ticks)
        rule.mainClock.advanceTimeBy(CaptureFreeze.FREEZE_MS - 50)
        assertEquals(emptyList<Long>(), ended)
        // Frozen, then shrinking: still not ended right after the freeze.
        rule.mainClock.advanceTimeBy(100)
        assertEquals(emptyList<Long>(), ended)
        rule.mainClock.advanceTimeBy(SHRINK_MS.toLong() + 100)
        assertEquals(listOf(7L), ended)
    }

    @Test
    fun `reduced motion keeps the freeze and skips the shrink`() {
        showFrozen(animatorScale = 0f)
        rule.onNodeWithTag("scanFrozen").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(CaptureFreeze.FREEZE_MS - 50)
        assertEquals(emptyList<Long>(), ended)
        rule.mainClock.advanceTimeBy(100)
        assertEquals(listOf(7L), ended)
    }

    /** The ViewModel side of a freeze: the frozen capture and its end, which ignores a stale id (GH #158). */
    private var heldFrozen by mutableStateOf<FrozenCapture?>(null)
    private var heldPhase by mutableStateOf<GuidedScanPhase>(GuidedScanPhase.Viewfinder(stepIndex = 1))

    private val freezeEnded: (Long) -> Unit = { id ->
        if (heldFrozen?.id == id) {
            heldFrozen = null
            ended += id
        }
    }

    @androidx.compose.runtime.Composable
    private fun FrozenScreen() {
        val haptics =
            object : HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                    ticks += hapticFeedbackType
                }
            }
        CompositionLocalProvider(LocalHapticFeedback provides haptics) {
            CubentiaTheme { GuidedScanScreen(heldPhase, strings, GuidedScanActions(onFreezeEnd = freezeEnded), heldFrozen) }
        }
    }

    private fun frozenCapture() = FrozenCapture(7, Face.F, Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImageBitmap())

    /** Applies a state change made by the test, then draws one frame (the test clock is paused). */
    private fun change(block: () -> Unit) {
        rule.runOnIdle(block)
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame()
    }

    @Test
    fun `leaving the viewfinder during a freeze ends it at once, so a held result is published`() {
        heldFrozen = frozenCapture()
        rule.mainClock.autoAdvance = false
        rule.setContent { FrozenScreen() }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("scanFrozen").assertIsDisplayed()
        assertEquals(emptyList<Long>(), ended)
        change { heldPhase = GuidedScanPhase.Unavailable }
        assertEquals(listOf(7L), ended)
        rule.onNodeWithTag("scanFrozen").assertDoesNotExist()
        // The freeze's own timer never ends it a second time.
        rule.mainClock.advanceTimeBy(CaptureFreeze.FREEZE_MS + SHRINK_MS + 200)
        assertEquals(listOf(7L), ended)
    }

    @Test
    fun `a recreate during a freeze ends it and ticks the haptic only once`() {
        heldFrozen = frozenCapture()
        var shown by mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        // A configuration change disposes the old composition before the recreated Activity composes a new one.
        rule.setContent { if (shown) FrozenScreen() }
        rule.mainClock.advanceTimeByFrame()
        assertEquals(listOf(HapticFeedbackType.KeyboardTap), ticks)
        change { shown = false }
        assertEquals(listOf(7L), ended)
        change { shown = true }
        rule.onNodeWithTag("scanFrozen").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(CaptureFreeze.FREEZE_MS + SHRINK_MS + 200)
        assertEquals(listOf(HapticFeedbackType.KeyboardTap), ticks)
        assertEquals(listOf(7L), ended)
    }

    @Test
    fun `prompt color names lowercase by the UI locale's casing rules`() {
        assertEquals("indigo", promptColorName("INDIGO", Locale.ENGLISH))
        assertEquals("ındıgo", promptColorName("INDIGO", Locale.forLanguageTag("tr")))
        assertEquals("white", promptColorName("White", Locale.forLanguageTag("tr")))
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

    @Test
    fun `a retake from review counts only its faces and shows kept faces as captured`() {
        val samples =
            Face.entries.map { face ->
                FaceSample(
                    face = face,
                    orientationDegrees = 0,
                    stickers = List(9) { Lab(55.0, -60.0, 40.0) },
                    quality = CaptureQuality.ACCEPTABLE,
                    centerColor = Color.home(face),
                )
            }
        val from =
            GuidedScanPhase.Review(
                dev.cubentia.domain.recognition
                    .ScanReview(List(54) { null }, null),
                samples = samples,
            )
        show(GuidedScanFlow.retakePhase(GuidedScanFlow.retakeRun(setOf(Face.R, Face.D), from)))
        assertFitsOnOneScreen()
        rule.onNodeWithText("Face 1 of 2").assertIsDisplayed()
        rule.onNodeWithText("Show the red center.").assertIsDisplayed()
        rule.onNodeWithTag("scanSwatch:WHITE").assertDoesNotExist()
        Face.entries.forEach { face ->
            assertEquals(
                face !in setOf(Face.R, Face.D),
                rule.onNodeWithTag("scanNetFace:${face.name}").fetchSemanticsNode().config[ScanNetFaceCaptured],
            )
        }
    }
}

/** True when this rectangle and [other] share any area. */
internal fun DpRect.overlaps(other: DpRect): Boolean = left < other.right && other.left < right && top < other.bottom && other.top < bottom

/** True when this rectangle lies inside [other]. */
internal fun DpRect.inside(other: DpRect): Boolean =
    left >= other.left && top >= other.top && right <= other.right && bottom <= other.bottom
