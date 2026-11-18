package dev.cubentia.domain.recognition.evaluation.matrix

object MatrixMarkdown {
    const val START = "<!-- recognition-matrix:start -->"
    const val END = "<!-- recognition-matrix:end -->"

    fun render(
        runs: MatrixRuns,
        truth: MatrixTruth,
    ): String =
        buildString {
            fun table(
                title: String,
                scopes: List<Triple<String, MatrixFeature, List<MatrixRun>>>,
            ) {
                appendLine("### $title")
                appendLine()
                if (scopes.isEmpty()) {
                    appendLine("NO DATA")
                } else {
                    appendLine("| Scope | Feature | Runs | Without truth | Metric | Result | Verdict |")
                    appendLine("| --- | --- | ---: | ---: | --- | --- | --- |")
                    for ((scope, feature, sample) in scopes) {
                        val missing = sample.count { truth[it] == null }
                        MatrixScoring.score(feature, sample, truth).forEach { metric ->
                            val verdict =
                                when {
                                    sample.isEmpty() -> "NO DATA"
                                    metric.verdict == "NO DATA" && sample.none { MatrixScoring.isGating(it.cell.device) } -> "reported only"
                                    else -> metric.verdict
                                }
                            appendLine(
                                "| $scope | ${feature.token} | ${sample.size} | $missing | ${metric.name} | ${metric.value} | $verdict |",
                            )
                        }
                    }
                }
                appendLine()
            }
            table("Per cell", runs.cells.map { (cell, sample) -> Triple(cell.name, cell.feature, sample) })
            val devices = runs.cells.entries.groupBy { it.key.device to it.key.feature }
            table("Per device", devices.map { (key, cells) -> Triple(key.first, key.second, cells.flatMap { it.value }) })
            val all = runs.cells.values.flatten()
            appendLine("### Matrix coverage")
            appendLine()
            for (feature in MatrixFeature.entries) {
                val cells = runs.cells.keys.filter { it.feature == feature }

                fun tokens(select: (MatrixCell) -> String) =
                    cells
                        .map(select)
                        .distinct()
                        .sorted()
                        .joinToString(", ")
                        .ifEmpty { "—" }

                appendLine(
                    "- ${feature.token}: ${cells.size}/24 cells present. Devices ${tokens { it.device }}. " +
                        "Cubes ${tokens { it.cube }}. Lights ${tokens { it.light }}.",
                )
            }
            appendLine()
            table(
                "Per feature",
                MatrixFeature.entries.map { feature ->
                    Triple("All devices", feature, all.filter { it.cell.feature == feature })
                },
            )
            appendLine("### Skipped files")
            appendLine()
            if (runs.skipped.isEmpty()) appendLine("None.") else runs.skipped.forEach { appendLine("- `$it`") }
            appendLine()
            appendLine("### Malformed lines")
            appendLine()
            if (runs.malformed.isEmpty()) appendLine("None.") else runs.malformed.forEach { appendLine("- `$it`") }
        }

    fun replace(
        report: String,
        block: String,
    ): String {
        val start = report.indexOf(START)
        val end = report.indexOf(END)
        require(start >= 0 && end > start && report.lastIndexOf(START) == start && report.lastIndexOf(END) == end) {
            "Expected one ordered pair of recognition matrix markers"
        }
        val newline = if (report.startsWith("\r\n", start + START.length)) "\r\n" else "\n"
        return report.substring(0, start + START.length) +
            newline + newline + block.trimEnd().replace("\n", newline) + newline + newline +
            report.substring(end)
    }
}
