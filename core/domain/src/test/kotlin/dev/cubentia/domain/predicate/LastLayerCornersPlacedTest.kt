package dev.cubentia.domain.predicate

import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.predicate.Fixtures.assertSameInEveryOrientation
import dev.cubentia.domain.predicate.Fixtures.scramble
import dev.cubentia.domain.predicate.Fixtures.state
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LastLayerCornersPlacedTest {
    private val p = LastLayerCornersPlaced

    @Test
    fun `solved cube satisfies last layer corners placed`() {
        assertTrue(p.isSatisfied(CubeState.SOLVED))
        assertEquals(ObjectiveProgress(4, 4), p.progress(CubeState.SOLVED))
        assertEquals(4, p.preservedPieces(CubeState.SOLVED).size)
        assertSameInEveryOrientation(p, "solved", CubeState.SOLVED)
    }

    @Test
    fun `corners placed is true for twisted in place corners`() {
        // Twist two corners without moving their slots:
        val twistOne = "R' D' R D R' D' R D"
        val unTwist = "D' R' D R D' R' D R"
        val twistTwoCorners = "$twistOne D $unTwist D'"
        val twisted = state(twistTwoCorners)
        assertTrue(p.isSatisfied(twisted))
        assertEquals(ObjectiveProgress(4, 4), p.progress(twisted))
        assertSameInEveryOrientation(p, "twisted", twisted)
    }

    @Test
    fun `swapping two corners reduces progress`() {
        // A corner cycle: D L D' R' D L' D' R
        val cycled = state("D L D' R' D L' D' R")
        assertFalse(p.isSatisfied(cycled))
        assertSameInEveryOrientation(p, "cycled", cycled)
    }

    @Test
    fun `scrambled cube does not satisfy corners placed`() {
        val scrambled = CubeState.SOLVED.apply(scramble)
        assertFalse(p.isSatisfied(scrambled))
        assertSameInEveryOrientation(p, "scrambled", scrambled)
    }
}
