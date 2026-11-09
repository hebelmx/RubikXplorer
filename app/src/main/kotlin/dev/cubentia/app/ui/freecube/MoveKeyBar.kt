package dev.cubentia.app.ui.freecube

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.cubentia.app.R
import dev.cubentia.app.preferences.LocalHaptics
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.domain.cube.Axis
import dev.cubentia.domain.cube.CubeRotation
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.Slice
import dev.cubentia.domain.cube.SliceTurn
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.cube.WideTurn

/** Key order within each layer: `X X' X2`. */
private val KEY_TURNS = listOf(Turn.CLOCKWISE, Turn.COUNTERCLOCKWISE, Turn.HALF)

/** The keys of the Move key bar, by row: face, Cube Rotation, then (behind "more") wide and slice. */
object MoveKeys {
    val face: List<Move> = Face.entries.flatMap { f -> KEY_TURNS.map { FaceTurn(f, it) } }
    val rotation: List<Move> = Axis.entries.flatMap { a -> KEY_TURNS.map { CubeRotation(a, it) } }
    val wide: List<Move> = Face.entries.flatMap { f -> KEY_TURNS.map { WideTurn(f, it) } }
    val slice: List<Move> = Slice.entries.flatMap { s -> KEY_TURNS.map { SliceTurn(s, it) } }
    val all: List<Move> = face + rotation + wide + slice
}

/**
 * Keyboard-like keys for every Move. A tap commits the Move with a light haptic tick; a long press
 * only reports the key's plain name through [onShowName].
 */
@Composable
fun MoveKeyBar(
    onMove: (Move) -> Unit,
    onShowName: (String) -> Unit,
    modifier: Modifier = Modifier,
    tickOnCommit: Boolean = LocalHaptics.current,
) {
    var more by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
        KeyRow(MoveKeys.face, onMove, onShowName, tickOnCommit)
        KeyRow(MoveKeys.rotation, onMove, onShowName, tickOnCommit) {
            val description = stringResource(R.string.move_group_rotations_description)
            Text(
                text = stringResource(R.string.move_group_rotations),
                style = RubikTheme.textStyles.notation,
                color = RubikTheme.tokens.inkSecondary,
                modifier = Modifier.semantics { contentDescription = description },
            )
            val toggleDescription = stringResource(if (more) R.string.move_less_description else R.string.move_more_description)
            Key(
                label = stringResource(if (more) R.string.move_less else R.string.move_more),
                description = toggleDescription,
                tag = "moveKey:more",
                onClick = { more = !more },
                onLongClick = { onShowName(toggleDescription) },
                labelStyleIsNotation = false,
            )
        }
        if (more) {
            KeyRow(MoveKeys.wide, onMove, onShowName, tickOnCommit)
            KeyRow(MoveKeys.slice, onMove, onShowName, tickOnCommit)
        }
    }
}

@Composable
private fun KeyRow(
    moves: List<Move>,
    onMove: (Move) -> Unit,
    onShowName: (String) -> Unit,
    tickOnCommit: Boolean,
    leading: @Composable () -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        moves.forEach { move ->
            val token = Notation.format(move)
            val name = movePlainName(move)
            Key(
                label = token,
                description = name,
                tag = "moveKey:$token",
                onClick = {
                    if (tickOnCommit) haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                    onMove(move)
                },
                onLongClick = { onShowName(name) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Key(
    label: String,
    description: String,
    tag: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    labelStyleIsNotation: Boolean = true,
) {
    val tokens = RubikTheme.tokens
    val shape = MaterialTheme.shapes.small
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier =
            Modifier
                .testTag(tag)
                .sizeIn(minWidth = Spacing.touchMin, minHeight = Spacing.touchMin)
                .clip(shape)
                .background(if (pressed) tokens.inkPrimary else tokens.surfaceRaised)
                .border(1.dp, tokens.hairline, shape)
                .semantics { contentDescription = description }
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onLongClickLabel = stringResource(R.string.move_show_name),
                    onLongClick = onLongClick,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = if (labelStyleIsNotation) RubikTheme.textStyles.notation else MaterialTheme.typography.labelLarge,
            color = if (pressed) tokens.surfaceRaised else tokens.inkPrimary,
            modifier = Modifier.padding(horizontal = Spacing.s2).clearAndSetSemantics {},
        )
    }
}
