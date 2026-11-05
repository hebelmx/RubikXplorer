package dev.rubikxplorer.app.ui.playback

import dev.rubikxplorer.app.render.CubeRenderer
import dev.rubikxplorer.app.render.PresentationItem
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Facelets
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.NotationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The revision-to-Move mapping stays bounded by what the renderer may still present. */
class NetPlaybackTest {
    private val moves = (Notation.parse("R U F") as NotationResult.Parsed).moves

    /** Records submissions; [oldestLive] stands in for the renderer's queue. */
    private class FakeRenderer : CubeRenderer {
        val submitted = mutableListOf<PresentationItem>()
        var oldestLive: Long? = null

        override fun submit(items: List<PresentationItem>) {
            submitted += items
        }

        override fun renderedFacelets(): Facelets = CubeState.SOLVED.toFacelets()
    }

    private fun drive(playback: NetPlayback) {
        repeat(200) {
            playback.stepForward()
            playback.stepForward()
            playback.stepBack()
            playback.replay()
            playback.advance(10_000)
        }
    }

    @Test
    fun `drained mappings are discarded, so repeated replay and stepping stay bounded`() {
        val renderer = FakeRenderer()
        val playback = NetPlayback(CubeState.SOLVED, moves, reduceMotion = false, renderer) { renderer.oldestLive }
        drive(playback)
        // Idle renderer: only the items of the last submission can still be presented.
        assertTrue(playback.mappedRevisions <= moves.size + 1)
        val last = renderer.submitted.last { it is PresentationItem.Animate }
        assertTrue(playback.moveIndexOf(last.revision) != null)
    }

    @Test
    fun `mappings the renderer may still present survive the next submission`() {
        val renderer = FakeRenderer()
        val playback = NetPlayback(CubeState.SOLVED, moves, reduceMotion = false, renderer) { renderer.oldestLive }
        playback.stepForward()
        val first = renderer.submitted.single().revision
        renderer.oldestLive = first
        playback.stepForward()
        playback.stepForward()
        assertEquals(0, playback.moveIndexOf(first))
        assertEquals(3, playback.mappedRevisions)
        // Once the renderer moves on, the earlier ones go.
        renderer.oldestLive = renderer.submitted.last().revision
        playback.stepBack()
        assertEquals(null, playback.moveIndexOf(first))
        assertEquals(2, playback.mappedRevisions)
    }

    @Test
    fun `without a consumer no mapping is kept`() {
        val playback = NetPlayback(CubeState.SOLVED, moves, reduceMotion = false, FakeRenderer())
        drive(playback)
        assertEquals(0, playback.mappedRevisions)
    }
}
