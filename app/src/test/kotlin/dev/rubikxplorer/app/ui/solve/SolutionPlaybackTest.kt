package dev.rubikxplorer.app.ui.solve

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.rubikxplorer.app.content.ContentStrings
import dev.rubikxplorer.app.ui.theme.RubikXplorerTheme
import dev.rubikxplorer.application.session.SessionEvent
import dev.rubikxplorer.application.session.SessionState
import dev.rubikxplorer.application.session.reduce
import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.content.ContentLoader
import dev.rubikxplorer.domain.content.ContentSource
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/** Story 3.6 AC 2: a `SOLVE_PLAYBACK` session's Solution through the shared transport, with generated notes. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class SolutionPlaybackTest {
    @get:Rule
    val rule = createComposeRule()

    private val strings =
        ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)

    private val r = FaceTurn(Face.R, Turn.CLOCKWISE)
    private val u = FaceTurn(Face.U, Turn.CLOCKWISE)
    private val f = FaceTurn(Face.F, Turn.CLOCKWISE)
    private val scrambled = CubeState.SOLVED.apply(r, u, f)
    private val solution: List<Move> = listOf(f.inverse(), u.inverse(), r.inverse())

    private lateinit var state: SolutionPlaybackState

    private fun session(
        start: CubeState = scrambled,
        moves: List<Move> = solution,
    ): SessionState = reduce(SessionState.IDLE, SessionEvent.SolvePlaybackStarted(start, moves, Instant.fromEpochMilliseconds(0))).state

    private fun show(session: SessionState = session()) {
        rule.setContent {
            RubikXplorerTheme {
                state = rememberSolutionPlaybackState(session, reduceMotion = true)
                SolutionPlayback(state, strings, Modifier.fillMaxSize())
            }
        }
        rule.waitForIdle()
    }

    private fun tag(name: String) = rule.onNodeWithTag("playback:$name")

    private fun click(name: String) {
        tag(name).performClick()
        rule.waitForIdle()
    }

    @Test
    fun `it starts at the session's entry with the first Move and its note`() {
        show()
        rule.onNodeWithTag("solutionPlayback").assertExists()
        assertEquals(scrambled.toFacelets(), state.renderer.renderedFacelets())
        tag("counter").assertTextEquals("0 / 3")
        tag("move").assertTextEquals("F'")
        tag("note").assertTextEquals("Turn the Front face counter-clockwise: the front layer turns to the left.")
        tag("back").assertIsNotEnabled()
        tag("forward").assertIsEnabled()
    }

    @Test
    fun `step forward and back move the counter by one, with the current Move's note`() {
        show()
        click("forward")
        tag("counter").assertTextEquals("1 / 3")
        tag("move").assertTextEquals("F'")
        click("forward")
        tag("counter").assertTextEquals("2 / 3")
        tag("move").assertTextEquals("U'")
        tag("note").assertTextEquals("Turn the Up face counter-clockwise: the top row moves right.")
        assertEquals(scrambled.apply(solution.take(2)).toFacelets(), state.renderer.renderedFacelets())
        click("back")
        tag("counter").assertTextEquals("1 / 3")
        tag("move").assertTextEquals("F'")
        assertEquals(scrambled.apply(solution.take(1)).toFacelets(), state.renderer.renderedFacelets())
    }

    @Test
    fun `the final step draws the solved state and disables forward and end`() {
        show()
        repeat(3) { click("forward") }
        tag("counter").assertTextEquals("3 / 3")
        tag("move").assertTextEquals("R'")
        tag("note").assertTextEquals("Turn the Right face counter-clockwise: the right column moves down.")
        assertTrue(state.atEnd)
        assertEquals(CubeState.SOLVED.toFacelets(), state.renderer.renderedFacelets())
        tag("forward").assertIsNotEnabled()
        tag("end").assertIsNotEnabled()
    }

    @Test
    fun `end jumps to the solved state`() {
        show()
        click("end")
        tag("counter").assertTextEquals("3 / 3")
        assertEquals(CubeState.SOLVED.toFacelets(), state.renderer.renderedFacelets())
    }

    @Test
    fun `pause stops where it is and replay snaps to the start and plays again`() {
        show()
        rule.mainClock.autoAdvance = false
        tag("playPause").performClick()
        rule.mainClock.advanceTimeByFrame()
        tag("counter").assertTextEquals("1 / 3")
        assertTrue(state.isPlaying)
        tag("playPause").performClick()
        rule.mainClock.advanceTimeBy(2_000)
        assertFalse(state.isPlaying)
        tag("counter").assertTextEquals("1 / 3")

        rule.mainClock.autoAdvance = true
        click("end")
        tag("counter").assertTextEquals("3 / 3")
        rule.mainClock.autoAdvance = false
        tag("restart").performClick()
        rule.mainClock.advanceTimeByFrame()
        assertTrue(state.isPlaying)
        tag("counter").assertTextEquals("1 / 3")
        assertEquals(scrambled.apply(solution.take(1)).toFacelets(), state.renderer.renderedFacelets())
    }

    @Test
    fun `the note is a polite live region`() {
        show()
        tag("note").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun `a new session starts the playback over`() {
        var current by mutableStateOf(session())
        rule.setContent {
            RubikXplorerTheme {
                state = rememberSolutionPlaybackState(current, reduceMotion = true)
                SolutionPlayback(state, strings, Modifier.fillMaxSize())
            }
        }
        rule.waitForIdle()
        click("forward")
        tag("counter").assertTextEquals("1 / 3")

        val other = CubeState.SOLVED.apply(r)
        current = reduce(current, SessionEvent.SolvePlaybackStarted(other, listOf(r.inverse()), Instant.fromEpochMilliseconds(1))).state
        rule.waitForIdle()
        tag("counter").assertTextEquals("0 / 1")
        tag("move").assertTextEquals("R'")
        assertEquals(other.toFacelets(), state.renderer.renderedFacelets())

        click("forward")
        tag("counter").assertTextEquals("1 / 1")
        current = reduce(current, SessionEvent.SolvePlaybackStarted(other, listOf(r.inverse()), Instant.fromEpochMilliseconds(2))).state
        rule.waitForIdle()
        tag("counter").assertTextEquals("0 / 1")
        tag("move").assertTextEquals("R'")
        assertEquals(other.toFacelets(), state.renderer.renderedFacelets())
    }

    @Test
    fun `an empty Solution disables the transport and shows no note`() {
        show(session(CubeState.SOLVED, emptyList()))
        listOf("restart", "back", "playPause", "forward", "end").forEach { tag(it).assertIsNotEnabled() }
        tag("counter").assertTextEquals("0 / 0")
        tag("move").assertTextEquals("")
        tag("note").assertDoesNotExist()
        assertEquals(CubeState.SOLVED.toFacelets(), state.renderer.renderedFacelets())
    }

    @Test
    fun `only a Solve Playback session can be played`() {
        assertThrows(IllegalArgumentException::class.java) { SolutionPlaybackState(SessionState.IDLE, reduceMotion = true) }
    }
}
