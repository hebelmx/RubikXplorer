package dev.cubentia.app.ui.solve

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.text.Text
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Story 15.3: the *Solution style* status line renders the shipped copy. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class SolutionStyleChipsTest {
    @get:Rule val rule = createComposeRule()

    private val strings = ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)

    @Test fun pendingFailureAndTimeoutCopy() {
        var state by mutableStateOf(SolutionStyleState(selected = SolutionStyle.BEGINNER, building = SolutionStyle.BEGINNER))
        rule.setContent {
            CubentiaTheme { SolutionStyleChips(state, SolutionStyle.BEGINNER, locked = false, strings = strings, onChoose = {}) }
        }
        rule.onNodeWithTag("solutionStyle:message").assertTextEquals("Building a step-by-step solution…")
        rule.onNodeWithTag("solutionStyle:BEGINNER:building", useUnmergedTree = true).assertExists()

        rule.runOnIdle {
            state = SolutionStyleState(message = Text("msg.playback.style.failed", mapOf("method" to Text(SolutionStyle.TWO_LOOK.label))))
        }
        rule
            .onNodeWithTag(
                "solutionStyle:message",
            ).assertTextEquals("Couldn't build a 2-look solution for this cube. Showing the shortest.")
        rule.onNodeWithTag("solutionStyle:BEGINNER:building", useUnmergedTree = true).assertDoesNotExist()

        rule.runOnIdle { state = SolutionStyleState(message = Text("msg.playback.style.timed-out")) }
        rule
            .onNodeWithTag("solutionStyle:message")
            .assertTextEquals("Couldn't build that solution in time. The shortest one is still here.")
    }
}
