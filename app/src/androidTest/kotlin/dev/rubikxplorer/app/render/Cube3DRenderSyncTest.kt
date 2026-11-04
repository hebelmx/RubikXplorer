package dev.rubikxplorer.app.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rubikxplorer.app.render.Cube3DRenderer
import dev.rubikxplorer.app.render.RenderStyle
import dev.rubikxplorer.app.session.SessionHost
import dev.rubikxplorer.app.session.TickingClock
import dev.rubikxplorer.app.session.allMoves
import dev.rubikxplorer.app.session.shippedTutorConfig
import dev.rubikxplorer.app.session.toItems
import dev.rubikxplorer.app.session.whiteCrossLesson
import dev.rubikxplorer.application.session.Effect
import dev.rubikxplorer.application.session.SessionEvent
import dev.rubikxplorer.application.session.Transition
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

    private companion object {
        const val SEED = 20261030L
        const val STEPS = 10_000
        const val MAX_BURST = 5
    }
}
