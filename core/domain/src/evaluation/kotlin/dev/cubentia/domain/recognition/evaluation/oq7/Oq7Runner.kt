package dev.cubentia.domain.recognition.evaluation.oq7

import dev.cubentia.domain.recognition.evaluation.LiveFixtureLoader
import java.io.File

object Oq7Runner {
    /**
     * Scores the fixtures under [fixtures], measures each record's frame with [frameFeatures] and rewrites the results span
     * of [document]. Everything is computed before the document is touched, so any failure leaves it unchanged.
     */
    fun run(
        fixtures: File,
        document: File,
        aars: List<File>,
        frameFeatures: (File) -> List<CellFeature>,
        crossChecks: () -> List<String> = { emptyList() },
    ) {
        require(document.isFile) { "Missing document ${document.path}" }
        val original = document.readText()
        // Fail on missing markers before the (slow) evaluation.
        Oq7Markdown.replace(original, "", document.path)
        val sizes = aars.map(AarSize::read)
        val groups =
            Observations.from(LiveFixtureLoader.load(fixtures)) { group, record ->
                record.frame?.let { frameFeatures(File(File(fixtures, group.name), it)) }
            }
        val report = Oq7Report.build(groups, sizes)
        val updated = Oq7Markdown.replace(original, Oq7Markdown.render(report, crossChecks()), document.path)
        if (updated != original) document.writeText(updated)
    }
}
