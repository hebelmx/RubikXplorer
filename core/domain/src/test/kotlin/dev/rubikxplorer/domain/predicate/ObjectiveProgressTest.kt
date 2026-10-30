package dev.rubikxplorer.domain.predicate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ObjectiveProgressTest {
    @Test
    fun `progress orders by done`() {
        assertTrue(ObjectiveProgress(1, 4) < ObjectiveProgress(2, 4))
        assertTrue(ObjectiveProgress(4, 4) > ObjectiveProgress(3, 4))
        assertEquals(0, ObjectiveProgress(2, 4).compareTo(ObjectiveProgress(2, 4)))
        assertEquals(listOf(0, 2, 4), listOf(4, 0, 2).map { ObjectiveProgress(it, 4) }.sorted().map { it.done })
    }

    @Test
    fun `bounds are inclusive`() {
        assertEquals(0, ObjectiveProgress(0, 1).done)
        assertEquals(1, ObjectiveProgress(1, 1).done)
    }

    @Test
    fun `out-of-range values are programmer errors`() {
        assertThrows<IllegalArgumentException> { ObjectiveProgress(0, 0) }
        assertThrows<IllegalArgumentException> { ObjectiveProgress(-1, 4) }
        assertThrows<IllegalArgumentException> { ObjectiveProgress(5, 4) }
    }

    @Test
    fun `comparing different totals is a programmer error`() {
        assertThrows<IllegalArgumentException> { ObjectiveProgress(1, 4).compareTo(ObjectiveProgress(1, 5)) }
    }
}
