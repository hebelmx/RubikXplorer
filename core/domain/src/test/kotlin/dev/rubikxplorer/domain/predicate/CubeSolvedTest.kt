package dev.rubikxplorer.domain.predicate

import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.predicate.Fixtures.assertSameInEveryOrientation
import dev.rubikxplorer.domain.predicate.Fixtures.scramble
import dev.rubikxplorer.domain.predicate.Fixtures.state
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CubeSolvedTest {
    private val p = CubeSolved

    @Test
    fun `solved cube satisfies cube solved with all 20 pieces`() {
        assertTrue(p.isSatisfied(CubeState.SOLVED))
        assertEquals(ObjectiveProgress(20, 20), p.progress(CubeState.SOLVED))
        assertEquals(20, p.preservedPieces(CubeState.SOLVED).size)
        assertSameInEveryOrientation(p, "solved", CubeState.SOLVED)
    }

    @Test
    fun `single move disturbs pieces and fails cube solved`() {
        val moved = state("U")
        assertFalse(p.isSatisfied(moved))
        assertSameInEveryOrientation(p, "moved", moved)
    }

    @Test
    fun `scrambled cube does not satisfy cube solved`() {
        val scrambled = CubeState.SOLVED.apply(scramble)
        assertFalse(p.isSatisfied(scrambled))
        assertSameInEveryOrientation(p, "scrambled", scrambled)
    }
}
