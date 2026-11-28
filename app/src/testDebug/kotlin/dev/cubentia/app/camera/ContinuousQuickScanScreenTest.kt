package dev.cubentia.app.camera

import android.app.Application
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.preferences.LocalHaptics
import dev.cubentia.app.ui.theme.CubeTokens
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.continuous.ContinuousScanCue
import dev.cubentia.domain.recognition.continuous.Point2D
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp-port")
class ContinuousQuickScanScreenTest {
    @get:Rule val rule = createComposeRule()
    private val strings = ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)

    @Test fun unavailableCardAnnouncesOnFirstCompositionAndOffersBothHandOversAndClose() {
        val taps = mutableListOf<String>()
        val phase = mutableStateOf<GuidedScanPhase>(GuidedScanPhase.QuickScanUnavailable)
        rule.setContent {
            CubentiaTheme {
                GuidedScanScreen(
                    phase.value,
                    strings,
                    GuidedScanActions(
                        onClose = { taps += "close" },
                        onFaceByFace = { taps += "guided" },
                        onEnterColors = { taps += "editor" },
                    ),
                    cornerTorch = true,
                )
            }
        }
        val message = "Quick Scan isn't available right now. You can scan face by face or enter your cube's colors by hand."
        val title = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, message)
        rule.onNodeWithTag("quickScanUnavailable").assertIsDisplayed().assert(title)
        rule.runOnIdle { phase.value = GuidedScanPhase.Continuous }
        rule.onNodeWithTag("quickScan:continuous").assertIsDisplayed()
        rule.runOnIdle { phase.value = GuidedScanPhase.QuickScanUnavailable }
        rule.onNodeWithTag("quickScanUnavailable").assertIsDisplayed().assert(title)
        rule.onNodeWithText(message).assertIsDisplayed()
        rule.onNodeWithText("Scan face by face").performClick()
        rule.onNodeWithText("Enter colors instead").performClick()
        rule.onNodeWithText("Close").performClick()
        assertEquals(listOf("guided", "editor", "close"), taps)
        for (tag in listOf("continuous", "count", "cue", "net", "announcement", "reset", "torch", "reviewAndCorrect")) {
            rule.onNodeWithTag("quickScan:$tag").assertDoesNotExist()
        }
        rule.onNodeWithTag("scanViewfinder").assertDoesNotExist()
        rule.onNodeWithText("Bring the cube into view.").assertDoesNotExist()
    }

    @Test fun onlyTheCueAndIncreasingCountArePoliteLiveRegions() {
        val state = mutableStateOf(ContinuousQuickScanFlow())
        rule.setContent {
            CubentiaTheme {
                GuidedScanScreen(
                    GuidedScanPhase.Continuous,
                    strings,
                    GuidedScanActions(),
                    continuous = state.value,
                )
            }
        }
        val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        rule
            .onNodeWithTag("quickScan:count")
            .assertTextEquals("Sides mapped: 0/6")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        rule.onNodeWithTag("quickScan:cue").assertTextEquals("Bring the cube into view.").assert(polite)
        rule.onNodeWithTag("quickScan:torch").assertDoesNotExist()
        rule.onNodeWithTag("quickScan:faceByFace").assertIsEnabled()
        // One node for the whole scan, empty until the first side: TalkBack is told of a change to a node it knows, and
        // of nothing about a node that has just been added.
        val announcer =
            rule
                .onNodeWithTag("quickScan:announcement")
                .assert(polite)
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
                .fetchSemanticsNode()
                .id

        fun assertTheSameAnnouncer() = assertEquals(announcer, rule.onNodeWithTag("quickScan:announcement").fetchSemanticsNode().id)
        val cues =
            listOf(
                ContinuousScanCue.TurnRemaining(3),
                ContinuousScanCue.TiltAdjacent,
                ContinuousScanCue.KeepTurningRealign,
                ContinuousScanCue.TiltAwayLight,
                ContinuousScanCue.TiltAwayLightRemaining(3),
                ContinuousScanCue.ResolveConflict(Face.F),
                ContinuousScanCue.LayerTwistDetected,
                ContinuousScanCue.TiltRemainingStickers(42),
                ContinuousScanCue.AllVerified,
            )
        for (cue in cues) {
            rule.runOnIdle {
                state.value =
                    state.value.next(ContinuousFrame(testSnapshot(testHandoff(Face.entries.take(3)), cue), emptyList()), 0)
            }
            rule.onNodeWithTag("quickScan:cue").assertTextEquals(strings.resolve(cue.toText())).assert(polite)
            rule
                .onNodeWithTag(
                    "quickScan:announcement",
                ).assert(polite)
                .assertContentDescriptionEquals("Sides mapped: ${state.value.count}/6")
            assertTheSameAnnouncer()
        }
        rule.runOnIdle { state.value = state.value.next(ContinuousFrame(testSnapshot(testHandoff(Face.entries.take(2))), emptyList()), 0) }
        rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 2/6")
        // The count fell: nothing is announced, and the old number is no longer readable beside the visible one.
        rule
            .onNodeWithTag("quickScan:announcement")
            .assert(polite)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        assertTheSameAnnouncer()
        rule.runOnIdle { state.value = state.value.next(ContinuousFrame(testSnapshot(testHandoff(Face.entries.take(4))), emptyList()), 0) }
        rule.onNodeWithTag("quickScan:announcement").assert(polite).assertContentDescriptionEquals("Sides mapped: 4/6")
        assertTheSameAnnouncer()
        // The next increase changes the description of that same node.
        rule.runOnIdle { state.value = state.value.next(ContinuousFrame(testSnapshot(testHandoff(Face.entries.take(5))), emptyList()), 0) }
        rule.onNodeWithTag("quickScan:announcement").assert(polite).assertContentDescriptionEquals("Sides mapped: 5/6")
        assertTheSameAnnouncer()
        assertTrue(
            rule
                .onNodeWithTag("quickScan:net")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .children
                .isEmpty(),
        )
    }

    @Test fun reviewAndCorrectIsOnTheScreenOnlyWhileBothLimitsAreMet() {
        val state = mutableStateOf(ContinuousQuickScanFlow())
        var taps = 0
        rule.setContent {
            CubentiaTheme {
                GuidedScanScreen(
                    GuidedScanPhase.Continuous,
                    strings,
                    GuidedScanActions(onReviewAndCorrect = { taps++ }),
                    continuous = state.value,
                )
            }
        }

        fun feed(
            faces: Int,
            cue: ContinuousScanCue = ContinuousScanCue.TiltAdjacent,
        ) = rule.runOnIdle {
            state.value = state.value.next(ContinuousFrame(testSnapshot(testHandoff(Face.entries.take(faces)), cue), emptyList()), 0)
        }
        // Hidden, not disabled: no frame yet, then three sides (27 stickers).
        rule.onNodeWithTag("quickScan:reviewAndCorrect").assertDoesNotExist()
        feed(3)
        rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 3/6")
        rule.onNodeWithTag("quickScan:reviewAndCorrect").assertDoesNotExist()
        // Four sides and 36 stickers: on the screen, between *Scan face by face* and *Enter colors instead*.
        feed(4)
        val action =
            rule
                .onNodeWithTag("quickScan:reviewAndCorrect")
                .assertIsDisplayed()
                .assertIsEnabled()
                .assertTextEquals("Review and Correct")
                // It appears with no announcement: it is no live region, and the count's announcement is the count's own.
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(
            rule
                .onNodeWithTag("quickScan:faceByFace")
                .fetchSemanticsNode()
                .boundsInRoot.bottom <= action.top,
        )
        assertTrue(
            action.bottom <=
                rule
                    .onNodeWithTag("scanEnterColors")
                    .fetchSemanticsNode()
                    .boundsInRoot.top,
        )
        rule.onNodeWithTag("quickScan:announcement").assertContentDescriptionEquals("Sides mapped: 4/6")
        rule.onNodeWithTag("quickScan:reviewAndCorrect").performClick()
        assertEquals(1, taps)
        // The same label under a latched twist; it hides again when a number falls back, and for the completion hold.
        feed(4, ContinuousScanCue.LayerTwistDetected)
        rule.onNodeWithTag("quickScan:reviewAndCorrect").assertIsDisplayed().assertTextEquals("Review and Correct")
        feed(3)
        rule.onNodeWithTag("quickScan:reviewAndCorrect").assertDoesNotExist()
        // Hidden, its place is kept but is no button: a tap there does nothing.
        rule.onRoot().performTouchInput { click(action.center) }
        rule.waitForIdle()
        assertEquals(1, taps)
        feed(6)
        rule.onNodeWithTag("quickScan:reviewAndCorrect").assertIsDisplayed()
        feed(6, ContinuousScanCue.AllVerified)
        rule.onNodeWithTag("quickScan:cue").assertTextEquals("All 54 stickers verified.")
        rule.onNodeWithTag("quickScan:reviewAndCorrect").assertDoesNotExist()
    }

    /**
     * *Reset*, *Scan face by face* and *Enter colors instead* are where they were when *Review and Correct* appears and
     * when it hides, so a tap aimed at one of them never lands on another. Hidden, the action is no node at all.
     */
    private fun theOtherActionsStayPut() {
        val state = mutableStateOf(ContinuousQuickScanFlow())
        rule.setContent {
            CubentiaTheme { GuidedScanScreen(GuidedScanPhase.Continuous, strings, GuidedScanActions(), continuous = state.value) }
        }

        fun feed(faces: Int) =
            rule.runOnIdle {
                val snapshot = testSnapshot(testHandoff(Face.entries.take(faces)), ContinuousScanCue.TiltAdjacent)
                state.value = state.value.next(ContinuousFrame(snapshot, emptyList()), 0)
            }

        fun others() =
            listOf("quickScan:reset", "quickScan:faceByFace", "scanEnterColors").associateWith {
                rule
                    .onNodeWithTag(it)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            }
        feed(3)
        val hidden = others()
        rule.onNodeWithTag("quickScan:reviewAndCorrect").assertDoesNotExist()
        rule.onNodeWithText("Review and Correct").assertDoesNotExist()
        feed(4)
        rule.onNodeWithTag("quickScan:reviewAndCorrect").assertIsDisplayed()
        assertEquals(hidden, others())
        feed(3)
        rule.onNodeWithTag("quickScan:reviewAndCorrect").assertDoesNotExist()
        rule.onNodeWithText("Review and Correct").assertDoesNotExist()
        assertEquals(hidden, others())
    }

    @Test fun theOtherActionsStayPutWhenReviewAndCorrectAppearsOrHides() = theOtherActionsStayPut()

    /** The short landscape panel, where the action shares the last row with *Enter colors instead*. */
    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun theOtherActionsStayPutOnALandscapePhoneWhenReviewAndCorrectAppearsOrHides() = theOtherActionsStayPut()

    /** The fill of the button [tag], sampled inside its left end, clear of its label. */
    private fun fill(tag: String): Color {
        val image = rule.onNodeWithTag(tag).captureToImage()
        val x = with(rule.density) { 16.dp.roundToPx() }
        return image.toPixelMap()[x, image.height / 2]
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun reviewAndCorrectIsNeverTheEmphasisedAction() {
        val state = mutableStateOf(ContinuousQuickScanFlow())
        rule.setContent {
            CubentiaTheme { GuidedScanScreen(GuidedScanPhase.Continuous, strings, GuidedScanActions(), continuous = state.value) }
        }

        fun feed(cue: ContinuousScanCue) =
            rule.runOnIdle {
                state.value = state.value.next(ContinuousFrame(testSnapshot(testHandoff(Face.entries.take(5)), cue), emptyList()), 0)
            }
        // Scanning: every action is outlined, so all three look alike.
        feed(ContinuousScanCue.TiltAdjacent)
        assertEquals(fill("quickScan:faceByFace"), fill("quickScan:reviewAndCorrect"))
        assertEquals(fill("quickScan:reset"), fill("quickScan:reviewAndCorrect"))
        // A latched twist: *Reset* alone is filled; *Review and Correct* stays outlined like *Scan face by face*.
        feed(ContinuousScanCue.LayerTwistDetected)
        assertEquals(fill("quickScan:faceByFace"), fill("quickScan:reviewAndCorrect"))
        assertTrue(fill("quickScan:reset") != fill("quickScan:reviewAndCorrect"))
    }

    /**
     * Two panes at 600 dp in portrait (AD-17): the overlay covers exactly the preview, the left half of the window, so an
     * outline's view fractions land on the picture they were measured on.
     */
    @Test
    @Config(qualifiers = "w600dp-h960dp-port")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun faceOutlinesStayOnThePreviewInTheTwoPaneLayoutAtSixHundredDpPortrait() {
        val corners = listOf(Point2D(0.2, 0.25), Point2D(0.8, 0.25), Point2D(0.8, 0.75), Point2D(0.2, 0.75))
        val state = ContinuousQuickScanFlow(outlines = mapOf(OutlineKey(Face.F, anchored = false) to TrackedOutline(corners)))
        rule.setContent {
            CubentiaTheme {
                GuidedScanScreen(GuidedScanPhase.Continuous, strings, GuidedScanActions(), continuous = state) {
                    Box(Modifier.fillMaxSize().background(Color.Black).testTag("preview"))
                }
            }
        }
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val preview = rule.onNodeWithTag("preview").fetchSemanticsNode().boundsInRoot
        assertEquals(Rect(root.left, root.top, root.left + root.width / 2, root.bottom), preview)
        assertEquals(preview, rule.onNodeWithTag("scanViewfinder").fetchSemanticsNode().boundsInRoot)
        assertTrue(
            rule
                .onNodeWithTag("quickScan:net")
                .fetchSemanticsNode()
                .boundsInRoot.left >= preview.right,
        )
        val pixels = rule.onNodeWithTag("scanViewfinder").captureToImage().toPixelMap()

        fun at(
            x: Double,
            y: Double,
        ) = pixels[(x * preview.width).toInt(), (y * preview.height).toInt()].toArgb()
        // The middle of each side of the outline, in fractions of the preview; nothing is drawn inside it.
        for ((x, y) in listOf(0.5 to 0.25, 0.8 to 0.5, 0.5 to 0.75, 0.2 to 0.5)) {
            assertEquals("outline at $x, $y", CubeTokens.stickerHighlight.toArgb(), at(x, y))
        }
        assertEquals(Color.Black.toArgb(), at(0.5, 0.5))
    }

    /**
     * GH #409, two panes at 600 dp in portrait: the debug overlay's peak dots and lattice cells land inside the preview at
     * their view fractions (an inlier cell filled, a cell with no peak a ring), with the counts line in the camera area.
     */
    @Test
    @Config(qualifiers = "w600dp-h960dp-port")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theDebugOverlayDrawsPeaksAndLatticeCellsOnThePreviewInTheTwoPaneLayout() {
        val cells = List(9) { i -> Point2D(0.3 + (i % 3) * 0.1, 0.55 + (i / 3) * 0.1) }
        val debug =
            ContinuousDebugOverlay(
                peaks = listOf(DebugPeak(0.25, 0.2, 1f), DebugPeak(0.75, 0.3, 0.2f)),
                lattices = listOf(DebugLattice(cells, List(9) { it != 8 })),
                counts = "peaks=2 fitted=1 sampled=1 kept=1 tracked=0",
            )
        rule.setContent {
            CubentiaTheme {
                GuidedScanScreen(
                    GuidedScanPhase.Continuous,
                    strings,
                    GuidedScanActions(),
                    continuous = ContinuousQuickScanFlow(debugOverlay = debug),
                ) {
                    Box(Modifier.fillMaxSize().background(Color.Black).testTag("preview"))
                }
            }
        }
        val preview = rule.onNodeWithTag("preview").fetchSemanticsNode().boundsInRoot
        assertEquals(preview, rule.onNodeWithTag("scanViewfinder").fetchSemanticsNode().boundsInRoot)
        rule
            .onNode(
                androidx.compose.ui.test
                    .hasTestTag("quickScan:debugCounts") and
                    androidx.compose.ui.test
                        .hasAnyAncestor(
                            androidx.compose.ui.test
                                .hasTestTag("scanViewfinder"),
                        ),
            ).assertIsDisplayed()
            .assertTextEquals("peaks=2 fitted=1 sampled=1 kept=1 tracked=0")
        val pixels = rule.onNodeWithTag("scanViewfinder").captureToImage().toPixelMap()

        fun at(
            x: Double,
            y: Double,
        ) = pixels[(x * preview.width).toInt(), (y * preview.height).toInt()].toArgb()
        for (peak in debug.peaks) {
            assertEquals(
                "peak at ${peak.viewX}, ${peak.viewY}",
                DEBUG_PEAK_COLOR.toArgb(),
                at(peak.viewX, peak.viewY),
            )
        }
        cells.take(8).forEach { c -> assertEquals("inlier cell at ${c.x}, ${c.y}", DEBUG_LATTICE_COLOR.toArgb(), at(c.x, c.y)) }
        // The cell no peak matched is a ring: hollow at its centre, drawn on its edge.
        val ring = cells[8]
        assertEquals(Color.Black.toArgb(), at(ring.x, ring.y))
        val radius = with(rule.density) { 4.dp.toPx() }
        assertTrue(
            "ring edge drawn",
            Color.Black.toArgb() != pixels[(ring.x * preview.width + radius).toInt(), (ring.y * preview.height).toInt()].toArgb(),
        )
        // Away from every mark the preview is untouched.
        assertEquals(Color.Black.toArgb(), at(0.5, 0.45))
    }

    /** GH #409: with no overlay (toggle off, and always in release) nothing of it is drawn and it has no node. */
    @Test
    @Config(qualifiers = "w600dp-h960dp-port")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun withoutADebugOverlayNoCountsNodeOrMarkIsDrawn() {
        rule.setContent {
            CubentiaTheme {
                GuidedScanScreen(GuidedScanPhase.Continuous, strings, GuidedScanActions(), continuous = ContinuousQuickScanFlow()) {
                    Box(Modifier.fillMaxSize().background(Color.Black).testTag("preview"))
                }
            }
        }
        rule.onNodeWithTag("quickScan:debugCounts").assertDoesNotExist()
        rule.onNodeWithTag("quickScan:debugInput").assertDoesNotExist()
        rule.onNodeWithTag("quickScan:debugInputInfo").assertDoesNotExist()
        val pixels = rule.onNodeWithTag("scanViewfinder").captureToImage().toPixelMap()
        val preview = rule.onNodeWithTag("preview").fetchSemanticsNode().boundsInRoot
        for ((x, y) in listOf(0.25 to 0.2, 0.75 to 0.3, 0.4 to 0.65, 0.5 to 0.75)) {
            assertEquals(Color.Black.toArgb(), pixels[(x * preview.width).toInt(), (y * preview.height).toInt()].toArgb())
        }
    }

    /**
     * GH #414, two panes at 600 dp in portrait: the model-input thumbnail is drawn inside the viewfinder, unsmoothed, with
     * its peak dot at the peak's model-input position and its info line.
     */
    @Test
    @Config(qualifiers = "w600dp-h960dp-port")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theModelInputThumbnailIsDrawnInsideTheViewfinderInTheTwoPaneLayout() {
        val size = 256
        val blue = 0xFF0000FF.toInt()
        val green = 0xFF00FF00.toInt()
        // Left half blue, right half green, the top 32 rows a black letterbox bar.
        val pixels =
            IntArray(size * size) { i ->
                if (i / size < 32) {
                    Color.Black.toArgb()
                } else if (i % size < 128) {
                    blue
                } else {
                    green
                }
            }
        val peak = Point2D(64.0, 192.0)
        val thumbnail = ModelInputThumbnail(size, pixels, listOf(peak), "input=256x192 crop=640x480 spacing=-")
        val debug = ContinuousDebugOverlay(emptyList(), emptyList(), "peaks=1 fitted=0 sampled=0 kept=0 tracked=0", thumbnail)
        rule.setContent {
            CubentiaTheme {
                GuidedScanScreen(
                    GuidedScanPhase.Continuous,
                    strings,
                    GuidedScanActions(),
                    continuous = ContinuousQuickScanFlow(debugOverlay = debug),
                ) {
                    Box(Modifier.fillMaxSize().background(Color.Black).testTag("preview"))
                }
            }
        }
        val inViewfinder = { tag: String ->
            androidx.compose.ui.test
                .hasTestTag(tag) and
                androidx.compose.ui.test
                    .hasAnyAncestor(
                        androidx.compose.ui.test
                            .hasTestTag("scanViewfinder"),
                    )
        }
        rule.onNode(inViewfinder("quickScan:debugInput")).assertIsDisplayed()
        rule.onNode(inViewfinder("quickScan:debugInputInfo")).assertIsDisplayed().assertTextEquals(thumbnail.info)
        val viewfinder = rule.onNodeWithTag("scanViewfinder").fetchSemanticsNode().boundsInRoot
        val bounds = rule.onNodeWithTag("quickScan:debugInput").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "thumbnail inside the viewfinder",
            viewfinder.contains(bounds.topLeft) &&
                viewfinder.contains(
                    bounds.bottomRight -
                        androidx.compose.ui.geometry
                            .Offset(1f, 1f),
                ),
        )
        val side = with(rule.density) { 96.dp.toPx() }
        assertEquals(side, bounds.width, 1f)
        val image = rule.onNodeWithTag("quickScan:debugInput").captureToImage().toPixelMap()

        fun at(
            mx: Double,
            my: Double,
        ) = image[(mx / size * image.width).toInt(), (my / size * image.height).toInt()].toArgb()
        assertEquals(Color.Black.toArgb(), at(100.0, 10.0))
        assertEquals(blue, at(30.0, 100.0))
        assertEquals(green, at(200.0, 100.0))
        assertEquals(green, at(200.0, 192.0))
        assertEquals("peak dot", DEBUG_PEAK_COLOR.toArgb(), at(peak.x, peak.y))
    }

    @Test fun confirmAndRejectFallBackToALongPressBelowApi30() {
        val pulses = ContinuousQuickScanFlow.Pulse.entries
        assertEquals(
            listOf(HapticFeedbackType.KeyboardTap, HapticFeedbackType.Confirm, HapticFeedbackType.Reject),
            pulses.map { hapticFor(it, Build.VERSION_CODES.R) },
        )
        assertEquals(
            listOf(HapticFeedbackType.KeyboardTap, HapticFeedbackType.LongPress, HapticFeedbackType.LongPress),
            pulses.map { hapticFor(it, Build.VERSION_CODES.Q) },
        )
    }

    /** Plays the pulses of a count increase, a conflict, a twist and a completion with the haptics setting [enabled]. */
    private fun played(
        enabled: Boolean,
        sdk: Int,
    ): List<HapticFeedbackType> {
        val played = mutableListOf<HapticFeedbackType>()
        val haptics =
            object : HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                    played += hapticFeedbackType
                }
            }
        val pulses = Channel<ContinuousQuickScanFlow.Pulse>(Channel.UNLIMITED)
        rule.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics, LocalHaptics provides enabled) {
                ContinuousQuickScanHaptics(pulses, sdk)
            }
        }
        val three = testHandoff(Face.entries.take(3))
        val full = testHandoff(Face.entries)
        var state = ContinuousQuickScanFlow()
        val snapshots =
            listOf(
                testSnapshot(three),
                // The same snapshot again: no transition, no pulse.
                testSnapshot(three),
                testSnapshot(three, ContinuousScanCue.ResolveConflict(Face.F)),
                testSnapshot(three, ContinuousScanCue.ResolveConflict(Face.F)),
                testSnapshot(three, ContinuousScanCue.LayerTwistDetected),
                testSnapshot(full, ContinuousScanCue.AllVerified),
                testSnapshot(full, ContinuousScanCue.AllVerified),
            )
        for (snapshot in snapshots) {
            val before = state
            state = before.next(ContinuousFrame(snapshot, emptyList()), 0)
            state.pulsesSince(before).forEach { pulses.trySend(it) }
        }
        rule.waitForIdle()
        return played
    }

    @Test fun eachTransitionPlaysItsHapticOnce() {
        assertEquals(
            listOf(
                // 0 → 3 sides.
                HapticFeedbackType.KeyboardTap,
                // The cue becomes a conflict face, then a layer twist.
                HapticFeedbackType.Reject,
                HapticFeedbackType.Reject,
                // 3 → 6 sides, and all 54 verified.
                HapticFeedbackType.KeyboardTap,
                HapticFeedbackType.Confirm,
            ),
            played(enabled = true, sdk = Build.VERSION_CODES.R),
        )
    }

    @Test fun belowApi30TheConfirmAndRejectPulsesAreLongPresses() {
        assertEquals(
            listOf(
                HapticFeedbackType.KeyboardTap,
                HapticFeedbackType.LongPress,
                HapticFeedbackType.LongPress,
                HapticFeedbackType.KeyboardTap,
                HapticFeedbackType.LongPress,
            ),
            played(enabled = true, sdk = Build.VERSION_CODES.Q),
        )
    }

    @Test fun noHapticPlaysWithTheHapticsSettingOff() {
        assertTrue(played(enabled = false, sdk = Build.VERSION_CODES.R).isEmpty())
    }

    /** Story 10.34: with no session (every non-debuggable build) there is no Rec node; a session draws in the camera area. */
    @Test fun withoutASessionOverlayThereIsNoRecControlAndWithOneItSitsInTheCameraArea() {
        val overlay = mutableStateOf<(@androidx.compose.runtime.Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)?>(null)
        val enabled = mutableStateOf(true)
        // Four sides and 36 stickers: a state that offers Review and Correct.
        val offering = ContinuousQuickScanFlow().next(ContinuousFrame(testSnapshot(testHandoff(Face.entries.take(4))), emptyList()), 0)
        assertTrue(offering.reviewAndCorrectOffered())
        rule.setContent {
            CubentiaTheme {
                GuidedScanScreen(
                    GuidedScanPhase.Continuous,
                    strings,
                    GuidedScanActions(),
                    continuous = offering,
                    sessionOverlay = overlay.value,
                    quickScanActionsEnabled = enabled.value,
                )
            }
        }
        rule.onNodeWithTag("quickScan:continuous").assertIsDisplayed()
        rule.onNodeWithTag("quickScan:rec").assertDoesNotExist()
        rule.onNodeWithTag("quickScan:reset").assertIsEnabled()
        rule.onNodeWithTag("quickScan:reviewAndCorrect").assertIsDisplayed()
        rule.runOnIdle {
            overlay.value = {
                androidx.compose.material3.Text(
                    "Rec",
                    Modifier.align(androidx.compose.ui.Alignment.BottomStart).testTag("quickScan:rec"),
                )
            }
            enabled.value = false
        }
        rule
            .onNode(
                androidx.compose.ui.test
                    .hasTestTag("quickScan:rec") and
                    androidx.compose.ui.test
                        .hasAnyAncestor(
                            androidx.compose.ui.test
                                .hasTestTag("scanViewfinder"),
                        ),
            ).assertExists()
        // The session's baseline run disables Reset, Scan face by face and Enter colors instead.
        rule.onNodeWithTag("quickScan:reset").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
        rule.onNodeWithTag("quickScan:faceByFace").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
        rule.onNodeWithTag("scanEnterColors").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
        // ... and hides Review and Correct (its place kept, no node), though the state offers it.
        rule.onNodeWithTag("quickScan:reviewAndCorrect").assertDoesNotExist()
    }
}
