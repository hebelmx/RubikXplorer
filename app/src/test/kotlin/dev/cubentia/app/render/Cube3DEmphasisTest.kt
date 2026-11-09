package dev.cubentia.app.render

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.cubentia.app.render.gl.CubeMesh
import dev.cubentia.app.render.gl.CubieModel
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.predicate.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class Cube3DEmphasisTest {
    @get:Rule val rule = createComposeRule()

    private fun config() = rule.onNodeWithTag("cube3d").fetchSemanticsNode().config

    @Test fun `flags follow pieces through animation and match snapshots and semantics`() {
        val start = CubeState.SOLVED.apply(listOf(FaceTurn(Face.F, Turn.CLOCKWISE), FaceTurn(Face.U, Turn.HALF)))
        val pieces = (0 until 54).mapNotNull { Piece.at(start.toFacelets(), it) }.distinct()
        var emphasis by mutableStateOf<NetEmphasis?>(NetEmphasis(setOf(pieces[0]), setOf(pieces[1]), 0.7f))
        val cube = Cube3DRenderer(start.toFacelets())
        val liveModel = CubieModel(start.toFacelets())
        rule.setContent { cube.Content(RenderStyle(), Modifier.size(300.dp), emphasis = emphasis) }

        fun verify() {
            rule.waitForIdle()
            val facelets = cube.renderedFacelets()
            val draws = config()[RenderedStickersKey]
            val model = CubieModel(facelets)
            assertEquals(CubeMesh.stickerDraws(model, RenderStyle(), emphasis), draws)
            draws.forEachIndexed { i, draw ->
                val piece = Piece.at(facelets, i)
                assertEquals(emphasis?.dims(piece) == true, draw.dimmed)
                assertEquals(emphasis?.highlights(piece) == true, draw.highlighted)
                assertEquals(draw.highlighted && (emphasis?.pulse ?: 0f) > 0f, draw.pulsing)
            }
            // Compare every cubie flag in the live model order, including after a turn.
            val snapshot = cube.glSnapshot!!
            assertEquals(CubeMesh.emphasisFlags(liveModel, emphasis).toList(), snapshot.emphasisFlags.toList())
            assertEquals(emphasis?.pulse ?: 0f, snapshot.pulse)
        }
        verify()
        val move = FaceTurn(Face.R, Turn.CLOCKWISE)
        rule.runOnIdle { cube.submit(listOf(PresentationItem.Animate(move, 1, start.apply(move).toFacelets()))) }
        liveModel.begin(move)
        liveModel.commit()
        verify()
        assertEquals(start.apply(move).toFacelets(), cube.renderedFacelets())
        rule.runOnIdle { emphasis = null }
        verify()
        assertTrue(cube.glSnapshot!!.emphasisFlags.all { it == 0 })
        assertNull(config().getOrNull(RenderedArrowKey))
    }

    @Test fun `arrow draws in on frame clock restarts on change and snaps under reduce motion`() {
        val cube = Cube3DRenderer()
        var arrow by mutableStateOf<Move?>(null)
        var reduced by mutableStateOf(false)
        rule.setContent { cube.Content(RenderStyle(), Modifier.size(300.dp), arrow = arrow, reduceMotion = reduced) }
        rule.waitForIdle()
        assertNull(config().getOrNull(RenderedArrowKey))
        assertTrue(config()[RenderedStickersKey].all { !it.dimmed && !it.highlighted && !it.pulsing })
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { arrow = FaceTurn(Face.R, Turn.CLOCKWISE) }
        shadowOf(Looper.getMainLooper()).idle()
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        assertEquals(RenderedArrow("R", 0f), config()[RenderedArrowKey])
        rule.mainClock.advanceTimeBy(100)
        assertTrue(config()[RenderedArrowKey].progress in 0.1f..0.9f)
        rule.mainClock.advanceTimeBy(100)
        assertEquals(RenderedArrow("R", 1f), config()[RenderedArrowKey])
        rule.runOnIdle { arrow = FaceTurn(Face.U, Turn.HALF) }
        shadowOf(Looper.getMainLooper()).idle()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        assertEquals(RenderedArrow("U2", 0f), config()[RenderedArrowKey])
        rule.runOnIdle { reduced = true }
        shadowOf(Looper.getMainLooper()).idle()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        assertEquals(RenderedArrow("U2", 1f), config()[RenderedArrowKey])
        rule.runOnIdle { arrow = null }
        shadowOf(Looper.getMainLooper()).idle()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        assertNull(config().getOrNull(RenderedArrowKey))
    }
}
