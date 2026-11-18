package dev.cubentia.app.ui.playback

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.domain.playback.MovePicture
import dev.cubentia.domain.playback.MovePicture.Direction
import dev.cubentia.domain.playback.MovePicture.Shape
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun MovePictureView(
    picture: MovePicture,
    modifier: Modifier = Modifier,
) {
    val ink = RubikTheme.tokens.inkPrimary
    val shading = RubikTheme.tokens.inkSecondary.copy(alpha = 0.35f)
    Canvas(modifier.clearAndSetSemantics {}) {
        val unit = minOf(size.width / 30f, size.height / 24f)
        scale(unit, unit, pivot = Offset.Zero) {
            val rearArrowTip = if (picture.arrow.behind) drawTurnArrow(picture.arrow, ink) else null
            for ((row, col) in picture.shadedCells) {
                drawRect(shading, Offset(3f + col * 6f, 3f + row * 6f), Size(6f, 6f))
            }
            drawRect(ink, Offset(3f, 3f), Size(18f, 18f), style = Stroke(0.8f))
            for (line in listOf(9f, 15f)) {
                drawLine(ink, Offset(line, 3f), Offset(line, 21f), 0.8f)
                drawLine(ink, Offset(3f, line), Offset(21f, line), 0.8f)
            }
            if (picture.doubledOutline) drawRect(ink, Offset(1.4f, 1.4f), Size(21.2f, 21.2f), style = Stroke(0.7f))
            if (picture.view == MovePicture.View.TOP) drawLine(ink, Offset(10f, 1.2f), Offset(14f, 1.2f), 1f)
            val tip = rearArrowTip ?: drawTurnArrow(picture.arrow, ink)
            if (picture.superscriptTwo) drawHalfTurnMarker(tip, ink)
        }
    }
}

private fun DrawScope.drawTurnArrow(
    arrow: MovePicture.Arrow,
    ink: Color,
): Offset {
    if (arrow.shape == Shape.ROUND) {
        val radii = if (arrow.doubled) listOf(6f, 8.5f) else listOf(7.5f)
        val sweep = if (arrow.direction == Direction.CW) 275f else -275f
        val start = if (sweep > 0) -55f else 220f
        val stroke =
            Stroke(
                width = 1.2f,
                cap = StrokeCap.Round,
                pathEffect = if (arrow.behind) PathEffect.dashPathEffect(floatArrayOf(2f, 1.2f)) else null,
            )
        val angle = Math.toRadians((start + sweep).toDouble())
        val radial = Offset(cos(angle).toFloat(), sin(angle).toFloat())
        val tangent = if (sweep > 0) Offset(-radial.y, radial.x) else Offset(radial.y, -radial.x)
        val center = Offset(12f, 12f)
        for (radius in radii) {
            drawArc(ink, start, sweep, false, Offset(12f - radius, 12f - radius), Size(radius * 2, radius * 2), style = stroke)
            drawArrowhead(center + radial * radius, tangent, ink, if (arrow.doubled) 1.1f else 1.7f)
        }
        return center + radial * radii.last()
    }

    val lane = 6f + (arrow.span.first + arrow.span.last) * 3f
    val curveDepth = if (arrow.span.count() == 3) 7f else 3f
    val bend =
        when {
            arrow.shape != Shape.CURVED -> 0f
            lane > 12f -> -curveDepth
            else -> curveDepth
        }
    val vertical = arrow.direction == Direction.UP || arrow.direction == Direction.DOWN

    fun point(
        along: Float,
        across: Float,
    ) = if (vertical) Offset(across, along) else Offset(along, across)
    val forward = arrow.direction == Direction.DOWN || arrow.direction == Direction.RIGHT
    val start = point(if (forward) 4.5f else 19.5f, lane)
    val end = point(if (forward) 19.5f else 4.5f, lane)
    val control = point(12f, lane + bend)
    val path =
        Path().apply {
            moveTo(start.x, start.y)
            quadraticTo(control.x, control.y, end.x, end.y)
        }
    drawPath(path, ink, style = Stroke(1.4f, cap = StrokeCap.Round))
    val tangent = end - control
    drawArrowhead(end, tangent / tangent.getDistance(), ink, 1.8f)
    return end
}

private fun DrawScope.drawHalfTurnMarker(
    tip: Offset,
    ink: Color,
) {
    // In the clear margin right of the grid, level with the arrowhead, so it never crosses the grid.
    val x = 23f
    val y = (tip.y - 6f).coerceIn(0f, 16f)
    val two =
        Path().apply {
            moveTo(x, y + 3f)
            cubicTo(x + 2f, y, x + 5f, y + 1.4f, x + 3.5f, y + 4f)
            lineTo(x, y + 8f)
            lineTo(x + 4.5f, y + 8f)
        }
    drawPath(two, ink, style = Stroke(1.2f, cap = StrokeCap.Round))
}

private fun DrawScope.drawArrowhead(
    tip: Offset,
    direction: Offset,
    ink: Color,
    width: Float,
) {
    val base = tip - direction * (width * 1.75f)
    val side = Offset(-direction.y, direction.x) * width
    val head =
        Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(base.x + side.x, base.y + side.y)
            lineTo(base.x - side.x, base.y - side.y)
            close()
        }
    drawPath(head, ink)
}
