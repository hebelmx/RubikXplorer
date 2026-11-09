package dev.cubentia.app.render.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import dev.cubentia.app.R

/** Six transparent cells with bundled IBM Plex Sans SemiBold glyphs. */
object GlyphAtlas {
    private const val GLYPHS = "WYROGB"

    fun u(
        glyph: Char,
        fraction: Float,
    ): Float = (GLYPHS.indexOf(glyph) + fraction) / GLYPHS.length

    fun create(context: Context): Bitmap {
        val bitmap = Bitmap.createBitmap(768, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                typeface = context.resources.getFont(R.font.ibm_plex_sans_semibold)
                textSize = 88f
                textAlign = Paint.Align.CENTER
            }
        GLYPHS.forEachIndexed { i, c ->
            canvas.drawText(c.toString(), i * 128f + 64f, 64f - (paint.ascent() + paint.descent()) / 2f, paint)
        }
        return bitmap
    }
}
