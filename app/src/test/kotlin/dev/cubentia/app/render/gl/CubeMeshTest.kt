package dev.cubentia.app.render.gl

import dev.cubentia.app.render.RenderStyle
import dev.cubentia.app.session.allMoves
import dev.cubentia.domain.appearance.StickerAppearance
import dev.cubentia.domain.cube.CubeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CubeMeshTest {
    @Test fun meshUsesAppearanceForEveryReadBackSticker() {
        val model = CubieModel(CubeState.SOLVED.apply(allMoves.take(23)).toFacelets())
        allMoves.forEach { move ->
            model.begin(move)
            model.commit()
            listOf(false, true).forEach { glyphs ->
                val style = RenderStyle(glyphs)
                val draws = CubeMesh.stickerDraws(model, style)
                assertEquals(54, draws.size)
                draws.forEachIndexed { i, d ->
                    val a = StickerAppearance.of(model.facelets().colors[i])
                    assertEquals(a.fill, d.fill)
                    assertEquals(if (glyphs) a.glyph else null, d.glyph)
                    assertEquals(if (glyphs) a.glyphInk else null, d.glyphInk)
                }
                assertEquals(CubeMesh.vertexCounts(model).sum() * CubeMesh.FLOATS_PER_VERTEX, CubeMesh.vertices(model, style).size)
            }
        }
    }

    /** Decodes the vertex attributes GL consumes and checks them against `StickerAppearance`. */
    @Test fun vertexAttributesCarryFillInkGlyphCellAndFlag() {
        val model = CubieModel(CubeState.SOLVED.apply(allMoves.take(31)).toFacelets())
        allMoves.take(7).forEach { move ->
            model.begin(move)
            model.commit()
        }
        val cells = "WYROGB".map { GlyphAtlas.u(it, 0f) }
        cells.forEach { assertTrue("glyph cell $it", it in 0f..<1f) }
        assertEquals("every glyph has its own atlas cell", 6, cells.toSet().size)
        listOf(false, true).forEach { glyphs ->
            val style = RenderStyle(glyphs)
            val draws = CubeMesh.stickerDraws(model, style)
            val data = CubeMesh.vertices(model, style)
            var vertex = 0

            fun attr(
                offset: Int,
                size: Int,
            ) = (0 until size).map { data[vertex * CubeMesh.FLOATS_PER_VERTEX + offset + it] }
            for (cubie in model.cubies) {
                if (cubie.stickers.isEmpty()) continue
                repeat(BOX_VERTICES) {
                    assertEquals(CubeMesh.rgb(StickerAppearance.CUBIE_BODY).toList(), attr(COLOR, 3))
                    assertEquals(0f, attr(FLAG, 1).single())
                    assertEquals("body is not a sticker", 0f, attr(STICKER, 1).single())
                    vertex++
                }
                val position = cubie.position()
                for (sticker in cubie.stickers) {
                    val r = cubie.rotation
                    val n = IntArray(3) { r[it * 3] * sticker.nx + r[it * 3 + 1] * sticker.ny + r[it * 3 + 2] * sticker.nz }
                    val index = CubieModel.faceletIndex(position[0], position[1], position[2], n[0], n[1], n[2])
                    val appearance = StickerAppearance.of(sticker.color)
                    assertEquals(appearance.fill, draws[index].fill)
                    repeat(QUAD_VERTICES) {
                        assertEquals("fill at $index", CubeMesh.rgb(appearance.fill).toList(), attr(COLOR, 3))
                        assertEquals(if (glyphs) 1f else 0f, attr(FLAG, 1).single())
                        assertEquals("sticker marker at $index", 1f, attr(STICKER, 1).single())
                        attr(LOCAL, 2).forEach { assertTrue("sticker-local corner $it", it == -1f || it == 1f) }
                        if (glyphs) {
                            assertEquals("ink at $index", CubeMesh.rgb(appearance.glyphInk).toList(), attr(INK, 3))
                            val u = attr(UV, 1).single()
                            assertTrue(
                                "u $u in cell of ${appearance.glyph}",
                                u in GlyphAtlas.u(appearance.glyph, 0f)..GlyphAtlas.u(appearance.glyph, 1f),
                            )
                        }
                        vertex++
                    }
                }
            }
            assertEquals(data.size / CubeMesh.FLOATS_PER_VERTEX, vertex)
        }
    }

    private companion object {
        const val BOX_VERTICES = 36
        const val QUAD_VERTICES = 6
        const val COLOR = 6
        const val UV = 9
        const val INK = 11
        const val FLAG = 14
        const val LOCAL = 15
        const val STICKER = 17
    }
}
