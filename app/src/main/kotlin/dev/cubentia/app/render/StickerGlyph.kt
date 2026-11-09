package dev.cubentia.app.render

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import dev.cubentia.app.ui.theme.PlexSans
import dev.cubentia.domain.appearance.StickerAppearance
import kotlin.math.max
import kotlin.math.min

/** The gap around each sticker inside its cell, as a fraction of the cell. */
internal const val STICKER_GAP_FRACTION = 0.04f
private const val GLYPH_FRACTION = 0.55f
private const val MIN_GLYPH_DP = 16f

/**
 * The color-blind glyph of [appearance] (AD-17) for a sticker drawn in a [cell] with the
 * [STICKER_GAP_FRACTION] gap. Sized in dp so font scaling never shrinks it below legibility (≥ 16dp,
 * weight 600), but never larger than the sticker, so it cannot spill onto its neighbours.
 */
@Composable
internal fun StickerGlyph(
    appearance: StickerAppearance,
    cell: Dp,
) {
    val inner = cell.value * (1f - 2f * STICKER_GAP_FRACTION)
    val glyphDp = min(max(MIN_GLYPH_DP, cell.value * GLYPH_FRACTION), inner)
    val fontSize = with(LocalDensity.current) { (glyphDp / fontScale).sp }
    Text(
        text = appearance.glyph.toString(),
        color = Color(appearance.glyphInk),
        fontFamily = PlexSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = fontSize,
        lineHeight = fontSize,
        softWrap = false,
    )
}
