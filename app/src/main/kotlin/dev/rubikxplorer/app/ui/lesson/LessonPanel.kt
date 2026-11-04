package dev.rubikxplorer.app.ui.lesson

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.rubikxplorer.app.R
import dev.rubikxplorer.app.ui.theme.RubikTheme
import dev.rubikxplorer.app.ui.theme.Spacing
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.tutor.HintLadder
import dev.rubikxplorer.domain.tutor.TeachingMode

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
 * shown rung (null at rung 0), drawn by [HintLine] under the Moves.
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
)

/**
 * The Lesson panel, top to bottom (UX-DR8): the Objective, the step card, the Teaching Mode
 * switcher, then Hint (secondary, labelled **Hint (n/3)** with [hintRung] as n) and Next (primary).
 * It decides nothing: every flag comes from the session's `TutorDecision` and Hint Ladder rung.
 */
@Composable
internal fun LessonPanel(
    objective: String,
    step: StepCardContent,
    modes: List<TeachingMode>,
    mode: TeachingMode,
    onModeChange: (TeachingMode) -> Unit,
    onShowMove: () -> Unit,
    hintRung: Int,
    hintEnabled: Boolean,
    onHint: () -> Unit,
    nextEnabled: Boolean,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    onShowMeHow: (List<Move>) -> Unit = {},
) {
    val tokens = RubikTheme.tokens
    Column(
        modifier = modifier.testTag("lesson:panel").padding(Spacing.s4),
        verticalArrangement = Arrangement.spacedBy(Spacing.s4),
    ) {
        Text(
            text = objective,
            style = MaterialTheme.typography.titleLarge,
            color = tokens.inkPrimary,
            modifier = Modifier.testTag("lesson:objective").semantics { heading() },
        )
        StepCard(step, onShowMove, onShowMeHow, Modifier.fillMaxWidth())
        TeachingModeSwitcher(modes, mode, onModeChange, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s3), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onHint,
                enabled = hintEnabled,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f).heightIn(min = Spacing.touchMin).testTag("lesson:hint"),
            ) {
                Text(
                    stringResource(R.string.lesson_hint, hintRung, HintLadder.RUNGS),
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
                Text(stringResource(R.string.lesson_next))
            }
        }
    }
}

@Composable
private fun StepCard(
    step: StepCardContent,
    onShowMove: () -> Unit,
    onShowMeHow: (List<Move>) -> Unit,
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
            Text(
                text = stringResource(R.string.lesson_step, step.number, step.count),
                style = RubikTheme.textStyles.data,
                color = tokens.inkSecondary,
                modifier = Modifier.testTag("lesson:stepNumber"),
            )
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
                        stringResource(R.string.lesson_objective_met),
                        style = MaterialTheme.typography.labelLarge,
                        color = tokens.inkPrimary,
                    )
                }
            }
        }
        Field(R.string.lesson_goal, step.goal, "lesson:goal")
        Field(R.string.lesson_keep, step.keep, "lesson:keep")
        Field(R.string.lesson_watch, step.watch, "lesson:watch")
        MovesRow(step.moves, step.showMoveControl, onShowMove)
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
        } else {
            step.feedback?.let { key(step.feedbackMove) { Feedback(it) } }
        }
        step.notice?.let { Field(R.string.lesson_notice, it, "lesson:notice") }
    }
}

@Composable
private fun Field(
    @StringRes label: Int,
    text: String,
    tag: String,
) {
    val tokens = RubikTheme.tokens
    Column(modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(tag)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = tokens.inkSecondary)
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
internal fun NotationChip(move: Move) {
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
                .border(1.dp, tokens.hairline, shape)
                .padding(horizontal = Spacing.s2),
        contentAlignment = Alignment.Center,
    ) {
        Text(token, style = RubikTheme.textStyles.notation, color = tokens.inkPrimary)
    }
}
