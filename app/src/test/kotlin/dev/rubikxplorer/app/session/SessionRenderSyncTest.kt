package dev.rubikxplorer.app.session

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.rubikxplorer.app.render.Net2DRenderer
import dev.rubikxplorer.app.render.RenderStyle
import dev.rubikxplorer.application.session.Effect
import dev.rubikxplorer.application.session.SessionEvent
import dev.rubikxplorer.application.session.Transition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random
import kotlin.time.Instant

/**
 * AC 5 (NFR-4): 10,000 seeded random Move / Undo / Redo / Reset steps through [SessionHost] and the
 * pure reducer into [Net2DRenderer]; the drawn facelets equal `virtual` at every drained step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class SessionRenderSyncTest {
    @get:Rule
    val rule = createComposeRule()

    private val host = SessionHost(TickingClock())
    private val renderer = Net2DRenderer()

    private fun dispatch(event: (Instant) -> SessionEvent): Transition =
        runBlocking { host.dispatch(event) }.also { renderer.submit(it.presentation.toItems()) }

    private fun assertInSync(step: Int) {
        assertEquals(
            "step $step",
            host.state.value.virtual
                .toFacelets(),
            renderer.renderedFacelets(),
        )
    }

    @Test(timeout = 300_000)
    fun `ten thousand random steps keep the net equal to the virtual state`() {
        rule.setContent { renderer.Content(RenderStyle(), Modifier.fillMaxSize()) }
        dispatch { SessionEvent.LessonStarted(whiteCrossLesson(), shippedTutorConfig, it) }
        rule.waitForIdle()
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
            rule.waitForIdle()
            assertInSync(steps)
        }

        listOf("MoveCommitted", "Undo", "Redo", "Reset", "ResetConfirmed", "ResetDeclined").forEach {
            assertTrue("no $it in $counts", (counts[it] ?: 0) > 0)
        }
        assertTrue("no stale result was discarded", discards > 0)
    }

    private companion object {
        const val SEED = 20261030L
        const val STEPS = 10_000
        const val MAX_BURST = 5
    }
}
