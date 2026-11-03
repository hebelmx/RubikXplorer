package dev.rubikxplorer.app.ui.editor

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.rubikxplorer.app.content.ContentStrings
import dev.rubikxplorer.app.render.RenderStyle
import dev.rubikxplorer.app.render.StickerColorKey
import dev.rubikxplorer.app.render.StickerGlyphInkKey
import dev.rubikxplorer.app.ui.editor.EditorFixtures.centers
import dev.rubikxplorer.app.ui.editor.EditorFixtures.scrambled
import dev.rubikxplorer.app.ui.editor.EditorFixtures.solved
import dev.rubikxplorer.app.ui.editor.EditorFixtures.swappedEdges
import dev.rubikxplorer.app.ui.editor.EditorFixtures.twistedAndFlipped
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

/** The editor's I/O matrix and ACs at the composable surface, on the JVM through Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
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

    private fun launch(initial: Facelets = CubeState.SOLVED.toFacelets()) {
        rule.setContent {
            RubikXplorerTheme {
                ManualCubeEditor(
                    strings = strings,
                    onAccept = { accepted += it },
                    initial = initial,
                    style = RenderStyle(colorBlindGlyphs = glyphs),
                )
            }
        }
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

    @Test
    fun `tapping a sticker then a color paints it and keeps it selected`() {
        launch()
        paint(0, CubeColor.RED)
        assertEquals(fill(CubeColor.RED), drawnColor(0))
        assertEquals(true, sticker(0).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        assertEquals(fill(solved[1]), drawnColor(1))
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
    fun `wrong counts show the count line without outlines and disable Accept`() {
        launch()
        paint(0, CubeColor.RED)
        assertEquals(listOf("Each color should appear on exactly nine stickers. Check the colors you entered."), problemLines())
        assertEquals(emptySet<Int>(), outlined())
        accept().assertIsNotEnabled()
    }

    @Test
    fun `an impossible corner outlines exactly that piece and shows its line`() {
        launch()
        paint(18, CubeColor.WHITE)
        paint(8, CubeColor.GREEN)
        assertEquals(setOf(6, 8, 9, 18, 20, 38), outlined())
        (0 until Facelets.SIZE).forEach { index ->
            val state = sticker(index).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
            assertEquals("$index", if (index in outlined()) "Check this piece" else null, state)
        }
        assertEquals(listOf("Some corners show colors that no real corner has. Check the outlined stickers."), problemLines())
        accept().assertIsNotEnabled()
    }

    @Test
    fun `a twisted corner and a flipped edge each get their own line and no outlines`() {
        launch(twistedAndFlipped)
        assertEquals(
            listOf("One corner is twisted — check the corners you changed last.", "One edge is flipped."),
            problemLines(),
        )
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
        swatch(CubeColor.WHITE).performClick()
        rule.waitForIdle()
        assertTrue(problemLines().isEmpty())
        accept().assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(CubeState.SOLVED.toFacelets(), accepted.single().toFacelets())
        assertFalse(isInvalid(0))
    }
}
