package dev.cubentia.domain.predicate

import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.predicate.Fixtures.assertSameInEveryOrientation
import dev.cubentia.domain.predicate.Fixtures.scramble
import dev.cubentia.domain.predicate.Fixtures.state
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MiddleLayerSolvedTest {
    private val p = MiddleLayerSolved

    @Test
    fun `solved cube satisfies middle layer with all 4 edges`() {
        assertTrue(p.isSatisfied(CubeState.SOLVED))
        assertEquals(ObjectiveProgress(4, 4), p.progress(CubeState.SOLVED))
        assertEquals(4, p.preservedPieces(CubeState.SOLVED).size)
        assertSameInEveryOrientation(p, "solved", CubeState.SOLVED)
    }

    @Test
    fun `scrambled cube does not satisfy middle layer`() {
        val scrambled = CubeState.SOLVED.apply(scramble)
        assertFalse(p.isSatisfied(scrambled))
        assertSameInEveryOrientation(p, "scrambled", scrambled)
    }

    @Test
    fun `turning a middle slice move or inserting moves modifies progress`() {
        val partial = state("D R D' R' D' F' D F")
        assertEquals(ObjectiveProgress(2, 4), p.progress(partial))
        assertFalse(p.isSatisfied(partial))
        assertSameInEveryOrientation(p, "partial", partial)
    }
}
