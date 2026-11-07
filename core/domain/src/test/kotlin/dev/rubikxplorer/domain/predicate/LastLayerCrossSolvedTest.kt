package dev.rubikxplorer.domain.predicate

import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.predicate.Fixtures.assertSameInEveryOrientation
import dev.rubikxplorer.domain.predicate.Fixtures.scramble
import dev.rubikxplorer.domain.predicate.Fixtures.state
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LastLayerCrossSolvedTest {
    private val p = LastLayerCrossSolved

    @Test
    fun `solved cube satisfies last layer cross`() {
        assertTrue(p.isSatisfied(CubeState.SOLVED))
        assertEquals(ObjectiveProgress(4, 4), p.progress(CubeState.SOLVED))
        assertEquals(4, p.preservedPieces(CubeState.SOLVED).size)
        assertSameInEveryOrientation(p, "solved", CubeState.SOLVED)
    }

    @Test
    fun `scrambled cube does not satisfy last layer cross`() {
        val scrambled = CubeState.SOLVED.apply(scramble)
        assertFalse(p.isSatisfied(scrambled))
        assertSameInEveryOrientation(p, "scrambled", scrambled)
    }

    @Test
    fun `edges flipped on D face reduce cross progress`() {
        // Flipping edges on D: B L D L' D' B' algorithm changes D cross orientation
        val alg = state("B L D L' D' B'")
        assertFalse(p.isSatisfied(alg))
        assertSameInEveryOrientation(p, "alg", alg)
    }
}
