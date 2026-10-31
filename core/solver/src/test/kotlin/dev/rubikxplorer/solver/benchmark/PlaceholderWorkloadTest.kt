package dev.rubikxplorer.solver.benchmark

import dev.rubikxplorer.application.usecase.NewScramble
import dev.rubikxplorer.domain.cube.CubeState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PlaceholderWorkloadTest {
    @Test
    fun `corpus is the solved state plus seeded Scrambles`() {
        val corpus = PlaceholderWorkload(scrambles = 20, seed = 7).corpus()
        assertEquals(21, corpus.size)
        assertEquals(CubeState.SOLVED, corpus.first().state)
        assertTrue(corpus.drop(1).all { it.scramble.size == NewScramble.LENGTH && !it.state.isSolved })
    }

    @Test
    fun `same seed gives the same corpus, another seed another`() {
        assertEquals(PlaceholderWorkload(10, 7).corpus(), PlaceholderWorkload(10, 7).corpus())
        assertNotEquals(PlaceholderWorkload(10, 7).corpus(), PlaceholderWorkload(10, 8).corpus())
    }

    @Test
    fun `every case solves through the harness`() {
        val stats = SolveCorpusHarness(warmup = 0).run(PlaceholderWorkload(scrambles = 50, seed = 7))
        assertEquals(51, stats.cases)
        assertEquals(0, stats.failures)
    }

    @Test
    fun `non-positive Scramble count is rejected`() {
        assertThrows<IllegalArgumentException> { PlaceholderWorkload(0, 7) }
    }
}
