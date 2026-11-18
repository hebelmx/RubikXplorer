package dev.cubentia.domain.recognition.evaluation.matrix

import java.io.File

fun main(args: Array<String>) {
    require(args.size in 2..3) { "Usage: evaluateMatrix --args='<logs dir> <truth file> [report]'" }
    val truth = MatrixTruth.load(File(args[1]))
    val runs = MatrixRuns.load(File(args[0]))
    val report = File(args.getOrElse(2) { "docs/spikes/oq-8-recognition-matrix.md" })
    report.writeText(MatrixMarkdown.replace(report.readText(), MatrixMarkdown.render(runs, truth)))
    println("Updated ${report.path}")
}
