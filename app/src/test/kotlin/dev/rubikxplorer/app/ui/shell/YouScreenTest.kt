package dev.rubikxplorer.app.ui.shell

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.rubikxplorer.app.content.CatalogState
import dev.rubikxplorer.app.ui.theme.RubikXplorerTheme
import dev.rubikxplorer.app.ui.trend.DotSparkline
import dev.rubikxplorer.app.ui.trend.TrendRow
import dev.rubikxplorer.domain.content.ContentLoadError
import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.content.ContentLoader
import dev.rubikxplorer.domain.content.ContentSource
import dev.rubikxplorer.domain.tutor.AssistanceLevel
import dev.rubikxplorer.domain.tutor.AttemptOutcome
import dev.rubikxplorer.domain.tutor.ProgressEvent
import dev.rubikxplorer.domain.tutor.StageAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class YouScreenTest {
    @get:Rule val rule = createComposeRule()

    private val shipped = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
    private val ready = CatalogState.Ready(shipped)

    @Test
    fun youTabRendersSevenStagesWithStatusBadgesAndTrendRows() {
        var settingsOpened = false
        rule.setContent {
            RubikXplorerTheme {
                YouScreen(
                    catalog = ready,
                    progressEvents = emptyList(),
                    attemptRecords = emptyList(),
                    onSettings = { settingsOpened = true },
                )
            }
        }

        // Header and settings button
        rule.onNodeWithTag("you:screen").assertExists()
        rule.onNodeWithTag("you:title").assertExists()
        rule.onNodeWithTag("you:settings").assertExists().performClick()
        assertTrue(settingsOpened)

        // Stage 1 is IN_PROGRESS
        rule.onNodeWithTag("you:stage:stage-01-fundamentals").assertExists()
        rule.onNodeWithTag("you:stage:title:stage-01-fundamentals").assertTextContains("Cube fundamentals")
        rule.onNodeWithTag("you:stage:status:stage-01-fundamentals").assertTextContains("In progress")
        rule.onNodeWithTag("you:stage:trend:stage-01-fundamentals").assertExists()

        // Stages 2-7 are LOCKED on fresh epoch
        shipped.stages.drop(1).forEach { stage ->
            val stageTag = "you:stage:${stage.id.value}"
            rule.onNodeWithTag(stageTag).performScrollTo().assertExists()
            rule.onNodeWithTag("you:stage:status:${stage.id.value}").assertTextContains("Locked")
            rule.onNodeWithTag("you:stage:trend:${stage.id.value}").assertExists()
        }
    }

    @Test
    fun youTabShowsTrendRowAttemptCountsAndIsolatesEpochs() {
        val stage2Id = shipped.stages[1].id
        val attempts =
            listOf(
                StageAttempt(
                    objectiveId = "practice-${stage2Id.value}",
                    outcome = AttemptOutcome.COMPLETED,
                    assistanceLevel = AssistanceLevel.REDUCED,
                    at = Instant.fromEpochMilliseconds(100),
                    hints = 3,
                ),
                StageAttempt(
                    objectiveId = "practice-${stage2Id.value}",
                    outcome = AttemptOutcome.COMPLETED,
                    assistanceLevel = AssistanceLevel.REDUCED,
                    at = Instant.fromEpochMilliseconds(200),
                    hints = 1,
                ),
                StageAttempt(
                    objectiveId = "practice-${stage2Id.value}",
                    outcome = AttemptOutcome.COMPLETED,
                    assistanceLevel = AssistanceLevel.REDUCED,
                    at = Instant.fromEpochMilliseconds(500),
                    hints = 2,
                ),
            )
        // Reset at 300ms: attempts at 100ms and 200ms are excluded; only the attempt at 500ms counts in epoch 1
        val events =
            listOf(
                ProgressEvent.ProgressReset(epoch = 1L, at = Instant.fromEpochMilliseconds(300)),
            )

        rule.setContent {
            RubikXplorerTheme {
                YouScreen(
                    catalog = ready,
                    progressEvents = events,
                    attemptRecords = attempts,
                    onSettings = {},
                )
            }
        }

        // Stage 2 trend row shows 1 attempt (the attempt after reset)
        rule.onNodeWithTag("you:stage:${stage2Id.value}").performScrollTo().assertExists()
        rule.onNodeWithTag("you:stage:trend:${stage2Id.value}").assertExists()
        rule.onNodeWithTag("you:stage:attempts:${stage2Id.value}").assertTextContains("1")
        rule.onNodeWithTag("you:stage:sparkline:${stage2Id.value}").assertContentDescriptionContains("Trend: 1 attempts, latest 2 hints")
    }

    @Test
    fun youTabDisplaysStageNumbers() {
        rule.setContent {
            RubikXplorerTheme {
                YouScreen(
                    catalog = ready,
                    onSettings = {},
                )
            }
        }
        rule.onNodeWithTag("you:stage:number:stage-01-fundamentals").assertTextContains("1")
        rule.onNodeWithTag("you:stage:number:stage-02-first-cross").performScrollTo().assertTextContains("2")
    }

    @Test
    fun youTabDisplaysErrorOnFailedCatalog() {
        rule.setContent {
            RubikXplorerTheme {
                YouScreen(
                    catalog = CatalogState.Failed(ContentLoadError.MissingResource("bundle.json")),
                    onSettings = {},
                )
            }
        }
        rule.onNodeWithTag("you:error").assertExists()
    }

    @Test
    fun trendRowRendersZeroSingleMultipleAndFallingAttempts() {
        rule.setContent {
            RubikXplorerTheme {
                TrendRow(
                    attemptsCount = 5,
                    hintCounts = listOf(3, 2, 2, 1, 1),
                    modifier = Modifier.testTag("test:trendRow"),
                    label = "Test Stage",
                )
            }
        }

        rule.onNodeWithTag("test:trendRow").assertExists()
        rule.onNodeWithTag("trend:label").assertTextContains("Test Stage")
        rule.onNodeWithTag("trend:sparkline").assertExists().assertContentDescriptionContains("Trend: 5 attempts, latest 1 hints")
        rule.onNodeWithTag("trend:attempts").assertTextContains("5")
    }

    @Test
    fun dotSparklineRendersEmptyAndPopulatedWithoutError() {
        rule.setContent {
            RubikXplorerTheme {
                Column(Modifier.fillMaxWidth()) {
                    DotSparkline(
                        hintCounts = emptyList(),
                        modifier = Modifier.fillMaxWidth().testTag("sparkline:empty"),
                    )
                    DotSparkline(
                        hintCounts = listOf(2),
                        modifier = Modifier.fillMaxWidth().testTag("sparkline:single"),
                    )
                    DotSparkline(
                        hintCounts = listOf(3, 2, 1),
                        modifier = Modifier.fillMaxWidth().testTag("sparkline:multiple"),
                    )
                }
            }
        }

        rule.onNodeWithTag("sparkline:empty").assertIsDisplayed()
        rule.onNodeWithTag("sparkline:single").assertIsDisplayed()
        rule.onNodeWithTag("sparkline:multiple").assertIsDisplayed()
    }
}
