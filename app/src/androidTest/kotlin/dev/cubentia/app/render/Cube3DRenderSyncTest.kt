package dev.cubentia.app.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.preferences.PreferenceProvider
import dev.cubentia.app.preferences.PreferenceStore
import dev.cubentia.app.scan.QuickCheckCapture
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.session.TickingClock
import dev.cubentia.app.session.allMoves
import dev.cubentia.app.session.shippedTutorConfig
import dev.cubentia.app.session.toItems
import dev.cubentia.app.session.whiteCrossLesson
import dev.cubentia.app.ui.solve.SolutionPlayback
import dev.cubentia.app.ui.solve.SolutionPlaybackState
import dev.cubentia.app.ui.solve.rememberSolutionPlaybackState
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.application.preference.PlaybackView
import dev.cubentia.application.session.CubeMode
import dev.cubentia.application.session.Effect
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.application.session.Transition
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.playback.PlaybackSpeed
import dev.cubentia.domain.recognition.PhysicalObservation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random
import kotlin.time.Instant

/**
 * AC 5 (NFR-4): 10,000 seeded random Move / Undo / Redo / Reset steps through [SessionHost] and the
 * pure reducer into [Cube3DRenderer]; the drawn facelets equal `virtual` at every drained step.
 */
@RunWith(AndroidJUnit4::class)
class Cube3DRenderSyncTest {
    @get:Rule
    val rule = createComposeRule()

    private val host = SessionHost(TickingClock())
    private val renderer = Cube3DRenderer()

    private fun dispatch(event: (Instant) -> SessionEvent): Transition =
        runBlocking { host.dispatch(event) }.also { renderer.submit(it.presentation.toItems()) }

    private fun drain() {
        rule.mainClock.advanceTimeBy(2000)
        rule.waitForIdle()
    }

    private fun assertInSync(step: Int) {
        assertEquals(
            "step $step",
            host.state.value.virtual
                .toFacelets(),
            renderer.renderedFacelets(),
        )
    }

    @Test(timeout = 300_000)
    fun tenThousandRandomStepsKeepTheCubeEqualToTheVirtualState() {
        rule.mainClock.autoAdvance = false
        rule.setContent { renderer.Content(RenderStyle(), Modifier.fillMaxSize()) }
        dispatch { SessionEvent.LessonStarted(whiteCrossLesson(), shippedTutorConfig, it) }
        drain()
        assertInSync(0)

        val random = Random(SEED)
        var steps = 0
        val counts = mutableMapOf<String, Int>()
        var pending: Effect.ConfirmReset? = null

        var discards = 0

        fun step(event: (Instant) -> SessionEvent) {
            val discardedBefore = host.state.value.lastDiscarded
            val t = dispatch { at -> event(at).also { counts.merge(it::class.java.simpleName, 1, Int::plus) } }
            steps++
            if (t.state.lastDiscarded != discardedBefore) discards++
            t.effects
                .filterIsInstance<Effect.ConfirmReset>()
                .lastOrNull()
                ?.let { pending = it }
        }

        while (steps < STEPS) {
            repeat(1 + random.nextInt(MAX_BURST)) {
                val asked = pending
                when {
                    // Answer a pending confirmation, sometimes only after a Move made it stale.
                    asked != null && random.nextInt(4) != 0 -> {
                        pending = null
                        if (random.nextBoolean()) {
                            step { SessionEvent.ResetConfirmed(asked.effectId, asked.revision, it) }
                        } else {
                            step { SessionEvent.ResetDeclined(asked.effectId, asked.revision, it) }
                        }
                    }

                    else -> {
                        when (random.nextInt(20)) {
                            in 0..11 -> {
                                allMoves[random.nextInt(allMoves.size)].let { move ->
                                    step { SessionEvent.MoveCommitted(move, it) }
                                }
                            }

                            in 12..14 -> {
                                step(SessionEvent::Undo)
                            }

                            in 15..17 -> {
                                step(SessionEvent::Redo)
                            }

                            else -> {
                                step(SessionEvent::Reset)
                            }
                        }
                    }
                }
            }
            drain()
            assertInSync(steps)
        }

        listOf("MoveCommitted", "Undo", "Redo", "Reset", "ResetConfirmed", "ResetDeclined").forEach {
            assertTrue("no $it in $counts", (counts[it] ?: 0) > 0)
        }
        assertTrue("no stale result was discarded", discards > 0)
        rule.waitUntil(10_000) { renderer.drawnFrames > 0 }
        assertTrue(renderer.drawnFrames > 0)
    }

