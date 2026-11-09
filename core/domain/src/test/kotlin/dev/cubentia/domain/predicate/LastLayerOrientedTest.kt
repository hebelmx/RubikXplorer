package dev.cubentia.domain.predicate

import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.predicate.Fixtures.assertSameInEveryOrientation
import dev.cubentia.domain.predicate.Fixtures.scramble
import dev.cubentia.domain.predicate.Fixtures.state
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LastLayerOrientedTest {
    private val p = LastLayerOriented

    @Test
    fun `solved cube satisfies last layer oriented`() {
        assertTrue(p.isSatisfied(CubeState.SOLVED))
        assertEquals(ObjectiveProgress(8, 8), p.progress(CubeState.SOLVED))
        assertEquals(8, p.preservedPieces(CubeState.SOLVED).size)
        assertSameInEveryOrientation(p, "solved", CubeState.SOLVED)
    }

    @Test
    fun `last layer oriented is true despite wrong permutation`() {
        // A D turn moves pieces around on D, but all 9 stickers on D remain yellow
        val dTurn = state("D")
        assertTrue(p.isSatisfied(dTurn))
        assertEquals(ObjectiveProgress(8, 8), p.progress(dTurn))
        assertSameInEveryOrientation(p, "D turn", dTurn)
    }

    @Test
    fun `scrambled cube does not satisfy last layer oriented`() {
        val scrambled = CubeState.SOLVED.apply(scramble)
        assertFalse(p.isSatisfied(scrambled))
        assertSameInEveryOrientation(p, "scrambled", scrambled)
    }

    @Test
    fun `sune twists corners and reduces orientation progress`() {
        // Sune algorithm: L D L' D L D2 L'
        val sune = state("L D L' D L D2 L'")
        assertFalse(p.isSatisfied(sune))
        assertSameInEveryOrientation(p, "sune", sune)
    }
}
