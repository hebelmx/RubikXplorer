package dev.rubikxplorer.app.ui.lesson

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.rubikxplorer.app.ui.theme.RubikTheme
import dev.rubikxplorer.app.ui.theme.Spacing

/**
 * The tutor's Diagnosis of the last Move (UX-DR24, UX-DR28): its [explanation], then the Recovery
 * Path's [prompt], both already resolved. Neutral ink in one polite live region, with no icon, no
 * red, no shake and no animation: a mistake is named calmly, never staged.
 */
@Composable
internal fun DiagnosisLine(
    explanation: String,
    prompt: String?,
    modifier: Modifier = Modifier,
) {
    val tokens = RubikTheme.tokens
    Column(
        modifier =
            modifier
                .testTag("lesson:diagnosis")
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Spacing.s1),
    ) {
        Text(explanation, style = MaterialTheme.typography.bodyLarge, color = tokens.inkPrimary)
        prompt?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = tokens.inkPrimary) }
    }
}
