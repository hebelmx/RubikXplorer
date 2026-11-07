package dev.rubikxplorer.app.ui.trend

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rubikxplorer.app.ui.theme.RubikTheme

/**
 * A dot sparkline showing hints per attempt in date order (UX-DR18, UX-DR28, SM-C1).
 *
 * Rendered in `inkSecondary` with the latest point emphasized (in `accent`).
 * Total hints is never displayed as a score, and red is never used for hint use.
 */
@Composable
fun DotSparkline(
    hintCounts: List<Int>,
    modifier: Modifier = Modifier,
) {
    val tokens = RubikTheme.tokens
    Canvas(
        modifier =
            modifier
                .heightIn(min = 20.dp)
                .testTag("trend:sparkline"),
    ) {
        val radiusNormal = 2.5.dp.toPx()
        val radiusLatest = 4.5.dp.toPx()
        val paddingY = radiusLatest + 1.dp.toPx()
        val paddingX = radiusLatest + 2.dp.toPx()

        if (hintCounts.isEmpty()) {
            val y = size.height - paddingY
            drawLine(
                color = tokens.hairline,
                start = Offset(paddingX, y),
                end = Offset(size.width - paddingX, y),
                strokeWidth = 1.dp.toPx(),
            )
            return@Canvas
        }

        val windowed = hintCounts.takeLast(20)
        val maxHints = maxOf(windowed.maxOrNull() ?: 1, 3)

        val availableHeight = (size.height - 2 * paddingY).coerceAtLeast(0f)
        val availableWidth = (size.width - 2 * paddingX).coerceAtLeast(0f)

        fun yForHints(hints: Int): Float = paddingY + availableHeight * (1f - (hints.toFloat() / maxHints).coerceIn(0f, 1f))

        if (windowed.size == 1) {
            val cx = size.width / 2f
            val cy = yForHints(windowed[0])
            drawCircle(
                color = tokens.accent,
                radius = radiusLatest,
                center = Offset(cx, cy),
            )
            return@Canvas
        }

        val stepX = availableWidth / (windowed.size - 1)
        val points =
            windowed.mapIndexed { i, hints ->
                Offset(
                    x = paddingX + i * stepX,
                    y = yForHints(hints),
                )
            }

        // Connecting hairline line
        val strokeWidth = 1.5.dp.toPx()
        val lineColor = tokens.inkSecondary.copy(alpha = 0.35f)
        for (i in 0 until points.size - 1) {
            drawLine(
                color = lineColor,
                start = points[i],
                end = points[i + 1],
                strokeWidth = strokeWidth,
            )
        }

        // Earlier dots
        for (i in 0 until points.size - 1) {
            drawCircle(
                color = tokens.inkSecondary,
                radius = radiusNormal,
                center = points[i],
            )
        }

        // Latest dot emphasized
        drawCircle(
            color = tokens.accent,
            radius = radiusLatest,
            center = points.last(),
        )
    }
}
