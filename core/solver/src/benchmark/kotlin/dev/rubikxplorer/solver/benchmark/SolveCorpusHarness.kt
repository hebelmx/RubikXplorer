package dev.rubikxplorer.solver.benchmark

import java.lang.management.ManagementFactory
import java.lang.management.MemoryType

/**
 * A corpus the harness can time: [corpus] builds the cases up front (not timed), [attempt] solves
 * one case and reports whether it succeeded. Story 3.3 supplies a workload backed by `CubeSolver`.
 */
interface SolveWorkload<C> {
    val name: String

    fun corpus(): List<C>

    fun attempt(case: C): Boolean
}

/**
 * Runs a [SolveWorkload] for NFR-3: [warmup] untimed attempts (cycling through the corpus) let the
 * JIT settle, then every case is timed once with `System.nanoTime`. A `false` result or an
 * exception counts as a failure and never aborts the run; its time is still recorded. Peak heap is
 * the sum of the heap pools' peak usage since the timed run began.
 */
class SolveCorpusHarness(
    private val warmup: Int = DEFAULT_WARMUP,
    private val budgetNanos: Long = CorpusStats.NFR3_BUDGET_NANOS,
    private val clock: () -> Long = System::nanoTime,
) {
    init {
        require(warmup >= 0) { "Warmup must not be negative: $warmup" }
    }

    fun <C> run(workload: SolveWorkload<C>): CorpusStats {
        val corpus = workload.corpus()
        require(corpus.isNotEmpty()) { "Workload ${workload.name} has an empty corpus." }
        repeat(warmup) { i -> attemptSafely(workload, corpus[i % corpus.size]) }

        val heapPools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP && it.isValid }
        heapPools.forEach { it.resetPeakUsage() }
        val samples =
            corpus.map { case ->
                val start = clock()
                val succeeded = attemptSafely(workload, case)
                Sample(clock() - start, succeeded)
            }
        val peakHeap = heapPools.sumOf { it.peakUsage?.used ?: 0L }
        return CorpusStats.of(samples, budgetNanos, peakHeap)
    }

    @Suppress("TooGenericExceptionCaught") // Any throw from a workload is a failed attempt, not a harness error.
    private fun <C> attemptSafely(
        workload: SolveWorkload<C>,
        case: C,
    ): Boolean =
        try {
            workload.attempt(case)
        } catch (e: Exception) {
            false
        }

    companion object {
        const val DEFAULT_WARMUP = 200
    }
}
