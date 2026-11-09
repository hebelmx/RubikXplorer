package dev.cubentia.domain.appearance

import dev.cubentia.domain.cube.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.pow

class StickerAppearanceTest {
    @Test
    fun `fills are the DESIGN md sticker hexes`() {
        val expected =
            mapOf(
                Color.WHITE to 0xFFF4F5F0,
                Color.YELLOW to 0xFFFFD500,
                Color.RED to 0xFFC41E3A,
                Color.ORANGE to 0xFFFF5800,
                Color.GREEN to 0xFF009E60,
                Color.BLUE to 0xFF0051BA,
            )
        Color.entries.forEach { assertEquals(expected.getValue(it), StickerAppearance.of(it).fill, it.name) }
    }

    @Test
    fun `glyphs are W Y R O G B and distinct`() {
        val expected =
            mapOf(
                Color.WHITE to 'W',
                Color.YELLOW to 'Y',
                Color.RED to 'R',
                Color.ORANGE to 'O',
                Color.GREEN to 'G',
                Color.BLUE to 'B',
            )
        Color.entries.forEach { assertEquals(expected.getValue(it), StickerAppearance.of(it).glyph, it.name) }
        assertEquals(
            Color.entries.size,
            Color.entries
                .map { StickerAppearance.of(it).glyph }
                .toSet()
                .size,
        )
    }

    @Test
    fun `glyph ink is cubie-body on light stickers and sticker-white on dark ones`() {
        val dark = setOf(Color.RED, Color.BLUE)
        Color.entries.forEach {
            val ink = if (it in dark) 0xFFF4F5F0 else 0xFF121417
            assertEquals(ink, StickerAppearance.of(it).glyphInk, it.name)
        }
    }

    @Test
    fun `glyph ink meets WCAG 4_5 to 1 on every sticker`() {
        Color.entries.forEach {
            val appearance = StickerAppearance.of(it)
            val ratio = contrast(appearance.fill, appearance.glyphInk)
            assertTrue(ratio >= 4.5, "${it.name} glyph contrast $ratio")
        }
    }

    @Test
    fun `patterns are deferred`() {
        Color.entries.forEach { assertNull(StickerAppearance.of(it).pattern, it.name) }
    }

    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun contrast(
        a: Long,
        b: Long,
    ): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }
}
