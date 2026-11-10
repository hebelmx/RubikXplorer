package dev.cubentia.app.ui.lesson

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp
import dev.cubentia.app.R
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.preferences.LocalRenderStyle
import dev.cubentia.app.ui.library.RecognitionDiagramView
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.application.session.SyncState
import dev.cubentia.domain.content.AlgorithmId
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.lastlayer.RecognitionDiagram
import dev.cubentia.domain.tutor.HintRung
import dev.cubentia.domain.tutor.RecoveryOffer
import dev.cubentia.domain.tutor.TeachingMode
import kotlinx.coroutines.launch
import dev.cubentia.domain.text.Text as ContentText

/**
 * What the step card shows, already resolved: step [number] of [count], its Goal, Keep and Watch,
 * its [notice] (null until it is due), its [moves] (null while hidden), whether the Lesson's
 * Objective is met, the Test me [feedback] on the last Move (null when there is none to show) or,
 * when the tutor names a mistake, its [diagnosis] with the Recovery Path's [recoveryPrompt] instead
 * (and, for a turned cube, the [showMeHow] Cube Rotations back to the Lesson's hold, which the
 * stage animates as a demonstration when the offer, labelled [showMeHowLabel], is tapped),
 * with [feedbackMove] naming that Move (the session's revision, so each Move's line is a fresh node that
 * screen readers announce even when its text repeats) and,
 * while the Moves are hidden, whether the "Show the Move" control offers them ([showMoveControl],
 * Guide me) or a quiet line says they wait for a Hint. [hint] is the resolved Hint Ladder line of the
 * shown rung (null at rung 0), drawn by [HintLine] under the Moves. [recoveryActions] are the
 * Diagnosis's Recovery Path offers as buttons (*Undo*, *Guide me back*, *Try again*), drawn by
 * [RecoveryPrompt] under the Diagnosis; empty when there is none. [guidedRecovery] is the Guided
 * recovery line (pending, the plan, or no plan), drawn by [GuidedRecoveryLine] after the recovery
 * prompt whenever the session has one, even once the Diagnosis is gone. [assistance] is the
 * Assistance line (why adaptive help changed), drawn by [AssistanceLine] directly under the card;
 * null at the usual level and whenever `ADAPTIVE_ASSISTANCE` is off.
 */
internal data class StepCardContent(
    val number: Int,
    val count: Int,
    val goal: String,
    val keep: String,
    val watch: String,
    val notice: String?,
    val moves: List<Move>?,
    val objectiveMet: Boolean,
    val feedback: String? = null,
    val diagnosis: String? = null,
    val recoveryPrompt: String? = null,
    val showMeHow: List<Move>? = null,
    val showMeHowLabel: String = "",
    val feedbackMove: Long = 0,
    val showMoveControl: Boolean = true,
    val hint: String? = null,
    val recoveryActions: List<RecoveryAction> = emptyList(),
    val guidedRecovery: GuidedRecoveryContent? = null,
    val assistance: AssistanceContent? = null,
    val isPractice: Boolean = false,
    val isPhysical: Boolean = false,
    val objectiveMetCopy: String? = null,
    val candidate: String? = null,
    val isEndOfStage6: Boolean = false,
)

/**
 * The Lesson panel, top to bottom (UX-DR8): the Objective, the step card, the Teaching Mode
 * switcher, then Hint (secondary, labelled **Hint (n/top)** with [hintRung] as n and [hintTop] the
 * ladder's top: 3, or 2 while adaptive assistance reduces help) and Next (primary).
 * It decides nothing: every flag comes from the session's `TutorDecision` and Hint Ladder rung.
 */
