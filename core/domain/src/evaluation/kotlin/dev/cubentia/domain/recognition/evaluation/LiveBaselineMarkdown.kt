package dev.cubentia.domain.recognition.evaluation

import java.util.Locale

object LiveBaselineMarkdown {
    const val START = "<!-- live-baseline:start -->"
    const val END = "<!-- live-baseline:end -->"

    fun render(baseline: LiveBaseline): String =
        buildString {
            appendLine("Gate: **${baseline.gate}** (per-sticker target ≥ 99%).")
            appendLine()
            appendLine(
                "Coverage: ${baseline.groups.size} groups, ${baseline.devices.size} devices, ${baseline.cubes} cubes. " +
                    "The Story 1.9 live gate needs both gating devices × both cubes under normal indoor light; check the rows below before relying on the verdict.",
            )
            appendLine()
            appendLine(
                "The prototype has no uncertainty output; every misread sticker of a classified face counts as confidently wrong. Unavailable captures are excluded from accuracy. INCOMPLETE has no gate verdict.",
            )
            appendLine()
            appendLine(
                "| Scope | Correct stickers | Sticker accuracy | Correct faces | Face accuracy | Confidently wrong | Unavailable | Missing classified faces |",
            )
            appendLine("|---|---:|---:|---:|---:|---:|---:|---|")

            fun row(
                scope: String,
                score: BaselineScore,
                missing: String,
            ) {
                fun percent(value: Double) = String.format(Locale.ROOT, "%.4f%%", value * 100)
                val faceAccuracy = if (score.classifiedFaces == 0) 0.0 else score.correctFaces.toDouble() / score.classifiedFaces
                appendLine(
                    "| ${scope.replace(
                        "|",
                        "\\|",
                    ).replace(
                        "\n",
                        " ",
                    ).replace(
                        "\r",
                        " ",
                    )} | ${score.correct}/${score.stickers} | ${percent(
                        score.accuracy,
                    )} | ${score.correctFaces}/${score.classifiedFaces} | ${percent(
                        faceAccuracy,
                    )} | ${score.confidentlyWrong} | ${score.unavailable} | $missing |",
                )
            }
            baseline.groups.forEach { row("Group: ${it.name}", it, it.missing.joinToString { face -> face.name }.ifEmpty { "—" }) }
            baseline.devices.forEach { row("Device: ${it.name}", it, "see groups") }
            row("Total", baseline.total, "see groups")
        }

    fun replace(
        document: String,
        section: String,
    ): String {
        val start = document.indexOf(START)
        val end = document.indexOf(END)
        require(start >= 0 && end > start && document.lastIndexOf(START) == start && document.lastIndexOf(END) == end) {
            "Expected ordered unique markers $START and $END"
        }
        return document.substring(0, start + START.length) + "\n" + section.trimEnd() + "\n" + document.substring(end)
    }
}
