package dev.cubentia.app.ui.lesson

import android.app.Application
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.session.TickingClock
import dev.cubentia.app.session.shippedTutorConfig
import dev.cubentia.app.session.whiteCrossLesson
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.application.session.SessionState
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Turn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** AC 6: Reset asks only when Lesson steps would be lost; confirm resets, cancel keeps the cube. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class ResetConfirmationTest {
    @get:Rule
    val rule = createComposeRule()

    private val host = SessionHost(TickingClock())
    private val strings = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings
    private val r = FaceTurn(Face.R, Turn.CLOCKWISE)

    @Before
    fun setUp() {
        runBlocking { host.dispatch { SessionEvent.LessonStarted(whiteCrossLesson(), shippedTutorConfig, it) } }
        rule.setContent {
            CubentiaTheme {
                val scope = rememberCoroutineScope()
                Column {
                    // Story 2.5 places the real Reset button in the Lesson screen.
                    Button(onClick = { scope.launch { host.dispatch(SessionEvent::Reset) } }, modifier = Modifier.testTag("reset")) {
                        Text("R")
                    }
                    ResetConfirmation(host = host, resolve = { strings.getValue(it.key) })
                }
            }
        }
    }

    private fun state(): SessionState = host.state.value

    private fun commit() = runBlocking { host.dispatch { SessionEvent.MoveCommitted(r, it) } }

    private fun settle(condition: () -> Boolean) {
        rule.waitUntil(timeoutMillis = 5_000, condition = condition)
        rule.waitForIdle()
    }

    private fun tapReset() = rule.onNodeWithTag("reset").performClick()

    @Test
    fun `with no unsaved steps Reset happens without asking`() {
        commit()
        runBlocking { host.dispatch(SessionEvent::Undo) }
        val before = state()
        assertTrue(before.history.canRedo)
        tapReset()
        // Nothing to lose: the reset applies at once (redo cleared), and no dialog appears.
        settle { state().revision == before.revision + 1 }
        assertNull(state().pendingReset)
        assertFalse(state().history.canRedo)
        rule.onNodeWithTag("resetConfirmation").assertDoesNotExist()
        assertEquals(before.entry, state().virtual)
    }

    @Test
    fun `with unsaved steps Reset asks with the content prompt and confirming resets to entry`() {
        commit()
        tapReset()
        settle { state().pendingReset != null }
        rule.onNodeWithTag("resetConfirmation:message").assertTextEquals("Reset the cube? This lesson's unsaved steps will be lost.")
        rule.onNodeWithTag("resetConfirmation:confirm").assertTextEquals("Reset")
        rule.onNodeWithTag("resetConfirmation:cancel").assertTextEquals("Cancel")
        assertEquals(state().entry.apply(r), state().virtual)

        rule.onNodeWithTag("resetConfirmation:confirm").performClick()
        settle { state().pendingReset == null }
        assertEquals(state().entry, state().virtual)
        assertFalse(state().hasUnsavedSteps)
        rule.onNodeWithTag("resetConfirmation").assertDoesNotExist()
    }

    @Test
    fun `cancelling keeps the cube and its steps`() {
        commit()
        val moved = state().virtual
        tapReset()
        settle { state().pendingReset != null }
        rule.onNodeWithTag("resetConfirmation:cancel").performClick()
        settle { state().pendingReset == null }
        assertEquals(moved, state().virtual)
        assertEquals(1, state().history.done.size)
        rule.onNodeWithTag("resetConfirmation").assertDoesNotExist()
    }

    @Test
    fun `dismissing with back keeps the cube and its steps`() {
        commit()
        val moved = state().virtual
        tapReset()
        settle { state().pendingReset != null }
        rule.onNodeWithTag("resetConfirmation").assertExists()
        // A system back press on the dialog window, which Compose routes to onDismissRequest.
        (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        settle { state().pendingReset == null }
        assertEquals(moved, state().virtual)
        assertEquals(1, state().history.done.size)
        rule.onNodeWithTag("resetConfirmation").assertDoesNotExist()
    }
}
