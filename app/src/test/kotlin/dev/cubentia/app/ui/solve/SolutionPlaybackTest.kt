package dev.cubentia.app.ui.solve

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.session.TickingClock
import dev.cubentia.app.ui.lesson.DashedBorderKey
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.application.session.SessionState
import dev.cubentia.application.session.SyncState
import dev.cubentia.application.session.reduce
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Turn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
        val host = SessionHost(TickingClock(), Dispatchers.Unconfined)
        runBlocking { host.dispatch { SessionEvent.SolvePlaybackStarted(session.entry, session.solution, it) } }
        rule.setContent {
            val current by host.state.collectAsState()
            CubentiaTheme {
                state =
                    rememberSolutionPlaybackState(current, reduceMotion = true, onEvent = { event ->
                        runBlocking { host.dispatch { event } }
                    })
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
            CubentiaTheme {
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

    @Test
    fun `tapping follow on cube enters physical mode and each done advances one step with MoveNote`() {
        show()
        tag("followOnMyCube").assertTextEquals("I'm following on my cube")
        click("followOnMyCube")
        tag("done").assertTextEquals("Done")
        assertTrue(state.isPhysical)
        assertEquals(SyncState.PHYSICAL_PREDICTED, state.session.syncState)
        rule.onNodeWithTag("stage:frame").assertTextEquals("EXPECTED")
        assertTrue(rule.onNodeWithTag("stage:frame").fetchSemanticsNode().config[DashedBorderKey])

        // First step (0 / 3) awaiting F'
        tag("counter").assertTextEquals("0 / 3")
        tag("move").assertTextEquals("F'")
        tag("note").assertTextEquals("Turn the Front face counter-clockwise: the front layer turns to the left.")
        tag("back").assertIsNotEnabled()
        tag("done").assertIsEnabled()

        // Done advances to step 1 (1 / 3) awaiting U'
        click("done")
        assertEquals(1, state.position)
        assertEquals(scrambled.apply(solution.take(1)), state.session.expected)
        assertNull(state.session.observed)
        tag("counter").assertTextEquals("1 / 3")
        tag("move").assertTextEquals("U'")
        tag("note").assertTextEquals("Turn the Up face counter-clockwise: the top row moves right.")
        tag("back").assertIsEnabled()
        tag("done").assertIsEnabled()

        // Done advances to step 2 (2 / 3) awaiting R'
        click("done")
        assertEquals(2, state.position)
        assertEquals(scrambled.apply(solution.take(2)), state.session.expected)
        assertNull(state.session.observed)
        tag("counter").assertTextEquals("2 / 3")
        tag("move").assertTextEquals("R'")
        tag("note").assertTextEquals("Turn the Right face counter-clockwise: the right column moves down.")
        tag("back").assertIsEnabled()
        tag("done").assertIsEnabled()
    }

    @Test
    fun `confirming final step says cube should be solved without asserting observed`() {
        show()
        click("followOnMyCube")
        repeat(3) { click("done") }

        assertEquals(3, state.position)
        assertTrue(state.atEnd)
        assertEquals(CubeState.SOLVED, state.session.expected)
        assertNull(state.session.observed)
        assertEquals(SyncState.PHYSICAL_PREDICTED, state.session.syncState)
        rule.onNodeWithTag("stage:frame").assertTextEquals("EXPECTED")
        assertTrue(rule.onNodeWithTag("stage:frame").fetchSemanticsNode().config[DashedBorderKey])

        tag("counter").assertTextEquals("3 / 3")
        tag("move").assertTextEquals("")
        tag("note").assertDoesNotExist()
        tag("done").assertIsNotEnabled()
        tag("solvedNow").assertTextEquals("Your cube should be solved now.")
        tag("solvedNow").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        assertEquals(CubeState.SOLVED.toFacelets(), state.renderer.renderedFacelets())
    }

    @Test
    fun `stepping back in physical playback decrements position and redisplays move note`() {
        show()
        click("followOnMyCube")
        click("done")
        tag("counter").assertTextEquals("1 / 3")
        tag("move").assertTextEquals("U'")
        assertEquals(scrambled.apply(solution.take(1)), state.session.expected)

        click("back")
        tag("counter").assertTextEquals("0 / 3")
        tag("move").assertTextEquals("F'")
        tag("note").assertTextEquals("Turn the Front face counter-clockwise: the front layer turns to the left.")
        tag("back").assertIsNotEnabled()
        tag("done").assertIsEnabled()
        assertEquals(scrambled, state.session.expected)

        repeat(3) { click("done") }
        assertEquals(CubeState.SOLVED, state.session.expected)
        tag("solvedNow").assertTextEquals("Your cube should be solved now.")
    }

    @Test
    fun `entering physical mode after virtual progression rewinds position and pauses`() {
        show()
        click("forward")
        assertEquals(1, state.position)

        click("followOnMyCube")
        assertTrue(state.isPhysical)
        assertEquals(0, state.position)
        assertEquals(scrambled, state.session.expected)
        assertFalse(state.isPlaying)
        tag("counter").assertTextEquals("0 / 3")
        tag("move").assertTextEquals("F'")
    }

    @Test
    fun `toggling physical mode via switch alternates between virtual and physical`() {
        show()
        click("followOnMyCube")
        assertTrue(state.isPhysical)

        tag("physicalModeSwitch").performClick()
        rule.waitForIdle()
        assertFalse(state.isPhysical)
        assertEquals(SyncState.VIRTUAL, state.session.syncState)
    }

    @Test
    fun `active autoplay pauses when entering physical mode`() {
        show()
        rule.mainClock.autoAdvance = false
        tag("playPause").performClick()
        rule.mainClock.advanceTimeByFrame()
        assertTrue(state.isPlaying)

        tag("followOnMyCube").performClick()
        rule.mainClock.advanceTimeByFrame()
        assertFalse(state.isPlaying)
        assertTrue(state.isPhysical)
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun `switching back to virtual restores virtual state and note`() {
        show()
        click("followOnMyCube")
        click("done")
        assertTrue(state.isPhysical)

        tag("switchToVirtual").assertTextEquals("Physical mode")
        click("switchToVirtual")
        assertFalse(state.isPhysical)
        assertEquals(SyncState.VIRTUAL, state.session.syncState)
        assertEquals(state.session.expected, state.session.virtual)
        assertNull(state.session.observed)
        tag("restoreNote").assertTextEquals("The on-screen cube now matches what we expected on yours.")
        tag("playPause").assertExists()
    }

    @Test
    fun `empty solution in physical mode displays solved now notice without asserting observed`() {
        show(session(CubeState.SOLVED, emptyList()))
        click("followOnMyCube")
        assertTrue(state.isPhysical)
        assertTrue(state.atEnd)
        tag("counter").assertTextEquals("0 / 0")
        tag("move").assertTextEquals("")
        tag("note").assertDoesNotExist()
        tag("done").assertIsNotEnabled()
        tag("solvedNow").assertTextEquals("Your cube should be solved now.")
        assertNull(state.session.observed)
        assertEquals(SyncState.PHYSICAL_PREDICTED, state.session.syncState)
    }

    @Test
    fun `re-entering physical follow after confirmed progress keeps position and expected aligned`() {
        show()
        click("followOnMyCube")
        assertTrue(state.isPhysical)
        assertEquals(0, state.position)
        assertEquals(scrambled, state.session.expected)

        // Done x 2 (N = 2)
        click("done")
        assertEquals(1, state.position)
        assertEquals(scrambled.apply(solution.take(1)), state.session.expected)

        click("done")
        assertEquals(2, state.position)
        assertEquals(scrambled.apply(solution.take(2)), state.session.expected)

        // Switch to virtual
        click("switchToVirtual")
        assertFalse(state.isPhysical)
        assertEquals(2, state.position)
        assertEquals(scrambled.apply(solution.take(2)), state.session.expected)

        // Follow again: should keep position and expected aligned (no rewinding to 0)
        click("followOnMyCube")
        assertTrue(state.isPhysical)
        assertEquals(2, state.position)
        assertEquals(scrambled.apply(solution.take(2)), state.session.expected)
        tag("counter").assertTextEquals("2 / 3")
        tag("move").assertTextEquals("R'")

        // Confirm final move to reach solved
        click("done")
        assertEquals(3, state.position)
        assertTrue(state.atEnd)
        assertEquals(CubeState.SOLVED, state.session.expected)
        tag("solvedNow").assertTextEquals("Your cube should be solved now.")
        assertEquals(CubeState.SOLVED.toFacelets(), state.renderer.renderedFacelets())
    }

    @Test
    fun `re-entering physical follow after virtual scrub re-aligns position to expected`() {
        show()
        click("followOnMyCube")
        click("done")
        assertEquals(1, state.position)
        assertEquals(scrambled.apply(solution.take(1)), state.session.expected)

        click("switchToVirtual")
        assertFalse(state.isPhysical)

        // User scrubs in virtual mode
        click("back")
        assertEquals(0, state.position)

        // Follow again: re-aligns playback position to expected (1)
        click("followOnMyCube")
        assertTrue(state.isPhysical)
        assertEquals(1, state.position)
        assertEquals(scrambled.apply(solution.take(1)), state.session.expected)
        tag("counter").assertTextEquals("1 / 3")
        tag("move").assertTextEquals("U'")
    }

    @Test
    fun physicalStepBackStopsAtHostUndoBarrier() {
        show()
        click("followOnMyCube")
        click("done")
        click("switchToVirtual")
        click("followOnMyCube")
        tag("back").assertIsNotEnabled()
        rule.runOnIdle {
            assertFalse(state.session.history.canUndo)
            assertFalse(state.canStepBack)
            var dispatched = false
            state.onEvent = { dispatched = true }
            state.stepBack()
            assertEquals(1, state.position)
            assertFalse(dispatched)
        }
    }

    @Test
    fun aDoubleBackBeforeTheHostAcknowledgesCannotCrossTheUndoBarrier() {
        show()
        click("followOnMyCube")
        click("done")
        click("switchToVirtual")
        click("followOnMyCube")
        rule.runOnIdle {
            // A delayed host: events queue up and only reach the reducer when acknowledged.
            val queued = mutableListOf<SessionEvent>()
            val delayed = SolutionPlaybackState(state.session, reduceMotion = true, onEvent = { queued += it })

            fun acknowledge() {
                var host = delayed.session
                queued.forEach { host = reduce(host, it).state }
                queued.clear()
                delayed.updateSession(host)
            }
            assertEquals(1, delayed.position)
            assertFalse(delayed.session.history.canUndo)

            delayed.confirmStep()
            acknowledge()
            assertEquals(2, delayed.position)
            assertTrue(delayed.canStepBack)

            delayed.stepBack()
            assertFalse(delayed.canStepBack)
            delayed.stepBack()
            assertEquals(1, delayed.position)
            assertEquals(1, queued.size)

            acknowledge()
            assertEquals(1, delayed.position)
            assertEquals(scrambled.apply(solution.take(1)), delayed.session.expected)
            assertFalse(delayed.canStepBack)
        }
    }

    @Test
    fun aStateRebuiltMidFollowStartsAtTheHostExpectedStep() {
        show()
        click("followOnMyCube")
        click("done")
        click("done")
        rule.runOnIdle {
            val rebuilt = SolutionPlaybackState(state.session, reduceMotion = true)
            assertTrue(rebuilt.isPhysical)
            assertEquals(2, rebuilt.position)
            assertEquals(solution[2], rebuilt.currentMove)
        }
    }

    @Test
    fun aStateRebuiltAfterReturningToVirtualStillPlaysFromTheSolutionStart() {
        show()
        click("followOnMyCube")
        click("done")
        click("done")
        click("switchToVirtual")
        rule.runOnIdle {
            assertNotEquals(scrambled, state.session.entry)
            val rebuilt = SolutionPlaybackState(state.session, reduceMotion = true)
            assertFalse(rebuilt.isPhysical)
            assertEquals(0, rebuilt.position)
            assertEquals(scrambled.toFacelets(), rebuilt.renderer.renderedFacelets())
        }
    }

    @Test fun netFitsPhone() = assertNetFits()

    @Test
    @Config(qualifiers = "w411dp-h480dp")
    fun netFitsShortPhone() = assertNetFits()

    private fun assertNetFits() {
        rule.setContent {
            CubentiaTheme { SolvePlaybackScreen(session(), strings, {}, {}, {}) }
        }
        val netNode = rule.onNodeWithTag("net").fetchSemanticsNode()
        val origin = netNode.positionInRoot
        val net =
            androidx.compose.ui.geometry.Rect(
                origin,
                androidx.compose.ui.geometry
                    .Size(netNode.size.width.toFloat(), netNode.size.height.toFloat()),
            )
        val parent = rule.onNodeWithTag("solutionPlayback").fetchSemanticsNode().boundsInRoot
        assertTrue(net.width > 0 && net.height > 0)
        assertTrue(net.left >= parent.left && net.right <= parent.right && net.top >= parent.top && net.bottom <= parent.bottom)
        tag("back").assertIsNotEnabled()
        tag("playPause").assertIsEnabled()
        tag("forward").assertIsEnabled()
        val note = tag("note").fetchSemanticsNode()
        assertTrue(note.boundsInRoot.left >= 16 * note.layoutInfo.density.density)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun darkPlaybackPaintsThemeSurface() {
        rule.setContent {
            CubentiaTheme(darkTheme = true) { SolvePlaybackScreen(session(), strings, {}, {}, {}) }
        }
        val pixels = rule.onNodeWithTag("solvePlayback:screen").captureToImage().toPixelMap()
        assertEquals(dev.cubentia.app.ui.theme.DarkTokens.surfaceBase, pixels[0, 0])
    }

    @Test fun physicalPlaybackNoteHasStandardMargin() {
        show()
        click("followOnMyCube")
        val note = tag("note").fetchSemanticsNode()
        assertTrue(note.boundsInRoot.left >= 16 * note.layoutInfo.density.density)
    }

    @Test fun transportColorsReflectEnabledStateAtStart() {
        show()
        listOf(
            "back" to dev.cubentia.app.ui.theme.LightTokens.inkDisabled,
            "playPause" to dev.cubentia.app.ui.theme.LightTokens.inkPrimary,
            "forward" to dev.cubentia.app.ui.theme.LightTokens.inkPrimary,
        ).forEach { (name, color) ->
            val texts =
                rule
                    .onAllNodes(
                        androidx.compose.ui.test
                            .hasAnyAncestor(
                                androidx.compose.ui.test
                                    .hasTestTag("playback:$name"),
                            ) and
                            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult),
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes()
            assertTrue(texts.isNotEmpty())
            texts.forEach { node ->
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
                assertEquals(
                    color,
                    results
                        .single()
                        .layoutInput.style.color,
                )
            }
        }
    }
}
