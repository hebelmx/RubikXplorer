package dev.cubentia.solver.benchmark

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
 * Peak-memory probe for a timed run. [reset] starts the window, [sample] is called after every
 * attempt (outside its timing), [peakBytes] reads the result. The JVM runner uses heap-pool peaks
 * (`java.lang.management`, absent on Android); the device runner uses [RuntimeHeapSampler].
 */
interface HeapSampler {
    fun reset()

    fun sample()

    fun peakBytes(): Long
}

/** Portable sampler: the largest `Runtime` used heap seen at reset or after any attempt. */
class RuntimeHeapSampler(
    private val usedBytes: () -> Long = { Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } },
) : HeapSampler {
    private var peak = 0L

    override fun reset() {
        peak = usedBytes()
    }

    override fun sample() {
        peak = maxOf(peak, usedBytes())
    }

    override fun peakBytes(): Long = peak
}

/**
 * Runs a [SolveWorkload] for NFR-3: [warmup] untimed attempts (cycling through the corpus) let the
 * JIT settle, then every case is timed once with `System.nanoTime`. A `false` result or an
 * exception counts as a failure and never aborts the run; its time is still recorded.
 *
 * [timeScale] is the Story 3.8 calibrated-profile factor k: each measured time is multiplied by k
 * before the statistics (and the budget comparison) are computed, and a case whose scaled time
 * exceeds the budget counts as a failure (the solver would have timed out on the reference device).
 * 1.0 is the uncalibrated host; there the solver's own deadline decides success.
 */
class SolveCorpusHarness(
    private val warmup: Int = DEFAULT_WARMUP,
    private val budgetNanos: Long = CorpusStats.NFR3_BUDGET_NANOS,
    private val clock: () -> Long = System::nanoTime,
    private val heapSampler: HeapSampler = RuntimeHeapSampler(),
    private val timeScale: Double = 1.0,
) {
    init {
        require(warmup >= 0) { "Warmup must not be negative: $warmup" }
        require(timeScale.isFinite() && timeScale > 0.0) { "Time scale must be a positive finite number: $timeScale" }
    }

    fun <C> run(workload: SolveWorkload<C>): CorpusStats {
        val corpus = workload.corpus()
        require(corpus.isNotEmpty()) { "Workload ${workload.name} has an empty corpus." }
        repeat(warmup) { i -> attemptSafely(workload, corpus[i % corpus.size]) }

        heapSampler.reset()
        val samples =
            corpus.map { case ->
                val start = clock()
                val succeeded = attemptSafely(workload, case)
                val nanos = clock() - start
                heapSampler.sample()
                val scaled = scale(nanos)
                // On the calibrated profile a scaled time over budget is the device's timeout: a failure.
                Sample(scaled, succeeded && (timeScale == 1.0 || scaled <= budgetNanos))
            }
        return CorpusStats.of(samples, budgetNanos, heapSampler.peakBytes())
    }

    private fun scale(nanos: Long): Long = if (timeScale == 1.0) nanos else Math.round(nanos * timeScale)

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