@Composable
internal fun LessonPanel(
    objective: String,
    strings: ContentStrings,
    step: StepCardContent,
    modes: List<TeachingMode>,
    mode: TeachingMode,
    onModeChange: (TeachingMode) -> Unit,
    onShowMove: () -> Unit,
    hintRung: Int,
    hintTop: HintRung,
    hintEnabled: Boolean,
    onHint: () -> Unit,
    nextEnabled: Boolean,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    onShowMeHow: (List<Move>) -> Unit = {},
    onRecovery: (RecoveryOffer) -> Unit = {},
    onUsualHelp: () -> Unit = {},
    isPhysical: Boolean = false,
    syncState: SyncState = SyncState.VIRTUAL,
    onConfirmMatches: () -> Unit = {},
    onConfirmEnter: () -> Unit = {},
    onDone: () -> Unit = {},
    onDidSomethingElse: () -> Unit = {},
    onMismatchReported: () -> Unit = {},
    onMatchesAfterAll: () -> Unit = {},
    mismatchRung: Int = 1,
    onMismatchRungChange: (Int) -> Unit = {},
    isEndOfStage6: Boolean = step.isEndOfStage6,
    onYesItsSolved: () -> Unit = {},
    recognition: RecognitionDiagram? = null,
    offerAlgorithms: List<AlgorithmId> = emptyList(),
    offerKey: String = "",
    onAddToLearning: suspend () -> Boolean = { true },
) {
    val tokens = RubikTheme.tokens
    Column(
        modifier =
            modifier
                .semantics {
                    isTraversalGroup = true
                    traversalIndex = 4f
                }.testTag("lesson:panel")
                .padding(Spacing.s4),
        verticalArrangement = Arrangement.spacedBy(Spacing.s4),
    ) {
        Text(
            text = objective,
            style = MaterialTheme.typography.titleLarge,
            color = tokens.inkPrimary,
            modifier = Modifier.testTag("lesson:objective").semantics { heading() },
        )
        recognition?.let { diagram ->
            Box(Modifier.testTag("lesson:recognition")) {
                RecognitionDiagramView(
                    diagram,
                    strings.resolve(ContentText("msg.lesson.recognition.description", mapOf("objective" to objective))),
                    LocalRenderStyle.current,
                )
            }
        }
        var offerDismissed by rememberSaveable(offerKey) { mutableStateOf(false) }
        var offerFailed by rememberSaveable(offerKey) { mutableStateOf(false) }
        var offerSaving by remember(offerKey) { mutableStateOf(false) }
        val offerScope = rememberCoroutineScope()
        if (step.objectiveMet && offerAlgorithms.isNotEmpty() && !offerDismissed) {
            TwoLookOffer(
                strings = strings,
                onAdd = {
                    if (!offerSaving) {
                        offerSaving = true
                        offerScope.launch {
                            val saved = onAddToLearning()
                            offerSaving = false
                            offerFailed = !saved
                            if (saved) offerDismissed = true
                        }
                    }
                },
                onDecline = { offerDismissed = true },
                failed = offerFailed,
            )
        }
        StepCard(step, onShowMove, onShowMeHow, onRecovery, Modifier.fillMaxWidth())
        step.assistance?.let { AssistanceLine(it, onUsualHelp, Modifier.fillMaxWidth()) }
        if (!step.isPractice) {
            TeachingModeSwitcher(
                modes,
                mode,
                onModeChange,
                Modifier.fillMaxWidth().semantics {
                    isTraversalGroup = true
                    traversalIndex = 1f
                },
            )
        }
        if (syncState == SyncState.PHYSICAL_AWAITING_CONFIRM) {
            ConfirmCubeCard(
                strings = strings,
                onMatches = onConfirmMatches,
                onEnter = onConfirmEnter,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (syncState == SyncState.PHYSICAL_MISMATCH) {
            MismatchLadder(
                onOpenEditor = onConfirmEnter,
                strings = strings,
                onMatchesAfterAll = onMatchesAfterAll,
                rung = mismatchRung,
                onRungChange = onMismatchRungChange,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (isPhysical) {
            step.candidate?.let { candidate ->
                Text(
                    text = strings.resolve(ContentText("msg.physical.mismatch.candidate", mapOf("candidate" to candidate))),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.statusLikely,
                    modifier = Modifier.testTag("mismatch:candidate"),
                )
            }
            var menuExpanded by remember { mutableStateOf(false) }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = onDidSomethingElse,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f).heightIn(min = Spacing.touchMin).testTag("lesson:didSomethingElse"),
                ) {
                    Text(strings.resolve(ContentText("btn.physical.did-something-else")), color = tokens.inkPrimary)
                }
                if (isEndOfStage6 && step.objectiveMet) {
                    Button(
                        onClick = onYesItsSolved,
                        enabled = true,
                        shape = MaterialTheme.shapes.medium,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = tokens.accent,
                                contentColor = tokens.onAccent,
                                disabledContainerColor = tokens.surfaceStage,
                                disabledContentColor = tokens.inkDisabled,
                            ),
                        modifier = Modifier.weight(1f).heightIn(min = Spacing.touchMin).testTag("lesson:yesItsSolved"),
                    ) {
                        Text(text = strings.resolve(ContentText("btn.physical.yes-its-solved")))
                    }
                } else {
                    Button(
                        onClick = if (nextEnabled || step.objectiveMet) onNext else onDone,
                        enabled = true,
                        shape = MaterialTheme.shapes.medium,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = tokens.accent,
                                contentColor = tokens.onAccent,
                                disabledContainerColor = tokens.surfaceStage,
                                disabledContentColor = tokens.inkDisabled,
                            ),
                        modifier =
                            Modifier.weight(1f).heightIn(min = Spacing.touchMin).testTag(
                                if (nextEnabled ||
                                    step.objectiveMet
                                ) {
                                    "lesson:next"
                                } else {
                                    "lesson:done"
                                },
                            ),
                    ) {
                        Text(
                            text =
                                if (nextEnabled ||
                                    step.objectiveMet
                                ) {
                                    stringResource(R.string.lesson_next)
                                } else {
                                    strings.resolve(ContentText("btn.physical.done"))
                                },
                        )
                    }
                }
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.testTag("lesson:overflowMenu"),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_more_vert),
                            contentDescription = strings.resolve(ContentText("btn.physical.more-options")),
                            tint = tokens.inkPrimary,
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(text = strings.resolve(ContentText("btn.physical.my-cube-doesnt-match"))) },
                            onClick = {
                                menuExpanded = false
                                onMismatchReported()
                            },
                            modifier = Modifier.testTag("lesson:menuMismatch"),
                        )
                        DropdownMenuItem(
                            text = { Text(text = strings.resolve(ContentText("btn.physical.not-sure-what-i-did"))) },
                            onClick = {
                                menuExpanded = false
                                onMismatchReported()
                            },
                            modifier = Modifier.testTag("lesson:menuNotSure"),
                        )
                    }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s3), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onHint,
                    enabled = hintEnabled,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f).heightIn(min = Spacing.touchMin).testTag("lesson:hint"),
                ) {
                    Text(
                        stringResource(R.string.lesson_hint, hintRung, hintTop.ordinal),
                        color = if (hintEnabled) tokens.inkPrimary else tokens.inkDisabled,
                    )
                }
                Button(
                    onClick = onNext,
                    enabled = nextEnabled,
                    shape = MaterialTheme.shapes.medium,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = tokens.accent,
                            contentColor = tokens.onAccent,
                            disabledContainerColor = tokens.surfaceStage,
                            disabledContentColor = tokens.inkDisabled,
                        ),
                    modifier = Modifier.weight(1f).heightIn(min = Spacing.touchMin).testTag("lesson:next"),
                ) {
                    Text(text = stringResource(R.string.lesson_next))
                }
            }
        }
    }
}

