package dev.rubikxplorer.app.ui.lesson

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.rubikxplorer.app.R
import dev.rubikxplorer.app.ui.theme.RubikTheme
import dev.rubikxplorer.app.ui.theme.Spacing
import dev.rubikxplorer.domain.tutor.TeachingMode

/** The label of each [TeachingMode] segment; exhaustive, so a new mode cannot be left out of the switcher. */
private fun labelOf(mode: TeachingMode): Int =
    when (mode) {
        TeachingMode.SHOW_ME -> R.string.mode_show_me
        TeachingMode.GUIDE_ME -> R.string.mode_guide_me
        TeachingMode.TEST_ME -> R.string.mode_test_me
        TeachingMode.LET_ME_TRY -> R.string.mode_let_me_try
    }

/**
 * The Teaching Mode segmented switcher: one segment per available mode in [modes] (feature flags
 * may hide one), the active one filled with `accent`. Choosing a segment only reports it through
 * [onSelect]; the mode lives in the session.
 */
@Composable
fun TeachingModeSwitcher(
    modes: List<TeachingMode>,
    selected: TeachingMode,
    onSelect: (TeachingMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = RubikTheme.tokens
    val shape = MaterialTheme.shapes.medium
    val description = stringResource(R.string.lesson_mode_description)
    Row(
        modifier =
            modifier
                .testTag("lesson:modeSwitcher")
                .clip(shape)
                .border(1.dp, tokens.hairline, shape)
                .selectableGroup()
                .semantics { contentDescription = description },
    ) {
        modes.forEach { mode ->
            Segment(mode, labelOf(mode), mode == selected, onSelect, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Segment(
    mode: TeachingMode,
    @StringRes label: Int,
    active: Boolean,
    onSelect: (TeachingMode) -> Unit,
    modifier: Modifier,
) {
    val tokens = RubikTheme.tokens
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.touchMin)
                .background(if (active) tokens.accent else tokens.surfaceRaised)
                .selectable(selected = active, role = Role.RadioButton, onClick = { onSelect(mode) })
                .testTag("mode:${mode.name}")
                .padding(horizontal = Spacing.s3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelLarge,
            color = if (active) tokens.onAccent else tokens.inkPrimary,
        )
    }
}
