package dev.cubentia.app.ui.editor

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cubentia.app.R
import dev.cubentia.app.preferences.LocalRenderStyle
import dev.cubentia.app.render.Net2DRenderer
import dev.cubentia.app.render.RenderStyle
import dev.cubentia.app.render.STICKER_GAP_FRACTION
import dev.cubentia.app.render.StickerGlyph
import dev.cubentia.app.render.stickerColor
import dev.cubentia.app.render.stickerGlyphInk
import dev.cubentia.app.ui.theme.CubeTokens
import dev.cubentia.app.ui.theme.Radii
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.domain.appearance.StickerAppearance
import dev.cubentia.domain.cube.Facelets
import kotlin.math.max
import kotlin.math.min
import dev.cubentia.domain.cube.Color as CubeColor

/** Whether a sticker carries the `status-invalid` outline (it belongs to an offending piece). */
val StickerInvalidKey = SemanticsPropertyKey<Boolean>("StickerInvalid")
var SemanticsPropertyReceiver.stickerInvalid by StickerInvalidKey

private const val OUTLINE_DP = 3
private const val INNER_STROKE_DP = 1
private const val DASH_DP = 4
private const val CELLS_PER_FACE = 9
private const val FACE_SIDE = 3

/** Plain face names in URFDLB order, for sticker descriptions. */
internal val FACE_NAMES =
    listOf(
        R.string.move_face_u,
        R.string.move_face_r,
        R.string.move_face_f,
        R.string.move_face_d,
        R.string.move_face_l,
        R.string.move_face_b,
    )

/**
 * A tappable Kociemba cross net of [facelets] (Sticker review net, UX-DR16): fills and glyphs come
 * from [StickerAppearance] (glyphs while [style] asks for them), the [selected] sticker gets an
 * `accent` ring and every [invalid] sticker a `status-invalid` outline. Tapping an [editable] sticker
 * calls [onStickerClick]; other stickers are not clickable. With a [face] (URFDLB index) only that
 * face's 3×3 stickers are drawn, for windows too narrow to give every net sticker a touch-sized cell.
 * Reusable for scan review.
 */
@Composable
fun StickerReviewNet(
    facelets: Facelets,
    style: RenderStyle = LocalRenderStyle.current,
    selected: Int?,
    invalid: Set<Int>,
    onStickerClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    editable: (Int) -> Boolean = { true },
    face: Int? = null,
) {
    val columns = if (face == null) Net2DRenderer.NET_COLUMNS else FACE_SIDE
    val rows = if (face == null) Net2DRenderer.NET_ROWS else FACE_SIDE
    val indices = if (face == null) 0 until Facelets.SIZE else face * CELLS_PER_FACE until (face + 1) * CELLS_PER_FACE
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val cell = min(maxWidth.value / columns, maxHeight.value / rows).dp
        Box(Modifier.requiredSize(cell * columns, cell * rows).testTag("reviewNet")) {
            for (index in indices) {
                val (column, row) =
                    if (face == null) {
                        Net2DRenderer.netCell(index)
                    } else {
                        (index % CELLS_PER_FACE % FACE_SIDE) to (index % CELLS_PER_FACE / FACE_SIDE)
                    }
                ReviewSticker(
                    color = facelets.colors[index],
                    index = index,
                    style = style,
                    isSelected = index == selected,
                    isInvalid = index in invalid,
                    onClick = if (editable(index)) ({ onStickerClick(index) }) else null,
                    cell = cell,
                    modifier = Modifier.offset(cell * column, cell * row),
                )
            }
        }
    }
}

@Composable
private fun ReviewSticker(
    color: CubeColor,
    index: Int,
    style: RenderStyle,
    isSelected: Boolean,
    isInvalid: Boolean,
    onClick: (() -> Unit)?,
    cell: Dp,
    modifier: Modifier,
) {
    val appearance = StickerAppearance.of(color)
    val fill = Color(appearance.fill)
    val ink = Color(appearance.glyphInk)
    val tokens = RubikTheme.tokens
    val description =
        stringResource(
            R.string.editor_sticker,
            stringResource(colorName(color)),
            stringResource(FACE_NAMES[index / CELLS_PER_FACE]),
            index % CELLS_PER_FACE + 1,
        )
    val invalidState = stringResource(R.string.editor_sticker_invalid)
    Box(
        modifier =
            modifier
                .requiredSize(cell)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(cell * STICKER_GAP_FRACTION)
                .background(fill, RoundedCornerShape(Radii.xs))
                .drawWithContent {
                    drawContent()
                    var inset = 0f
                    if (isInvalid) inset = drawRing(tokens.statusInvalid, inset, dashed = false)
                    // Dashed, so selection differs from the invalid outline in shape, not only color.
                    if (isSelected) drawRing(tokens.accent, inset, dashed = true)
                }.testTag("sticker:$index")
                .semantics {
                    contentDescription = description
                    stickerColor = fill
                    if (style.colorBlindGlyphs) stickerGlyphInk = ink
                    selected = isSelected
                    stickerInvalid = isInvalid
                    if (isInvalid) stateDescription = invalidState
                },
        contentAlignment = Alignment.Center,
    ) {
        if (style.colorBlindGlyphs) StickerGlyph(appearance, cell)
    }
}

/**
 * A 3dp [color] ring with a 1dp `cubie-body` inner stroke, [inset] px inside the sticker edge, so it
 * reads on every sticker fill, solid or [dashed]; returns the inset for a further ring.
 */
private fun DrawScope.drawRing(
    color: Color,
    inset: Float,
    dashed: Boolean,
): Float {
    val outer = OUTLINE_DP.dp.toPx()
    val inner = INNER_STROKE_DP.dp.toPx()
    val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(DASH_DP.dp.toPx(), DASH_DP.dp.toPx())) else null
    val radius = Radii.xs.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(inset + outer / 2, inset + outer / 2),
        size = Size(size.width - 2 * inset - outer, size.height - 2 * inset - outer),
        cornerRadius = CornerRadius(max(0f, radius - inset)),
        style = Stroke(outer, pathEffect = effect),
    )
    drawRoundRect(
        color = CubeTokens.cubieBody,
        topLeft = Offset(inset + outer + inner / 2, inset + outer + inner / 2),
        size = Size(size.width - 2 * (inset + outer) - inner, size.height - 2 * (inset + outer) - inner),
        cornerRadius = CornerRadius(max(0f, radius - inset - outer)),
        style = Stroke(inner, pathEffect = effect),
    )
    return inset + outer + inner
}

/** The plain name of a sticker [color], for content descriptions and palette labels. */
@StringRes
internal fun colorName(color: CubeColor): Int =
    when (color) {
        CubeColor.WHITE -> R.string.color_white
        CubeColor.YELLOW -> R.string.color_yellow
        CubeColor.RED -> R.string.color_red
        CubeColor.ORANGE -> R.string.color_orange
        CubeColor.GREEN -> R.string.color_green
        CubeColor.BLUE -> R.string.color_blue
    }
