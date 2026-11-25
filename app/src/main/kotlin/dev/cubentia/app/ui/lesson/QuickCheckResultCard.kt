package dev.cubentia.app.ui.lesson

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.application.session.SessionState
import dev.cubentia.application.session.SyncState
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.tutor.Certainty
import dev.cubentia.domain.text.Text as ContentText

internal data class QuickCheckResultAction(
    val label: ContentText,
    val tag: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

internal val SessionState.hasVerifiedQuickCheckFrame: Boolean
    get() = syncState == SyncState.PHYSICAL_VERIFIED && quickCheck?.certainty == Certainty.VERIFIED

internal fun quickCheckResultMessage(
    session: SessionState,
    strings: ContentStrings,
): String? =
    when {
        session.quickCheck?.certainty == Certainty.UNKNOWN -> strings.resolve(ContentText("msg.diagnosis.unknown-discrepancy"))
        session.offersFullScan -> strings.resolve(ContentText("msg.quick-check.likely-mismatch"))
        else -> null
    }

@Composable
internal fun QuickCheckResultCard(
    session: SessionState,
    strings: ContentStrings,
    actions: List<QuickCheckResultAction>,
    modifier: Modifier = Modifier,
) {
    val check = session.quickCheck ?: return
    Surface(
        modifier = modifier.fillMaxWidth().testTag("quickCheck:result"),
        color = RubikTheme.tokens.surfaceRaised,
        contentColor = RubikTheme.tokens.inkPrimary,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(Spacing.s3), verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
            val frame = if (session.hasVerifiedQuickCheckFrame) "msg.physical.frame-verified" else "msg.physical.frame-expected"
            Text(strings.resolve(ContentText(frame)), Modifier.testTag("quickCheck:frame"), style = MaterialTheme.typography.labelMedium)
            CertaintyChip(check.certainty)
            quickCheckResultMessage(session, strings)?.let { message ->
                Text(message, Modifier.testTag("quickCheck:message"))
                if (check.certainty != Certainty.UNKNOWN && session.offersFullScan) {
                    check.consistent.firstOrNull()?.let { candidate ->
                        Text(
                            strings.resolve(
                                ContentText(
                                    "msg.physical.mismatch.candidate",
                                    mapOf("candidate" to Notation.format(candidate.moves)),
                                ),
                            ),
                        )
                    }
                }
            }
            if (actions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s2), verticalArrangement = Arrangement.spacedBy(Spacing.s1)) {
                    actions.forEach { action ->
                        OutlinedButton(
                            onClick = action.onClick,
                            enabled = action.enabled,
                            modifier = Modifier.heightIn(min = Spacing.touchMin).testTag(action.tag),
                        ) {
                            Text(strings.resolve(action.label))
                        }
                    }
                }
            }
        }
    }
}
