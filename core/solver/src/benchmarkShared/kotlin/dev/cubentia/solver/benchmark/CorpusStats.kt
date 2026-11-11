package dev.cubentia.solver.benchmark

import java.util.Locale
import kotlin.math.ceil

/** One timed attempt of a corpus case: wall time in nanoseconds and whether it solved the case. */
data class Sample(
    val nanos: Long,
    val succeeded: Boolean,
)

/**
 * NFR-3 metrics over one corpus run: case count, median, p95 (nearest-rank), max, the share of
 * cases within [budgetNanos], failures and peak heap. Pure; [SolveCorpusHarness] measures.
 */
data class CorpusStats(
    val cases: Int,
    val medianNanos: Double,
    val p95Nanos: Long,
    val maxNanos: Long,
    val budgetNanos: Long,
    val withinBudget: Int,
    val failures: Int,
    val peakHeapBytes: Long,
) {
    val withinBudgetShare: Double get() = withinBudget.toDouble() / cases

    val failureRate: Double get() = failures.toDouble() / cases

    /** A plain-text report, one metric per line. */
    fun report(workload: String): String =
        buildString {
            appendLine("Solve corpus benchmark: $workload")
            appendLine("  cases          $cases")
            appendLine("  median         ${ms(medianNanos)}")
            appendLine("  p95            ${ms(p95Nanos.toDouble())}")
            appendLine("  max            ${ms(maxNanos.toDouble())}")
            appendLine("  within budget  ${percent(withinBudgetShare)} ($withinBudget/$cases at or under ${ms(budgetNanos.toDouble())})")
            appendLine("  failures       $failures (${percent(failureRate)})")
            append("  peak heap      ${mib(peakHeapBytes)}")
        }

    companion object {
        /** NFR-3: 95% of the corpus within 3 s on the reference device. */
        const val NFR3_BUDGET_NANOS: Long = 3_000_000_000L

        fun of(
            samples: List<Sample>,
            budgetNanos: Long = NFR3_BUDGET_NANOS,
            peakHeapBytes: Long,
        ): CorpusStats {
            require(samples.isNotEmpty()) { "A corpus run needs at least one sample." }
            val sorted = samples.map { it.nanos }.sorted()
            return CorpusStats(
                cases = samples.size,
                medianNanos = median(sorted),
                p95Nanos = nearestRank(sorted, P95),
                maxNanos = sorted.last(),
                budgetNanos = budgetNanos,
                withinBudget = samples.count { it.succeeded && it.nanos <= budgetNanos },
                failures = samples.count { !it.succeeded },
                peakHeapBytes = peakHeapBytes,
            )
        }

        /** Median of ascending [sorted]; the mean of the two middle values for an even count. */
        fun median(sorted: List<Long>): Double {
            require(sorted.isNotEmpty()) { "Median of no values." }
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid].toDouble() else (sorted[mid - 1] + sorted[mid]) / 2.0
        }

        /** Nearest-rank percentile of ascending [sorted]: the value at rank ⌈p·n⌉ (1-based). */
        fun nearestRank(
            sorted: List<Long>,
            percentile: Double,
        ): Long {
            require(sorted.isNotEmpty()) { "Percentile of no values." }
            require(percentile > 0.0 && percentile <= 1.0) { "Percentile must be in (0, 1]: $percentile" }
            val rank = ceil(percentile * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }

        private const val P95 = 0.95

        private fun ms(nanos: Double): String = String.format(Locale.ROOT, "%.3f ms", nanos / NANOS_PER_MS)

        private fun percent(share: Double): String = String.format(Locale.ROOT, "%.1f%%", share * PERCENT)

        private fun mib(bytes: Long): String = String.format(Locale.ROOT, "%.1f MiB", bytes / BYTES_PER_MIB)

        private const val NANOS_PER_MS = 1_000_000.0
        private const val PERCENT = 100.0
        private const val BYTES_PER_MIB = 1024.0 * 1024.0
    }
}
