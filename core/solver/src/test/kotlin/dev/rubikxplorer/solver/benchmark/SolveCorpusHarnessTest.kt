package dev.rubikxplorer.solver.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SolveCorpusHarnessTest {
    /** Cases are ints: even succeeds, odd returns false, multiples of 3 throw. */
    private class MixedWorkload(
        private val size: Int,
    ) : SolveWorkload<Int> {
        var attempts = 0

        override val name = "mixed"

        override fun corpus() = (1..size).toList()

        override fun attempt(case: Int): Boolean {
            attempts++
            if (case % 3 == 0) throw IllegalStateException("boom $case")
            return case % 2 == 0
        }
    }

    @Test
    fun `false results and exceptions count as failures without aborting the run`() {
        // 1..6: 1 false, 2 ok, 3 throws, 4 ok, 5 false, 6 throws -> 4 failures of 6.
        val stats = SolveCorpusHarness(warmup = 0).run(MixedWorkload(6))
        assertEquals(6, stats.cases)
        assertEquals(4, stats.failures)
        assertEquals(4.0 / 6, stats.failureRate)
    }

    @Test
    fun `timing is recorded for every case, failures included`() {
        var now = 0L
        val clock = {
            now += 10
            now
        }
        val stats = SolveCorpusHarness(warmup = 0, budgetNanos = 10, clock = clock).run(MixedWorkload(3))
        assertEquals(10L, stats.maxNanos)
        assertEquals(10.0, stats.medianNanos)
        assertEquals(1, stats.withinBudget)
    }

    @Test
    fun `warmup attempts are untimed and cycle through the corpus`() {
        val workload = MixedWorkload(2)
        val stats = SolveCorpusHarness(warmup = 5).run(workload)
        assertEquals(7, workload.attempts)
        assertEquals(2, stats.cases)
        assertTrue(stats.peakHeapBytes >= 0)
    }

    @Test
    fun `negative warmup and empty corpus are rejected`() {
        assertThrows<IllegalArgumentException> { SolveCorpusHarness(warmup = -1) }
        assertThrows<IllegalArgumentException> { SolveCorpusHarness(warmup = 0).run(MixedWorkload(0)) }
    }
}
