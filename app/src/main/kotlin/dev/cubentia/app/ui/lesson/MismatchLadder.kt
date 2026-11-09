package dev.cubentia.app.ui.lesson

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.domain.text.Text as ContentText

/**
 * The physical mismatch ladder (Story 8.3, FR-9).
 *
 * Asks in FR-9 order (skipping Quick Check while scanning is unavailable):
 * 1. "Which turn did you make?" with Move keys.
 * 2. One-face picker: "Look at the front face — which corner is white?" It is a prompt only: every
 *    answer leads to rung 3, the session does not change and no partial observation is made.
 * 3. "Let's re-enter your cube" via the Manual Cube Editor.
 *
 * Every rung also offers *It matches after all*, which dismisses a false alarm ([onMatchesAfterAll]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MismatchLadder(
    onOpenEditor: () -> Unit,
    strings: ContentStrings,
    onMatchesAfterAll: () -> Unit,
    rung: Int,
    onRungChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = RubikTheme.tokens
    val shape = MaterialTheme.shapes.large

    Column(
        modifier =
            modifier
                .testTag("mismatch:ladder")
                .clip(shape)
                .background(tokens.surfaceRaised)
                .border(1.dp, tokens.hairline, shape)
                .padding(Spacing.s4),
        verticalArrangement = Arrangement.spacedBy(Spacing.s3),
    ) {
        when (rung) {
            1 -> {
                Text(
                    text = strings.resolve(ContentText("msg.physical.mismatch.which-turn")),
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.inkPrimary,
                    modifier = Modifier.testTag("mismatch:question"),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = { onRungChange(2) },
                        shape = MaterialTheme.shapes.medium,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = Spacing.touchMin)
                                .testTag("mismatch:nextQuestion"),
                    ) {
                        Text(
                            text = strings.resolve(ContentText("msg.physical.mismatch.next-question")),
                            color = tokens.inkPrimary,
                        )
                    }
                }
            }

            2 -> {
                Text(
                    text = strings.resolve(ContentText("msg.physical.mismatch.one-face")),
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.inkPrimary,
                    modifier = Modifier.testTag("mismatch:question"),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s2),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = { onRungChange(3) },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.testTag("mismatch:corner:top_left"),
                    ) {
                        Text(text = strings.resolve(ContentText("msg.physical.mismatch.corner-top-left")))
                    }
                    OutlinedButton(
                        onClick = { onRungChange(3) },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.testTag("mismatch:corner:top_right"),
                    ) {
                        Text(text = strings.resolve(ContentText("msg.physical.mismatch.corner-top-right")))
                    }
                    OutlinedButton(
                        onClick = { onRungChange(3) },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.testTag("mismatch:corner:bottom_left"),
                    ) {
                        Text(text = strings.resolve(ContentText("msg.physical.mismatch.corner-bottom-left")))
                    }
                    OutlinedButton(
                        onClick = { onRungChange(3) },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.testTag("mismatch:corner:bottom_right"),
                    ) {
                        Text(text = strings.resolve(ContentText("msg.physical.mismatch.corner-bottom-right")))
                    }
                    OutlinedButton(
                        onClick = { onRungChange(3) },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.testTag("mismatch:corner:none"),
                    ) {
                        Text(text = strings.resolve(ContentText("msg.physical.mismatch.corner-none")))
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = { onRungChange(3) },
                        shape = MaterialTheme.shapes.medium,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = Spacing.touchMin)
                                .testTag("mismatch:nextQuestion"),
                    ) {
                        Text(
                            text = strings.resolve(ContentText("msg.physical.mismatch.next-question")),
                            color = tokens.inkPrimary,
                        )
                    }
                }
            }

            3 -> {
                Text(
                    text = strings.resolve(ContentText("msg.physical.mismatch.re-enter")),
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.inkPrimary,
                    modifier = Modifier.testTag("mismatch:question"),
                )
                Button(
                    onClick = onOpenEditor,
                    shape = MaterialTheme.shapes.medium,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = tokens.accent,
                            contentColor = tokens.onAccent,
                        ),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = Spacing.touchMin)
                            .testTag("mismatch:reenter"),
                ) {
                    Text(text = strings.resolve(ContentText("btn.physical.confirm-enter")))
                }
            }
        }
        OutlinedButton(
            onClick = onMatchesAfterAll,
            shape = MaterialTheme.shapes.medium,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Spacing.touchMin)
                    .testTag("mismatch:matchesAfterAll"),
        ) {
            Text(text = strings.resolve(ContentText("msg.physical.mismatch.matches-after-all")), color = tokens.inkPrimary)
        }
    }
}
