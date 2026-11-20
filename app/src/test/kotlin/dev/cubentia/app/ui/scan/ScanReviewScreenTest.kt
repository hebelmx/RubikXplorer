package dev.cubentia.app.ui.scan

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
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
import dev.cubentia.app.ui.editor.StickerProblemFocusedKey
import dev.cubentia.app.ui.editor.StickerProblemTagShape
import dev.cubentia.app.ui.editor.StickerProblemTagShapeKey
import dev.cubentia.app.ui.editor.StickerProblemsKey
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
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.Lab
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
    private lateinit var backDispatcher: OnBackPressedDispatcher
    private var closed = false
    private var enterColors: (() -> Unit)? = null
    private var retake: ((Set<Face>) -> Unit)? = null
    private var calibrate: (() -> Unit)? = null
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
        edited: Boolean = false,
    ) {
        rule.setContent {
            backDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            CompositionLocalProvider(
                LocalReduceMotionOverride provides reduceMotion,
                LocalHaptics provides hapticsOn,
                LocalHapticFeedback provides haptics,
            ) {
                CubentiaTheme {
                    if (large) fontScale200 { screen(edited) } else screen(edited)
                }
            }
        }
    }

    @androidx.compose.runtime.Composable private fun screen(edited: Boolean = false) {
        ScanReviewScreen(
            review,
            strings,
            { review = it },
            { accepted = it },
            { closed = true },
            edited = edited,
            onEnterColors = enterColors,
            onRetake = retake,
            onCalibrate = calibrate,
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
        assertEquals(true, rule.onNodeWithTag("sticker:20").fetchSemanticsNode().config[StickerInvalidKey])
        rule.onNodeWithTag("review:accept").performScrollTo().assertIsNotEnabled()
        assertNull(accepted)
        rule.onNodeWithTag("swatch:RED").performScrollTo().performClick()
        rule.onNodeWithTag("review:accept").performScrollTo().assertIsEnabled()
    }

    @Test fun `numbered problem links its whole piece and names conflicting stickers for TalkBack`() {
        review =
            ScanReview(
                CubeState.SOLVED
                    .toFacelets()
                    .colors
                    .toMutableList()
                    .also { it[9] = Color.WHITE },
                null,
            )
        launch()
        assertProblemTag("review:problem:0:tag", "1", focused = true)
        assertProblemTag("sticker:8:tag", "1", focused = true)
        assertProblemTag("sticker:9:tag", "1", focused = true)
        assertProblemTag("sticker:20:tag", "1", focused = true, shape = StickerProblemTagShape.ROUNDED_SQUARE)
        for (index in listOf(8, 9, 20)) {
            val sticker = rule.onNodeWithTag("sticker:$index").fetchSemanticsNode().config
            assertTrue(sticker[StickerInvalidKey])
            assertEquals(listOf(1), sticker[StickerProblemsKey])
            val state = if (index == 20) "Part of problem 1" else "Conflicting sticker in problem 1"
            assertEquals(state, sticker[SemanticsProperties.StateDescription])
        }
        val line = rule.onNodeWithTag("review:problem:0").fetchSemanticsNode().config
        assertEquals(
            listOf(
                "Problem 1: This corner shows two whites. Its piece: Up face, position 9; Right face, position 1; " +
                    "Front face, position 3. Conflicting stickers: Up face, position 9; Right face, position 1.",
            ),
            line[SemanticsProperties.ContentDescription],
        )
        assertEquals("Select it on the net", line[SemanticsActions.OnClick].label)
        val global = rule.onNodeWithTag("review:problem:1").fetchSemanticsNode().config
        assertNull(global.getOrNull(SemanticsActions.OnClick))
        rule.onAllNodesWithTag("review:problem:1:tag", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun `tapping a numbered problem focuses its tags and painting restores the first focus`() {
        val colors =
            CubeState.SOLVED.toFacelets().colors.toMutableList().also {
                it[9] = Color.WHITE
                it[18] = Color.WHITE
            }
        review = ScanReview(colors, null)
        launch(reduceMotion = true)
        val firstTag = rule.onNodeWithTag("sticker:8:tag", useUnmergedTree = true)
        assertTagFill(CubeTokens.stickerHighlight, firstTag.captureToImage().toPixelMap())
        rule
            .onNodeWithTag("review:problem:1")
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        assertEquals(6, review.selected)
        rule.onNodeWithTag("sticker:6").assertIsSelected()
        assertProblemTag("review:problem:1:tag", "2", focused = true)
        assertProblemTag("sticker:6:tag", "2", focused = true)
        assertProblemTag("review:problem:0:tag", "1", focused = false)
        assertProblemTag("sticker:8:tag", "1", focused = false)
        assertTagFill(CubeTokens.cubieBody, firstTag.captureToImage().toPixelMap())
        rule.onNodeWithTag("swatch:RED").performScrollTo().performClick()
        rule.onNodeWithTag("review:problem:0").assertIsSelected()
        assertProblemTag("sticker:8:tag", "1", focused = true)
    }

    @Test fun `a conflicting sticker announces both problems and shows the focused number`() {
        val colors =
            CubeState.SOLVED.toFacelets().colors.toMutableList().also {
                it[9] = Color.WHITE
                it[20] = Color.YELLOW
            }
        review = ScanReview(colors, null)
        launch()
        val sticker = rule.onNodeWithTag("sticker:8").fetchSemanticsNode().config
        assertEquals(listOf(1, 2), sticker[StickerProblemsKey])
        assertEquals("Conflicting sticker in problems 1, 2", sticker[SemanticsProperties.StateDescription])
        assertProblemTag("sticker:8:tag", "1", focused = true)
        // Sticker 20 conflicts only in problem 2, so problem 2 keeps a visible number while problem 1 is focused.
        val mixed = rule.onNodeWithTag("sticker:20").fetchSemanticsNode().config
        assertEquals(listOf(1, 2), mixed[StickerProblemsKey])
        assertEquals("Conflicting sticker in problem 2. Part of problem 1", mixed[SemanticsProperties.StateDescription])
        assertProblemTag("sticker:20:tag", "2", focused = false)
        rule.onNodeWithTag("review:problem:1").performScrollTo().performClick()
        assertProblemTag("sticker:8:tag", "2", focused = true)
        assertProblemTag("sticker:20:tag", "2", focused = true)
    }

    @Test
    @Config(qualifiers = "w360dp-h240dp")
    fun `in a window shorter than the net a linked line scrolls the selected row into view`() {
        review =
            ScanReview(
                CubeState.SOLVED
                    .toFacelets()
                    .colors
                    .toMutableList()
                    .also { it[9] = Color.WHITE },
                27,
            )
        launch()
        rule.onNodeWithTag("review:problem:0").performScrollTo().performClick()
        rule.waitForIdle()
        // Sticker 8 sits on the bottom row of the Up face; no further scroll call is made.
        rule.onNodeWithTag("sticker:8").assertIsDisplayed().assertIsSelected()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `a linked line switches the compact net to its first conflicting face`() {
        review =
            ScanReview(
                CubeState.SOLVED
                    .toFacelets()
                    .colors
                    .toMutableList()
                    .also { it[9] = Color.WHITE },
                27,
            )
        launch()
        rule.onNodeWithTag("sticker:27").assertIsSelected()
        rule.onNodeWithTag("review:problem:0").performScrollTo().performClick()
        rule
            .onNodeWithTag("sticker:8")
            .assertIsDisplayed()
            .assertIsSelected()
        assertEquals(8, review.selected)
        for (face in 0..2) {
            val tab = rule.onNodeWithTag("editor:face:$face").fetchSemanticsNode().config
            assertEquals("Has pieces to check", tab[SemanticsProperties.StateDescription])
        }
        rule.onNodeWithTag("editor:face:0").assertIsSelected()
    }

    @Test fun `validator impossible corners link every sticker and list each position once`() {
        val colors =
            CubeState.SOLVED.toFacelets().colors.toMutableList().also {
                it[9] = Color.ORANGE
                it[36] = Color.RED
            }
        review = ScanReview(colors, null)
        launch()
        val line = rule.onNodeWithTag("review:problem:0")
        assertProblemTag("review:problem:0:tag", "1", focused = true)
        for (index in listOf(0, 8, 9, 20, 36, 47)) {
            val sticker = rule.onNodeWithTag("sticker:$index").fetchSemanticsNode().config
            assertEquals(listOf(1), sticker[StickerProblemsKey])
            assertProblemTag("sticker:$index:tag", "1", focused = true)
        }
        val description = line.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertTrue(description.startsWith("Problem 1: "))
        assertTrue(
            description.endsWith(
                "Stickers: Up face, position 1; Up face, position 9; Right face, position 1; " +
                    "Front face, position 3; Left face, position 1; Back face, position 3.",
            ),
        )
        assertEquals(1, Regex("Up face, position 1").findAll(description).count())
        line.performScrollTo().performClick()
        assertEquals(0, review.selected)
        rule.onNodeWithTag("sticker:0").assertIsDisplayed().assertIsSelected()
    }

    // #174: wrong color counts point at the likely misread stickers.

    private val labs =
        mapOf(
            Color.WHITE to Lab(90.0, 0.0, 0.0),
            Color.YELLOW to Lab(85.0, -5.0, 80.0),
            Color.GREEN to Lab(55.0, -60.0, 40.0),
            Color.BLUE to Lab(40.0, 10.0, -50.0),
            Color.RED to Lab(45.0, 65.0, 45.0),
            Color.ORANGE to Lab(65.0, 45.0, 65.0),
        )

    /** Solved with the L-B edge read yellow (39) and red (50): 10 yellow, 8 orange, 10 red, 8 blue; no piece conflict. */
    private val miscounted =
        CubeState.SOLVED.toFacelets().colors.toMutableList().also {
            it[39] = Color.YELLOW
            it[50] = Color.RED
        }

    private fun suspectReview(): ScanReview {
        val measured =
            miscounted.mapIndexed { index, color ->
                when (index) {
                    // A yellow that measures close to orange.
                    39 -> Lab(70.0, 30.0, 70.0)

                    // A red that measures close to blue.
                    50 -> Lab(42.0, 20.0, -30.0)

                    else -> labs.getValue(color)
                }
            }
        return ScanReview.from(
            ScanResult.Full(Facelets(miscounted), List(54) { StickerConfidence.CONFIDENT }, measured),
            CalibrationCenters(labs),
        )
    }

    @Test fun `wrong counts with scan evidence number the line, outline the suspects and suggest the top one`() {
        review = suspectReview()
        assertEquals(listOf(39, 50, 9), review.suspects.map { it.index })
        launch()
        rule.onNodeWithTag("review:problem:0").performScrollTo().assertIsSelected()
        assertEquals(
            "msg.validation.wrong-color-counts",
            review.problems
                .single()
                .reason.key,
        )
        assertProblemTag("review:problem:0:tag", "1", focused = true)
        for (index in listOf(39, 50, 9)) {
            val sticker = rule.onNodeWithTag("sticker:$index").fetchSemanticsNode().config
            assertTrue(sticker[StickerInvalidKey])
            assertEquals(listOf(1), sticker[StickerProblemsKey])
        }
        assertEquals(setOf(9, 39, 50), review.outlined)
        rule.onNodeWithTag("sticker:39").assertIsSelected()
        val suggestion = rule.onNodeWithTag("review:suggestion")
        suggestion.assertTextEquals("This sticker may be orange — check it.")
        assertEquals(LiveRegionMode.Polite, suggestion.fetchSemanticsNode().config[SemanticsProperties.LiveRegion])
    }

    @Test fun `a top suspect that also leads a piece problem opens on the counts line`() {
        // Solved with R's sticker 9 read white: corner 8-9-20 shows two whites (problem 1, positions 8 and 9) and
        // whites are over by one. Sticker 8 measures close to red, so it is the top suspect and leads both problems.
        val colors =
            CubeState.SOLVED
                .toFacelets()
                .colors
                .toMutableList()
                .also { it[9] = Color.WHITE }
        review =
            ScanReview.from(
                ScanResult.Full(
                    Facelets(colors),
                    List(54) { StickerConfidence.CONFIDENT },
                    colors.mapIndexed { index, color -> if (index == 8) Lab(55.0, 50.0, 40.0) else labs.getValue(color) },
                ),
                CalibrationCenters(labs),
            )
        assertEquals(8, review.selected)
        assertEquals(listOf(8, 9), review.problems[0].positions)
        val counts = review.problems.indexOfFirst { it.reason.key == "msg.validation.wrong-color-counts" }
        assertEquals(8, review.problems[counts].positions.first())
        launch()
        rule.onNodeWithTag("review:problem:$counts").performScrollTo().assertIsSelected()
        rule.onNodeWithTag("review:problem:0").performScrollTo().assertIsNotSelected()
    }

    @Test fun `the suggestion follows the selected suspect and disappears on any other sticker`() {
        review = suspectReview()
        launch()
        rule.onNodeWithTag("sticker:50").performClick()
        rule.onNodeWithTag("review:suggestion").assertTextEquals("This sticker may be blue — check it.")
        rule.onNodeWithTag("sticker:9").performClick()
        rule.onNodeWithTag("review:suggestion").assertTextEquals("This sticker may be orange — check it.")
        rule.onNodeWithTag("sticker:0").performClick()
        rule.onNodeWithTag("review:suggestion").assertDoesNotExist()
    }

    @Test fun `wrong counts without scan evidence stay a plain line with nothing outlined`() {
        review = ScanReview(miscounted, 39)
        launch()
        val line = rule.onNodeWithTag("review:problem:0").performScrollTo()
        assertNull(line.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick))
        rule.onAllNodesWithTag("review:problem:0:tag", useUnmergedTree = true).assertCountEquals(0)
        assertTrue(review.outlined.isEmpty())
        assertNull(
            rule
                .onNodeWithTag("sticker:39")
                .fetchSemanticsNode()
                .config
                .getOrNull(StickerProblemsKey),
        )
        rule.onNodeWithTag("review:suggestion").assertDoesNotExist()
    }

    @Test fun `right counts with impossible corners open on problem 1's first conflicting sticker`() {
        val colors =
            CubeState.SOLVED.toFacelets().colors.toMutableList().also {
                it[9] = Color.ORANGE
                it[36] = Color.RED
            }
        review = ScanReview.from(ScanResult.Full(Facelets(colors), List(54) { StickerConfidence.CONFIDENT }))
        assertEquals(0, review.selected)
        launch()
        rule.onNodeWithTag("sticker:0").assertIsSelected()
        rule.onNodeWithTag("review:problem:0").performScrollTo().assertIsSelected()
        assertProblemTag("review:problem:0:tag", "1", focused = true)
        assertProblemTag("sticker:0:tag", "1", focused = true)
        rule.onNodeWithTag("review:suggestion").assertDoesNotExist()
    }

    private fun assertTagFill(
        expected: androidx.compose.ui.graphics.Color,
        pixels: androidx.compose.ui.graphics.PixelMap,
    ) {
        assertEquals(expected, pixels[pixels.width / 4, pixels.height / 2])
    }

    private fun assertProblemTag(
        tag: String,
        number: String,
        focused: Boolean,
        shape: StickerProblemTagShape = StickerProblemTagShape.CIRCLE,
    ) {
        val node = rule.onNodeWithTag(tag, useUnmergedTree = true)
        assertEquals(focused, node.fetchSemanticsNode().config[StickerProblemFocusedKey])
        assertEquals(shape, node.fetchSemanticsNode().config[StickerProblemTagShapeKey])
        node.onChildren().filterToOne(hasText(number)).assertTextEquals(number)
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
        val sticker = rule.onNodeWithTag("sticker:7").fetchSemanticsNode().config
        assertEquals(true, sticker[StickerCueScalesKey])
        assertTrue(sticker.getOrNull(SemanticsProperties.Text).orEmpty().none { '✓' in it.text })
        assertTrue(sticker.getOrNull(SemanticsProperties.ContentDescription).orEmpty().none { '✓' in it })
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

    @Test fun `filling every blank hides the coach mark without recording dismissal`() {
        coachPending = true
        launch()
        rule.onNodeWithTag("review:coach").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("swatch:WHITE").performScrollTo().performClick()
        rule.onNodeWithTag("swatch:GREEN").performScrollTo().performClick()
        assertTrue(review.blanks.isEmpty())
        rule.onNodeWithTag("review:coach").assertDoesNotExist()
        assertEquals(0, coachDismissed)
        assertTrue(coachPending)
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

    @Test fun `Back after painting asks before discarding and cancel keeps the review`() {
        launch()
        rule.onNodeWithTag("swatch:WHITE").performScrollTo().performClick()
        rule.runOnIdle { backDispatcher.onBackPressed() }
        rule.onNodeWithTag("review:discard").assertExists()
        assertFalse(closed)
        rule.onNodeWithTag("review:discard:cancel").performClick()
        rule.onNodeWithTag("review:discard").assertDoesNotExist()
        assertFalse(closed)
        assertEquals(Color.WHITE, review.stickers[7])
        rule.runOnIdle { backDispatcher.onBackPressed() }
        rule.onNodeWithTag("review:discard:confirm").performClick()
        assertTrue(closed)
    }

    @Test fun `Back asks when the review was already edited before it was shown`() {
        launch(edited = true)
        rule.runOnIdle { backDispatcher.onBackPressed() }
        rule.onNodeWithTag("review:discard").assertExists()
        assertFalse(closed)
    }

    @Test fun `an unedited review does not intercept Back`() {
        launch()
        rule.runOnIdle { assertFalse(backDispatcher.hasEnabledCallbacks()) }
        assertFalse(closed)
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

    // Story 10.16: retake faces from the review.

    private var retaken: Set<Face>? = null
    private var retakeStarts = 0

    private fun launchRetake(
        large: Boolean = false,
        edited: Boolean = false,
    ) {
        retake = {
            retakeStarts++
            retaken = it
        }
        enterColors = {}
        launch(large = large, edited = edited)
        rule.onNodeWithTag("review:retakeFaces").performScrollTo().performClick()
    }

    private fun faceDescription(index: Int) =
        rule
            .onNodeWithTag("reviewNet:face:$index")
            .fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription]
            .single()

    @Test fun `without onRetake there is no Retake faces button`() {
        launch()
        rule.onNodeWithTag("review:close").assertExists()
        rule.onNodeWithTag("review:retakeFaces").assertDoesNotExist()
    }

    @Test fun `Retake faces sits directly after Close`() {
        retake = {}
        enterColors = {}
        launch()
        val close = rule.onNodeWithTag("review:close").performScrollTo().getUnclippedBoundsInRoot()
        val retakeButton = rule.onNodeWithTag("review:retakeFaces").assertTextEquals("Retake faces").getUnclippedBoundsInRoot()
        val enter = rule.onNodeWithTag("review:enterColors").getUnclippedBoundsInRoot()
        assertTrue(close.bottom <= retakeButton.top)
        assertTrue(retakeButton.bottom <= enter.top)
    }

    @Test fun `retake mode shows only the net, the hint, Retake N faces and Cancel`() {
        // A review with a problem line, a suspect suggestion and a calibration offer, and a color just set.
        val labs =
            mapOf(
                Color.GREEN to Lab(55.0, -60.0, 40.0),
                Color.RED to Lab(45.0, 65.0, 45.0),
                Color.BLUE to Lab(40.0, 10.0, -50.0),
                Color.ORANGE to Lab(65.0, 45.0, 65.0),
                Color.WHITE to Lab(90.0, 0.0, 0.0),
                Color.YELLOW to Lab(85.0, -5.0, 80.0),
            )
        val colors =
            CubeState.SOLVED
                .toFacelets()
                .colors
                .toMutableList()
                .also { it[38] = Color.YELLOW }
        review =
            ScanReview.from(
                ScanResult.Full(
                    Facelets(colors),
                    List(54) { StickerConfidence.CONFIDENT },
                    colors.mapIndexed { i, color -> if (i == 38) Lab(77.0, 15.0, 74.0) else labs.getValue(color) },
                ),
                CalibrationCenters(labs),
            )
        calibrate = {}
        retake = { retaken = it }
        enterColors = {}
        launch()
        rule.onNodeWithTag("sticker:0").performClick()
        rule.onNodeWithTag("swatch:WHITE").performScrollTo().performClick()
        val hidden =
            listOf(
                "review:accept",
                "review:close",
                "review:enterColors",
                "review:retakeFaces",
                "review:calibrate",
                "review:selected",
                "review:suggestion",
                "review:updated",
                "review:problem:0",
                "swatch:WHITE",
            )
        hidden.forEach { rule.onNodeWithTag(it).assertExists() }
        rule.onNodeWithTag("review:retakeFaces").performScrollTo().performClick()
        rule.onNodeWithTag("review:instruction").assertTextEquals("Tap each face to scan again.")
        rule
            .onNodeWithText(
                strings.resolve(
                    dev.cubentia.domain.text
                        .Text("msg.scan.review-title"),
                ),
            ).assertExists()
        (hidden + listOf("review:coach", "editor:face:0")).forEach { rule.onNodeWithTag(it).assertDoesNotExist() }
        rule.onNodeWithTag("review:retake:start").assertIsNotEnabled()
        rule.onNodeWithTag("review:retake:cancel").assertTextEquals("Cancel")
    }

    private fun assertFaceSelected(
        index: Int,
        selected: Boolean,
    ) = assertEquals(selected, faceDescription(index).endsWith(", selected for retake"))

    @Test fun `tapping faces toggles them with an outline, a check badge and their announcement`() {
        launchRetake()
        val before = review
        // Stickers are silent and inert: only the face targets are announced.
        listOf(7, 20).forEach { index ->
            val config = rule.onNodeWithTag("sticker:$index", useUnmergedTree = true).fetchSemanticsNode().config
            assertNull(config.getOrNull(SemanticsProperties.ContentDescription))
            assertNull(config.getOrNull(SemanticsProperties.Selected))
            assertNull(config.getOrNull(StickerPulsingKey))
            assertNull(config.getOrNull(StickerBlankEdgeKey))
            assertNull(config.getOrNull(SemanticsActions.OnClick))
        }
        assertEquals("Green face", faceDescription(2))
        assertNull(
            rule
                .onNodeWithTag("reviewNet:face:2")
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Selected),
        )
        rule.onNodeWithTag("reviewNet:face:2:check", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("reviewNet:face:2").performClick()
        assertEquals("Green face, selected for retake", faceDescription(2))
        assertNull(
            rule
                .onNodeWithTag("reviewNet:face:2")
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Selected),
        )
        rule.onNodeWithTag("reviewNet:face:2:check", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("review:retake:start").assertIsEnabled().assertTextEquals("Retake 1 face")
        rule.onNodeWithTag("reviewNet:face:1").performClick()
        rule.onNodeWithTag("review:retake:start").assertTextEquals("Retake 2 faces")
        rule.onNodeWithTag("reviewNet:face:1").performClick()
        assertEquals("Red face", faceDescription(1))
        // A tap on a sticker toggles its face and edits nothing.
        rule.onNodeWithTag("sticker:7", useUnmergedTree = true).performClick()
        assertFaceSelected(0, true)
        assertEquals(before, review)
        rule.onNodeWithTag("review:retake:start").performClick()
        assertEquals(setOf(Face.U, Face.F), retaken)
        assertEquals(1, retakeStarts)
        // Starting a retake stays in retake mode, inert, until the screen leaves the review: no review control flashes.
        rule.onNodeWithTag("review:accept").assertDoesNotExist()
        rule.onNodeWithTag("review:retakeFaces").assertDoesNotExist()
        rule.onNodeWithTag("review:retake:start").assertIsNotEnabled().assertTextEquals("Retake 2 faces")
        rule.onNodeWithTag("review:retake:cancel").assertIsNotEnabled()
        rule.onNodeWithTag("review:retake:start").performClick()
        rule.onNodeWithTag("reviewNet:face:3").performClick()
        assertEquals(1, retakeStarts)
        assertFaceSelected(3, false)
    }

    @Test fun `Cancel leaves retake mode with the selection cleared and the review unchanged`() {
        launchRetake(edited = true)
        val before = review
        rule.onNodeWithTag("reviewNet:face:3").performClick()
        rule.onNodeWithTag("review:retake:cancel").performClick()
        rule.onNodeWithTag("review:discard").assertDoesNotExist()
        rule.onNodeWithTag("review:accept").assertExists()
        assertEquals(before, review)
        assertNull(retaken)
        rule.onNodeWithTag("review:retakeFaces").performScrollTo().performClick()
        rule.onNodeWithTag("review:retake:start").assertIsNotEnabled()
        assertFaceSelected(3, false)
    }

    @Test fun `system Back in retake mode is Cancel, with no discard dialog`() {
        launchRetake(edited = true)
        rule.onNodeWithTag("reviewNet:face:4").performClick()
        rule.runOnIdle { backDispatcher.onBackPressed() }
        rule.onNodeWithTag("review:discard").assertDoesNotExist()
        rule.onNodeWithTag("review:accept").assertExists()
        assertFalse(closed)
        assertNull(retaken)
        // Back in review, Back asks before discarding again.
        rule.runOnIdle { backDispatcher.onBackPressed() }
        rule.onNodeWithTag("review:discard").assertExists()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `retake mode shows the full net on a compact phone`() {
        launchRetake()
        (0 until 6).forEach { rule.onNodeWithTag("reviewNet:face:$it").assertIsDisplayed() }
        rule.onAllNodesWithTag("sticker:7").assertCountEquals(1)
        rule.onAllNodesWithTag("sticker:20").assertCountEquals(1)
        rule.onNodeWithTag("reviewNet:face:5").performClick()
        rule.onNodeWithTag("review:retake:start").performScrollTo().performClick()
        assertEquals(setOf(Face.B), retaken)
    }

    @Test
    @Config(qualifiers = "w600dp-h960dp")
    fun `retake mode at 200 percent has no broken words overflow or undersized controls`() {
        launchRetake(large = true)
        rule.onNodeWithTag("reviewNet:face:0").performClick()
        rule.onNodeWithTag("reviewNet:face:1").performClick()
        rule.onNodeWithTag("review:retake:start").performScrollTo().assertTextEquals("Retake 2 faces")
        rule.assertNoMidWordBreaks()
        rule.assertNoTextOverflow("retake mode at 200 percent")
        rule.assertClickablesAtLeast48dp("retake mode")
    }

    // ai-b4: copy, focus and layout polish (#199, #174).

    @Test fun `a global first problem with nothing outlined reads as the problem alone`() {
        review = ScanReview(miscounted, 39)
        launch()
        rule
            .onNodeWithTag("review:instruction")
            .assertTextEquals("Each color should appear on exactly nine stickers. Check the colors you entered.")
        rule
            .onNodeWithTag("review:problem:0")
            .performScrollTo()
            .assertTextEquals("Each color should appear on exactly nine stickers. Check the colors you entered.")
    }

    @Test fun `wrong counts with suspects read a scan reason and name the likely misreads best first`() {
        review = suspectReview()
        launch()
        val reason = "Each color should appear on exactly nine stickers. The camera may have misread a sticker."
        rule.onNodeWithTag("review:instruction").assertTextEquals("$reason Check the outlined piece.")
        val line = rule.onNodeWithTag("review:problem:0").performScrollTo()
        line.assertTextEquals(reason)
        assertEquals(
            "Problem 1: $reason Likely misreads, most likely first: " +
                "Left face, position 4; Back face, position 6; Right face, position 1.",
            line.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single(),
        )
    }

    @Test fun `painting the last blank that leaves the counts off focuses the counts line led by the top suspect`() {
        // As in the top-suspect fixture, but R's sticker 9 is unread: painting it white leaves a corner with two whites
        // (problem 1) and whites over by one, and the top suspect 8 leads the counts line.
        val colors =
            CubeState.SOLVED
                .toFacelets()
                .colors
                .toMutableList()
                .also { it[9] = Color.WHITE }
        review =
            ScanReview.from(
                ScanResult.Full(
                    Facelets(colors),
                    List(54) { if (it == 9) StickerConfidence.UNCERTAIN else StickerConfidence.CONFIDENT },
                    colors.mapIndexed { index, color -> if (index == 8) Lab(55.0, 50.0, 40.0) else labs.getValue(color) },
                ),
                CalibrationCenters(labs),
            )
        assertEquals(listOf(9), review.blanks)
        launch()
        rule.onNodeWithTag("swatch:WHITE").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(8, review.selected)
        assertEquals(listOf(8, 9), review.problems[0].positions)
        val counts = review.problems.indexOfFirst { it.reason.key == "msg.validation.wrong-color-counts" }
        assertTrue(counts > 0)
        rule.onNodeWithTag("review:problem:$counts").performScrollTo().assertIsSelected()
        rule.onNodeWithTag("review:problem:0").performScrollTo().assertIsNotSelected()
    }

    @Test fun `a problem line's tap target is its first conflicting sticker, else its piece's first`() {
        val reason =
            dev.cubentia.domain.text
                .Text("msg.validation.parity")
        assertEquals(
            3,
            problemTapTarget(
                dev.cubentia.domain.cube
                    .ReviewProblem(reason, emptyList(), piece = listOf(3, 5)),
            ),
        )
        assertEquals(
            5,
            problemTapTarget(
                dev.cubentia.domain.cube
                    .ReviewProblem(reason, listOf(5), piece = listOf(3, 5)),
            ),
        )
    }

    @Test fun `on the smallest full-net cell a problem tag and the glyph never overlap`() {
        val cell = 48.dp
        rule.setContent {
            CubentiaTheme {
                dev.cubentia.app.ui.editor.StickerReviewNet(
                    style =
                        dev.cubentia.app.render
                            .RenderStyle(colorBlindGlyphs = true),
                    selected = null,
                    invalid = setOf(8),
                    onStickerClick = {},
                    unresolved = CubeState.SOLVED.toFacelets().colors,
                    strings = strings,
                    problemMarks =
                        mapOf(
                            8 to
                                dev.cubentia.app.ui.editor
                                    .StickerProblemMark(listOf(1), listOf(1), 1),
                        ),
                    modifier =
                        androidx.compose.ui.Modifier.size(
                            cell * Net2DRenderer.NET_COLUMNS,
                            cell * Net2DRenderer.NET_ROWS,
                        ),
                )
            }
        }
        val tag = rule.onNodeWithTag("sticker:8:tag", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val glyph = rule.onNodeWithTag("sticker:8:glyph", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("glyph $glyph overlaps tag $tag", glyph.left >= tag.right || glyph.top >= tag.bottom)
        assertTrue("glyph size ${glyphSize("sticker:8:glyph")}", glyphSize("sticker:8:glyph") >= 16f)
        // Without a mark the glyph stays centered at its old size (55% of the cell).
        val sticker = rule.onNodeWithTag("sticker:0").getUnclippedBoundsInRoot()
        val plain = rule.onNodeWithTag("sticker:0:glyph", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(((sticker.left + sticker.right) / 2).value, ((plain.left + plain.right) / 2).value, 0.5f)
        assertEquals(((sticker.top + sticker.bottom) / 2).value, ((plain.top + plain.bottom) / 2).value, 0.5f)
        assertEquals(cell.value * 0.55f, glyphSize("sticker:0:glyph"), 0.5f)
    }

    /** The glyph's font size in dp (its line height equals it). */
    private fun glyphSize(tag: String): Float {
        val node = rule.onNodeWithTag(tag, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val density = node.fetchSemanticsNode().layoutInfo.density
        return with(density) {
            layouts
                .single()
                .layoutInput.style.fontSize
                .toDp()
                .value
        }
    }
}

/** The selected ring: 5dp in all (3dp accent dashes edged by 1dp cubie-body), 8dp dashes. */
private val DASHED_ACCENT_RING = SelectedRing(LightTokens.accent, 5.dp, 8.dp)