@Composable
private fun StepCard(
    step: StepCardContent,
    onShowMove: () -> Unit,
    onShowMeHow: (List<Move>) -> Unit,
    onRecovery: (RecoveryOffer) -> Unit,
    modifier: Modifier,
) {
    val tokens = RubikTheme.tokens
    val shape = MaterialTheme.shapes.large
    Column(
        modifier =
            modifier
                .testTag("lesson:stepCard")
                .clip(shape)
                .background(tokens.surfaceRaised)
                .border(1.dp, tokens.hairline, shape)
                .padding(Spacing.s4),
        verticalArrangement = Arrangement.spacedBy(Spacing.s3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (!step.isPractice) {
                Text(
                    text = stringResource(R.string.lesson_step, step.number, step.count),
                    style = RubikTheme.textStyles.data,
                    color = tokens.inkSecondary,
                    modifier = Modifier.testTag("lesson:stepNumber"),
                )
            }
            Spacer(Modifier.weight(1f))
            if (step.objectiveMet) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s1),
                    modifier =
                        Modifier.testTag("lesson:objectiveMet").semantics(mergeDescendants = true) {
                            liveRegion =
                                LiveRegionMode.Polite
                        },
                ) {
                    Icon(
                        painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = tokens.accent,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        step.objectiveMetCopy ?: stringResource(R.string.lesson_objective_met),
                        style = MaterialTheme.typography.labelLarge,
                        color = tokens.inkPrimary,
                    )
                }
            }
        }
        Field(R.string.lesson_goal, step.goal, "lesson:goal", showLabel = !step.isPractice)
        Field(R.string.lesson_keep, step.keep, "lesson:keep")
        Field(R.string.lesson_watch, step.watch, "lesson:watch")
        if (!step.isPractice) {
            MovesRow(step.moves, step.showMoveControl, onShowMove)
        }
        HintLine(step.hint)
        val diagnosis = step.diagnosis
        if (diagnosis != null) {
            key(step.feedbackMove) {
                DiagnosisLine(
                    diagnosis,
                    step.recoveryPrompt,
                    showMeHow = step.showMeHow,
                    showMeHowLabel = step.showMeHowLabel,
                    onShowMeHow = onShowMeHow,
                )
            }
            RecoveryPrompt(step.recoveryActions, onRecovery)
        } else {
            step.feedback?.let { key(step.feedbackMove) { Feedback(it) } }
        }
        step.guidedRecovery?.let { GuidedRecoveryLine(it) }
        step.notice?.let { Field(R.string.lesson_notice, it, "lesson:notice") }
    }
}

