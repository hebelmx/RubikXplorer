package dev.cubentia.solver.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CorpusStatsTest {
    private fun samples(vararg nanos: Long) = nanos.map { Sample(it, succeeded = true) }

    @Test
    fun `one sample is its own median, p95 and max`() {
        val stats = CorpusStats.of(samples(7), budgetNanos = 10, peakHeapBytes = 0)
        assertEquals(1, stats.cases)
        assertEquals(7.0, stats.medianNanos)
        assertEquals(7L, stats.p95Nanos)
        assertEquals(7L, stats.maxNanos)
        assertEquals(1.0, stats.withinBudgetShare)
    }

    @Test
    fun `even count median is the mean of the middle two`() {
        assertEquals(2.5, CorpusStats.of(samples(4, 1, 3, 2), peakHeapBytes = 0).medianNanos)
    }

    @Test
    fun `p95 is nearest-rank`() {
        val values = (1L..20L).toList()
        assertEquals(19L, CorpusStats.nearestRank(values, 0.95))
        assertEquals(20L, CorpusStats.nearestRank((1L..21L).toList(), 0.95))
        assertEquals(1L, CorpusStats.nearestRank(listOf(1L), 0.95))
    }

    @Test
    fun `all over budget gives zero within budget`() {
        val stats = CorpusStats.of(samples(5, 6, 7), budgetNanos = 4, peakHeapBytes = 0)
        assertEquals(0, stats.withinBudget)
        assertEquals(0.0, stats.withinBudgetShare)
    }

    @Test
    fun `budget boundary counts as within`() {
        assertEquals(1, CorpusStats.of(samples(4, 5), budgetNanos = 4, peakHeapBytes = 0).withinBudget)
    }

    @Test
    fun `failure rate is failures over cases`() {
        val stats = CorpusStats.of(listOf(Sample(1, true), Sample(2, false), Sample(3, true), Sample(4, false)), peakHeapBytes = 0)
        assertEquals(2, stats.failures)
        assertEquals(0.5, stats.failureRate)
    }

    @Test
    fun `empty samples are rejected`() {
        assertThrows<IllegalArgumentException> { CorpusStats.of(emptyList(), peakHeapBytes = 0) }
        assertThrows<IllegalArgumentException> { CorpusStats.median(emptyList()) }
        assertThrows<IllegalArgumentException> { CorpusStats.nearestRank(emptyList(), 0.95) }
        assertThrows<IllegalArgumentException> { CorpusStats.nearestRank(listOf(1L), 0.0) }
    }

    @Test
    fun `report names every metric`() {
        val report =
            CorpusStats
                .of(listOf(Sample(1_500_000, true), Sample(2_000_000, false)), peakHeapBytes = 3L * 1024 * 1024)
                .report("demo")
        listOf(
            "demo",
            "cases",
            "median",
            "p95",
            "max",
            "within budget  50.0% (1/2 at or under 3000.000 ms)",
            "(50.0%)",
            "failures       1",
            "peak heap      3.0 MiB",
        ).forEach { assertTrue(it in report, "missing '$it' in\n$report") }
    }
}