    private val scramble = (Notation.parse("R U F L D B") as NotationResult.Parsed).moves
    private val solution = scramble.asReversed().map { it.inverse() }
    private val start = CubeState.SOLVED.apply(scramble)
    private lateinit var playback: SolutionPlaybackState
    private val playbackEvents = mutableListOf<SessionEvent>()

    private fun showPlayback(physical: Boolean = false) {
        val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
        val store = PreferenceStore()
        runBlocking {
            host.dispatch { SessionEvent.SolvePlaybackStarted(start, solution, it, bundle.tutorConfig) }
            if (physical) {
                host.dispatch { SessionEvent.ModeSwitched(CubeMode.PHYSICAL, it) }
                host.dispatch { SessionEvent.PhysicalConfirmed(solution.first(), it) }
                val pending = host.dispatch { SessionEvent.QuickCheckRequested(at = it, fullScan = true) }.state.pendingQuickCheck!!
                host.dispatch {
                    SessionEvent.QuickCheckObserved(pending.effectId, pending.revision, PhysicalObservation.Full(start), it)
                }
            }
        }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val current by host.state.collectAsState()
            CubentiaTheme {
                PreferenceProvider(store) {
                    playback =
                        rememberSolutionPlaybackState(
                            current,
                            reduceMotion = false,
                            onEvent = { event ->
                                playbackEvents += event
                                runBlocking { host.dispatch { event } }
                            },
                        )
                    SolutionPlayback(playback, ContentStrings(bundle.strings), Modifier.fillMaxSize(), quickCheck = QuickCheckCapture {})
                }
            }
        }
        drain()
    }

    private fun assertPlaybackInSync(position: Int) {
        assertEquals(position, playback.position)
        assertEquals(start.apply(solution.take(position)).toFacelets(), playback.renderer.renderedFacelets())
    }

    @Test
    fun solutionPlayback3DDrainsForwardBackRestartAndSpeedChanges() {
        showPlayback()
        rule.runOnIdle { playback.stepForward() }
        drain()
        assertPlaybackInSync(1)
        rule.runOnIdle { playback.stepBack() }
        drain()
        assertPlaybackInSync(0)
        rule.runOnIdle {
            playback.stepForward()
            playback.stepForward()
        }
        drain()
        assertPlaybackInSync(2)
        rule.runOnIdle {
            playback.replay()
            playback.togglePlay()
        }
        drain()
        assertPlaybackInSync(1)

        PlaybackSpeed.entries.forEachIndexed { index, speed ->
            rule.runOnIdle {
                playback.setSpeed(speed)
                playback.stepForward()
            }
            drain()
            assertPlaybackInSync(index + 2)
        }
        assertTrue(playbackEvents.isEmpty())
    }

    @Test
    fun switchingEitherWayDuringATurnSnapsToTheCommittedStep() {
        showPlayback()
        rule.runOnIdle { playback.togglePlay() }
        rule.mainClock.advanceTimeBy(96)
        assertEquals(1, playback.position)
        assertEquals(solution.first(), (playback.renderer as Cube3DRenderer).presentedMove)
        switchView()
        assertEquals(PlaybackView.NET_2D, playback.view)
        assertPlaybackInSync(1)
        assertTrue(playback.isPlaying)

        rule.runOnIdle { playback.advance(playback.speed.stepMillis) }
        rule.mainClock.advanceTimeBy(48)
        assertEquals(2, playback.position)
        switchView()
        assertEquals(PlaybackView.CUBE_3D, playback.view)
        assertPlaybackInSync(2)
        assertTrue(playback.isPlaying)
        assertTrue(playbackEvents.isEmpty())
    }

    @Test
    fun physicalSwitchKeepsTheStepControlsAndQuickCheckEvidence() {
        showPlayback(physical = true)
        rule.onNodeWithTag("net").assertExists()
        assertPhysicalEvidence()
        assertPlaybackInSync(1)

        switchView()
        rule.onNodeWithTag("cube3d").assertExists()
        assertPhysicalEvidence()
        assertPlaybackInSync(1)
        assertTrue(playbackEvents.isEmpty())
    }

    private fun switchView() {
        rule.onNodeWithTag("playback:viewSwitch").performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    private fun assertPhysicalEvidence() {
        rule.onNodeWithTag("stage:frame").assertTextEquals("EXPECTED")
        rule.onNodeWithTag("certainty:label").assertExists()
        rule.onNodeWithTag("stage:seen").assertExists()
        rule.onNodeWithTag("playback:quickCheck").assertExists()
        rule.onNodeWithTag("playback:done").assertIsEnabled()
        rule.onNodeWithTag("playback:back").assertIsEnabled()
    }

    private companion object {
        const val SEED = 20261030L
        const val STEPS = 10_000
        const val MAX_BURST = 5
    }
}
