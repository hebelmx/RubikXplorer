package dev.rubikxplorer.app.ui.freecube

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.rubikxplorer.app.R
import dev.rubikxplorer.app.ui.editor.colorName
import dev.rubikxplorer.app.ui.theme.RubikTheme
import dev.rubikxplorer.app.ui.theme.Spacing
import dev.rubikxplorer.domain.cube.Color
import dev.rubikxplorer.domain.cube.CubeRotation
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.MoveHistory
import dev.rubikxplorer.domain.input.MoveIntent
import dev.rubikxplorer.domain.input.ViewOrbit
import kotlinx.coroutines.delay

/**
 * The free cube (FR-1, FR-3): the cube stage (toolbar, frame label, 2D net, name caption) over the
 * Move key bar and the Haptics switch, then the debug toggles when [showDebugToggles].
 */
@Composable
fun FreeCubeScreen(
    viewModel: FreeCubeViewModel,
    showDebugToggles: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = RubikTheme.tokens
    var caption by rememberSaveable { mutableStateOf<String?>(null) }
    var front by remember { mutableStateOf<Color?>(null) }
    var notice by remember { mutableStateOf(0) }
    LaunchedEffect(notice) {
        if (front != null) {
            delay(FRONT_NOTICE_MILLIS)
            front = null
        }
    }
    val haptics = LocalHapticFeedback.current
    val commit: (MoveIntent) -> Unit = { intent ->
        caption = null
        if (viewModel.hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
        viewModel.onMoveIntent(intent)
        if (intent.move is CubeRotation) {
            front =
                viewModel.history.state.orientation
                    .colorAt(Face.F)
            notice++
        }
    }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(tokens.surfaceBase)
                .safeDrawingPadding()
                .padding(Spacing.s4),
        verticalArrangement = Arrangement.spacedBy(Spacing.s3),
    ) {
        CubeStage(
            viewModel = viewModel,
            caption = caption,
            front = front,
            onMoveIntent = commit,
            onClearCaption = {
                caption = null
                front = null
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        // Takes at most half the height left after the stage's share, scrolling when it needs more.
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.s3),
        ) {
            MoveKeyBar(
                onMove = { commit(MoveIntent(it)) },
                tickOnCommit = false,
                onShowName = { caption = it },
                modifier = Modifier.fillMaxWidth(),
            )
            LabelledSwitch(R.string.free_cube_haptics, "freeCube:haptics", viewModel.hapticsEnabled, viewModel::onHapticsChange)
            if (showDebugToggles) {
                DebugToggles(viewModel)
            }
        }
    }
}

@Composable
private fun CubeStage(
    viewModel: FreeCubeViewModel,
    caption: String?,
    front: Color?,
    onMoveIntent: (MoveIntent) -> Unit,
    onClearCaption: () -> Unit,
    modifier: Modifier,
) {
    val tokens = RubikTheme.tokens
    val history = viewModel.history
    val frameLabel =
        if (front == null) {
            stringResource(R.string.frame_virtual)
        } else {
            stringResource(R.string.frame_virtual_front, stringResource(colorName(front)))
        }
    Column(
        modifier =
            modifier
                .background(tokens.surfaceStage, MaterialTheme.shapes.large)
                .padding(Spacing.s2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = frameLabel,
                style = MaterialTheme.typography.labelMedium,
                color = tokens.inkSecondary,
                modifier =
                    Modifier.weight(1f).padding(start = Spacing.s2).testTag("stage:frame").semantics {
                        // Announce the front notice only, not the return to the plain frame label.
                        if (front != null) liveRegion = LiveRegionMode.Polite
                    },
            )
            ToolbarButton(R.drawable.ic_undo, R.string.toolbar_undo, "toolbar:undo", history.canUndo) {
                onClearCaption()
                viewModel.onUndo()
            }
            ToolbarButton(R.drawable.ic_redo, R.string.toolbar_redo, "toolbar:redo", history.canRedo) {
                onClearCaption()
                viewModel.onRedo()
            }
            ToolbarButton(R.drawable.ic_reset, R.string.toolbar_reset, "toolbar:reset", history != MoveHistory()) {
                onClearCaption()
                viewModel.onReset()
            }
            ViewControls(viewModel.renderer)
        }
        viewModel.renderer.Content(
            style = viewModel.style,
            onMoveIntent = onMoveIntent,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(Spacing.s2),
        )
        Box(Modifier.fillMaxWidth().heightIn(min = Spacing.s5), contentAlignment = Alignment.Center) {
            if (caption != null) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.inkPrimary,
                    modifier =
                        Modifier
                            .testTag("stage:caption")
                            .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@Composable
internal fun ToolbarButton(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tokens = RubikTheme.tokens
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors =
            IconButtonDefaults.iconButtonColors(
                contentColor = tokens.inkPrimary,
                disabledContentColor = tokens.inkDisabled,
            ),
        modifier = Modifier.testTag(tag).size(Spacing.touchMin),
    ) {
        Icon(painter = painterResource(icon), contentDescription = stringResource(label))
    }
}

@Composable
private fun DebugToggles(viewModel: FreeCubeViewModel) {
    var objective by rememberSaveable { mutableStateOf(false) }
    LabelledSwitch(R.string.debug_glyphs, "debug:glyphs", viewModel.style.colorBlindGlyphs, viewModel::onColorBlindGlyphsChange)
    LabelledSwitch(R.string.debug_objective, "debug:objective", objective) { objective = it }
    OutlinedButton(
        onClick = viewModel::onScramble,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchMin).testTag("debug:scramble"),
    ) {
        Text(text = stringResource(R.string.debug_scramble), color = RubikTheme.tokens.inkPrimary)
    }
    if (objective) {
        ObjectiveOverlay(viewModel, Modifier.fillMaxWidth())
    }
}

@Composable
private fun LabelledSwitch(
    @StringRes label: Int,
    tag: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.touchMin)
                .testTag(tag)
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyLarge,
            color = RubikTheme.tokens.inkPrimary,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = null)
    }
}

internal const val FRONT_NOTICE_MILLIS = 1500L
