package dev.cubentia.domain.recognition.evaluation

import java.io.File

fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: LiveBaselineMain <fixtures root> <document path>" }
    val baseline = LiveBaselineEvaluator.evaluate(LiveFixtureLoader.load(File(args[0])))
    val document = File(args[1])
    val updated = LiveBaselineMarkdown.replace(document.readText(), LiveBaselineMarkdown.render(baseline))
    document.writeText(updated)
}
