package dev.cubentia.domain.cube

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StickerOrderTest {
    @Test
    fun `next moves to the following sticker on the same face`() {
        assertEquals(1, StickerOrder.next(0))
    }

    @Test
    fun `next skips the center`() {
        assertEquals(5, StickerOrder.next(3))
        assertEquals(14, StickerOrder.next(12))
    }

    @Test
    fun `next crosses into the next face`() {
        assertEquals(9, StickerOrder.next(8))
        assertEquals(45, StickerOrder.next(44))
    }

    @Test
    fun `without wrap the last sticker has no next`() {
        assertNull(StickerOrder.next(53))
        assertNull(StickerOrder.next(52) { it != 53 })
    }

    @Test
    fun `with wrap the scan continues from the first sticker`() {
        assertEquals(0, StickerOrder.next(53, wrap = true))
        assertEquals(5, StickerOrder.next(50, wrap = true) { it in setOf(5, 30) })
    }

    @Test
    fun `with wrap the scan may return the starting sticker`() {
        assertEquals(7, StickerOrder.next(7, wrap = true) { it == 7 })
    }

    @Test
    fun `the predicate filters stickers and never sees a center`() {
        val seen = mutableListOf<Int>()
        assertEquals(
            30,
            StickerOrder.next(20) {
                seen += it
                it >= 30
            },
        )
        assertEquals(listOf(21, 23, 24, 25, 26, 27, 28, 29, 30), seen)
        assertNull(StickerOrder.next(0, wrap = true) { false })
    }
}
