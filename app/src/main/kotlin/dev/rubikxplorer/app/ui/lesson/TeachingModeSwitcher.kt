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

/** The label of each [TeachingMode] segment, in switcher order. */
private val MODE_LABELS: List<Pair<TeachingMode, Int>> =
    listOf(
        TeachingMode.SHOW_ME to R.string.mode_show_me,
        TeachingMode.GUIDE_ME to R.string.mode_guide_me,
    )

/**
 * The Teaching Mode segmented switcher: one segment per mode, the active one filled with `accent`.
 * Choosing a segment only reports it through [onSelect]; the mode lives in the session.
 */
@Composable
fun TeachingModeSwitcher(
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
        MODE_LABELS.forEach { (mode, label) ->
            Segment(mode, label, mode == selected, onSelect, Modifier.weight(1f))
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
