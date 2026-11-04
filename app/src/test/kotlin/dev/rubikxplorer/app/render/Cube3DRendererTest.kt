package dev.rubikxplorer.app.render

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.rubikxplorer.app.session.allMoves
import dev.rubikxplorer.domain.cube.CubeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class Cube3DRendererTest {
    @get:Rule val rule = createComposeRule()

    @Test fun netAndMeshHaveIdenticalAppearanceWithGlyphsOnAndOff() {
        val state = CubeState.SOLVED.apply(allMoves.take(19)).toFacelets()
        val net = Net2DRenderer(state)
        val cube = Cube3DRenderer(state)
        var glyphs by mutableStateOf(false)
        rule.setContent {
            Column {
                net.Content(RenderStyle(glyphs), Modifier.size(300.dp, 225.dp))
                cube.Content(RenderStyle(glyphs), Modifier.size(300.dp))
            }
        }
        listOf(false, true, false).forEach { enabled ->
            rule.runOnIdle { glyphs = enabled }
            rule.waitForIdle()
            val draws = rule.onNodeWithTag("cube3d").fetchSemanticsNode().config[RenderedStickersKey]
            draws.forEachIndexed { i, draw ->
                val cell = rule.onNodeWithTag("sticker:$i").fetchSemanticsNode().config
                assertEquals(cell[StickerColorKey], Color(draw.fill))
                assertEquals(cell.getOrNull(StickerGlyphInkKey), draw.glyphInk?.let { Color(it) })
                assertEquals(enabled, draw.glyph != null)
            }
        }
    }

    @Test
    fun glyphToggleDoesNotCompleteAnActiveTurnEarly() {
        val cube = Cube3DRenderer()
        var glyphs by mutableStateOf(false)
        rule.setContent { cube.Content(RenderStyle(glyphs), Modifier.size(300.dp)) }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        val move = allMoves.first()
        val target = CubeState.SOLVED.apply(move).toFacelets()
        rule.runOnIdle { cube.submit(listOf(PresentationItem.Animate(move, 1, target))) }
        rule.mainClock.advanceTimeBy(50)
        rule.runOnIdle { glyphs = true }
        rule.mainClock.advanceTimeBy(50)
        assertEquals(CubeState.SOLVED.toFacelets(), cube.renderedFacelets())
        rule.mainClock.advanceTimeBy(150)
        assertEquals(target, cube.renderedFacelets())
        val draws = rule.onNodeWithTag("cube3d").fetchSemanticsNode().config[RenderedStickersKey]
        assertTrue(draws.all { it.glyph != null && it.glyphInk != null })
    }
}
