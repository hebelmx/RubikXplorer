package dev.cubentia.domain.recognition.evaluation.matrix

import dev.cubentia.domain.cube.Face
import java.util.Locale

private val gatingDevices = setOf("lenovo-tb128xu", "sm-s721u1")
private val recognitionResults = setOf("UNIQUE", "SEVERAL", "NO_FIT", "LL_SOLVED", "NOT_AT_LL")
private const val STICKERS_PER_FACE = 9
private val stickers = Regex("[WYGBRO-]{54}")

data class MatrixMetric(
    val name: String,
    val value: String,
    val verdict: String,
)

object MatrixScoring {
    fun score(
        feature: MatrixFeature,
        runs: List<MatrixRun>,
        truth: MatrixTruth,
    ): List<MatrixMetric> {
        // Report all data, with the verdict calculated on the eligible subset only.
        val gating = runs.filter { it.cell.device in gatingDevices }
        return when (feature) {
            MatrixFeature.GUIDED, MatrixFeature.QUICK_SCAN -> scan(feature, runs, gating, truth)
            MatrixFeature.QUICK_CHECK -> quickCheck(runs, gating, truth)
            MatrixFeature.RECOGNIZE -> recognize(runs, gating, truth)
            MatrixFeature.PLACEMENT -> placement(runs, truth)
        }
    }

    private fun scan(
        feature: MatrixFeature,
        runs: List<MatrixRun>,
        gating: List<MatrixRun>,
        truth: MatrixTruth,
    ): List<MatrixMetric> {
        val guided = feature == MatrixFeature.GUIDED

        fun accepted(run: MatrixRun) = run["outcome"] == "ACCEPTED"

        fun valid(run: MatrixRun) =
            accepted(run) && (run["valid"] == "true" || (!guided && "valid" !in run.fields)) &&
                run.number("corrections")?.let { it <= 3 } == true

        fun times(sample: List<MatrixRun>) = sample.filter(::accepted).map { it.number("elapsedMs") }

        fun comparisons(
            sample: List<MatrixRun>,
            key: String,
        ) = sample.flatMap { run ->
            val read = run[key]?.takeIf(stickers::matches)
            val expected = truth[run]
            if (read == null || expected == null) emptyList() else compare(read, expected)
        }
        val indoor = if (guided) gating.filter { it.cell.light in setOf("warm", "cool") } else gating
        val initial = comparisons(runs, "initial")
        val acceptedWithTruth = runs.filter { accepted(it) && truth[it] != null }
        return listOf(
            rate("Valid with ≤ 3 corrections", runs.map(::valid), gating.map(::valid)) { it >= if (guided) .98 else .95 },
            medianMetric("Accepted median time", times(runs), times(gating), if (guided) 45_000 else 15_000),
            rate(
                "Sticker accuracy",
                comparisons(acceptedWithTruth, "state").map { (seen, expected) -> seen == expected },
                comparisons(indoor.filter(::accepted), "state").map { (seen, expected) -> seen == expected },
                truthDependent = true,
                target = if (guided) ({ it >= .995 }) else null,
            ),
            rate(
                "Initial-read accuracy",
                initial.map { (seen, expected) -> seen == expected },
                truthDependent = true,
            ),
            rate(
                "Confidently wrong",
                initial.map(::confidentlyWrong),
                comparisons(gating, "initial").map(::confidentlyWrong),
                truthDependent = true,
            ) { it <= .001 },
            rate("Accepted but wrong", acceptedWithTruth.map { it["state"] != truth[it] }, truthDependent = true),
        )
    }

    private fun quickCheck(
        runs: List<MatrixRun>,
        gating: List<MatrixRun>,
        truth: MatrixTruth,
    ): List<MatrixMetric> {
        fun verified(run: MatrixRun) = run["certainty"] == "VERIFIED"

        fun falseVerified(sample: List<MatrixRun>) =
            sample.filter { truth[it] != null }.map { run ->
                verified(run) && run["matches"]?.toBooleanStrictOrNull() != (truth[run] == "match")
            }

        fun matchConfirmed(sample: List<MatrixRun>) = sample.filter { truth[it] == "match" }.map { verified(it) && it["matches"] == "true" }

        fun partial(sample: List<MatrixRun>) =
            sample.filter { it["kind"] == "Partial" && truth[it] == "match" }.flatMap(::partialStickers).map(::confidentlyWrong)
        return listOf(
            rate("False VERIFIED", falseVerified(runs), falseVerified(gating), truthDependent = true) { it == 0.0 },
            rate("Match confirmed", matchConfirmed(runs), matchConfirmed(gating), truthDependent = true) { it >= .98 },
            every("Captures", runs.map { it.number("captures") }, gating.map { it.number("captures") }, "") { it <= 2 },
            rate("Confidently wrong (Partial matches)", partial(runs), partial(gating), truthDependent = true) { it <= .001 },
        )
    }

    private fun recognize(
        runs: List<MatrixRun>,
        gating: List<MatrixRun>,
        truth: MatrixTruth,
    ): List<MatrixMetric> {
        fun wrongSingle(sample: List<MatrixRun>) =
            sample.filter { it["outcome"] == "UNIQUE" && truth[it] != null }.map { it["case"] != truth[it] }

        fun times(sample: List<MatrixRun>) = sample.filter { it["outcome"] in recognitionResults }.map { it.number("elapsedMs") }
        val several = runs.filter { it["outcome"] == "SEVERAL" && truth[it] != null }
        return listOf(
            rate("Wrong single Case", wrongSingle(runs), wrongSingle(gating), truthDependent = true) { it <= .01 },
            rate(
                "SEVERAL missing truth candidate",
                several.map { truth[it] !in it["candidates"].orEmpty().split(',') },
                truthDependent = true,
            ),
            every("Result time", times(runs), times(gating), " ms") { it < 1_000 },
            rate("Outcome matches truth", runs.filter { truth[it] != null }.map { outcomeMatches(it, truth[it]) }, truthDependent = true),
        )
    }

