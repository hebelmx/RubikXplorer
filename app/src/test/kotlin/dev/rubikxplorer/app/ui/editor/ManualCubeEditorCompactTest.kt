package dev.rubikxplorer.app.ui.editor

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
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
import dev.rubikxplorer.app.content.ContentStrings
import dev.rubikxplorer.app.render.RenderStyle
import dev.rubikxplorer.app.render.StickerColorKey
import dev.rubikxplorer.app.render.StickerGlyphInkKey
import dev.rubikxplorer.app.ui.editor.EditorFixtures.solved
import dev.rubikxplorer.app.ui.theme.RubikXplorerTheme
import dev.rubikxplorer.domain.appearance.StickerAppearance
import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.content.ContentLoader
import dev.rubikxplorer.domain.content.ContentSource
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Facelets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.rubikxplorer.domain.cube.Color as CubeColor

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
    ) {
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            RubikXplorerTheme {
                ManualCubeEditor(
                    strings = strings,
                    onAccept = {},
                    initial = initial,
                    style = RenderStyle(colorBlindGlyphs = glyphs),
                )
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

    private fun facesMarked(): Set<Int> =
        (0 until FACES).filterTo(mutableSetOf()) {
            faceTab(it).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) == "Has pieces to check"
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
        // URF corner (U 8, R 9, F 20) and UFL corner (U 6, F 18, L 38): faces U, R, F, L.
        assertEquals(setOf(0, 1, 2, 4), facesMarked())
        showFace(3)
        assertEquals(listOf("Some corners show colors that no real corner has. Check the outlined stickers."), problemLines())
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
        assertEquals(true, sticker(20).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        swatch(CubeColor.GREEN).performClick()
        rule.waitForIdle()
        assertEquals(fill(CubeColor.GREEN), drawnColor(20))
    }

    @Test
    @Config(qualifiers = "w640dp-h891dp")
    fun `at a wide width the full net shows with no face selector`() {
        launch()
        assertEquals((0 until Facelets.SIZE).toList(), visibleStickers())
        rule.onAllNodesWithTag("editor:face:0").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        // Every selectable sticker's touch target is a full 48dp cell; centers are not targets.
        (0 until Facelets.SIZE).filter(ManualCubeEditorState::isEditable).forEach {
            assertTrue("$it", sticker(it).bounds().let { r -> r.right - r.left } >= 48.dp)
        }
    }
}
