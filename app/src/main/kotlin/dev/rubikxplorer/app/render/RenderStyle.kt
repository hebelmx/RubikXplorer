package dev.rubikxplorer.app.render

/** How stickers are drawn; [colorBlindGlyphs] adds the `W Y R O G B` glyph to every sticker (AD-17). */
data class RenderStyle(
    val colorBlindGlyphs: Boolean = false,
)
