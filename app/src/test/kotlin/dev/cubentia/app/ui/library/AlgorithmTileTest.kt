package dev.cubentia.app.ui.library

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.preferences.LocalRenderStyle
import dev.cubentia.app.render.RenderStyle
import dev.cubentia.app.render.StickerColorKey
import dev.cubentia.app.render.StickerGlyphInkKey
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.app.ui.theme.PlexMono
import dev.cubentia.app.ui.theme.RubikTypography
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.domain.appearance.StickerAppearance
import dev.cubentia.domain.content.Algorithm
import dev.cubentia.domain.content.AlgorithmId
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.lastlayer.RecognitionDiagram
import dev.cubentia.domain.lastlayer.RecognitionDiagramResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import dev.cubentia.domain.cube.Color as CubeColor

/** Story 9.3: the Algorithm tile, its recognition diagram and the cut fallback. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w320dp-h640dp")
class AlgorithmTileTest {
    @get:Rule
    val rule = createComposeRule()

    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
    private val strings = ContentStrings(bundle.strings)
    private val tPerm = bundle.algorithms.first { it.id.value == "alg-pll-t-a" }
    private val case = bundle.cases.first { it.id == tPerm.caseId }
    private val cells = (RecognitionDiagram.derive(tPerm) as RecognitionDiagramResult.Derived).diagram.cells
    private val drawn = cells.indices.filter { cells[it] != null }

    private fun launch(
        algorithm: Algorithm = tPerm,
        glyphs: Boolean = false,
        showDiagram: Boolean = true,
    ) {
        rule.setContent {
            CubentiaTheme {
                AlgorithmTile(case, algorithm, strings, showDiagram = showDiagram, style = RenderStyle(colorBlindGlyphs = glyphs))
            }
        }
    }

    private fun sticker(index: Int) = rule.onNodeWithTag("$STICKER_TAG_PREFIX$index", useUnmergedTree = true)

    private fun config(index: Int) = sticker(index).fetchSemanticsNode().config

    private fun glyphCount(glyph: Char) = rule.onAllNodesWithText(glyph.toString(), useUnmergedTree = true)

    private fun assertNameAndNotationOnly(notation: String) {
        rule.onNodeWithText(strings.resolve(case.name)).assertIsDisplayed()
        rule.onNodeWithText(notation).assertIsDisplayed()
        val nameTop = rule.onNodeWithText(strings.resolve(case.name)).getUnclippedBoundsInRoot().top
        assertTrue("nothing above the Case name, top $nameTop", abs((nameTop - Spacing.s3).value) < 1f)
        rule.onAllNodesWithTag(DIAGRAM_TAG).assertCountEquals(0)
        (0 until cells.size).forEach { rule.onAllNodesWithTag("$STICKER_TAG_PREFIX$it", useUnmergedTree = true).assertCountEquals(0) }
    }

    private fun textStyleOf(text: String) =
        mutableListOf<TextLayoutResult>()
            .also {
                rule
                    .onNodeWithText(text)
                    .fetchSemanticsNode()
                    .config[SemanticsActions.GetTextLayoutResult]
                    .action
                    ?.invoke(it)
            }.single()
            .layoutInput.style

    @Test
    fun `with glyphs on each sticker has its fill and glyph ink`() {
        launch(glyphs = true)
        assertEquals(21, drawn.size)
        drawn.forEach { index ->
            val appearance = StickerAppearance.of(cells[index]!!)
            assertEquals("$index", Color(appearance.fill), config(index)[StickerColorKey])
            assertEquals("$index", Color(appearance.glyphInk), config(index)[StickerGlyphInkKey])
        }
        CubeColor.entries.forEach { color ->
            val appearance = StickerAppearance.of(color)
            glyphCount(appearance.glyph).assertCountEquals(cells.count { it == color })
        }
    }

    @Test
    fun `with glyphs off stickers have fills and no glyph`() {
        launch(glyphs = false)
        drawn.forEach { index ->
            assertEquals("$index", Color(StickerAppearance.of(cells[index]!!).fill), config(index)[StickerColorKey])
            assertNull("$index", config(index).getOrNull(StickerGlyphInkKey))
        }
        assertEquals(Color(StickerAppearance.of(CubeColor.YELLOW).fill), config(12)[StickerColorKey])
        CubeColor.entries.forEach { glyphCount(StickerAppearance.of(it).glyph).assertCountEquals(0) }
    }

    @Test
    fun `the four corner cells draw nothing`() {
        launch()
        listOf(0, 4, 20, 24).forEach { rule.onAllNodesWithTag("$STICKER_TAG_PREFIX$it", useUnmergedTree = true).assertCountEquals(0) }
    }

    @Test
    fun `each sticker sits in its row and column`() {
        launch()
        val origin = sticker(1).getUnclippedBoundsInRoot()
        drawn.forEach { index ->
            val bounds = sticker(index).getUnclippedBoundsInRoot()
            val column = index % RecognitionDiagram.SIZE - 1
            val row = index / RecognitionDiagram.SIZE
            assertTrue("$index left", abs((bounds.left - origin.left).value - column * 20f) < 1f)
            assertTrue("$index top", abs((bounds.top - origin.top).value - row * 20f) < 1f)
        }
    }

    @Test
    fun `the diagram is announced once with the Case name`() {
        launch(glyphs = true)
        rule.onNodeWithContentDescription("Last layer diagram for ${strings.resolve(case.name)}").assertIsDisplayed()
        rule.onNodeWithTag(DIAGRAM_TAG).onChildren().assertCountEquals(0)
    }

    @Test
    fun `a right-to-left layout does not mirror the diagram`() {
        rule.setContent {
            CubentiaTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    AlgorithmTile(case, tPerm, strings, showDiagram = true, style = RenderStyle())
                }
            }
        }
        val rBand = sticker(9).getUnclippedBoundsInRoot()
        val lBand = sticker(5).getUnclippedBoundsInRoot()
        assertTrue("R band at ${rBand.left}, L band at ${lBand.left}", rBand.left > lBand.left)
    }

    @Test
    fun `the Case name uses the label role and the Notation the notation role`() {
        launch()
        assertEquals(RubikTypography.labelLarge.fontSize, textStyleOf(strings.resolve(case.name)).fontSize)
        assertEquals(PlexMono, textStyleOf(tPerm.notation).fontFamily)
    }

    @Test
    fun `with diagrams cut the tile shows the Case name and Notation only`() {
        launch(showDiagram = false)
        assertNameAndNotationOnly(tPerm.notation)
    }

    @Test
    fun `bad Notation shows the Case name and Notation with no diagram`() {
        launch(Algorithm(AlgorithmId("alg-x-a"), case.id, "R Q"))
        assertNameAndNotationOnly("R Q")
    }

    @Test
    fun `an Algorithm that breaks the first two layers shows no diagram`() {
        launch(Algorithm(AlgorithmId("alg-x-a"), case.id, "R"))
        assertNameAndNotationOnly("R")
    }

    @Test
    fun `the build constant and the render style are the tile's defaults`() {
        rule.setContent {
            CubentiaTheme {
                CompositionLocalProvider(LocalRenderStyle provides RenderStyle(colorBlindGlyphs = true)) {
                    AlgorithmTile(case, tPerm, strings)
                }
            }
        }
        val expected = if (RECOGNITION_DIAGRAMS_ENABLED) 1 else 0
        rule.onAllNodesWithTag(DIAGRAM_TAG).assertCountEquals(expected)
        glyphCount('Y').assertCountEquals(expected * cells.count { it == CubeColor.YELLOW })
    }
}
