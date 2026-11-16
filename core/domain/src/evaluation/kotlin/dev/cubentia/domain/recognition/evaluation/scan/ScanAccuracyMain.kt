package dev.cubentia.domain.recognition.evaluation.scan

import dev.cubentia.domain.recognition.evaluation.LiveFixtureLoader
import java.io.File

/** Regenerates the scan accuracy span of [document] from [fixtures]; a missing marker fails before anything is written. */
object ScanAccuracyRunner {
    fun run(
        fixtures: File,
        document: File,
    ) {
        require(document.isFile) { "Missing document ${document.path}" }
        val original = document.readText()
        ScanAccuracyMarkdown.replace(original, "", document.path)
        val report =
            ScanAccuracy.build(LiveFixtureLoader.load(fixtures)) { group, record ->
                record.frame?.let { FrameReader.read(File(File(fixtures, group.name), it)) }
            }
        val updated = ScanAccuracyMarkdown.replace(original, ScanAccuracyMarkdown.render(report), document.path)
        if (updated != original) document.writeText(updated)
    }
}

fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: ScanAccuracyMain <fixtures root> <document path>" }
    ScanAccuracyRunner.run(File(args[0]), File(args[1]))
}
