package dev.rubikxplorer.app.ui.lesson

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.rubikxplorer.app.R
import dev.rubikxplorer.app.ui.theme.RubikTheme
import dev.rubikxplorer.app.ui.theme.Spacing
import dev.rubikxplorer.domain.tutor.Certainty

/**
 * A rounded.full chip with an icon plus a label: ✓ Verified, ≈ Likely, ? Unknown (DESIGN.md).
 * Its color comes from the status tokens.
 */
@Composable
fun CertaintyChip(
    certainty: Certainty,
    modifier: Modifier = Modifier,
) {
    val tokens = RubikTheme.tokens
    val (icon, labelRes, color) =
        when (certainty) {
            Certainty.VERIFIED -> Triple("✓", R.string.certainty_verified, tokens.statusVerified)
            Certainty.LIKELY -> Triple("≈", R.string.certainty_likely, tokens.statusLikely)
            Certainty.UNKNOWN -> Triple("?", R.string.certainty_unknown, tokens.statusUnknown)
        }
    val label = stringResource(labelRes)
    val shape = CircleShape

    Row(
        modifier =
            modifier
                .testTag("certainty:chip")
                .clip(shape)
                .background(tokens.surfaceRaised)
                .border(1.dp, color, shape)
                .padding(horizontal = Spacing.s2, vertical = Spacing.s1)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s1),
    ) {
        Text(
            text = icon,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.testTag("certainty:icon"),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.testTag("certainty:label"),
        )
    }
}
