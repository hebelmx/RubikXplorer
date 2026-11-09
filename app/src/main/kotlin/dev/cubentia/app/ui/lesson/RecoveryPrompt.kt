package dev.cubentia.app.ui.lesson

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.domain.tutor.RecoveryOffer

/** One recovery button, already resolved: the Recovery Path's [offer], its content-text [label] and whether it can be tapped. */
internal data class RecoveryAction(
    val offer: RecoveryOffer,
    val label: String,
    val enabled: Boolean,
)

/**
 * The recovery prompt (UX-DR11, UX-DR25): the Diagnosis's Recovery Path offers as inline outlined
 * buttons, in the order given, inside the step card and never in a dialog. It decides nothing: the
 * offers, their labels and whether each is enabled come from the caller (the tutor's decision).
 * Wraps under large type; renders nothing for an empty list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecoveryPrompt(
    actions: List<RecoveryAction>,
    onAction: (RecoveryOffer) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return
    val tokens = RubikTheme.tokens
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
        verticalArrangement = Arrangement.spacedBy(Spacing.s2),
        modifier = modifier.testTag("lesson:recovery"),
    ) {
        actions.forEach { action ->
            OutlinedButton(
                onClick = { onAction(action.offer) },
                enabled = action.enabled,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.heightIn(min = Spacing.touchMin).testTag(recoveryTag(action.offer)),
            ) {
                Text(action.label, color = if (action.enabled) tokens.inkPrimary else tokens.inkDisabled)
            }
        }
    }
}

/** `lesson:recovery:<offer>`, with the offer in camel case (`undo`, `guideMeBack`, `tryAgain`). */
internal fun recoveryTag(offer: RecoveryOffer): String {
    val camel =
        offer.name
            .lowercase()
            .split('_')
            .mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar { it.uppercase() } }
            .joinToString("")
    return "lesson:recovery:$camel"
}
