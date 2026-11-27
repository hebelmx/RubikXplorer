package dev.cubentia.domain.recognition.evaluation.quickscan

import java.io.File
import java.util.Locale

object QuickScanMeasurement {
    private val run =
        Regex(
            """quickScan run elapsedMs=(\d+|-) fallbackFaces=(\d+) repromptFaces=(\d+) corrections=(\d+) outcome=(ACCEPTED|CANCELLED|FELL_BACK_TO_EDITOR)(?: [A-Za-z][A-Za-z0-9]*=[^\s=]+)*\s*$""",
        )

    private const val STAGE = """(\d+\.\d/\d+\.\d/\d+\.\d|-)"""

    /** GH #406: the debug `quickScan timing run` line, logged just before the `quickScan run` line it belongs to. */
    private val timing =
        Regex(
            "quickScan timing run frames=(\\d+) read=(\\d+) fps=(\\d+\\.\\d|-) detectorMs=$STAGE inferenceMs=$STAGE " +
                "fitMs=$STAGE sampleMs=$STAGE mapMs=$STAGE analyzerMs=$STAGE fitted=(\\d+) observations=(\\d+)" +
                "(?: [A-Za-z][A-Za-z0-9]*=[^\\s=]+)*\\s*$",
        )

    /** One stage's nearest-rank percentiles, in ms. */
    data class Percentiles(
        val p50: Double,
        val p90: Double,
        val max: Double,
    )

    /** GH #406: a run's per-stage timings ([run] is 1-based); a stage with no samples, or fps over no time, is null. */
    data class RunTiming(
        val run: Int,
        val frames: Int,
        val read: Int,
        val fps: Double?,
        val detectorMs: Percentiles?,
        val inferenceMs: Percentiles?,
        val fitMs: Percentiles?,
        val sampleMs: Percentiles?,
        val mapMs: Percentiles?,
        val analyzerMs: Percentiles?,
    )

    data class Report(
        val runs: Int,
        val successes: Int,
        val fallbackRuns: Int,
        val repromptRuns: Int,
        val runTimings: List<RunTiming> = emptyList(),
    ) {
        val recommendation: String get() = if (runs > 0 && successes.toLong() * 100 >= runs.toLong() * 95) "GA" else "BETA"

        val runsMissingTimings: Int get() = runs - runTimings.size

        fun markdown(): String =
            """
            # Quick Scan measurement

            - Runs: $runs
            - Successful runs: $successes
            - Runs with fallback faces: $fallbackRuns
            - Runs with re-prompted faces: $repromptRuns
            - Recommendation: $recommendation
            - Runs missing timings: $runsMissingTimings
            """.trimIndent() + "\n" + timingsTable()

        /** Per-run stage timings, p50/p90/max in ms; `-` for a stage with no samples. */
        private fun timingsTable(): String {
            if (runTimings.isEmpty()) return ""
            val header =
                "| Run | Frames | Read | fps | detector ms | inference ms | fit ms | sample ms | map ms | analyzer ms |\n" +
                    "|---|---|---|---|---|---|---|---|---|---|\n"
            return "\n## Per-stage timings (p50/p90/max)\n\n" + header +
                runTimings.joinToString("") { t ->
                    listOf(
                        "${t.run}",
                        "${t.frames}",
                        "${t.read}",
                        t.fps?.let(::one) ?: "-",
                        cell(t.detectorMs),
                        cell(t.inferenceMs),
                        cell(t.fitMs),
                        cell(t.sampleMs),
                        cell(t.mapMs),
                        cell(t.analyzerMs),
                    ).joinToString(" | ", prefix = "| ", postfix = " |\n")
                }
        }

        private fun cell(p: Percentiles?): String = p?.let { "${one(it.p50)}/${one(it.p90)}/${one(it.max)}" } ?: "-"

        private fun one(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
    }

    private fun percentiles(field: String): Percentiles? =
        field.takeIf { it != "-" }?.split('/')?.let { (p50, p90, max) -> Percentiles(p50.toDouble(), p90.toDouble(), max.toDouble()) }

    fun measure(lines: Sequence<String>): Report {
        var runs = 0
        var successes = 0
        var fallbackRuns = 0
        var repromptRuns = 0
        val runTimings = mutableListOf<RunTiming>()
        // The latest timing line waits for the run line it precedes; each run line clears it.
        var pending: MatchResult? = null
        for (line in lines) {
            timing.find(line)?.let {
                pending = it
                continue
            }
            val (elapsed, fallback, reprompt, corrections, outcome) = run.find(line)?.destructured ?: continue
            runs++
            if (fallback.toInt() > 0) fallbackRuns++
            if (reprompt.toInt() > 0) repromptRuns++
            if (outcome == "ACCEPTED" && elapsed.toLongOrNull()?.let { it < 15_000 } == true && corrections.toInt() <= 3) successes++
            pending?.groupValues?.let { g ->
                runTimings +=
                    RunTiming(
                        run = runs,
                        frames = g[1].toInt(),
                        read = g[2].toInt(),
                        fps = g[3].toDoubleOrNull(),
                        detectorMs = percentiles(g[4]),
                        inferenceMs = percentiles(g[5]),
                        fitMs = percentiles(g[6]),
                        sampleMs = percentiles(g[7]),
                        mapMs = percentiles(g[8]),
                        analyzerMs = percentiles(g[9]),
                    )
            }
            pending = null
        }
        return Report(runs, successes, fallbackRuns, repromptRuns, runTimings)
    }
}

fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: evaluateQuickScan --args=<log file>" }
    val report = File(args[0]).useLines { QuickScanMeasurement.measure(it).markdown() }
    File(args[1]).apply { parentFile.mkdirs() }.writeText(report)
    print(report)
}
