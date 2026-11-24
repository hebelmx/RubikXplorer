package dev.cubentia.app.ui.editor

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.preferences.LocalHaptics
import dev.cubentia.app.render.RenderStyle
import dev.cubentia.app.render.StickerColorKey
import dev.cubentia.app.render.StickerGlyphInkKey
import dev.cubentia.app.ui.editor.EditorFixtures.centers
import dev.cubentia.app.ui.editor.EditorFixtures.scrambled
import dev.cubentia.app.ui.editor.EditorFixtures.solved
import dev.cubentia.app.ui.editor.EditorFixtures.swappedEdges
import dev.cubentia.app.ui.editor.EditorFixtures.twistedAndFlipped
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.app.ui.theme.DarkTokens
import dev.cubentia.domain.appearance.StickerAppearance
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.cubentia.domain.cube.Color as CubeColor

/** The editor's I/O matrix and ACs at the composable surface, on the JVM through Robolectric. */
@RunWith(RobolectricTestRunner::class)
// Wide enough for the full net: every net sticker gets a 48dp cell (one-face mode is ManualCubeEditorCompactTest).
@Config(application = Application::class, qualifiers = "w640dp-h891dp")
class ManualCubeEditorTest {
    @get:Rule
    val rule = createComposeRule()

    private companion object {
        const val MAX_LINES = 6
    }

