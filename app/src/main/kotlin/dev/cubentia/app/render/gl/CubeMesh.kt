package dev.cubentia.app.render.gl

import dev.cubentia.app.render.NetEmphasis
import dev.cubentia.app.render.RenderStyle
import dev.cubentia.domain.appearance.StickerAppearance
import dev.cubentia.domain.cube.CubeGeometry
import dev.cubentia.domain.predicate.Piece

/** The same appearance values used to build GL vertices, in read-back facelet order. */
data class StickerDraw(
    val fill: Long,
    val glyph: Char?,
    val glyphInk: Long?,
    val dimmed: Boolean = false,
    val highlighted: Boolean = false,
    val pulsing: Boolean = false,
)

object CubeMesh {
    const val FLOATS_PER_VERTEX = 18
    private const val BODY_HALF = CubeGeometry.BODY_HALF
    private const val STICKER_HALF = CubeGeometry.STICKER_HALF
    private const val STICKER_LIFT = CubeGeometry.STICKER_LIFT

    fun stickerDraws(
        model: CubieModel,
        style: RenderStyle,
        emphasis: NetEmphasis? = null,
    ): List<StickerDraw> {
        val flags = emphasisFlags(model, emphasis)
        val byPiece = model.cubies.mapIndexed { i, cubie -> cubie.stickers.map { it.color }.toSet() to flags[i] }.toMap()
        val facelets = model.facelets()
        return facelets.colors.mapIndexed { index, color ->
            val flag = Piece.at(facelets, index)?.let { byPiece[it.colors] } ?: 0
            appearance(color, style).copy(
                dimmed = flag and 1 != 0,
                highlighted = flag and 2 != 0,
                pulsing =
                    flag and 2 != 0 && (emphasis?.pulse ?: 0f) > 0f,
            )
        }
    }

    /** One pure rule shared by the GL snapshot and read-back semantics. */
    fun emphasisFlags(
        model: CubieModel,
        emphasis: NetEmphasis?,
    ): IntArray =
        model.cubies
            .map { cubie ->
                val piece = cubie.stickers.takeIf { it.size > 1 }?.let { Piece(it.map { sticker -> sticker.color }.toSet()) }
                (if (emphasis?.dims(piece) == true) 1 else 0) or (if (emphasis?.highlights(piece) == true) 2 else 0)
            }.toIntArray()

    private fun appearance(
        color: dev.cubentia.domain.cube.Color,
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
            out += a
            out += b
            out += if (lift == STICKER_LIFT) 1f else 0f
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
