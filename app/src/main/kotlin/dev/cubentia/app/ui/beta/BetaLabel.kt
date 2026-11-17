package dev.cubentia.app.ui.beta

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.cubentia.app.ui.theme.Radii
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing

@Composable
internal fun BetaLabel(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = color,
        style = style,
        modifier = Modifier.border(1.dp, color, RoundedCornerShape(Radii.xs)).padding(horizontal = Spacing.s1).then(modifier),
    )
}

@Composable
internal fun BetaNote(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = RubikTheme.tokens.inkSecondary,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
