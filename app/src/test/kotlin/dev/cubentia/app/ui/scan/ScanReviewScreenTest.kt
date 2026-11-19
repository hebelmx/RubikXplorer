package dev.cubentia.app.ui.scan

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.preferences.LocalHaptics
import dev.cubentia.app.preferences.LocalReduceMotionOverride
import dev.cubentia.app.render.Net2DRenderer
import dev.cubentia.app.render.STICKER_GAP_FRACTION
import dev.cubentia.app.render.StickerColorKey
import dev.cubentia.app.ui.a11y.assertClickablesAtLeast48dp
import dev.cubentia.app.ui.a11y.assertNoMidWordBreaks
import dev.cubentia.app.ui.a11y.assertNoTextOverflow
import dev.cubentia.app.ui.a11y.fontScale200
import dev.cubentia.app.ui.editor.SelectedRing
import dev.cubentia.app.ui.editor.StickerBlankEdgeKey
import dev.cubentia.app.ui.editor.StickerCueScalesKey
import dev.cubentia.app.ui.editor.StickerInvalidKey
import dev.cubentia.app.ui.editor.StickerOutlineColorKey
import dev.cubentia.app.ui.editor.StickerPulsingKey
import dev.cubentia.app.ui.editor.StickerSelectedRingKey
import dev.cubentia.app.ui.theme.CubeTokens
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.app.ui.theme.DarkTokens
import dev.cubentia.app.ui.theme.LightTokens
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.recognition.ScanResult
import dev.cubentia.domain.recognition.ScanReview
import dev.cubentia.domain.recognition.StickerConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w640dp-h960dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScanReviewScreenTest {
    @get:Rule val rule = createComposeRule()
    private val strings = ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)
    private var review by mutableStateOf(
        ScanReview.from(
            ScanResult.Full(
                CubeState.SOLVED.toFacelets(),
                List(54) {
                    if (it == 7 || it == 20) StickerConfidence.UNCERTAIN else StickerConfidence.CONFIDENT
                },
            ),
        ),
    )
    private var accepted: CubeState? = null
    private var closed = false
    private var enterColors: (() -> Unit)? = null
    private var coachPending by mutableStateOf(false)
    private var coachDismissed = 0
    private var ticks = 0
    private val haptics =
        object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                ticks++
            }
        }

    private fun launch(
        large: Boolean = false,
        reduceMotion: Boolean = false,
        hapticsOn: Boolean = true,
    ) {
        rule.setContent {
            CompositionLocalProvider(
                LocalReduceMotionOverride provides reduceMotion,
                LocalHaptics provides hapticsOn,
                LocalHapticFeedback provides haptics,
            ) {
                CubentiaTheme {
                    if (large) fontScale200 { screen() } else screen()
                }
            }
        }
    }

    @androidx.compose.runtime.Composable private fun screen() {
        ScanReviewScreen(
            review,
            strings,
            { review = it },
            { accepted = it },
            { closed = true },
            onEnterColors = enterColors,
            coachMarkPending = coachPending,
            onCoachMarkDismissed = {
                coachPending = false
                coachDismissed++
            },
        )
    }

    @Test fun manualEntryLeavesAnUnchangedReviewDirectly() {
        var entered = false
        enterColors = { entered = true }
        launch()
        rule.onNodeWithTag("review:enterColors").performScrollTo().performClick()
        rule.onNodeWithTag("review:discard").assertDoesNotExist()
        assertTrue(entered)
        assertFalse(closed)
    }

    @Test fun manualEntryAsksBeforeDiscardingStickerEdits() {
        var entered = false
        enterColors = { entered = true }
        launch()
        rule.onNodeWithTag("swatch:WHITE").performScrollTo().performClick()
        rule.onNodeWithTag("review:enterColors").performScrollTo().performClick()
        rule.onNodeWithTag("review:discard:cancel").performClick()
        assertFalse(entered)
        assertEquals(Color.WHITE, review.stickers[7])
        rule.onNodeWithTag("review:enterColors").performScrollTo().performClick()
        rule.onNodeWithTag("review:discard:confirm").performClick()
        assertTrue(entered)
        assertFalse(closed)
    }

    @Test fun reviewWithoutSolveOfferDoesNotAddManualEntry() {
        launch()
        rule.onNodeWithTag("review:enterColors").assertDoesNotExist()
    }

    @Test fun `blank selected stickers paint with auto advance and accept only after validation`() {
        launch()
        rule.onNodeWithTag("sticker:7").assertIsSelected()
        assertNotEquals(
            rule.onNodeWithTag("sticker:0").fetchSemanticsNode().config[StickerColorKey],
            rule.onNodeWithTag("sticker:7").fetchSemanticsNode().config[StickerColorKey],
        )
        assertEquals("Color needed", rule.onNodeWithTag("sticker:7").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        rule.onNodeWithTag("review:accept").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("swatch:WHITE").performScrollTo().performClick()
        rule.onNodeWithTag("sticker:20").assertIsSelected()
        rule.onNodeWithTag("swatch:GREEN").performClick()
        rule
            .onNodeWithTag("review:accept")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(CubeState.SOLVED, accepted)
        // The Learner changed stickers, so Close asks first.
        rule.onNodeWithTag("review:close").performScrollTo().performClick()
        rule.onNodeWithTag("review:discard:confirm").performClick()
        assertEquals(true, closed)
    }

    @Test fun `two touches correct a selected sticker and two whites identify their positions`() {
        review = ScanReview(CubeState.SOLVED.toFacelets().colors, null)
        launch()
        rule.onNodeWithTag("sticker:9").performClick()
        rule.onNodeWithTag("swatch:WHITE").performScrollTo().performClick()
        rule.onNodeWithTag("review:problem:0").performScrollTo().assertTextEquals("This corner shows two whites.")
        assertEquals(true, rule.onNodeWithTag("sticker:8").fetchSemanticsNode().config[StickerInvalidKey])
        assertEquals(true, rule.onNodeWithTag("sticker:9").fetchSemanticsNode().config[StickerInvalidKey])
        assertEquals(false, rule.onNodeWithTag("sticker:20").fetchSemanticsNode().config[StickerInvalidKey])
        rule.onNodeWithTag("review:accept").performScrollTo().assertIsNotEnabled()
        assertNull(accepted)
        rule.onNodeWithTag("swatch:RED").performScrollTo().performClick()
        rule.onNodeWithTag("review:accept").performScrollTo().assertIsEnabled()
    }

    @Test
    @Config(qualifiers = "w600dp-h960dp")
    fun `review at 200 percent has no broken words overflow or undersized controls`() {
        val colors =
            CubeState.SOLVED
                .toFacelets()
                .colors
                .toMutableList<Color?>()
                .also { it[9] = Color.WHITE }
        // Blanks, the selected-sticker line and a problem line all render at 200%.
        colors[7] = null
        colors[30] = null
        review = ScanReview(colors, 7)
        launch(large = true)
        rule.onNodeWithTag("review:problem:0").performScrollTo().assertTextEquals("This corner shows two whites.")
        rule.onNodeWithTag("review:selected").assertTextEquals("Up face, position 8, needs a color")
        rule.assertNoMidWordBreaks()
        rule.assertNoTextOverflow("scan review at 200 percent")
        rule.assertClickablesAtLeast48dp("scan review")
        rule.onNodeWithTag("review:close").performScrollTo().performClick()
        assertEquals(true, closed)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `compact review shows one face and auto advance reveals the next blank face`() {
        launch()
        rule.onAllNodesWithTag("sticker:7").assertCountEquals(1)
        rule.onAllNodesWithTag("sticker:20").assertCountEquals(0)
        rule.onNodeWithTag("swatch:WHITE").performScrollTo().performClick()
        rule.onNodeWithTag("sticker:20").assertIsSelected()
        rule.onAllNodesWithTag("sticker:7").assertCountEquals(0)
        rule.onNodeWithTag("swatch:GREEN").performScrollTo().performClick()
        rule.onNodeWithTag("review:accept").performScrollTo().assertIsEnabled()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `switching faces never lets the palette paint a hidden sticker`() {
        launch()
        rule.onNodeWithTag("review:selected").assertTextEquals("Up face, position 8, needs a color")
        // A face with a blank moves the selection to that blank, and the change is announced.
        rule.onNodeWithTag("editor:face:2").performClick()
        rule.onNodeWithTag("sticker:20").assertIsSelected()
        rule.onNodeWithTag("review:selected").assertTextEquals("Front face, position 3, needs a color")
        // A face with no blank selects its first sticker, so the palette paints only what is shown.
        rule.onNodeWithTag("editor:face:3").performClick()
        rule.onNodeWithTag("sticker:27").assertIsSelected()
        assertEquals(27, review.selected)
        rule.onNodeWithTag("swatch:RED").performScrollTo().performClick()
        assertEquals(Color.RED, review.stickers[27])
        assertNull(review.stickers[20])
    }

    @Test fun `the selected sticker pulses`() {
        launch()
        assertEquals(true, rule.onNodeWithTag("sticker:7").fetchSemanticsNode().config[StickerPulsingKey])
    }

    @Test fun `under reduce motion the selected ring is static`() {
        launch(reduceMotion = true)
        rule.onNodeWithTag("sticker:7").assertIsSelected()
        assertEquals(false, rule.onNodeWithTag("sticker:7").fetchSemanticsNode().config[StickerPulsingKey])
        // Still the dashed ring, never a solid outline (PO 2026-11-16).
        assertEquals(
            DASHED_ACCENT_RING.copy(color = LightTokens.accent),
            rule.onNodeWithTag("sticker:7").fetchSemanticsNode().config[StickerSelectedRingKey],
        )
    }

    @Test fun `the selected ring is a thick dashed accent ring, distinct from an invalid piece's solid outline (PO 2026-11-16)`() {
        review = ScanReview(CubeState.SOLVED.toFacelets().colors, null)
        launch()
        rule.onNodeWithTag("sticker:9").performClick()
        rule.onNodeWithTag("swatch:WHITE").performScrollTo().performClick()
        // Sticker 9 is both invalid and selected: a solid sticker-highlight outline plus the dashed accent ring.
        val both = rule.onNodeWithTag("sticker:9").fetchSemanticsNode().config
        assertEquals(true, both[StickerInvalidKey])
        assertEquals(CubeTokens.stickerHighlight, both[StickerOutlineColorKey])
        assertEquals(DASHED_ACCENT_RING.copy(color = LightTokens.accent), both[StickerSelectedRingKey])
        // Another sticker of the invalid piece carries only the solid outline, no dashed ring.
        val outlinedOnly =
            review.outlined.first { it != 9 }.let { rule.onNodeWithTag("sticker:$it").fetchSemanticsNode().config }
        assertEquals(CubeTokens.stickerHighlight, outlinedOnly[StickerOutlineColorKey])
        assertFalse(outlinedOnly.contains(StickerSelectedRingKey))
    }

    @Test fun `in the dark theme, where accent equals sticker-highlight, the selected ring stays dashed`() {
        rule.setContent {
            CompositionLocalProvider(LocalReduceMotionOverride provides true) {
                CubentiaTheme(darkTheme = true) { screen() }
            }
        }
        val ring = rule.onNodeWithTag("sticker:7").fetchSemanticsNode().config[StickerSelectedRingKey]
        assertEquals(DarkTokens.accent, ring.color)
        assertEquals(DASHED_ACCENT_RING.width, ring.width)
        assertEquals(DASHED_ACCENT_RING.dash, ring.dash)
    }

    @Test fun `setting a color plays the update cue, ticks and announces the sticker`() {
        launch()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("swatch:WHITE").performScrollTo().performClick()
        rule.mainClock.advanceTimeBy(50)
        rule.onNodeWithTag("sticker:7:cue", useUnmergedTree = true).assertExists()
        assertEquals(true, rule.onNodeWithTag("sticker:7").fetchSemanticsNode().config[StickerCueScalesKey])
        assertEquals(1, ticks)
        rule.onNodeWithTag("review:updated").assertTextEquals("Up face, position 8: white")
        assertEquals(
            LiveRegionMode.Polite,
            rule.onNodeWithTag("review:updated").fetchSemanticsNode().config[SemanticsProperties.LiveRegion],
        )
        rule.onNodeWithTag("review:selected").assertTextEquals("Front face, position 3, needs a color")
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag("sticker:7:cue", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun `under reduce motion the update cue shows only its check and haptics follow the setting`() {
        launch(reduceMotion = true, hapticsOn = false)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("swatch:WHITE").performScrollTo().performClick()
        rule.mainClock.advanceTimeBy(50)
        rule.onNodeWithTag("sticker:7:cue", useUnmergedTree = true).assertExists()
        assertEquals(false, rule.onNodeWithTag("sticker:7").fetchSemanticsNode().config[StickerCueScalesKey])
        assertEquals(0, ticks)
    }

    @Test fun `the instruction line follows blanks, then problems, then a valid read`() {
        launch()
        rule.onNodeWithTag("review:instruction").assertTextEquals("2 stickers need a color. Tap the flashing one, then its color.")
        rule.onNodeWithTag("swatch:WHITE").performScrollTo().performClick()
        rule.onNodeWithTag("review:instruction").assertTextEquals("1 sticker needs a color. Tap the flashing one, then its color.")
        rule.onNodeWithTag("swatch:WHITE").performScrollTo().performClick()
        rule
            .onNodeWithTag("review:instruction")
            .assertTextEquals("This corner shows two whites. Check the outlined piece.")
        rule.onNodeWithTag("sticker:20").performClick()
        rule.onNodeWithTag("swatch:GREEN").performScrollTo().performClick()
        rule.onNodeWithTag("review:instruction").assertTextEquals("All set. Compare with your cube, then Accept.")
    }

    @Test fun `a valid scan that needed no fixes says everything was read (PO 2026-11-16)`() {
        review = ScanReview(CubeState.SOLVED.toFacelets().colors, null)
        launch()
        rule.onNodeWithTag("review:instruction").assertTextEquals("Everything was read. Compare with your cube, then Accept.")
    }

    @Test fun `the coach mark shows until one tap dismisses it, which records it as seen (PO 2026-11-16)`() {
        coachPending = true
        launch()
        rule.onNodeWithTag("review:coach").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("review:coach").assertTextEquals("Tap it, then the color you see on your cube")
        assertEquals(0, coachDismissed)
        rule.onNodeWithTag("review:coach").performClick()
        rule.onNodeWithTag("review:coach").assertDoesNotExist()
        assertEquals(1, coachDismissed)
    }

    @Test fun `a review closed without dismissing the coach mark shows it again next time`() {
        coachPending = true
        var open by mutableStateOf(true)
        rule.setContent { CubentiaTheme { if (open) screen() } }
        rule.onNodeWithTag("review:coach").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("review:close").performScrollTo().performClick()
        assertTrue(closed)
        assertEquals(0, coachDismissed)
        assertTrue(coachPending)
        // The next review: a fresh screen with the record still pending.
        open = false
        rule.waitForIdle()
        open = true
        rule.onNodeWithTag("review:coach").performScrollTo().assertIsDisplayed()
    }

    @Test fun `the coach mark is not shown when nothing needs a color`() {
        coachPending = true
        review = ScanReview(CubeState.SOLVED.toFacelets().colors, null)
        launch()
        rule.onNodeWithTag("review:coach").assertDoesNotExist()
        assertEquals(0, coachDismissed)
    }

    @Test fun `an invalid piece is outlined in sticker-highlight, never status-invalid red`() {
        review = ScanReview(CubeState.SOLVED.toFacelets().colors, null)
        launch()
        rule.onNodeWithTag("sticker:9").performClick()
        rule.onNodeWithTag("swatch:WHITE").performScrollTo().performClick()
        val outline = rule.onNodeWithTag("sticker:9").fetchSemanticsNode().config[StickerOutlineColorKey]
        assertEquals(CubeTokens.stickerHighlight, outline)
        assertNotEquals(LightTokens.statusInvalid, outline)
    }

    @Test fun `a blank sticker is a dark well with a sticker-highlight edge and a question mark`() {
        launch()
        val blank = rule.onNodeWithTag("sticker:7").fetchSemanticsNode().config
        assertEquals(DarkTokens.surfaceStage, blank[StickerColorKey])
        assertEquals(CubeTokens.stickerHighlight, blank[StickerBlankEdgeKey])
        rule
            .onNodeWithTag("sticker:7", useUnmergedTree = true)
            .onChildren()
            .filterToOne(hasText("?"))
            .assertExists()
    }

    @Test fun `blank labels fit their stickers in the full net at 200 percent`() {
        launch(large = true)
        rule.onAllNodesWithTag("sticker:53").assertCountEquals(1)
        assertBlankLabelsSizedLikeGlyphs(Net2DRenderer.NET_COLUMNS, "full scan review net at 200 percent")
    }

    @Test
    @Config(qualifiers = "w608dp-h960dp")
    fun `blank labels fit their stickers in the smallest full net at 200 percent`() {
        launch(large = true)
        rule.onAllNodesWithTag("sticker:53").assertCountEquals(1)
        assertBlankLabelsSizedLikeGlyphs(Net2DRenderer.NET_COLUMNS, "608 dp scan review net at 200 percent")
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `blank labels fit their stickers in the compact one-face view at 200 percent`() {
        launch(large = true)
        rule.onAllNodesWithTag("sticker:53").assertCountEquals(0)
        assertBlankLabelsSizedLikeGlyphs(3, "compact scan review at 200 percent")
    }

    /** Each shown blank's "?" is sized like StickerGlyph from its [columns]-wide net's cell and stays inside its sticker. */
    private fun assertBlankLabelsSizedLikeGlyphs(
        columns: Int,
        screen: String,
    ) {
        rule.assertNoTextOverflow(screen)
        val net = rule.onNodeWithTag("reviewNet").fetchSemanticsNode()
        val cellDp = net.size.width / net.layoutInfo.density.density / columns
        // Sized like StickerGlyph: 55% of the cell in dp (at least 16dp, at most the sticker), never scaled by font size.
        val expectedDp = (cellDp * 0.55f).coerceIn(16f, cellDp * (1f - 2f * STICKER_GAP_FRACTION))
        val shown = review.blanks.filter { rule.onAllNodesWithTag("sticker:$it").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("$screen shows a blank", shown.isNotEmpty())
        shown.forEach { index ->
            val sticker = rule.onNodeWithTag("sticker:$index", useUnmergedTree = true)
            val cell = sticker.fetchSemanticsNode()
            val label = sticker.onChildren().filterToOne(hasText("?")).fetchSemanticsNode()
            val cellBounds = Rect(cell.positionInRoot, cell.size.toSize())
            val labelBounds = Rect(label.positionInRoot, label.size.toSize())
            val layouts = mutableListOf<TextLayoutResult>()
            label.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val labelDp =
                with(label.layoutInfo.density) {
                    layouts
                        .single()
                        .layoutInput.style.fontSize
                        .toDp()
                        .value
                }
            assertEquals("$screen: blank label $index font size in dp", expectedDp, labelDp, 0.5f)
            assertTrue(
                "$screen: blank label $labelBounds exceeds sticker $index at $cellBounds",
                labelBounds.left >= cellBounds.left && labelBounds.top >= cellBounds.top &&
                    labelBounds.right <= cellBounds.right && labelBounds.bottom <= cellBounds.bottom,
            )
        }
    }

    @Test fun `close is enabled and closes at once when nothing was changed`() {
        launch()
        rule
            .onNodeWithTag("review:close")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        rule.onNodeWithTag("review:discard").assertDoesNotExist()
        assertEquals(true, closed)
    }

    @Test fun `close asks to discard after a change and cancel keeps the review`() {
        launch()
        rule.onNodeWithTag("swatch:WHITE").performScrollTo().performClick()
        rule.onNodeWithTag("review:close").performScrollTo().performClick()
        rule.onNodeWithTag("review:discard").assertExists()
        rule.onNodeWithText("Discard this scan?").assertExists()
        rule.onNodeWithTag("review:discard:cancel").performClick()
        rule.onNodeWithTag("review:discard").assertDoesNotExist()
        assertEquals(false, closed)
        rule.onNodeWithTag("review:close").performScrollTo().performClick()
        rule.onNodeWithTag("review:discard:confirm").performClick()
        assertEquals(true, closed)
    }

    @Test fun `a review already edited before it is shown still asks before discarding`() {
        rule.setContent { CubentiaTheme { ScanReviewScreen(review, strings, {}, {}, { closed = true }, edited = true) } }
        rule.onNodeWithTag("review:close").performScrollTo().performClick()
        rule.onNodeWithTag("review:discard").assertExists()
        assertEquals(false, closed)
    }

    @Test fun calibrationOfferRequiresConfirmation() {
        var calls = 0
        val strings = ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)
        val review = ScanReview(List(54) { null }, 0)
        rule.setContent { CubentiaTheme { ScanReviewScreen(review, strings, {}, {}, {}, onCalibrate = { calls++ }) } }
        rule.onNodeWithTag("review:calibrate:offer").performScrollTo().performClick()
        assertEquals(0, calls)
        rule.onNodeWithTag("review:calibrate:cancel").performClick()
        assertEquals(0, calls)
        rule.onNodeWithTag("review:calibrate:offer").performScrollTo().performClick()
        rule.onNodeWithTag("review:calibrate:confirm").performClick()
        assertEquals(1, calls)
    }
}

/** The selected ring: 5dp in all (3dp accent dashes edged by 1dp cubie-body), 8dp dashes. */
private val DASHED_ACCENT_RING = SelectedRing(LightTokens.accent, 5.dp, 8.dp)
