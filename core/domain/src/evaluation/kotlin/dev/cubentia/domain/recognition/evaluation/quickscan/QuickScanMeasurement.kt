package dev.cubentia.domain.recognition.evaluation.quickscan

import java.io.File

object QuickScanMeasurement {
    private val run =
        Regex(
            """quickScan run elapsedMs=(\d+) fallbackFaces=(\d+) repromptFaces=(\d+) corrections=(\d+) outcome=(ACCEPTED|CANCELLED|FELL_BACK_TO_EDITOR)\s*$""",
        )

    data class Report(
        val runs: Int,
        val successes: Int,
        val fallbackRuns: Int,
        val repromptRuns: Int,
    ) {
        val recommendation: String get() = if (runs > 0 && successes.toLong() * 100 >= runs.toLong() * 95) "GA" else "BETA"

        fun markdown(): String =
            """
            # Quick Scan measurement

            - Runs: $runs
            - Successful runs: $successes
            - Runs with fallback faces: $fallbackRuns
            - Runs with re-prompted faces: $repromptRuns
            - Recommendation: $recommendation
            """.trimIndent() + "\n"
    }

    fun measure(lines: Sequence<String>): Report {
        var runs = 0
        var successes = 0
        var fallbackRuns = 0
        var repromptRuns = 0
        for (line in lines) {
            val (elapsed, fallback, reprompt, corrections, outcome) = run.find(line)?.destructured ?: continue
            runs++
            if (fallback.toInt() > 0) fallbackRuns++
            if (reprompt.toInt() > 0) repromptRuns++
            if (outcome == "ACCEPTED" && elapsed.toLong() < 15_000 && corrections.toInt() <= 3) successes++
        }
        return Report(runs, successes, fallbackRuns, repromptRuns)
    }
}

fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: evaluateQuickScan --args=<log file>" }
    val report = File(args[0]).useLines { QuickScanMeasurement.measure(it).markdown() }
    File(args[1]).apply { parentFile.mkdirs() }.writeText(report)
    print(report)
}