@Composable
private fun Field(
    @StringRes label: Int,
    text: String,
    tag: String,
    showLabel: Boolean = true,
) {
    if (text.isEmpty()) return
    val tokens = RubikTheme.tokens
    Column(modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(tag)) {
        if (showLabel) {
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = tokens.inkSecondary)
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, color = tokens.inkPrimary)
    }
}

/**
 * Test me's word on the last Move: neutral ink in a polite live region, never red and never shaken,
 * whether the Move helped or not.
 */
@Composable
private fun Feedback(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = RubikTheme.tokens.inkPrimary,
        modifier = Modifier.testTag("lesson:feedback").semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * The step's Moves as `notation-chip`s, or, while they are hidden, the "Show the Move" control when
 * [showMoveControl] (Guide me) and otherwise a quiet line saying a Hint shows them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MovesRow(
    moves: List<Move>?,
    showMoveControl: Boolean,
    onShowMove: () -> Unit,
) {
    val tokens = RubikTheme.tokens
    Column(modifier = Modifier.testTag("lesson:moves")) {
        Text(stringResource(R.string.lesson_moves), style = MaterialTheme.typography.labelMedium, color = tokens.inkSecondary)
        if (moves == null && !showMoveControl) {
            Text(
                stringResource(R.string.lesson_moves_hidden),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.inkSecondary,
                modifier = Modifier.padding(top = Spacing.s1).testTag("lesson:movesHidden"),
            )
        } else if (moves == null) {
            TextButton(
                onClick = onShowMove,
                modifier = Modifier.heightIn(min = Spacing.touchMin).testTag("lesson:showMove"),
            ) {
                Text(stringResource(R.string.lesson_show_move), color = tokens.accent)
            }
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                verticalArrangement = Arrangement.spacedBy(Spacing.s2),
                modifier = Modifier.padding(top = Spacing.s1),
            ) {
                moves.forEach { NotationChip(it) }
            }
        }
    }
}

@Composable
internal fun NotationChip(
    move: Move,
    current: Boolean = false,
) {
    val tokens = RubikTheme.tokens
    val shape = MaterialTheme.shapes.small
    val token = Notation.format(move)
    Box(
        modifier =
            Modifier
                .testTag("moveChip:$token")
                .sizeIn(minWidth = Spacing.touchMin, minHeight = Spacing.s6)
                .clip(shape)
                .background(tokens.surfaceStage)
                .border(if (current) 1.5.dp else 1.dp, if (current) tokens.inkPrimary else tokens.hairline, shape)
                .padding(horizontal = Spacing.s2),
        contentAlignment = Alignment.Center,
    ) {
        Text(token, style = RubikTheme.textStyles.notation, color = tokens.inkPrimary)
    }
}
