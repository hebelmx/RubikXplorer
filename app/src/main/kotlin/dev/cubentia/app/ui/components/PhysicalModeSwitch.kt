package dev.cubentia.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing

@Composable
internal fun PhysicalModeSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = RubikTheme.tokens
    Row(
        modifier =
            modifier
                .heightIn(min = Spacing.touchMin)
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .clearAndSetSemantics { contentDescription = label }
                .padding(horizontal = Spacing.s2),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = tokens.inkSecondary)
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors =
                SwitchDefaults.colors(
                    uncheckedThumbColor = tokens.inkSecondary,
                    uncheckedTrackColor = tokens.surfaceStage,
                    uncheckedBorderColor = tokens.inkSecondary,
                ),
        )
    }
}
