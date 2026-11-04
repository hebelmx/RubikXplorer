package dev.rubikxplorer.app.render.gl

import dev.rubikxplorer.app.render.RenderStyle
import dev.rubikxplorer.domain.appearance.StickerAppearance

/** The same appearance values used to build GL vertices, in read-back facelet order. */
data class StickerDraw(
    val fill: Long,
    val glyph: Char?,
    val glyphInk: Long?,
)

object CubeMesh {
    const val FLOATS_PER_VERTEX = 15
    private const val BODY_HALF = 0.47f
    private const val STICKER_HALF = 0.40f
    private const val STICKER_LIFT = 0.472f

    fun stickerDraws(
        model: CubieModel,
        style: RenderStyle,
    ): List<StickerDraw> = model.facelets().colors.map { appearance(it, style) }

    private fun appearance(
        color: dev.rubikxplorer.domain.cube.Color,
        style: RenderStyle,
    ): StickerDraw {
        val a = StickerAppearance.of(color)
        return StickerDraw(a.fill, a.glyph.takeIf { style.colorBlindGlyphs }, a.glyphInk.takeIf { style.colorBlindGlyphs })
    }

    fun vertexCounts(model: CubieModel): IntArray =
        model.cubies
            .map {
                if (it.stickers.isEmpty()) 0 else 36 + it.stickers.size * 6
            }.toIntArray()

    fun vertices(
        model: CubieModel,
        style: RenderStyle,
    ): FloatArray {
        val data = ArrayList<Float>()
        for (cubie in model.cubies) {
            if (cubie.stickers.isEmpty()) continue
            box(data, BODY_HALF, rgb(StickerAppearance.CUBIE_BODY))
            for (sticker in cubie.stickers) {
                val a = appearance(sticker.color, style)
                quad(data, sticker.nx, sticker.ny, sticker.nz, rgb(a.fill), a.glyph, rgb(a.glyphInk ?: a.fill))
            }
        }
        return data.toFloatArray()
    }

    fun rgb(argb: Long) =
        floatArrayOf(
            ((argb shr 16) and 0xFF) / 255f,
            ((argb shr 8) and 0xFF) / 255f,
            (argb and 0xFF) / 255f,
        )

    /** Two counter-clockwise triangles of the square at distance [lift] along the normal. */
    fun face(
        out: MutableList<Float>,
        nx: Int,
        ny: Int,
        nz: Int,
        half: Float,
        lift: Float,
        color: FloatArray,
        glyph: Char? = null,
        ink: FloatArray = color,
    ) {
        val n = floatArrayOf(nx.toFloat(), ny.toFloat(), nz.toFloat())
        // Two tangents u, v with u × v = n.
        val u = if (nx != 0) floatArrayOf(0f, 0f, -nx.toFloat()) else floatArrayOf(1f, 0f, 0f)
        val v = cross(n, u)

        fun corner(
            a: Float,
            b: Float,
        ) {
            for (k in 0 until 3) out += n[k] * lift + (u[k] * a + v[k] * b) * half
            for (k in 0 until 3) out += n[k]
            for (k in 0 until 3) out += color[k]
            out += GlyphAtlas.u(glyph ?: 'W', (a + 1f) / 2f)
            out += (1f - b) / 2f
            for (k in 0 until 3) out += ink[k]
            out += if (glyph == null) 0f else 1f
        }
        corner(-1f, -1f)
        corner(1f, -1f)
        corner(1f, 1f)
        corner(-1f, -1f)
        corner(1f, 1f)
        corner(-1f, 1f)
    }

    fun cross(
        a: FloatArray,
        b: FloatArray,
    ) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    fun box(
        out: MutableList<Float>,
        half: Float,
        color: FloatArray,
        glyph: Char? = null,
        ink: FloatArray = color,
    ) {
        for ((nx, ny, nz) in AXES) face(out, nx, ny, nz, half, half, color)
    }

    fun quad(
        out: MutableList<Float>,
        nx: Int,
        ny: Int,
        nz: Int,
        color: FloatArray,
        glyph: Char? = null,
        ink: FloatArray = color,
    ) = face(out, nx, ny, nz, STICKER_HALF, STICKER_LIFT, color, glyph, ink)

    val AXES =
        listOf(
            Triple(1, 0, 0),
            Triple(-1, 0, 0),
            Triple(0, 1, 0),
            Triple(0, -1, 0),
            Triple(0, 0, 1),
            Triple(0, 0, -1),
        )
}
