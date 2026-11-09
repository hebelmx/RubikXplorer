package dev.cubentia.app.ui.lesson

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.cubentia.app.ui.theme.CubentiaTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StageMasteryMilestoneTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `stage mastery milestone renders headline, burst, next stage, practice, and continue buttons`() {
        var nextStageClicked = false
        var practiceClicked = false
        var continueClicked = false

        compose.setContent {
            CubentiaTheme {
                FirstSolveMilestone(
                    headline = "First cross mastered.",
                    onPracticeNewScramble = { practiceClicked = true },
                    onContinue = { continueClicked = true },
                    reduceMotion = false,
                    nextStageLabel = "First-layer corners",
                    onNextStage = { nextStageClicked = true },
                )
            }
        }

        compose.onNodeWithTag("milestone:overlay").assertExists()
        compose.onNodeWithTag("milestone:burst").assertExists()
        compose
            .onNodeWithTag("milestone:headline")
            .assertExists()
            .assertTextContains("First cross mastered.")

        compose
            .onNodeWithTag("milestone:nextStage")
            .assertExists()
            .assertHasClickAction()
            .assertTextContains("First-layer corners")
            .performClick()
        assertTrue(nextStageClicked)

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
    fun `stage mastery milestone under reduce motion renders static frame with all controls`() {
        compose.setContent {
            CubentiaTheme {
                FirstSolveMilestone(
                    headline = "First cross mastered.",
                    onPracticeNewScramble = {},
                    onContinue = {},
                    reduceMotion = true,
                    nextStageLabel = "First-layer corners",
                    onNextStage = {},
                )
            }
        }

        compose.onNodeWithTag("milestone:overlay").assertExists()
        compose.onNodeWithTag("milestone:burst").assertExists()
        compose.onNodeWithTag("milestone:headline").assertExists().assertTextContains("First cross mastered.")
        compose.onNodeWithTag("milestone:nextStage").assertExists().assertTextContains("First-layer corners")
        compose.onNodeWithTag("milestone:practice").assertExists()
        compose.onNodeWithTag("milestone:continue").assertExists()
    }

    @Test
    fun `milestone without next stage omits next stage button`() {
        compose.setContent {
            CubentiaTheme {
                FirstSolveMilestone(
                    headline = "Independent practice mastered.",
                    onPracticeNewScramble = {},
                    onContinue = {},
                    reduceMotion = false,
                    nextStageLabel = null,
                    onNextStage = null,
                )
            }
        }

        compose.onNodeWithTag("milestone:nextStage").assertDoesNotExist()
        compose.onNodeWithTag("milestone:practice").assertExists()
        compose.onNodeWithTag("milestone:continue").assertExists()
    }
}
