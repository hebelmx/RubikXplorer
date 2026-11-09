package dev.cubentia.app.ui.lesson

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.cubentia.app.ui.theme.CubentiaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirstSolveMilestoneTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `milestone renders headline, burst, and practice and continue buttons`() {
        var practiceClicked = false
        var continueClicked = false

        compose.setContent {
            CubentiaTheme {
                FirstSolveMilestone(
                    headline = "You solved the cube!",
                    onPracticeNewScramble = { practiceClicked = true },
                    onContinue = { continueClicked = true },
                    reduceMotion = false,
                )
            }
        }

        compose.onNodeWithTag("milestone:overlay").assertExists()
        compose.onNodeWithTag("milestone:burst").assertExists()
        compose
            .onNodeWithTag("milestone:headline")
            .assertExists()
            .assertTextContains("You solved the cube!")

        compose
            .onNodeWithTag("milestone:practice")
            .assertExists()
            .assertHasClickAction()
            .assertTextContains("Practice on a new scramble")
            .performClick()
        assertTrue(practiceClicked)

        compose
            .onNodeWithTag("milestone:continue")
            .assertExists()
            .assertHasClickAction()
            .assertTextContains("Continue")
            .performClick()
        assertTrue(continueClicked)
    }

    @Test
    fun `milestone under reduce motion renders static frame with same controls`() {
        compose.setContent {
            CubentiaTheme {
                FirstSolveMilestone(
                    headline = "You solved the cube!",
                    onPracticeNewScramble = {},
                    onContinue = {},
                    reduceMotion = true,
                )
            }
        }

        compose.onNodeWithTag("milestone:overlay").assertExists()
        compose.onNodeWithTag("milestone:burst").assertExists()
        compose.onNodeWithTag("milestone:headline").assertExists()
        compose.onNodeWithTag("milestone:practice").assertExists()
        compose.onNodeWithTag("milestone:continue").assertExists()
    }
}
