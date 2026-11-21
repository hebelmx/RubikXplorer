package dev.cubentia.app.ui.placement

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.cubentia.app.R
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.ui.editor.ManualCubeEditor
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.domain.content.Case
import dev.cubentia.domain.content.Stage
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.lastlayer.LastLayerMatcher
import dev.cubentia.domain.tutor.PlacementOutcome
import dev.cubentia.domain.text.Text as ContentText

@Composable
internal fun PlacementScreen(
    phase: PlacementPhase,
    flow: PlacementFlowViewModel,
    strings: ContentStrings,
    stages: List<Stage>,
    twoLookStages: List<Stage>,
    matcher: LastLayerMatcher?,
    cases: List<Case>,
    onAccept: (Stage, Boolean) -> Unit,
    onScanAgain: () -> Unit,
    modifier: Modifier = Modifier,
    accepting: Boolean = false,
    saveFailed: Boolean = false,
) {
    BackHandler { if (!accepting) flow.back() }
    Surface(modifier = modifier.fillMaxSize(), color = RubikTheme.tokens.surfaceBase, contentColor = RubikTheme.tokens.inkPrimary) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().testTag("placement:screen")) {
            if (phase != PlacementPhase.Idle) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("placement:topBar")) {
                    IconButton(onClick = flow::back, enabled = !accepting, modifier = Modifier.testTag("placement:close")) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = strings.resolve(ContentText("btn.solve.close")))
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f).padding(Spacing.s4),
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
                        Text(strings.resolve(phase.note), modifier = Modifier.testTag("placement:editorNote"))
                        ManualCubeEditor(strings, { flow.editorAccepted(it, stages, matcher) }, Modifier.weight(1f), fresh = true)
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
                        PlacementResult(phase.outcome, flow, strings, stages, twoLookStages, cases, onAccept, onScanAgain, accepting)
                    }
                }
            }
        }
    }
}

/** The result: reading, reason and suggestion as one polite node, then the options and the set-up line (DESIGN.md *Placement result*). */
@Composable
private fun PlacementResult(
    outcome: PlacementOutcome,
    flow: PlacementFlowViewModel,
    strings: ContentStrings,
    stages: List<Stage>,
    twoLookStages: List<Stage>,
    cases: List<Case>,
    onAccept: (Stage, Boolean) -> Unit,
    onScanAgain: () -> Unit,
    accepting: Boolean,
) {
    val copy = resultCopy(outcome, strings, cases)
    val tokens = RubikTheme.tokens
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.s2),
    ) {
        Column(
            modifier =
                Modifier.testTag("placement:prompt").semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                },
            verticalArrangement = Arrangement.spacedBy(Spacing.s2),
        ) {
            Text(
                copy.reading,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.inkPrimary,
                modifier = Modifier.testTag("placement:reading"),
            )
            Text(
                copy.reason,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.inkSecondary,
                modifier = Modifier.testTag("placement:reason"),
            )
            copy.suggestion?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.inkPrimary,
                    modifier = Modifier.testTag("placement:suggestion"),
                )
            }
        }
        when (outcome) {
            PlacementOutcome.Solved -> {
                Button(onClick = onScanAgain, modifier = Modifier.testTag("placement:scanAgain")) {
                    Text(strings.resolve(ContentText("btn.placement.scan-again")))
                }
                OutlinedButton(onClick = { onAccept(stages.first(), false) }, modifier = Modifier.testTag("placement:startStage1")) {
                    Text(strings.resolve(ContentText("btn.placement.start-stage-1")))
                }
            }

            is PlacementOutcome.NearlySolved -> {
                Button(onClick = onScanAgain, enabled = !accepting, modifier = Modifier.testTag("placement:scanAgain")) {
                    Text(strings.resolve(ContentText("btn.placement.scan-again")))
                }
                ChooseAnother(flow, strings, accepting)
            }

            else -> {
                val stage = suggestedStage(outcome) ?: stages.first()
                Button(
                    onClick = { onAccept(stage, false) },
                    enabled = !accepting,
                    modifier = Modifier.testTag("placement:start"),
                ) {
                    Text(strings.resolve(ContentText("btn.placement.start")))
                }
                (outcome as? PlacementOutcome.NonWhite)?.lastLayerStage?.let { lastLayer ->
                    OutlinedButton(
                        onClick = { onAccept(lastLayer, false) },
                        enabled = !accepting,
                        modifier = Modifier.testTag("placement:lastLayerAnyway"),
                    ) {
                        Text(strings.resolve(ContentText("btn.placement.last-layer-anyway")))
                    }
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
                ChooseAnother(flow, strings, accepting)
            }
        }
        Text(
            strings.resolve(ContentText("msg.placement.setup")),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.inkSecondary,
            modifier = Modifier.testTag("placement:setup"),
        )
    }
}

