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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cubentia.app.R
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing

/**
 * The physical mismatch ladder (Story 8.3, FR-9).
 *
 * Asks in FR-9 order (skipping Quick Check while scanning is unavailable):
 * 1. "Which turn did you make?" with Move keys.
 * 2. One-face picker: "Look at the front face — which corner is white?"
 * 3. "Let's re-enter your cube" via the Manual Cube Editor.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MismatchLadder(
    onOpenEditor: () -> Unit,
    modifier: Modifier = Modifier,
    candidate: String? = null,
) {
    var rung by rememberSaveable { mutableIntStateOf(1) }
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
                    text = stringResource(R.string.physical_mismatch_which_turn),
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.inkPrimary,
                    modifier = Modifier.testTag("mismatch:question"),
                )
                if (candidate != null) {
                    Text(
                        text = stringResource(R.string.physical_mismatch_candidate, candidate),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.statusLikely,
                        modifier = Modifier.testTag("mismatch:candidate"),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = { rung = 2 },
                        shape = MaterialTheme.shapes.medium,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = Spacing.touchMin)
                                .testTag("mismatch:nextQuestion"),
                    ) {
                        Text(
                            text = stringResource(R.string.physical_mismatch_next_question),
                            color = tokens.inkPrimary,
                        )
                    }
                }
            }

            2 -> {
                Text(
                    text = stringResource(R.string.physical_mismatch_one_face),
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
                        onClick = { rung = 3 },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.testTag("mismatch:corner:top_left"),
                    ) {
                        Text(stringResource(R.string.physical_mismatch_corner_top_left))
                    }
                    OutlinedButton(
                        onClick = { rung = 3 },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.testTag("mismatch:corner:top_right"),
                    ) {
                        Text(stringResource(R.string.physical_mismatch_corner_top_right))
                    }
                    OutlinedButton(
                        onClick = { rung = 3 },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.testTag("mismatch:corner:bottom_left"),
                    ) {
                        Text(stringResource(R.string.physical_mismatch_corner_bottom_left))
                    }
                    OutlinedButton(
                        onClick = { rung = 3 },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.testTag("mismatch:corner:bottom_right"),
                    ) {
                        Text(stringResource(R.string.physical_mismatch_corner_bottom_right))
                    }
                    OutlinedButton(
                        onClick = { rung = 3 },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.testTag("mismatch:corner:none"),
                    ) {
                        Text(stringResource(R.string.physical_mismatch_corner_none))
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = { rung = 3 },
                        shape = MaterialTheme.shapes.medium,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = Spacing.touchMin)
                                .testTag("mismatch:nextQuestion"),
                    ) {
                        Text(
                            text = stringResource(R.string.physical_mismatch_next_question),
                            color = tokens.inkPrimary,
                        )
                    }
                }
            }

            3 -> {
                Text(
                    text = stringResource(R.string.physical_mismatch_reenter),
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
                    Text(text = stringResource(R.string.confirm_cube_enter))
                }
            }
        }
    }
}
