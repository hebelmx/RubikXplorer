package dev.cubentia.domain.appearance

import dev.cubentia.domain.cube.Color

/**
 * How a sticker of one [Color] is drawn on every sticker surface (AD-17, UX-DR21): its [fill] and
 * color-blind [glyph] (`W Y R O G B`) drawn in [glyphInk]. Colors are ARGB and never depend on the
 * theme. [pattern] stays `null`: patterns are deferred.
 */
data class StickerAppearance(
    val fill: Long,
    val glyph: Char,
    val glyphInk: Long,
    val pattern: String?,
) {
    companion object {
        /** DESIGN.md `cubie-body`: the glyph ink on light stickers. */
        const val CUBIE_BODY: Long = 0xFF121417

        /** DESIGN.md `sticker-white`: the glyph ink on dark stickers. */
        const val STICKER_WHITE: Long = 0xFFF4F5F0

        private val TABLE: Map<Color, StickerAppearance> =
            mapOf(
                Color.WHITE to StickerAppearance(STICKER_WHITE, 'W', CUBIE_BODY, null),
                Color.YELLOW to StickerAppearance(0xFFFFD500, 'Y', CUBIE_BODY, null),
                Color.RED to StickerAppearance(0xFFC41E3A, 'R', STICKER_WHITE, null),
                Color.ORANGE to StickerAppearance(0xFFFF5800, 'O', CUBIE_BODY, null),
                Color.GREEN to StickerAppearance(0xFF009E60, 'G', CUBIE_BODY, null),
                Color.BLUE to StickerAppearance(0xFF0051BA, 'B', STICKER_WHITE, null),
            )

        /** The appearance of a [color] sticker. */
        fun of(color: Color): StickerAppearance = TABLE.getValue(color)
    }
}
