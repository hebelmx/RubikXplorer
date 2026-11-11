package dev.cubentia.solver.benchmark

import kotlin.system.exitProcess

/** Options of one `solveCorpusBenchmark` run. */
data class BenchmarkOptions(
    val cases: Int = DEFAULT_CASES,
    val seed: Long = DEFAULT_SEED,
    val warmup: Int = SolveCorpusHarness.DEFAULT_WARMUP,
    val timeScale: Double = 1.0,
) {
    companion object {
        const val DEFAULT_CASES = 10_000
        const val DEFAULT_SEED = 20_261_031L
    }
}

/** Result of parsing the command line: options, or a message naming the offending flag. */
sealed interface ParsedArgs {
    data class Ok(
        val options: BenchmarkOptions,
    ) : ParsedArgs

    data class Invalid(
        val message: String,
    ) : ParsedArgs
}

private val FLAGS = setOf("--cases", "--seed", "--warmup", "--time-scale")

/**
 * Parses `--cases N` (> 0), `--seed N` (any Long), `--warmup N` (>= 0) and `--time-scale K`
 * (finite, > 0; the Story 3.8 calibrated reference-device-equivalent factor, 1 = uncalibrated host).
 */
fun parseArgs(args: List<String>): ParsedArgs {
    var options = BenchmarkOptions()
    var i = 0
    while (i < args.size) {
        val flag = args[i]
        if (flag !in FLAGS) return ParsedArgs.Invalid("Unknown flag '$flag'. Use --cases N, --seed N, --warmup N, --time-scale K.")
        val value = args.getOrNull(i + 1) ?: return ParsedArgs.Invalid("$flag needs a value.")
        options =
            when (flag) {
                "--cases" -> {
                    val cases = value.toIntOrNull()
                    if (cases == null || cases <= 0) return ParsedArgs.Invalid("--cases must be a positive integer, got '$value'.")
                    options.copy(cases = cases)
                }

                "--seed" -> {
                    val seed = value.toLongOrNull() ?: return ParsedArgs.Invalid("--seed must be an integer, got '$value'.")
                    options.copy(seed = seed)
                }

                "--warmup" -> {
                    val warmup = value.toIntOrNull()
                    if (warmup == null || warmup < 0) return ParsedArgs.Invalid("--warmup must be a non-negative integer, got '$value'.")
                    options.copy(warmup = warmup)
                }

                "--time-scale" -> {
                    val scale = value.toDoubleOrNull()
                    if (scale == null || !scale.isFinite() || scale <= 0.0) {
                        return ParsedArgs.Invalid("--time-scale must be a positive number, got '$value'.")
                    }
                    options.copy(timeScale = scale)
                }

                else -> {
                    error("Unhandled flag $flag")
                }
            }
        i += 2
    }
    return ParsedArgs.Ok(options)
}

/** Runs the independently verified real solve corpus. */
fun runBenchmark(options: BenchmarkOptions): String {
    val workload = SolverWorkload(options.cases, options.seed)
    val stats =
        SolveCorpusHarness(warmup = options.warmup, heapSampler = JvmHeapPoolSampler(), timeScale = options.timeScale)
            .run(workload)
    val profile =
        if (options.timeScale == 1.0) {
            "uncalibrated JVM host"
        } else {
            "calibrated reference-device-equivalent profile: host time x ${options.timeScale}"
        }
    return stats.report(workload.name) + "\n  profile        " + System.getProperty("java.vm.name") + " " +
        System.getProperty("java.version") + "; " + System.getProperty("os.name") + " " +
        System.getProperty("os.arch") + "; CPUs=" + Runtime.getRuntime().availableProcessors() +
        "; maxHeap=" + Runtime.getRuntime().maxMemory() + "; " + profile
}

/** Entry point of `./gradlew :core:solver:solveCorpusBenchmark [--args="--cases N --seed N --warmup N --time-scale K"]`. */
fun main(args: Array<String>) {
    when (val parsed = parseArgs(args.toList())) {
        is ParsedArgs.Ok -> {
            println(runBenchmark(parsed.options))
        }

        is ParsedArgs.Invalid -> {
            System.err.println(parsed.message)
            exitProcess(2)
        }
    }
}
