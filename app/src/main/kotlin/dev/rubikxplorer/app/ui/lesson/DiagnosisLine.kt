package dev.rubikxplorer.app.ui.lesson

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.rubikxplorer.app.R
import dev.rubikxplorer.app.ui.theme.RubikTheme
import dev.rubikxplorer.app.ui.theme.Spacing
import dev.rubikxplorer.domain.cube.Move

/**
 * The tutor's Diagnosis of the last Move (UX-DR24, UX-DR28): its [explanation], then the Recovery
 * Path's [prompt], both already resolved. Neutral ink in one polite live region, with no icon, no
 * red, no shake and no animation: a mistake is named calmly, never staged. When the Recovery Path
 * offers *Show me how* (a turned cube, never undone), [showMeHow] holds the Cube Rotations back to
 * the Lesson's hold: tapping the offer calls [onShowMeHow] with them (the stage animates them as a
 * demonstration) and shows them as notation chips for a caption; it never turns the session's cube.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DiagnosisLine(
    explanation: String,
    prompt: String?,
    modifier: Modifier = Modifier,
    showMeHow: List<Move>? = null,
    onShowMeHow: (List<Move>) -> Unit = {},
) {
    val tokens = RubikTheme.tokens
    var shown by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.s1)) {
        Column(
            modifier =
                Modifier
                    .testTag("lesson:diagnosis")
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(Spacing.s1),
        ) {
            Text(explanation, style = MaterialTheme.typography.bodyLarge, color = tokens.inkPrimary)
            prompt?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = tokens.inkPrimary) }
        }
        if (showMeHow != null) {
            if (shown) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s2),
                    modifier = Modifier.testTag("lesson:reorientMoves"),
                ) {
                    showMeHow.forEach { NotationChip(it) }
                }
            } else {
                TextButton(
                    onClick = {
                        shown = true
                        onShowMeHow(showMeHow)
                    },
                    modifier = Modifier.heightIn(min = Spacing.touchMin).testTag("lesson:showMeHow"),
                ) {
                    Text(stringResource(R.string.lesson_show_me_how), color = tokens.accent)
                }
            }
        }
    }
}
