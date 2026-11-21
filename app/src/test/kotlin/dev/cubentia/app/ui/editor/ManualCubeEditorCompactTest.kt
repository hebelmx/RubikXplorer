package dev.cubentia.app.ui.editor

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.render.RenderStyle
import dev.cubentia.app.render.StickerColorKey
import dev.cubentia.app.render.StickerGlyphInkKey
import dev.cubentia.app.ui.editor.EditorFixtures.solved
import dev.cubentia.app.ui.theme.CubentiaTheme
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

/** One-face mode: windows too narrow for a 48dp cell per net sticker edit one face at a time. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w320dp-h640dp")
class ManualCubeEditorCompactTest {
    @get:Rule
    val rule = createComposeRule()

    private companion object {
        const val FACES = 6
        const val PER_FACE = 9
        const val CENTER = 4
        const val MAX_LINES = 6
    }

    private val strings =
        ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)

    private fun launch(
        initial: Facelets = CubeState.SOLVED.toFacelets(),
        glyphs: Boolean = false,
        restoration: StateRestorationTester? = null,
        windowSizeClass: WindowSizeClass? = null,
        fresh: Boolean = false,
        initialSet: Set<Int> = emptySet(),
    ) {
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            CubentiaTheme {
                if (windowSizeClass == null) {
                    ManualCubeEditor(
                        strings = strings,
                        onAccept = {},
                        initial = initial,
                        style = RenderStyle(colorBlindGlyphs = glyphs),
                        fresh = fresh,
                        initialSet = initialSet,
                    )
                } else {
                    ManualCubeEditor(
                        strings = strings,
                        onAccept = {},
                        initial = initial,
                        style = RenderStyle(colorBlindGlyphs = glyphs),
                        windowSizeClass = windowSizeClass,
                        fresh = fresh,
                        initialSet = initialSet,
                    )
                }
            }
        }
        if (restoration != null) restoration.setContent(content) else rule.setContent(content)
    }

    private fun sticker(index: Int) = rule.onNodeWithTag("sticker:$index")

    private fun faceTab(face: Int) = rule.onNodeWithTag("editor:face:$face")

    private fun swatch(color: CubeColor) = rule.onNodeWithTag("swatch:${color.name}").performScrollTo()

    private fun accept() = rule.onNodeWithTag("editor:accept").performScrollTo()

    private fun fill(color: CubeColor) = Color(StickerAppearance.of(color).fill)

    private fun drawnColor(index: Int): Color = sticker(index).fetchSemanticsNode().config[StickerColorKey]

    private fun visibleStickers(): List<Int> =
        (0 until Facelets.SIZE).filter { rule.onAllNodesWithTag("sticker:$it").fetchSemanticsNodes().isNotEmpty() }

    private fun showFace(face: Int) {
        faceTab(face).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun paint(
        index: Int,
        color: CubeColor,
    ) {
        showFace(index / PER_FACE)
        sticker(index).performScrollTo().performClick()
        swatch(color).performClick()
        rule.waitForIdle()
    }

    private fun problemLines(): List<String> =
        (0 until MAX_LINES)
            .flatMap { rule.onAllNodesWithTag("editor:problem:$it").fetchSemanticsNodes() }
            .map { node -> node.config[SemanticsProperties.Text].joinToString { it.text } }

    private fun tabState(face: Int): String? = faceTab(face).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    private fun facesMarked(): Set<Int> =
        (0 until FACES).filterTo(mutableSetOf()) { face ->
            val marked = tabState(face)?.split(", ")?.contains("Has pieces to check") == true
            val dot = rule.onAllNodesWithTag("editor:face:$face:dot", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            assertEquals("face $face dot", marked, dot)
            marked
        }

    private fun facesDone(): Set<Int> =
        (0 until FACES).filterTo(mutableSetOf()) { face ->
            val done = tabState(face)?.split(", ")?.contains("All stickers set") == true
            val check = rule.onAllNodesWithTag("editor:face:$face:done", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            assertEquals("face $face check", done, check)
            done
        }

    private fun SemanticsNodeInteraction.bounds(): DpRect = getUnclippedBoundsInRoot()

    private fun DpRect.overlaps(other: DpRect): Boolean =
        Rect(left.value, top.value, right.value, bottom.value)
            .intersect(Rect(other.left.value, other.top.value, other.right.value, other.bottom.value))
            .let { it.width > 0f && it.height > 0f }

    private fun assertTouchSizedAndApart(targets: Map<String, DpRect>) {
        targets.forEach { (name, rect) ->
            assertTrue("$name width ${rect.right - rect.left}", rect.right - rect.left >= 48.dp)
            assertTrue("$name height ${rect.bottom - rect.top}", rect.bottom - rect.top >= 48.dp)
            assertTrue("$name inside window", rect.left >= 0.dp && rect.right <= 320.dp)
        }
        val entries = targets.entries.toList()
        entries.forEachIndexed { i, a ->
            entries.drop(i + 1).forEach { b -> assertFalse("${a.key} overlaps ${b.key}", a.value.overlaps(b.value)) }
        }
    }

    @Test
    fun `at 320dp every visible sticker and face control is at least 48dp and none overlap`() {
        launch()
        repeat(FACES) { face ->
            showFace(face)
            val visible = visibleStickers()
            assertEquals((face * PER_FACE until (face + 1) * PER_FACE).toList(), visible)
            val targets =
                visible.associate { "sticker:$it" to sticker(it).bounds() } +
                    (0 until FACES).associate { "face:$it" to faceTab(it).bounds() }
            assertTouchSizedAndApart(targets)
            faceTab(face).assertIsSelected()
        }
    }

    @Test
    fun `switching faces lets every non-center sticker of all six faces be painted`() {
        launch()
        (0 until Facelets.SIZE).filter { it % PER_FACE != CENTER }.forEach { index ->
            val color = if (solved[index] == CubeColor.RED) CubeColor.BLUE else CubeColor.RED
            paint(index, color)
            // Painting a face's last sticker advances onto the next face; go back to check it.
            showFace(index / PER_FACE)
            assertEquals("$index", fill(color), drawnColor(index))
        }
    }

    @Test
    fun `centers stay fixed and unclickable on every face`() {
        launch()
        repeat(FACES) { face ->
            showFace(face)
            val center = face * PER_FACE + CENTER
            assertNull(sticker(center).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick))
            assertEquals(fill(solved[center]), drawnColor(center))
        }
    }

    @Test
    fun `glyphs show on the visible face while color-blind display is on`() {
        launch(glyphs = true)
        showFace(2)
        visibleStickers().forEach {
            assertEquals(Color(StickerAppearance.of(solved[it]).glyphInk), sticker(it).fetchSemanticsNode().config[StickerGlyphInkKey])
        }
    }

    @Test
    fun `offending pieces are outlined, their faces marked, and hidden faces still get problem lines`() {
        launch()
        paint(18, CubeColor.WHITE)
        paint(8, CubeColor.GREEN)
        // URF corner (U 8, R 9, F 20) and UFL corner (U 6, F 18, L 38): faces U, R, F, L; the dot is never red.
        assertEquals(setOf(0, 1, 2, 4), facesMarked())
        showFace(3)
        assertEquals(
            listOf(
                "The corner between Up, Front and Right shows two greens.",
                "The corner between Up, Front and Left shows two whites.",
            ),
            problemLines(),
        )
        accept().assertIsNotEnabled()
        showFace(4)
        assertTrue(sticker(38).fetchSemanticsNode().config[StickerInvalidKey])
        assertFalse(sticker(36).fetchSemanticsNode().config[StickerInvalidKey])

        paint(8, CubeColor.WHITE)
        paint(18, CubeColor.GREEN)
        assertEquals(emptySet<Int>(), facesMarked())
        assertEquals(emptyList<String>(), problemLines())
        accept().assertIsEnabled()
    }

    @Test
    fun `switching faces clears a selection on another face so hidden stickers are never painted`() {
        launch()
        sticker(0).performClick()
        showFace(2)
        swatch(CubeColor.RED).performClick()
        rule.waitForIdle()
        showFace(0)
        assertEquals(fill(CubeColor.WHITE), drawnColor(0))
        assertEquals(emptyList<String>(), problemLines())
    }

    @Test
    fun `the shown face, selection and stickers survive a saved-state restore`() {
        val restoration = StateRestorationTester(rule)
        launch(restoration = restoration)
        paint(20, CubeColor.RED)
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        faceTab(2).assertIsSelected()
        assertEquals(fill(CubeColor.RED), drawnColor(20))
        // Painting advanced the selection to 21; it survives the restore and takes the next color.
        assertEquals(true, sticker(21).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        swatch(CubeColor.GREEN).performClick()
        rule.waitForIdle()
        assertEquals(fill(CubeColor.RED), drawnColor(20))
        assertEquals(fill(CubeColor.GREEN), drawnColor(21))
    }

    @Test
    fun `painting the last sticker of a face shows the next face with its first sticker selected and announced`() {
        launch()
        paint(8, CubeColor.RED)
        faceTab(1).assertIsSelected()
        assertEquals((PER_FACE until 2 * PER_FACE).toList(), visibleStickers())
        assertEquals(true, sticker(9).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        val line = rule.onNodeWithTag("editor:selected").fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, line.config[SemanticsProperties.LiveRegion])
        assertEquals("Selected: Red sticker, right face, position 1", line.config[SemanticsProperties.Text].joinToString { it.text })
        showFace(0)
        assertEquals(fill(CubeColor.RED), drawnColor(8))
    }

    @Test
    fun `tapping a sticker on the shown face fixes it and advances from it`() {
        launch()
        paint(0, CubeColor.RED)
        paint(1, CubeColor.RED)
        assertEquals(true, sticker(2).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        sticker(0).performClick()
        swatch(CubeColor.WHITE).performClick()
        rule.waitForIdle()
        assertEquals(fill(CubeColor.WHITE), drawnColor(0))
        assertEquals(fill(CubeColor.RED), drawnColor(1))
        assertEquals(true, sticker(1).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        faceTab(0).assertIsSelected()
    }

    @Test
    @Config(qualifiers = "w640dp-h891dp")
    fun `at a wide width the full net shows under the face-tab status strip with no hold bands`() {
        launch()
        assertFullNet()
        // Every selectable sticker's touch target is a full 48dp cell; centers are not targets.
        (0 until Facelets.SIZE).filter(ManualCubeEditorState::isEditable).forEach {
            assertTrue("$it", sticker(it).bounds().let { r -> r.right - r.left } >= 48.dp)
        }
    }

    private fun assertFullNet() {
        assertEquals((0 until Facelets.SIZE).toList(), visibleStickers())
        // The full net keeps the six face tabs as a status strip (#253), but draws no hold bands.
        (0 until 6).forEach { assertEquals("$it", 1, rule.onAllNodesWithTag("editor:face:$it").fetchSemanticsNodes().size) }
        listOf("top", "right", "bottom", "left").forEach {
            assertTrue(it, rule.onAllNodesWithTag("editor:hold:$it", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        }
    }

    private fun assertOneFace() {
        assertEquals((0 until PER_FACE).toList(), visibleStickers())
        faceTab(0).assertIsSelected()
    }

    @Test
    @Config(qualifiers = "w640dp-h891dp")
    fun `the compact width class edits one face at a time even when the net would fit`() {
        launch(windowSizeClass = WindowSizeClass(minWidthDp = 0, minHeightDp = 0))
        assertOneFace()
    }

    @Test
    @Config(qualifiers = "w640dp-h891dp")
    fun `the medium width class with room for 48dp cells shows the full net`() {
        launch(windowSizeClass = WindowSizeClass(minWidthDp = WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND, minHeightDp = 0))
        assertFullNet()
    }

    @Test
    @Config(qualifiers = "w900dp-h891dp")
    fun `the expanded width class with room for 48dp cells shows the full net`() {
        launch(windowSizeClass = WindowSizeClass(minWidthDp = WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND, minHeightDp = 0))
        assertFullNet()
    }

    @Test
    fun `a wider class still edits one face at a time when the width cannot fit 48dp stickers`() {
        launch(windowSizeClass = WindowSizeClass(minWidthDp = WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND, minHeightDp = 0))
        assertOneFace()
        sticker(0).bounds().let { assertTrue(it.right - it.left >= 48.dp) }
    }

    private val editable = (0 until Facelets.SIZE).filter(ManualCubeEditorState::isEditable).toSet()

    private fun text(tag: String): String =
        rule
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString { it.text }

    private fun tapSwatch(color: CubeColor) {
        swatch(color).performClick()
        rule.waitForIdle()
    }

    @Test
    fun `a fresh Solve entry selects Up 1 with the pulsing cursor and asks for six faces`() {
        launch(fresh = true)
        faceTab(0).assertIsSelected()
        val first = sticker(0).fetchSemanticsNode().config
        assertEquals(true, first.getOrNull(SemanticsProperties.Selected))
        assertEquals(true, first.getOrNull(StickerPulsingKey))
        assertEquals("6 faces left to enter: Up, Front, Right, Back, Left, Down.", text("editor:instruction"))
        assertEquals(emptyList<String>(), problemLines())
        accept().assertIsNotEnabled()
    }

    @Test
    fun `a complete face gets its tab check and a problem face its dot`() {
        launch(fresh = true)
        assertEquals(emptySet<Int>(), facesDone())
        repeat(8) { tapSwatch(CubeColor.WHITE) }
        assertEquals(setOf(0), facesDone())
        // While faces are left to enter, no tab carries the warning dot.
        assertEquals(emptySet<Int>(), facesMarked())
        rule.onNodeWithTag("editor:face:0:dot", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a turned face's tab says so`() {
        val entered = EditorFixtures.turned(EditorFixtures.scrambled, mapOf(0 to 2))
        launch(entered, fresh = true, initialSet = editable - 53)
        paint(53, EditorFixtures.scrambled.colors[53])
        assertEquals("Turned to match", tabState(0)?.split(", ")?.last())
        assertEquals("We turned the Up face to match the others.", text("editor:turned"))
        accept().assertIsEnabled()
    }

    @Test
    fun `an unresolved face is named with its hold and its tab is marked`() {
        val r2 = CubeState.SOLVED.apply((Notation.parse("R2") as NotationResult.Parsed).moves).toFacelets()
        val entered = EditorFixtures.turned(r2, mapOf(0 to 1))
        launch(entered, fresh = true, initialSet = editable - 53)
        paint(53, entered.colors[53])
        assertEquals("Check the Up face: hold green toward you and look at the top.", text("editor:instruction"))
        assertTrue(0 in facesMarked())
        accept().assertIsNotEnabled()
    }

    @Test
    fun `the hold bands around Up show its neighbours' centers and stay hidden from TalkBack`() {
        launch(glyphs = true)
        val expected = mapOf("top" to CubeColor.BLUE, "right" to CubeColor.RED, "bottom" to CubeColor.GREEN, "left" to CubeColor.ORANGE)
        val up = sticker(4).bounds()
        expected.forEach { (side, color) ->
            val band = rule.onNodeWithTag("editor:hold:$side", useUnmergedTree = true)
            val config = band.fetchSemanticsNode().config
            assertEquals(side, fill(color), config[StickerColorKey])
            assertEquals(side, Color(StickerAppearance.of(color).glyphInk), config[StickerGlyphInkKey])
            assertTrue(side, config.contains(SemanticsProperties.HideFromAccessibility))
            val rect = band.bounds()
            when (side) {
                "top" -> assertTrue(side, rect.bottom <= up.top)
                "bottom" -> assertTrue(side, rect.top >= up.bottom)
                "left" -> assertTrue(side, rect.right <= up.left)
                else -> assertTrue(side, rect.left >= up.right)
            }
        }
        // Front's hold: white on top, red right, yellow below, orange left.
        showFace(2)
        assertEquals(
            fill(CubeColor.WHITE),
            rule.onNodeWithTag("editor:hold:top", useUnmergedTree = true).fetchSemanticsNode().config[StickerColorKey],
        )
        assertEquals(
            fill(CubeColor.YELLOW),
            rule.onNodeWithTag("editor:hold:bottom", useUnmergedTree = true).fetchSemanticsNode().config[StickerColorKey],
        )
    }

    private fun band(side: String) =
        rule.onNodeWithTag("editor:hold:$side", useUnmergedTree = true).fetchSemanticsNode().config[StickerColorKey]

    @Test
    fun `the hold bands around Down and Back show the faces next to them in the net`() {
        launch()
        // Down: green front above, red right, blue back below, orange left.
        showFace(3)
        assertEquals(
            listOf(CubeColor.GREEN, CubeColor.RED, CubeColor.BLUE, CubeColor.ORANGE).map(::fill),
            listOf("top", "right", "bottom", "left").map(::band),
        )
        // Back: white above, orange right, yellow below, red left.
        showFace(5)
        assertEquals(
            listOf(CubeColor.WHITE, CubeColor.ORANGE, CubeColor.YELLOW, CubeColor.RED).map(::fill),
            listOf("top", "right", "bottom", "left").map(::band),
        )
    }

    @Test
    fun `the hold bands keep their sides and colors in a right-to-left layout`() {
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl,
            ) {
                CubentiaTheme {
                    ManualCubeEditor(strings = strings, onAccept = {}, initial = CubeState.SOLVED.toFacelets(), style = RenderStyle())
                }
            }
        }
        val up = sticker(4).bounds()
        val expected = mapOf("top" to CubeColor.BLUE, "right" to CubeColor.RED, "bottom" to CubeColor.GREEN, "left" to CubeColor.ORANGE)
        expected.forEach { (side, color) ->
            assertEquals(side, fill(color), band(side))
            val rect = rule.onNodeWithTag("editor:hold:$side", useUnmergedTree = true).bounds()
            when (side) {
                "top" -> assertTrue(side, rect.bottom <= up.top)
                "bottom" -> assertTrue(side, rect.top >= up.bottom)
                "left" -> assertTrue(side, rect.right <= up.left)
                else -> assertTrue(side, rect.left >= up.right)
            }
        }
    }

    @Test
    fun `the Down face to check is held green on top with yellow toward the Learner`() {
        val context =
            androidx.test.core.app.ApplicationProvider
                .getApplicationContext<Application>()
        val faceNames = FACE_LABELS.map(context::getString)
        val colorNames = CubeColor.entries.associateWith { context.getString(colorName(it)) }
        assertEquals(
            "Check the Down face: hold green on top and turn yellow toward you.",
            strings.resolve(checkFaceText(3, faceNames, colorNames)),
        )
        assertEquals(
            "Check the Up face: hold green toward you and look at the top.",
            strings.resolve(checkFaceText(0, faceNames, colorNames)),
        )
        assertEquals(
            "Check the Back face: hold white on top and turn blue toward you.",
            strings.resolve(checkFaceText(5, faceNames, colorNames)),
        )
    }

    @Test
    fun `wrong counts show the counts line and never a face to check`() {
        val entered = Facelets(solved.toMutableList().also { it[18] = CubeColor.RED })
        launch(entered, fresh = true, initialSet = editable - 53)
        paint(53, entered.colors[53])
        rule.onNodeWithTag("editor:instruction").assertDoesNotExist()
        assertEquals("Red: 10 (one too many) · Green: 8 (one missing)", problemLines().first())
        assertTrue(2 in facesMarked())
        accept().assertIsNotEnabled()
    }

    @Test
    fun `completing Up shows the face-done line for about 1_5 s without moving the layout`() {
        launch(fresh = true)
        repeat(7) { tapSwatch(CubeColor.WHITE) }
        val paletteTop = swatch(CubeColor.WHITE).bounds().top
        rule.mainClock.autoAdvance = false
        swatch(CubeColor.WHITE).performClick()
        rule.mainClock.advanceTimeBy(100)
        val line = rule.onNodeWithTag("editor:faceDone").fetchSemanticsNode()
        assertEquals("Up done ✓ → Right", line.config[SemanticsProperties.Text].joinToString { it.text })
        assertEquals(listOf("Up face done. Right face next."), line.config[SemanticsProperties.ContentDescription])
        assertEquals(LiveRegionMode.Polite, line.config[SemanticsProperties.LiveRegion])
        val shownTop = swatch(CubeColor.WHITE).bounds().top
        rule.mainClock.advanceTimeBy(1_600)
        rule.onNodeWithTag("editor:faceDone").assertDoesNotExist()
        assertEquals(shownTop, swatch(CubeColor.WHITE).bounds().top)
        assertEquals(paletteTop, shownTop)
        faceTab(1).assertIsSelected()
    }

    @Test
    fun `the update-cue dot carries a ring in each sticker color's glyph ink`() {
        launch()
        CubeColor.entries.forEach { color ->
            sticker(0).performClick()
            rule.waitForIdle()
            rule.mainClock.autoAdvance = false
            swatch(color).performClick()
            rule.mainClock.advanceTimeBy(50)
            val cue = rule.onNodeWithTag("sticker:0:cue", useUnmergedTree = true).fetchSemanticsNode()
            assertEquals("$color", Color(StickerAppearance.of(color).glyphInk), cue.config[StickerCueRingKey])
            rule.mainClock.autoAdvance = true
            rule.waitForIdle()
        }
    }
}