    /** A Case truth matches a UNIQUE naming it; `LL_SOLVED` and `NOT_AT_LL` match that outcome. */
    private fun outcomeMatches(
        run: MatrixRun,
        expected: String?,
    ): Boolean =
        when (expected) {
            "LL_SOLVED", "NOT_AT_LL" -> run["outcome"] == expected
            else -> run["outcome"] == "UNIQUE" && run["case"] == expected
        }

    /** Whether [device] is one of the gating devices whose runs give verdicts; others are reported only. */
    fun isGating(device: String): Boolean = device in gatingDevices

    private fun placement(
        runs: List<MatrixRun>,
        truth: MatrixTruth,
    ): List<MatrixMetric> {
        val known = runs.filter { truth[it] != null }
        val choices = runs.filter { it["accepted"] != null && it["suggested"] != null }
        return listOf(
            rate("Suggestion matches truth", known.map { it["suggested"] == truth[it] }, truthDependent = true),
            rate("Accepted differs from suggestion", choices.map { it["accepted"] != it["suggested"] }),
        )
    }

    private fun partialStickers(run: MatrixRun): List<Pair<Char, Char>> {
        val expected = run["expectedHome"]?.takeIf(stickers::matches) ?: return emptyList()
        return run["observed"].orEmpty().split(',').flatMap { observed ->
            val face = Face.entries.firstOrNull { it.name == observed.substringBefore(':') }
            val read = observed.substringAfter(':', "")
            if (face == null || read.length != STICKERS_PER_FACE) {
                emptyList()
            } else {
                compare(read, expected.substring(face.ordinal * STICKERS_PER_FACE, (face.ordinal + 1) * STICKERS_PER_FACE))
            }
        }
    }

    private fun compare(
        read: String,
        expected: String,
    ) = read.zip(expected).filterIndexed { index, _ -> index % STICKERS_PER_FACE != STICKERS_PER_FACE / 2 }

    private fun confidentlyWrong(pair: Pair<Char, Char>) = pair.first != '-' && pair.first != pair.second

    private fun rate(
        name: String,
        sample: List<Boolean>,
        gating: List<Boolean> = emptyList(),
        truthDependent: Boolean = false,
        target: ((Double) -> Boolean)? = null,
    ): MatrixMetric {
        val value =
            when {
                sample.isNotEmpty() -> describeRate(sample)
                truthDependent -> "no truth or no eligible observations (n=0)"
                else -> "n=0"
            }
        val gated = target?.let { if (gating.isEmpty()) null else it(gating.count { event -> event }.toDouble() / gating.size) }
        val details = if (target != null && gating.isNotEmpty() && sample != gating) "$value; gate ${describeRate(gating)}" else value
        return MatrixMetric(name, details, verdict(gated, target != null))
    }

    private fun describeRate(sample: List<Boolean>): String {
        val count = sample.count { it }
        val size = sample.size
        val bound = if (count == 0) "; 95% upper bound ≈ ${percent((3.0 / size).coerceAtMost(1.0))}" else ""
        return "$count/$size (${percent(count.toDouble() / size)})$bound"
    }

    private fun percent(rate: Double): String = String.format(Locale.ROOT, "%.3f%%", 100 * rate)

    private fun medianMetric(
        name: String,
        sample: List<Long?>,
        gating: List<Long?>,
        limit: Int,
    ): MatrixMetric {
        fun median(values: List<Long>): Double? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            return (sorted[(sorted.size - 1) / 2] / 2.0) + (sorted[sorted.size / 2] / 2.0)
        }

        fun describe(values: List<Long?>): String {
            val timed = values.filterNotNull()
            val time = median(timed)?.let { "$it ms" } ?: "—"
            return "$time (${timed.size}/${values.size} timed)"
        }
        val value = describe(sample)
        val gate = median(gating.filterNotNull()).takeUnless { gating.any { it == null } }
        val details = if (sample != gating && gating.isNotEmpty()) "$value; gate ${describe(gating)}" else value
        return MatrixMetric(name, details, verdict(gate?.let { it < limit }))
    }

    private fun every(
        name: String,
        sample: List<Long?>,
        gating: List<Long?>,
        unit: String,
        target: (Long) -> Boolean,
    ): MatrixMetric {
        val known = sample.filterNotNull()
        val value = known.maxOrNull()?.let { "max $it$unit (n=${known.size}/${sample.size})" } ?: "n=0/${sample.size}"
        val result =
            when {
                // A measured breach is a miss even when another run lacks its measurement.
                gating.filterNotNull().any { !target(it) } -> false

                gating.isEmpty() || gating.any { it == null } -> null

                else -> true
            }
        return MatrixMetric(name, value, verdict(result))
    }

    private fun verdict(
        met: Boolean?,
        targeted: Boolean = true,
    ) = when {
        !targeted -> "—"
        met == null -> "NO DATA"
        met -> "MET"
        else -> "MISSED"
    }
}
