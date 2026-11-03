package dev.rubikxplorer.domain.predicate

import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.predicate.Fixtures.scramble
import dev.rubikxplorer.domain.predicate.Fixtures.state
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [AlwaysTrue] (`nothing`): satisfied everywhere, preserving no pieces, with complete progress. */
class AlwaysTrueTest {
    private val states = listOf(CubeState.SOLVED, CubeState.SOLVED.apply(scramble), state("L"), state("x2"))

    @Test
    fun `is satisfied on every state, preserves nothing and counts as complete`() {
        states.forEach { s ->
            assertTrue(AlwaysTrue.isSatisfied(s))
            assertEquals(emptySet<Piece>(), AlwaysTrue.preservedPieces(s))
            assertEquals(ObjectiveProgress(1, 1), AlwaysTrue.progress(s))
        }
    }

    @Test
    fun `allOf with it judges like its other child`() {
        states.forEach { s ->
            assertEquals(WhiteCrossSolved.isSatisfied(s), AllOf(listOf(AlwaysTrue, WhiteCrossSolved)).isSatisfied(s))
        }
    }
}
