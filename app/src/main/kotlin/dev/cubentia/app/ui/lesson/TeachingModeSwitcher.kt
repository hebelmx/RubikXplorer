package dev.cubentia.app.ui.lesson

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.cubentia.app.R
import dev.cubentia.app.ui.theme.Radii
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.domain.tutor.TeachingMode

/** The label of each [TeachingMode] segment; exhaustive, so a new mode cannot be left out of the switcher. */
private fun labelOf(mode: TeachingMode): Int =
    when (mode) {
        TeachingMode.SHOW_ME -> R.string.mode_show_me
        TeachingMode.GUIDE_ME -> R.string.mode_guide_me
        TeachingMode.TEST_ME -> R.string.mode_test_me
        TeachingMode.LET_ME_TRY -> R.string.mode_let_me_try
    }

/**
 * The beta label under the name of [mode]'s segment while its feature flag is BETA (DESIGN *Beta label*, AD-18);
 * [label] is the resolved content text. It is part of the segment, so tapping it chooses the mode, whose
 * explanation the caller shows (EXPERIENCE *Feature flag BETA*); the segment's label names the beta status.
 */
data class ModeBetaBadge(
    val mode: TeachingMode,
    val label: String,
)

/**
 * The Teaching Mode segmented switcher: one segment per available mode in [modes] (feature flags
 * may hide one), the active one filled with `accent`. Choosing a segment only reports it through
 * [onSelect]; the mode lives in the session. [beta] puts a beta label under one segment's name.
 */
@Composable
fun TeachingModeSwitcher(
    modes: List<TeachingMode>,
    selected: TeachingMode,
    onSelect: (TeachingMode) -> Unit,
    modifier: Modifier = Modifier,
    beta: ModeBetaBadge? = null,
) {
    val tokens = RubikTheme.tokens
    val shape = MaterialTheme.shapes.medium
    val description = stringResource(R.string.lesson_mode_description)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.labelLarge
    val labels = modes.map { stringResource(labelOf(it)) }
    val badge = beta?.takeIf { it.mode in modes }
    val badgeStyle = MaterialTheme.typography.labelMedium
    BoxWithConstraints(
        modifier =
            modifier
                .testTag("lesson:modeSwitcher")
                .clip(shape)
                .border(1.dp, tokens.hairline, shape)
                .selectableGroup()
                .semantics { contentDescription = description },
    ) {
        val width = with(density) { (maxWidth / modes.size.coerceAtLeast(1) - Spacing.s3 * 2).toPx() }
        // Wrapping at spaces is fine; reflow only if a single word (or the beta label) cannot fit.
        val badgeWidth =
            badge?.let { measurer.measure(it.label, badgeStyle).size.width + with(density) { (Spacing.s1 * 2).toPx() } } ?: 0f
        val grid =
            badgeWidth > width ||
                labels.any { label ->
                    label.split(Regex("\\s+")).any { measurer.measure(it, style).size.width > width }
                }
        Column {
            (if (grid) modes.chunked(2) else listOf(modes)).forEach { row ->
                val badged = badge != null && badge.mode in row
                Row(if (badged) Modifier.fillMaxWidth().height(IntrinsicSize.Min) else Modifier.fillMaxWidth()) {
                    row.forEach { mode ->
                        Segment(
                            mode,
                            labelOf(mode),
                            mode == selected,
                            onSelect,
                            if (badged) Modifier.weight(1f).fillMaxHeight() else Modifier.weight(1f),
                            badge?.takeIf { it.mode == mode },
                        )
                    }
                }
            }
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
    badge: ModeBetaBadge? = null,
) {
    val tokens = RubikTheme.tokens
    val text = stringResource(label)
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.touchMin)
                .background(if (active) tokens.accent else tokens.surfaceRaised)
                .selectable(selected = active, role = Role.RadioButton, onClick = { onSelect(mode) })
                .then(if (badge != null) Modifier.semantics { contentDescription = "$text, ${badge.label}" } else Modifier)
                .testTag("mode:${mode.name}")
                .padding(horizontal = Spacing.s3),
        contentAlignment = Alignment.Center,
    ) {
        val ink = if (active) tokens.onAccent else tokens.inkPrimary
        if (badge == null) {
            Text(text = text, style = MaterialTheme.typography.labelLarge, color = ink)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = text, style = MaterialTheme.typography.labelLarge, color = ink)
                Text(
                    text = badge.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = ink,
                    modifier =
                        Modifier
                            .border(1.dp, ink, RoundedCornerShape(Radii.xs))
                            .padding(horizontal = Spacing.s1)
                            .testTag("mode:${mode.name}:beta"),
                )
            }
        }
    }
}
