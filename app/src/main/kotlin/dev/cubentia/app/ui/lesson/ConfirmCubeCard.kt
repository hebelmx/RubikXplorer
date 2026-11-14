package dev.cubentia.app.ui.lesson

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.domain.text.Text as ContentText

/**
 * The Confirm-your-cube card (Epic 8, Story 8.2): shown upon entering physical mode while
 * in `SyncState.PHYSICAL_AWAITING_CONFIRM`. Offers *It matches* (confirms the current state)
 * and *Enter it* (opens the Manual Cube Editor). *Scan it* remains hidden until Epic 10.
 */
@Composable
fun ConfirmCubeCard(
    strings: ContentStrings,
    onMatches: () -> Unit,
    onEnter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = RubikTheme.tokens
    val shape = MaterialTheme.shapes.large
    Column(
        modifier =
            modifier
                .testTag("confirmCube:card")
                .clip(shape)
                .background(tokens.surfaceRaised)
                .border(1.dp, tokens.hairline, shape)
                .padding(Spacing.s4),
        verticalArrangement = Arrangement.spacedBy(Spacing.s3),
    ) {
        Text(
            text = strings.resolve(ContentText("msg.physical.confirm.title")),
            style = MaterialTheme.typography.titleMedium,
            color = tokens.inkPrimary,
            modifier = Modifier.testTag("confirmCube:title"),
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val style = MaterialTheme.typography.labelLarge
            val labels = listOf("btn.physical.confirm-enter", "btn.physical.confirm-matches").map { strings.resolve(ContentText(it)) }
            val width = with(density) { ((maxWidth - Spacing.s3) / 2 - Spacing.s3 * 2).toPx() }
            val stack = labels.any { label -> label.split(Regex("\\s+")).any { measurer.measure(it, style).size.width > width } }
            val buttons: @Composable (Modifier) -> Unit = { buttonModifier ->
                OutlinedButton(
                    onClick = onEnter,
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = Spacing.s3, vertical = Spacing.s2),
                    modifier =
                        Modifier
                            .then(buttonModifier)
                            .heightIn(min = Spacing.touchMin)
                            .testTag("confirmCube:enter"),
                ) {
                    Text(
                        text = strings.resolve(ContentText("btn.physical.confirm-enter")),
                        color = tokens.inkPrimary,
                    )
                }
                Button(
                    onClick = onMatches,
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = Spacing.s3, vertical = Spacing.s2),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = tokens.accent,
                            contentColor = tokens.onAccent,
                        ),
                    modifier =
                        Modifier
                            .then(buttonModifier)
                            .heightIn(min = Spacing.touchMin)
                            .testTag("confirmCube:matches"),
                ) {
                    Text(text = strings.resolve(ContentText("btn.physical.confirm-matches")))
                }
            }
            if (stack) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s3)) { buttons(Modifier.fillMaxWidth()) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s3)) { buttons(Modifier.weight(1f)) }
            }
        }
    }
}
