package dev.cubentia.solver.benchmark

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
    fun `time scale multiplies every measured time before budget and percentiles`() {
        var now = 0L
        val clock = {
            now += 10
            now
        }
        // Raw 10 ns per case; k = 2.5 gives 25 ns, which exceeds a 20 ns budget.
        val stats = SolveCorpusHarness(warmup = 0, budgetNanos = 20, clock = clock, timeScale = 2.5).run(MixedWorkload(2))
        assertEquals(25L, stats.maxNanos)
        assertEquals(25.0, stats.medianNanos)
        assertEquals(25L, stats.p95Nanos)
        assertEquals(0, stats.withinBudget)
    }

    @Test
    fun `a scaled time over budget counts as a failure on the calibrated profile`() {
        var now = 0L
        val clock = {
            now += 10
            now
        }
        // Even cases succeed on the host; k = 2.5 turns 10 ns into 25 ns, over a 20 ns budget.
        val stats = SolveCorpusHarness(warmup = 0, budgetNanos = 20, clock = clock, timeScale = 2.5).run(MixedWorkload(2))
        assertEquals(2, stats.failures)
        // At exactly the budget the scaled case still succeeds.
        now = 0L
        val atBudget = SolveCorpusHarness(warmup = 0, budgetNanos = 20, clock = clock, timeScale = 2.0).run(MixedWorkload(2))
        assertEquals(1, atBudget.failures)
        assertEquals(1, atBudget.withinBudget)
    }

    @Test
    fun `without a time scale the measured time alone never turns a success into a failure`() {
        var now = 0L
        val clock = {
            now += 10
            now
        }
        // Host run (k = 1): a successful case over the budget stays a success, as before 3.8.
        val stats = SolveCorpusHarness(warmup = 0, budgetNanos = 5, clock = clock).run(MixedWorkload(2))
        assertEquals(1, stats.failures)
        assertEquals(0, stats.withinBudget)
    }

    @Test
    fun `runtime sampler keeps the largest used heap after reset`() {
        val readings = ArrayDeque(listOf(5L, 9L, 3L))
        val sampler = RuntimeHeapSampler { readings.removeFirst() }
        sampler.reset()
        sampler.sample()
        sampler.sample()
        assertEquals(9L, sampler.peakBytes())
    }

    @Test
    fun `the injected sampler is reset before and sampled after each timed attempt`() {
        val events = mutableListOf<String>()
        val sampler =
            object : HeapSampler {
                override fun reset() {
                    events += "reset"
                }

                override fun sample() {
                    events += "sample"
                }

                override fun peakBytes() = 42L
            }
        val stats = SolveCorpusHarness(warmup = 3, heapSampler = sampler).run(MixedWorkload(2))
        assertEquals(listOf("reset", "sample", "sample"), events)
        assertEquals(42L, stats.peakHeapBytes)
    }

    @Test
    fun `negative warmup, invalid time scale and empty corpus are rejected`() {
        assertThrows<IllegalArgumentException> { SolveCorpusHarness(warmup = -1) }
        assertThrows<IllegalArgumentException> { SolveCorpusHarness(timeScale = 0.0) }
        assertThrows<IllegalArgumentException> { SolveCorpusHarness(timeScale = Double.NaN) }
        assertThrows<IllegalArgumentException> { SolveCorpusHarness(warmup = 0).run(MixedWorkload(0)) }
    }
}