    private val strings =
        ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)

    private val accepted = mutableListOf<CubeState>()
    private var glyphs by mutableStateOf(false)

    private val changes = mutableListOf<Pair<Facelets, Set<Int>>>()

    private var ticks = 0
    private val haptics =
        object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                ticks++
            }
        }

    private fun launch(
        initial: Facelets = CubeState.SOLVED.toFacelets(),
        fresh: Boolean = false,
        initialSet: Set<Int> = emptySet(),
        restoration: StateRestorationTester? = null,
        hapticsOn: Boolean = true,
        seeded: Boolean = false,
    ) {
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            CompositionLocalProvider(LocalHaptics provides hapticsOn, LocalHapticFeedback provides haptics) {
                CubentiaTheme {
                    ManualCubeEditor(
                        strings = strings,
                        onAccept = { accepted += it },
                        initial = initial,
                        style = RenderStyle(colorBlindGlyphs = glyphs),
                        fresh = fresh,
                        initialSet = initialSet,
                        onChange = { facelets, set -> changes += facelets to set },
                        seeded = seeded,
                    )
                }
            }
        }
        if (restoration == null) rule.setContent(content) else restoration.setContent(content)
    }

    private fun sticker(index: Int) = rule.onNodeWithTag("sticker:$index")

    private fun swatch(color: CubeColor) = rule.onNodeWithTag("swatch:${color.name}").performScrollTo()

    private fun accept() = rule.onNodeWithTag("editor:accept").performScrollTo()

    private fun fill(color: CubeColor) = Color(StickerAppearance.of(color).fill)

    private fun drawnColor(index: Int): Color = sticker(index).fetchSemanticsNode().config[StickerColorKey]

    private fun isInvalid(index: Int): Boolean = sticker(index).fetchSemanticsNode().config[StickerInvalidKey]

    private fun outlined(): Set<Int> = (0 until Facelets.SIZE).filterTo(mutableSetOf()) { isInvalid(it) }

    private fun problemLines(): List<String> {
        val nodes = (0 until MAX_LINES).map { rule.onAllNodesWithTag("editor:problem:$it").fetchSemanticsNodes() }.flatten()
        return nodes.map { node -> node.config[SemanticsProperties.Text].joinToString { it.text } }
    }

    private fun paint(
        index: Int,
        color: CubeColor,
    ) {
        sticker(index).performClick()
        swatch(color).performClick()
        rule.waitForIdle()
    }

    private fun isSelected(index: Int): Boolean = sticker(index).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true

    private fun selectedLine(): String {
        val node = rule.onNodeWithTag("editor:selected").fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, node.config[SemanticsProperties.LiveRegion])
        return node.config[SemanticsProperties.Text].joinToString { it.text }
    }

    @Test
    fun `tapping a sticker then a color paints it and advances the selection`() {
        launch()
        assertTrue(rule.onAllNodesWithTag("editor:selected").fetchSemanticsNodes().isEmpty())
        paint(0, CubeColor.RED)
        assertEquals(fill(CubeColor.RED), drawnColor(0))
        assertFalse(isSelected(0))
        assertTrue(isSelected(1))
        assertEquals(fill(solved[1]), drawnColor(1))
        assertEquals("Selected: White sticker, up face, position 2", selectedLine())
    }

    @Test
    fun `the update cue stays on the painted sticker while the selection advances`() {
        launch()
        sticker(0).performClick()
        rule.mainClock.autoAdvance = false
        swatch(CubeColor.RED).performClick()
        rule.mainClock.advanceTimeBy(50)
        rule.onNodeWithTag("sticker:0:cue", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("sticker:1:cue", useUnmergedTree = true).assertDoesNotExist()
        assertTrue(isSelected(1))
    }

    @Test
    fun `painting skips the center and the polite line names the next sticker`() {
        launch()
        paint(3, CubeColor.RED)
        assertEquals(fill(CubeColor.RED), drawnColor(3))
        assertTrue(isSelected(5))
        assertEquals("Selected: White sticker, up face, position 6", selectedLine())
    }

    @Test
    fun `tapping an earlier sticker fixes it and advances from it`() {
        launch()
        paint(8, CubeColor.RED)
        swatch(CubeColor.RED).performClick()
        rule.waitForIdle()
        assertTrue(isSelected(10))
        paint(2, CubeColor.GREEN)
        assertEquals(fill(CubeColor.GREEN), drawnColor(2))
        assertTrue(isSelected(3))
        assertFalse(isSelected(10))
    }

    @Test
    fun `the last sticker stays selected after it is painted`() {
        launch()
        paint(53, CubeColor.GREEN)
        assertEquals(fill(CubeColor.GREEN), drawnColor(53))
        assertTrue(isSelected(53))
        assertEquals(solved.take(53).map(::fill), (0 until 53).map(::drawnColor))
        assertEquals("Selected: Green sticker, back face, position 9", selectedLine())
    }

    @Test
    fun `centers are not selectable and stay home colors even from other initial colors`() {
        launch(Facelets(List(Facelets.SIZE) { if (it % 9 == 4) CubeColor.BLUE else solved[it] }))
        centers.forEach { index ->
            assertEquals(fill(solved[index]), drawnColor(index))
            assertNull(sticker(index).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick))
            sticker(index).performClick()
            swatch(CubeColor.RED).performClick()
            rule.waitForIdle()
            assertEquals(fill(solved[index]), drawnColor(index))
            assertEquals(false, sticker(index).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        }
        accept().assertIsEnabled()
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `at 320dp every swatch keeps a full touch target inside the window and paints`() {
        launch()
        CubeColor.entries.forEach { color ->
            val node = swatch(color).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("$color left edge", bounds.left >= 0.dp)
            assertTrue("$color right edge", bounds.right <= 320.dp)
            paint(0, color)
            assertEquals(fill(color), drawnColor(0))
        }
    }

    @Test
    fun `swatches and stickers have distinct descriptions`() {
        launch()

        fun description(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        val names =
            mapOf(
                CubeColor.WHITE to "White",
                CubeColor.YELLOW to "Yellow",
                CubeColor.RED to "Red",
                CubeColor.ORANGE to "Orange",
                CubeColor.GREEN to "Green",
                CubeColor.BLUE to "Blue",
            )
        names.forEach { (color, name) -> assertEquals(listOf(name), description("swatch:${color.name}")) }
        assertEquals(listOf("White sticker, up face, position 1"), description("sticker:0"))
        assertEquals(listOf("Green sticker, front face, position 3"), description("sticker:20"))
    }

    @Test
    fun `a color tapped before any sticker changes nothing`() {
        launch()
        swatch(CubeColor.RED).performClick()
        rule.waitForIdle()
        assertEquals(solved.map(::fill), (0 until Facelets.SIZE).map(::drawnColor))
        assertEquals(emptyList<String>(), problemLines())
    }

    @Test
    fun `glyphs show on every sticker and swatch only while color-blind display is on`() {
        launch()

        fun inkOf(tag: String) =
            rule
                .onNodeWithTag(tag)
                .fetchSemanticsNode()
                .config
                .getOrNull(StickerGlyphInkKey)
        val swatchTags = CubeColor.entries.map { "swatch:${it.name}" }
        (0 until Facelets.SIZE).forEach { assertNull(inkOf("sticker:$it")) }
        swatchTags.forEach { assertNull(inkOf(it)) }
        rule.onAllNodesWithText("W", useUnmergedTree = true).assertCountEquals(0)

        glyphs = true
        rule.waitForIdle()
        (0 until Facelets.SIZE).forEach {
            assertEquals(Color(StickerAppearance.of(solved[it]).glyphInk), inkOf("sticker:$it"))
        }
        CubeColor.entries.forEach { assertEquals(Color(StickerAppearance.of(it).glyphInk), inkOf("swatch:${it.name}")) }
        CubeColor.entries.forEach { color ->
            val glyph = StickerAppearance.of(color).glyph.toString()
            // Nine stickers and one swatch per color.
            rule.onAllNodesWithText(glyph, useUnmergedTree = true).assertCountEquals(10)
        }

        glyphs = false
        rule.waitForIdle()
        (0 until Facelets.SIZE).forEach { assertNull(inkOf("sticker:$it")) }
        rule.onAllNodesWithText("W", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `wrong counts name each color that is off and the piece they broke, and disable Accept`() {
        launch()
        paint(18, CubeColor.RED)
        assertEquals(
            listOf("Red: 10 (one too many) · Green: 8 (one missing)", "The corner between Up, Front and Left shows opposite colors."),
            problemLines(),
        )
        // The counts line marks no sticker; the named corner is outlined and numbered.
        assertEquals(setOf(6, 18, 38), outlined())
        rule.onNodeWithTag("editor:problem:0:tag", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("editor:problem:1:tag", useUnmergedTree = true).assertExists()
        accept().assertIsNotEnabled()
    }

    @Test
    fun `an impossible corner outlines exactly that piece and shows its line`() {
        launch()
        paint(18, CubeColor.WHITE)
        paint(8, CubeColor.GREEN)
        assertEquals(setOf(6, 8, 9, 18, 20, 38), outlined())
        // Every outlined sticker carries the numbered marker of its piece's line, like Scan review.
        val numbers = mapOf(8 to 1, 9 to 1, 20 to 1, 6 to 2, 18 to 2, 38 to 2)
        (0 until Facelets.SIZE).forEach { index ->
            val marked = sticker(index).fetchSemanticsNode().config.getOrNull(StickerProblemsKey)
            assertEquals("$index", numbers[index]?.let(::listOf), marked)
        }
        rule.onNodeWithTag("sticker:8:tag", useUnmergedTree = true).assertExists()
        assertEquals(
            listOf(
                "The corner between Up, Front and Right shows two greens.",
                "The corner between Up, Front and Left shows two whites.",
            ),
            problemLines(),
        )
        rule.onNodeWithTag("editor:problem:0:tag", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("editor:problem:1:tag", useUnmergedTree = true).assertExists()
        accept().assertIsNotEnabled()
    }

    @Test
    fun `a twisted corner and a flipped edge each get their own line and no outlines`() {
        launch(twistedAndFlipped)
        assertEquals(
            listOf("One corner is twisted — check the corners you changed last.", "One edge is flipped."),
            problemLines(),
        )
        rule.onNodeWithTag("editor:instruction").assertDoesNotExist()
        assertEquals(emptySet<Int>(), outlined())
        accept().assertIsNotEnabled()
    }

    @Test
    fun `swapped pieces show the parity line and disable Accept`() {
        launch(swappedEdges)
        assertEquals(listOf("Two pieces are swapped — this can’t happen on a real cube."), problemLines())
        accept().assertIsNotEnabled()
    }

    @Test
    fun `a valid entry enables Accept and each tap returns the exact validated state once`() {
        launch(scrambled)
        assertEquals(emptyList<String>(), problemLines())
        assertEquals(emptySet<Int>(), outlined())
        accept().assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(1, accepted.size)
        assertEquals(scrambled, accepted.single().toFacelets())
        accept().performClick()
        rule.waitForIdle()
        assertEquals(2, accepted.size)
        assertEquals(scrambled, accepted.last().toFacelets())
    }

    @Test
    fun `fixing an invalid entry re-enables Accept with the entered state`() {
        launch()
        paint(0, CubeColor.RED)
        accept().assertIsNotEnabled()
        paint(0, CubeColor.WHITE)
        assertTrue(problemLines().isEmpty())
        accept().assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(CubeState.SOLVED.toFacelets(), accepted.single().toFacelets())
        assertFalse(isInvalid(0))
    }

    private fun text(tag: String): String =
        rule
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString { it.text }

    private val editable = (0 until Facelets.SIZE).filter(ManualCubeEditorState::isEditable).toSet()

    private val r2 = CubeState.SOLVED.apply((Notation.parse("R2") as NotationResult.Parsed).moves).toFacelets()

    @Test
    fun `a fresh entry asks for the faces left to enter, shows no problem and keeps Accept disabled`() {
        launch(fresh = true)
        assertTrue(isSelected(0))
        assertEquals("6 faces left to enter: Up, Front, Right, Back, Left, Down.", text("editor:instruction"))
        paint(0, CubeColor.RED)
        paint(18, CubeColor.GREEN)
        paint(36, CubeColor.ORANGE)
        // A face counts only once all its stickers are set (GH #352).
        assertEquals("6 faces left to enter: Up, Front, Right, Back, Left, Down.", text("editor:instruction"))
        assertEquals(emptyList<String>(), problemLines())
        assertEquals(emptySet<Int>(), outlined())
        accept().assertIsNotEnabled()
        // onChange reports the stickers and the set stickers.
        assertEquals(setOf(0, 18, 36), changes.last().second)
        listOf(1, 2, 3, 5, 6, 7, 8).forEach { paint(it, CubeColor.WHITE) }
        assertEquals("5 faces left to enter: Front, Right, Back, Left, Down.", text("editor:instruction"))
        assertEquals(emptyList<String>(), problemLines())
        accept().assertIsNotEnabled()
    }

    @Test
    fun `a partly entered last face names it, shows no problem, and its last sticker turns it`() {
        // GH #352/#353: five faces complete, Up entered at 270 degrees with 7 of its 8 stickers set.
        val entered = EditorFixtures.turned(scrambled, mapOf(0 to 3))
        launch(initial = entered, fresh = true, initialSet = editable - 3)
        assertEquals("1 face left to enter: Up.", text("editor:instruction"))
        assertEquals(emptyList<String>(), problemLines())
        assertEquals(emptySet<Int>(), outlined())
        accept().assertIsNotEnabled()
        paint(3, entered.colors[3])
        assertEquals(scrambled, changes.last().first)
        accept().assertIsEnabled()
    }

    @Test
    fun `a face entered at 180 degrees is turned, noted, and Accept returns the original cube`() {
        val entered = EditorFixtures.turned(scrambled, mapOf(0 to 2))
        launch(entered, fresh = true, initialSet = editable - 53)
        rule.onNodeWithTag("editor:turned").assertDoesNotExist()
        paint(53, CubeColor.entries.first { it == scrambled.colors[53] })
        (0 until 9).forEach { assertEquals("$it", fill(scrambled.colors[it]), drawnColor(it)) }
        val note = rule.onNodeWithTag("editor:turned").fetchSemanticsNode()
        assertEquals("We turned the Up face to match the others.", note.config[SemanticsProperties.Text].joinToString { it.text })
        assertEquals(LiveRegionMode.Polite, note.config[SemanticsProperties.LiveRegion])
        rule.onNodeWithTag("editor:instruction").assertDoesNotExist()
        assertEquals(scrambled, changes.last().first)
        accept().assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(scrambled, accepted.single().toFacelets())
    }

    @Test
    fun `a valid entry is kept as entered even when another turning also fits`() {
        launch(r2, fresh = true, initialSet = editable - 53)
        paint(53, r2.colors[53])
        rule.onNodeWithTag("editor:turned").assertDoesNotExist()
        accept().assertIsEnabled()
    }

    @Test
    fun `entered faces that fit several turnings name the face and its hold, and disable Accept`() {
        val entered = EditorFixtures.turned(r2, mapOf(0 to 1))
        launch(entered, fresh = true, initialSet = editable - 53)
        paint(53, entered.colors[53])
        val line = rule.onNodeWithTag("editor:instruction").fetchSemanticsNode()
        assertEquals(
            "Check the Up face: hold green toward you and look at the top.",
            line.config[SemanticsProperties.Text].joinToString { it.text },
        )
        assertEquals(LiveRegionMode.Polite, line.config[SemanticsProperties.LiveRegion])
        accept().assertIsNotEnabled()
    }

    @Test
    fun `a side face to check names its center and white on top`() {
        // Two Front edge stickers swapped: no turning fits, and the first face to check is Front.
        val entered = Facelets(EditorFixtures.swap(scrambled.colors, 21, 23))
        launch(entered)
        paint(53, entered.colors[53])
        assertEquals("Check the Front face: hold white on top and turn green toward you.", text("editor:instruction"))
        accept().assertIsNotEnabled()
    }

    @Test
    fun `a caller's cube shows its problems at once and selects nothing`() {
        launch(twistedAndFlipped, fresh = false)
        assertTrue(rule.onAllNodesWithTag("editor:selected").fetchSemanticsNodes().isEmpty())
        // No faces-left gating: the twist and flip lines show at once.
        assertEquals(2, problemLines().size)
    }

    @Test
    fun `a twisted corner with right counts and every piece legal shows its line and names no face`() {
        val twisted = Facelets(EditorFixtures.cycle(solved, 8, 9, 20))
        launch(twisted, fresh = true, initialSet = editable - 53)
        paint(53, twisted.colors[53])
        assertEquals(listOf("One corner is twisted — check the corners you changed last."), problemLines())
        rule.onNodeWithTag("editor:instruction").assertDoesNotExist()
        rule.onNodeWithTag("editor:turned").assertDoesNotExist()
        accept().assertIsNotEnabled()
    }

    @Test
    fun `the update cue plays on the painted sticker where its turned face moved it`() {
        // Up entered at 180 degrees with sticker 6 painted last: the fix turns Up, and 6 lands on 2.
        val entered = EditorFixtures.turned(scrambled, mapOf(0 to 2))
        launch(entered, fresh = true, initialSet = editable - 6)
        sticker(6).performClick()
        rule.mainClock.autoAdvance = false
        swatch(entered.colors[6]).performClick()
        rule.mainClock.advanceTimeBy(50)
        rule.onNodeWithTag("sticker:2:cue", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("sticker:6:cue", useUnmergedTree = true).assertDoesNotExist()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(scrambled, changes.last().first)
    }

    @Test
    fun `a turned note names two faces in URFDLB order`() {
        val entered = EditorFixtures.turned(scrambled, mapOf(1 to 2, 5 to 1))
        launch(entered, fresh = true, initialSet = editable - 53)
        paint(53, entered.colors[53])
        assertEquals("We turned the Right and Back faces to match the others.", text("editor:turned"))
        assertEquals(scrambled, changes.last().first)
        accept().assertIsEnabled()
    }

    @Test
    fun `an edge line names its two faces`() {
        launch()
        paint(7, CubeColor.GREEN)
        assertEquals(
            listOf("White: 8 (one missing) · Green: 10 (one too many)", "The edge between Up and Front shows two greens."),
            problemLines(),
        )
        rule.onNodeWithTag("editor:problem:1:tag", useUnmergedTree = true).assertExists()
        accept().assertIsNotEnabled()
    }

    @Test
    fun `a mirrored corner shows the impossible-piece line with its numbered markers`() {
        // URF corner with its Up and Right stickers swapped: right counts, no repeat or opposite colors.
        launch(Facelets(EditorFixtures.swap(solved, 8, 9)))
        assertEquals("The corner between Up, Front and Right has colors that don't fit the rest of the cube.", problemLines().first())
        rule.onNodeWithTag("editor:problem:0:tag", useUnmergedTree = true).assertExists()
        listOf(8, 9, 20).forEach { assertTrue("$it", isInvalid(it)) }
        accept().assertIsNotEnabled()
    }

    private fun stateDescription(tag: String): String? =
        rule
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.StateDescription)

    private fun exists(tag: String): Boolean = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the full net shows all six face tabs above the net and no hold bands`() {
        launch(fresh = true)
        val netTop = sticker(0).getUnclippedBoundsInRoot().top
        repeat(6) { face ->
            val tab = rule.onNodeWithTag("editor:face:$face")
            assertTrue("$face", tab.getUnclippedBoundsInRoot().bottom <= netTop)
        }
        listOf("top", "right", "bottom", "left").forEach { assertFalse(it, exists("editor:hold:$it")) }
        // The strip's current tab is the selected sticker's face.
        assertEquals(
            true,
            rule
                .onNodeWithTag("editor:face:0")
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Selected),
        )
    }

    @Test
    fun `the full-net strip marks a completed face with its tick`() {
        launch(fresh = true)
        assertFalse(exists("editor:face:0:done"))
        repeat(8) { swatch(CubeColor.WHITE).performClick() }
        rule.waitForIdle()
        assertTrue(exists("editor:face:0:done"))
        assertEquals("All stickers set", stateDescription("editor:face:0"))
    }

    @Test
    fun `the full-net strip dots the face the instruction line names`() {
        val entered = Facelets(EditorFixtures.swap(scrambled.colors, 21, 23))
        launch(entered)
        paint(53, entered.colors[53])
        assertEquals("Check the Front face: hold white on top and turn green toward you.", text("editor:instruction"))
        assertTrue(exists("editor:face:2:dot"))
        assertEquals("Has pieces to check", stateDescription("editor:face:2"))
    }

    @Test
    fun `the full-net strip says a uniquely turned face was turned to match`() {
        val entered = EditorFixtures.turned(scrambled, mapOf(0 to 2))
        launch(entered, fresh = true, initialSet = editable - 53)
        paint(53, scrambled.colors[53])
        assertTrue(stateDescription("editor:face:0")!!.contains("Turned to match"))
    }

    @Test
    fun `tapping a full-net tab selects that face's first sticker`() {
        launch()
        rule.onNodeWithTag("editor:face:5").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(isSelected(45))
        assertEquals("Selected: Blue sticker, back face, position 1", selectedLine())
    }
    // #249: paint mode in the editor.

    /** The paint announcement [action] made, read while it is still in the live region (null if it made none). */
    private var lastAnnouncement: String? = null

    private fun announcing(action: () -> Unit) {
        rule.mainClock.autoAdvance = false
        action()
        rule.mainClock.advanceTimeBy(100)
        val node = rule.onNodeWithTag("paint:announce", useUnmergedTree = true).fetchSemanticsNode()
        lastAnnouncement = node.config.getOrNull(SemanticsProperties.ContentDescription)?.single()
        if (lastAnnouncement != null) assertEquals(LiveRegionMode.Polite, node.config[SemanticsProperties.LiveRegion])
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
    }

    private fun longPress(
        color: CubeColor,
        resetTicks: Boolean = true,
    ) {
        announcing { swatch(color).performTouchInput { longClick() } }
        // Only the update cue's ticks are counted, not the long-press feedback.
        if (resetTicks) ticks = 0
    }

    private fun tapToStop(color: CubeColor) = announcing { swatch(color).performClick() }

    private fun customAction(
        color: CubeColor,
        label: String,
    ) {
        val actions = swatch(color).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        val action = actions.single { it.label == label }
        announcing {
            rule.runOnIdle {
                action.action()
                androidx.compose.runtime.snapshots.Snapshot
                    .sendApplyNotifications()
            }
        }
    }

    private fun announcement(): String? = lastAnnouncement

    private fun isLocked(color: CubeColor): Boolean = swatch(color).fetchSemanticsNode().config[SemanticsProperties.Selected]

    private fun assertPainting(color: CubeColor) {
        val name = color.name.lowercase()
        assertTrue(isLocked(color))
        rule.onNodeWithTag("swatch:${color.name}:check", useUnmergedTree = true).assertExists()
        assertEquals("Painting $name. Tap stickers to set them. Tap $name again to stop.", text("editor:instruction"))
        assertEquals("Painting $name", announcement())
        assertTrue(rule.onAllNodesWithTag("editor:selected").fetchSemanticsNodes().isEmpty())
    }

    private fun assertNotPainting() {
        CubeColor.entries.forEach { color ->
            assertFalse(isLocked(color))
            rule.onNodeWithTag("swatch:${color.name}:check", useUnmergedTree = true).assertDoesNotExist()
        }
        assertEquals("Painting off", announcement())
    }

    @Test
    fun `a long-press locks a color and pauses the selection`() {
        launch(fresh = true)
        assertTrue(isSelected(0))
        longPress(CubeColor.RED)
        assertPainting(CubeColor.RED)
        assertFalse(isLocked(CubeColor.WHITE))
        assertFalse(isSelected(0))
        assertFalse(sticker(0).fetchSemanticsNode().config.getOrNull(StickerPulsingKey) == true)
    }

    @Test
    fun `nine stickers take one long-press and nine taps, with a cue and a tick each and no face-done line`() {
        launch(fresh = true)
        longPress(CubeColor.RED)
        val stickers = listOf(0, 1, 2, 3, 5, 6, 7, 8, 9)
        stickers.forEach { sticker(it).performClick() }
        // A center tap is ignored.
        sticker(4).performClick()
        rule.waitForIdle()
        stickers.forEach { assertEquals("$it", fill(CubeColor.RED), drawnColor(it)) }
        assertEquals(fill(solved[4]), drawnColor(4))
        assertEquals(stickers.size, ticks)
        assertEquals(stickers.toSet(), changes.last().second)
        rule.onNodeWithTag("editor:faceDone").assertDoesNotExist()
        // The selection did not move: stopping shows it on the first sticker again.
        tapToStop(CubeColor.RED)
        assertNotPainting()
        assertTrue(isSelected(0))
        assertEquals("Selected: Red sticker, up face, position 1", selectedLine())
    }

    @Test
    fun `painting a sticker its own color still ticks and marks it set`() {
        launch(fresh = true)
        longPress(CubeColor.WHITE)
        rule.mainClock.autoAdvance = false
        sticker(3).performClick()
        rule.mainClock.advanceTimeBy(50)
        rule.onNodeWithTag("sticker:3:cue", useUnmergedTree = true).assertExists()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(1, ticks)
        assertEquals(setOf(3), changes.last().second)
        // The same-color tap set the sticker, but Up counts only once all 8 are set (GH #352).
        swatch(CubeColor.WHITE).performClick()
        assertEquals("6 faces left to enter: Up, Front, Right, Back, Left, Down.", text("editor:instruction"))
    }

    @Test
    fun `a plain tap, a long-press or Stop painting on the locked color ends paint mode`() {
        launch(fresh = true)
        longPress(CubeColor.GREEN)
        tapToStop(CubeColor.GREEN)
        assertNotPainting()
        assertTrue(isSelected(0))
        longPress(CubeColor.GREEN)
        longPress(CubeColor.GREEN)
        assertNotPainting()
        customAction(CubeColor.GREEN, "Lock this color")
        assertPainting(CubeColor.GREEN)
        customAction(CubeColor.GREEN, "Stop painting")
        assertNotPainting()
        assertEquals(EditorFixtures.SIX_FACES_LEFT, text("editor:instruction"))
    }

    @Test
    fun `a plain tap on another color only ends paint mode, then plain taps paint as before`() {
        launch(fresh = true)
        longPress(CubeColor.RED)
        tapToStop(CubeColor.BLUE)
        assertNotPainting()
        assertEquals(fill(solved[0]), drawnColor(0))
        assertEquals(0, ticks)
        swatch(CubeColor.BLUE).performClick()
        rule.waitForIdle()
        assertEquals(fill(CubeColor.BLUE), drawnColor(0))
        assertTrue(isSelected(1))
    }

    @Test
    fun `a long-press on another color switches the lock`() {
        launch(fresh = true)
        longPress(CubeColor.RED)
        longPress(CubeColor.BLUE)
        assertPainting(CubeColor.BLUE)
        assertFalse(isLocked(CubeColor.RED))
        sticker(2).performClick()
        rule.waitForIdle()
        assertEquals(fill(CubeColor.BLUE), drawnColor(2))
    }

    @Test
    fun `a paint that completes a uniquely turnable cube turns and notes it like a plain paint`() {
        val entered = EditorFixtures.turned(scrambled, mapOf(0 to 2))
        launch(entered, fresh = true, initialSet = editable - 53)
        longPress(scrambled.colors[53])
        sticker(53).performClick()
        rule.waitForIdle()
        (0 until 9).forEach { assertEquals("$it", fill(scrambled.colors[it]), drawnColor(it)) }
        assertEquals("We turned the Up face to match the others.", text("editor:turned"))
        assertEquals(scrambled, changes.last().first)
        accept().assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(scrambled, accepted.single().toFacelets())
    }

    @Test
    fun `paint mode survives recreation`() {
        val restoration = StateRestorationTester(rule)
        launch(fresh = true, restoration = restoration)
        longPress(CubeColor.ORANGE)
        assertPainting(CubeColor.ORANGE)
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertPainting(CubeColor.ORANGE)
        // The restored editor does not speak the old announcement again.
        assertNull(
            rule
                .onNodeWithTag("paint:announce", useUnmergedTree = true)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.ContentDescription),
        )
        sticker(5).performClick()
        rule.waitForIdle()
        assertEquals(fill(CubeColor.ORANGE), drawnColor(5))
    }

    @Test
    fun `with haptics off a paint-mode tap gives no tick`() {
        launch(fresh = true, hapticsOn = false)
        longPress(CubeColor.RED, resetTicks = false)
        sticker(0).performClick()
        rule.waitForIdle()
        assertEquals(fill(CubeColor.RED), drawnColor(0))
        assertEquals(0, ticks)
    }

    @Test
    fun `the announcer leaves the live region after a moment`() {
        launch(fresh = true)
        longPress(CubeColor.RED)
        assertEquals("Painting red", announcement())
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(2_000)
        val node = rule.onNodeWithTag("paint:announce", useUnmergedTree = true).fetchSemanticsNode()
        assertNull(node.config.getOrNull(SemanticsProperties.ContentDescription))
        assertNull(node.config.getOrNull(SemanticsProperties.LiveRegion))
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun `an editor opened on new content starts with paint mode off`() {
        var initial by mutableStateOf(CubeState.SOLVED.toFacelets())
        rule.setContent {
            CubentiaTheme {
                ManualCubeEditor(strings = strings, onAccept = {}, initial = initial)
            }
        }
        longPress(CubeColor.RED)
        assertTrue(isLocked(CubeColor.RED))
        initial = scrambled
        rule.waitForIdle()
        CubeColor.entries.forEach { assertFalse(isLocked(it)) }
    }

    /** GH #321: U read whole and two F stickers read; every other non-center sticker is unset. */
    private val seededSet = ((0 until 9).filter { it != 4 } + listOf(18, 19)).toSet()

    @Test
    fun `a seeded entry draws unset stickers as blank wells read not set`() {
        launch(seeded = true, initialSet = seededSet)
        rule
            .onNodeWithTag("sticker:9", useUnmergedTree = true)
            .onChildren()
            .filterToOne(hasText("?"))
            .assertExists()
        val node = sticker(9).fetchSemanticsNode().config
        assertEquals(DarkTokens.surfaceStage, node[StickerColorKey])
        assertEquals(listOf("Right sticker 1, not set"), node[SemanticsProperties.ContentDescription])
        assertNull(node.getOrNull(SemanticsProperties.StateDescription))
        assertTrue(node.contains(StickerBlankEdgeKey))
        // A read sticker keeps its color and no blank edge.
        assertEquals(fill(CubeColor.WHITE), drawnColor(0))
        assertFalse(sticker(0).fetchSemanticsNode().config.contains(StickerBlankEdgeKey))
        // It opens on the first unset sticker, named as not set.
        assertTrue(isSelected(9))
        assertEquals("Selected: Right sticker 1, not set", selectedLine())
    }

    @Test
    fun `a seeded entry lists the faces left, shows no problem and enables Accept only once every sticker is set`() {
        launch(initial = scrambled, seeded = true, initialSet = seededSet)
        assertEquals("5 faces left to enter: Front, Right, Back, Left, Down.", text("editor:instruction"))
        assertEquals(emptyList<String>(), problemLines())
        assertEquals(emptySet<Int>(), outlined())
        accept().assertIsNotEnabled()
        // Tap then a palette colour sets an unset sticker.
        paint(9, scrambled.colors[9])
        assertEquals(fill(scrambled.colors[9]), drawnColor(9))
        assertFalse(sticker(9).fetchSemanticsNode().config.contains(StickerBlankEdgeKey))
        assertEquals(seededSet + 9, changes.last().second)
        // Paint mode sets them too.
        val rest = editable - seededSet - 9
        for (color in CubeColor.entries) {
            val stickers = rest.filter { scrambled.colors[it] == color }
            if (stickers.isEmpty()) continue
            longPress(color)
            stickers.forEach { sticker(it).performScrollTo().performClick() }
            longPress(color)
        }
        rule.waitForIdle()
        rule.onNodeWithTag("editor:instruction").assertDoesNotExist()
        accept().assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(scrambled, accepted.single().toFacelets())
    }

    @Test
    fun `a seeded entry keeps its unset stickers across recreation`() {
        val restoration = StateRestorationTester(rule)
        launch(seeded = true, initialSet = seededSet, restoration = restoration)
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertTrue(sticker(9).fetchSemanticsNode().config.contains(StickerBlankEdgeKey))
        assertEquals("5 faces left to enter: Front, Right, Back, Left, Down.", text("editor:instruction"))
    }
}
