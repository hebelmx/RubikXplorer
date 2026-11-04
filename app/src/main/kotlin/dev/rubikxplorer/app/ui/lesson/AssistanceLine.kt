package dev.rubikxplorer.app.ui.lesson

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.rubikxplorer.app.content.ContentStrings
import dev.rubikxplorer.app.ui.theme.RubikTheme
import dev.rubikxplorer.app.ui.theme.Spacing
import dev.rubikxplorer.application.session.SessionState
import dev.rubikxplorer.domain.content.TextKey
import dev.rubikxplorer.domain.tutor.AdaptivePolicy
import dev.rubikxplorer.domain.tutor.AssistanceLevel

/** The Assistance line, already resolved: why help changed ([text]) and the *Back to my usual help* [label]. */
internal data class AssistanceContent(
    val text: String,
    val label: String,
)

/**
 * The session's Assistance line ([AdaptivePolicy.line] for its level and the shown step's mistakes), resolved; null at the usual level.
 * At the guide level [offPath] (the cube is off the authored path, so there is no next Move to show) says to Undo back instead.
 */
internal fun assistanceContent(
    session: SessionState,
    strings: ContentStrings,
    offPath: Boolean = false,
): AssistanceContent? =
    AdaptivePolicy.line(session.assistance, session.stepMistakes[session.stepIndex] ?: 0)?.let {
        val text = if (offPath && session.assistance == AssistanceLevel.GUIDE) strings.resolve(GUIDE_OFF_PATH) else strings.resolve(it)
        AssistanceContent(text, strings.resolve(USUAL_HELP_LABEL))
    }

/**
 * The Assistance line (FR-13, UX-DR10): one `meta` line under the step card saying why the help
 * changed, in a polite live region, with *Back to my usual help* as a text button. No dialog and no
 * red; it never switches the Teaching Mode. It decides nothing: the session's level is resolved by the caller.
 */
@Composable
internal fun AssistanceLine(
    content: AssistanceContent,
    onUsualHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = RubikTheme.tokens
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.s1)) {
        Text(
            text = content.text,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.inkSecondary,
            modifier = Modifier.testTag("lesson:assistance").semantics { liveRegion = LiveRegionMode.Polite },
        )
        TextButton(
            onClick = onUsualHelp,
            modifier = Modifier.heightIn(min = Spacing.touchMin).testTag("lesson:assistance:usual"),
        ) {
            Text(content.label, color = tokens.accent)
        }
    }
}

/** The Assistance line's *Back to my usual help* label (content text, AD-6). */
internal val USUAL_HELP_LABEL = TextKey("msg.assistance.usual-help")

/** The guide level's line off the authored path: Undo back to it before one-Move guidance resumes (content text, AD-6). */
internal val GUIDE_OFF_PATH = TextKey("msg.assistance.guide-off-path")
