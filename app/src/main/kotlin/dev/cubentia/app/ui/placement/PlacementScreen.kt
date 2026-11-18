package dev.cubentia.app.ui.placement

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.ui.editor.ManualCubeEditor
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.domain.content.Stage
import dev.cubentia.domain.tutor.PlacementOutcome
import dev.cubentia.domain.text.Text as ContentText

@Composable
internal fun PlacementScreen(
    phase: PlacementPhase,
    flow: PlacementFlowViewModel,
    strings: ContentStrings,
    stages: List<Stage>,
    twoLookStages: List<Stage>,
    onAccept: (Stage, Boolean) -> Unit,
    onScanAgain: () -> Unit,
    modifier: Modifier = Modifier,
    accepting: Boolean = false,
    saveFailed: Boolean = false,
) {
    BackHandler { if (!accepting) flow.back() }
    Column(
        modifier = modifier.fillMaxSize().safeDrawingPadding().padding(Spacing.s4),
        verticalArrangement = Arrangement.spacedBy(Spacing.s2),
    ) {
        if (saveFailed) {
            Text(
                strings.resolve(ContentText("msg.placement.save-failed")),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        when (phase) {
            PlacementPhase.Idle -> {}

            is PlacementPhase.Editing -> {
                TextButton(onClick = flow::back) {
                    Text(strings.resolve(ContentText("btn.solve.close")))
                }
                Text(strings.resolve(phase.note), modifier = Modifier.testTag("placement:editorNote"))
                ManualCubeEditor(strings, { flow.editorAccepted(it, stages) }, Modifier.weight(1f))
            }

            is PlacementPhase.Choosing -> {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s2),
                ) {
                    Text(
                        strings.resolve(ContentText("msg.placement.choose-title")),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    (stages + twoLookStages).forEachIndexed { index, stage ->
                        OutlinedButton(
                            onClick = { onAccept(stage, false) },
                            enabled = !accepting,
                            modifier = Modifier.fillMaxWidth().testTag("placement:stage:${stage.id.value}"),
                        ) {
                            Text(
                                strings.resolve(
                                    ContentText(
                                        "msg.placement.stage-row",
                                        mapOf(
                                            "number" to index + 1,
                                            "stage" to strings.resolve(stage.title),
                                        ),
                                    ),
                                ),
                            )
                        }
                    }
                }
            }

            is PlacementPhase.Result -> {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s2),
                ) {
                    val outcome = phase.outcome
                    val stage =
                        when (outcome) {
                            PlacementOutcome.Solved -> stages.first()
                            is PlacementOutcome.NonWhiteCross -> outcome.stage
                            is PlacementOutcome.Suggested -> outcome.stage
                            is PlacementOutcome.AtLastLayer -> outcome.stage
                        }
                    val prompt =
                        when (outcome) {
                            PlacementOutcome.Solved -> ContentText("msg.placement.solved")
                            is PlacementOutcome.NonWhiteCross -> ContentText("msg.placement.non-white-cross")
                            else -> ContentText("msg.placement.suggest", mapOf("stage" to strings.resolve(stage.title)))
                        }
                    Text(
                        strings.resolve(prompt),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier =
                            Modifier.testTag("placement:prompt").semantics {
                                liveRegion = LiveRegionMode.Polite
                            },
                    )
                    if (outcome == PlacementOutcome.Solved) {
                        Button(onClick = onScanAgain, modifier = Modifier.testTag("placement:scanAgain")) {
                            Text(strings.resolve(ContentText("btn.placement.scan-again")))
                        }
                        OutlinedButton(onClick = { onAccept(stage, false) }, modifier = Modifier.testTag("placement:startStage1")) {
                            Text(strings.resolve(ContentText("btn.placement.start-stage-1")))
                        }
                    } else {
                        Button(
                            onClick = { onAccept(stage, false) },
                            enabled = !accepting,
                            modifier = Modifier.testTag("placement:start"),
                        ) {
                            Text(strings.resolve(ContentText("btn.placement.start")))
                        }
                        if (outcome is PlacementOutcome.AtLastLayer) {
                            twoLookStages.firstOrNull()?.let { first ->
                                OutlinedButton(
                                    onClick = { onAccept(first, true) },
                                    enabled = !accepting,
                                    modifier = Modifier.testTag("placement:fasterLastLayer"),
                                ) {
                                    Text(strings.resolve(ContentText("btn.placement.faster-last-layer")))
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = flow::chooseAnother,
                            enabled = !accepting,
                            modifier = Modifier.testTag("placement:chooseAnother"),
                        ) {
                            Text(strings.resolve(ContentText("btn.placement.choose-another")))
                        }
                    }
                }
            }
        }
    }
}
