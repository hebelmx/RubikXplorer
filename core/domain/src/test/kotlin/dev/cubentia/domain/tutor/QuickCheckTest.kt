package dev.cubentia.domain.tutor

import dev.cubentia.domain.cube.Face
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QuickCheckTest {
    @Test fun `planner covers every size deterministically`() {
        assertEquals(2, QuickCheck.MAX_CAPTURES)
        assertEquals(setOf(Face.U, Face.F), QuickCheck.faces(emptySet()))
        Face.entries.forEach { face ->
            val plan = QuickCheck.faces(setOf(face))
            assertEquals(2, plan.size)
            assertTrue(face in plan)
            assertFalse(face.opposite in plan)
            Face.entries.filter { it != face }.forEach { other -> assertEquals(setOf(face, other), QuickCheck.faces(setOf(face, other))) }
        }
        for (n in 3..6) assertEquals(Face.entries.toSet(), QuickCheck.faces(Face.entries.take(n).toSet()))
    }
}
