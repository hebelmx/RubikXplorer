package dev.rubikxplorer.app.ui.lesson

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.rubikxplorer.app.R
import dev.rubikxplorer.app.session.SessionHost
import dev.rubikxplorer.app.ui.theme.RubikTheme
import dev.rubikxplorer.application.session.SessionEvent
import dev.rubikxplorer.application.session.Transition
import kotlinx.coroutines.launch
import dev.rubikxplorer.domain.text.Text as ContentText

/**
 * Asks before a Reset that would lose Lesson steps (FR-3). Shown while [host]'s session has a
 * pending reset confirmation; the prompt comes from the content strings through [resolve]. Confirm
 * dispatches `ResetConfirmed`, dismiss `ResetDeclined`, each echoing the pending effect's id and
 * revision; each [Transition] is handed to [onTransition] (so a screen can draw the Reset snap). The
 * dialog holds no state: the pending confirmation lives in the session.
 */
@Composable
fun ResetConfirmation(
    host: SessionHost,
    resolve: (ContentText) -> String,
    modifier: Modifier = Modifier,
    onTransition: (Transition) -> Unit = {},
) {
    val session by host.state.collectAsState()
    val pending = session.pendingReset ?: return
    val scope = rememberCoroutineScope()
    val tokens = RubikTheme.tokens
    val decline = { scope.launch { onTransition(host.dispatch { SessionEvent.ResetDeclined(pending.effectId, pending.revision, it) }) } }
    val confirm = { scope.launch { onTransition(host.dispatch { SessionEvent.ResetConfirmed(pending.effectId, pending.revision, it) }) } }
    AlertDialog(
        onDismissRequest = { decline() },
        confirmButton = {
            TextButton(
                onClick = { confirm() },
                modifier = Modifier.testTag("resetConfirmation:confirm"),
            ) {
                Text(stringResource(R.string.toolbar_reset), color = tokens.inkPrimary)
            }
        },
        dismissButton = {
            TextButton(
                onClick = { decline() },
                modifier = Modifier.testTag("resetConfirmation:cancel"),
            ) {
                Text(stringResource(R.string.dialog_cancel), color = tokens.inkSecondary)
            }
        },
        text = { Text(resolve(pending.prompt), color = tokens.inkPrimary, modifier = Modifier.testTag("resetConfirmation:message")) },
        containerColor = tokens.surfaceRaised,
        modifier = modifier.testTag("resetConfirmation"),
    )
}
