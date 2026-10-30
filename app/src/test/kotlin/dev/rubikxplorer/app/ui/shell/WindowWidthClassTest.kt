package dev.rubikxplorer.app.ui.shell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WindowWidthClassTest {
    @Test
    fun `widths below 600dp are compact, below 840dp medium, else expanded`() {
        assertEquals(WindowWidthClass.COMPACT, WindowWidthClass.of(0f))
        assertEquals(WindowWidthClass.COMPACT, WindowWidthClass.of(411f))
        assertEquals(WindowWidthClass.COMPACT, WindowWidthClass.of(599.9f))
        assertEquals(WindowWidthClass.MEDIUM, WindowWidthClass.of(600f))
        assertEquals(WindowWidthClass.MEDIUM, WindowWidthClass.of(839f))
        assertEquals(WindowWidthClass.EXPANDED, WindowWidthClass.of(840f))
        assertEquals(WindowWidthClass.EXPANDED, WindowWidthClass.of(1280f))
    }
}
