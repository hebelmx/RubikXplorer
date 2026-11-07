package dev.rubikxplorer.app.ui.trend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.rubikxplorer.app.R
import dev.rubikxplorer.app.ui.theme.RubikTheme
import dev.rubikxplorer.app.ui.theme.Spacing

/**
 * Reusable Compose component rendering a Stage's trend row: optional label,
 * [DotSparkline] of hints per attempt in date order, and attempt count in `data` (DESIGN.md, UX-DR18, UX-DR28).
 *
 * Totals are never shown as a score, and red is never used.
 */
@Composable
fun TrendRow(
    attemptsCount: Int,
    hintCounts: List<Int>,
    modifier: Modifier = Modifier,
    label: String? = null,
    attemptsTag: String = "trend:attempts",
    sparklineTag: String = "trend:sparkline",
) {
    val tokens = RubikTheme.tokens
    val description =
        if (hintCounts.isNotEmpty()) {
            stringResource(R.string.trend_description, attemptsCount, hintCounts.last())
        } else {
            stringResource(R.string.trend_description_empty)
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
    ) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.inkSecondary,
                modifier = Modifier.testTag("trend:label"),
            )
        }
        DotSparkline(
            hintCounts = hintCounts,
            modifier =
                Modifier
                    .weight(1f)
                    .height(24.dp)
                    .semantics {
                        contentDescription = description
                    }.testTag(sparklineTag),
        )
        Text(
            text = attemptsCount.toString(),
            style = RubikTheme.textStyles.data,
            color = tokens.inkPrimary,
            modifier = Modifier.testTag(attemptsTag),
        )
    }
}