@Composable
private fun ChooseAnother(
    flow: PlacementFlowViewModel,
    strings: ContentStrings,
    accepting: Boolean,
) {
    OutlinedButton(
        onClick = flow::chooseAnother,
        enabled = !accepting,
        modifier = Modifier.testTag("placement:chooseAnother"),
    ) {
        Text(strings.resolve(ContentText("btn.placement.choose-another")))
    }
}

/** The result's three lines, resolved from content strings; [suggestion] is absent for a solved or nearly solved cube. */
private class ResultCopy(
    val reading: String,
    val reason: String,
    val suggestion: String?,
)

private fun suggestedStage(outcome: PlacementOutcome): Stage? =
    when (outcome) {
        PlacementOutcome.Solved, is PlacementOutcome.NearlySolved -> null
        is PlacementOutcome.NothingSolved -> outcome.stage
        is PlacementOutcome.NonWhite -> outcome.stage
        is PlacementOutcome.PartlySolved -> outcome.stage
        is PlacementOutcome.AtLastLayer -> outcome.stage
    }

private fun resultCopy(
    outcome: PlacementOutcome,
    strings: ContentStrings,
    cases: List<Case>,
): ResultCopy {
    fun resolve(
        key: String,
        vararg args: Pair<String, Any>,
    ) = strings.resolve(ContentText(key, mapOf(*args)))
    val stage = suggestedStage(outcome)?.let { strings.resolve(it.title) }
    val suggestion = stage?.let { resolve("msg.placement.suggest", "stage" to it) }
    return when (outcome) {
        PlacementOutcome.Solved -> {
            ResultCopy(resolve("msg.placement.reading.solved"), resolve("msg.placement.reason.solved"), null)
        }

        is PlacementOutcome.NearlySolved -> {
            val turns = outcome.turns.map { resolve("msg.placement.turn", "color" to it.color.word, "turn" to it.turn.word) }
            val reading =
                if (turns.size == 1) {
                    resolve("msg.placement.reading.one-turn", "turn" to turns.single())
                } else {
                    resolve("msg.placement.reading.two-turns", "first" to turns.first(), "second" to turns.last())
                }
            ResultCopy(reading, resolve("msg.placement.reason.nearly-solved"), null)
        }

        is PlacementOutcome.NothingSolved -> {
            ResultCopy(
                resolve("msg.placement.reading.nothing-solved"),
                resolve("msg.placement.reason.starts-here", "stage" to stage!!),
                suggestion,
            )
        }

        is PlacementOutcome.NonWhite -> {
            val key = if (outcome.firstTwoLayers) "msg.placement.reading.non-white-layers" else "msg.placement.reading.non-white-cross"
            ResultCopy(resolve(key, "color" to outcome.color.word), resolve("msg.placement.reason.non-white"), suggestion)
        }

        is PlacementOutcome.PartlySolved -> {
            ResultCopy(
                resolve(
                    "msg.placement.reading.partly.${outcome.stage.id.value}",
                    "done" to outcome.progress.done,
                    "total" to outcome.progress.total,
                ),
                resolve("msg.placement.reason.picks-up", "stage" to stage!!),
                suggestion,
            )
        }

        is PlacementOutcome.AtLastLayer -> {
            val top =
                resolve(if (outcome.oriented) "msg.placement.reading.last-layer-permute" else "msg.placement.reading.last-layer-orient")
            val case =
                outcome.case
                    ?.let { id -> cases.firstOrNull { it.id == id } }
                    ?.let { " " + resolve("msg.placement.reading.case", "case" to strings.resolve(it.name)) }
                    .orEmpty()
            ResultCopy(top + case, resolve("msg.placement.reason.last-layer", "stage" to stage!!), suggestion)
        }
    }
}

// ICU select keywords in en.json.
private val Color.word: String get() = name.lowercase()

private val Turn.word: String
    get() =
        when (this) {
            Turn.CLOCKWISE -> "clockwise"
            Turn.COUNTERCLOCKWISE -> "counterclockwise"
            Turn.HALF -> "half"
        }
