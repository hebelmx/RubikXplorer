package dev.rubikxplorer.solver.benchmark

import kotlin.system.exitProcess

/** Options of one `solveCorpusBenchmark` run. */
data class BenchmarkOptions(
    val cases: Int = DEFAULT_CASES,
    val seed: Long = DEFAULT_SEED,
    val warmup: Int = SolveCorpusHarness.DEFAULT_WARMUP,
) {
    companion object {
        const val DEFAULT_CASES = 1_000
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

private val FLAGS = setOf("--cases", "--seed", "--warmup")

/** Parses `--cases N` (> 0), `--seed N` (any Long) and `--warmup N` (>= 0). */
fun parseArgs(args: List<String>): ParsedArgs {
    var options = BenchmarkOptions()
    var i = 0
    while (i < args.size) {
        val flag = args[i]
        if (flag !in FLAGS) return ParsedArgs.Invalid("Unknown flag '$flag'. Use --cases N, --seed N, --warmup N.")
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

                else -> {
                    error("Unhandled flag $flag")
                }
            }
        i += 2
    }
    return ParsedArgs.Ok(options)
}

/** Runs the placeholder corpus and returns the report; Story 3.3 swaps in the solver workload. */
fun runBenchmark(options: BenchmarkOptions): String {
    val workload = PlaceholderWorkload(options.cases, options.seed)
    val stats = SolveCorpusHarness(warmup = options.warmup).run(workload)
    return stats.report(workload.name)
}

/** Entry point of `./gradlew :core:solver:solveCorpusBenchmark [--args="--cases N --seed N --warmup N"]`. */
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
