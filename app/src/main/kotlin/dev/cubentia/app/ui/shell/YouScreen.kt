package dev.cubentia.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.cubentia.app.R
import dev.cubentia.app.content.CatalogState
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.app.ui.trend.TrendRow
import dev.cubentia.domain.tutor.Mastery
import dev.cubentia.domain.tutor.MasteryAttempt
import dev.cubentia.domain.tutor.ProgressEvent
import dev.cubentia.domain.tutor.StageProgress
import dev.cubentia.domain.tutor.StageStatus
import dev.cubentia.domain.tutor.StageTrendCalculator

@Composable
fun YouScreen(
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    catalog: CatalogState = CatalogState.Loading,
    progressEvents: List<ProgressEvent> = emptyList(),
    attemptRecords: List<MasteryAttempt> = emptyList(),
) {
    val tokens = RubikTheme.tokens
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(tokens.surfaceBase)
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .testTag("you:screen"),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.s4, vertical = Spacing.s2),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.nav_you),
                style = MaterialTheme.typography.titleLarge,
                color = tokens.inkPrimary,
                modifier = Modifier.testTag("you:title"),
            )
            TextButton(
                onClick = onSettings,
                modifier =
                    Modifier
                        .heightIn(min = Spacing.touchMin)
                        .testTag("you:settings"),
            ) {
                Text(stringResource(R.string.settings_title))
            }
        }
        HorizontalDivider(color = tokens.hairline)

        if (catalog is CatalogState.Failed) {
            Text(
                text = stringResource(R.string.learn_error),
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.inkSecondary,
                modifier = Modifier.padding(Spacing.s4).testTag("you:error"),
            )
        }

        val ready = catalog as? CatalogState.Ready
        if (ready != null) {
            val stages = ready.bundle.stages
            val strings = ready.strings
            val config = ready.bundle.tutorConfig.mastery
            val epochAttempts =
                remember(attemptRecords, progressEvents) {
                    Mastery.currentEpochAttempts(attemptRecords, progressEvents)
                }
            val masteredStages =
                remember(stages, epochAttempts, config) {
                    Mastery.masteredStages(stages, epochAttempts, config)
                }
            val statuses =
                remember(stages, progressEvents, masteredStages) {
                    StageProgress.statuses(stages, progressEvents, masteredStages)
                }

            stages.forEachIndexed { index, stage ->
                val stageNumber = (index + 1).toString()
                val status = statuses[stage.id] ?: StageStatus.LOCKED
                val trend =
                    remember(stage, epochAttempts) {
                        StageTrendCalculator.evaluate(stage, epochAttempts)
                    }
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.s4, vertical = Spacing.s3)
                            .testTag("you:stage:${stage.id.value}"),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                        ) {
                            Text(
                                text = stageNumber,
                                style = RubikTheme.textStyles.data,
                                color = if (status == StageStatus.LOCKED) tokens.inkDisabled else tokens.inkSecondary,
                                modifier = Modifier.testTag("you:stage:number:${stage.id.value}"),
                            )
                            Text(
                                text = strings.resolve(stage.title),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (status == StageStatus.LOCKED) tokens.inkDisabled else tokens.inkPrimary,
                                modifier = Modifier.testTag("you:stage:title:${stage.id.value}"),
                            )
                        }
                        StageStatusBadge(
                            status = status,
                            tag = "you:stage:status:${stage.id.value}",
                        )
                    }
                    Spacer(Modifier.height(Spacing.s2))
                    TrendRow(
                        attemptsCount = trend.attemptsCount,
                        hintCounts = trend.hintCounts,
                        modifier = Modifier.testTag("you:stage:trend:${stage.id.value}"),
                        attemptsTag = "you:stage:attempts:${stage.id.value}",
                        sparklineTag = "you:stage:sparkline:${stage.id.value}",
                    )
                }
                HorizontalDivider(color = tokens.hairline)
            }
        }
    }
}
