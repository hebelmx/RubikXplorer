package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Phase 1 spike replay: measures [CubieInvariantResolver] across every recorded corner group in `Fixtures/corner/` and
 * all 4 calibration scenarios (`NONE`, `MATCHING`, `OTHER_LIGHT`, `OTHER_CUBE`), enforcing strict `TUNING` vs `HELDOUT`
 * split separation (`Fixtures/corner/groups.json`) and the mandatory safety bar of **0** confidently wrong stickers on
 * resolved faces.
 */
class CubieInvariantReplayTest {
    private enum class Scenario(
        val title: String,
    ) {
        NONE("no saved calibration"),
        MATCHING("matching"),
        OTHER_LIGHT("other light, same cube"),
        OTHER_CUBE("other cube"),
    }

    private enum class Bucket { TUNING, HELDOUT, LEGACY }

    private data class Group(
        val name: String,
        val device: String,
        val cube: String,
        val light: String,
        val direction: LightDirection?,
        val bucket: Bucket,
        val records: List<CornerFixtureRecord>,
    ) {
        val samples: List<FaceSample> get() = records.flatMap { r -> r.labeledSamples.map { it.sample } }
        val calibration: CalibrationCenters get() = records.first().calibration

        /** Ground truth in URFDLB net order using [ScanAssembler.placeAllPosed], null if not 6 posed faces. */
        fun netTruth(): List<Color>? {
            val placed = ScanAssembler.placeAllPosed(samples) ?: return null
            val byFace = records.flatMap { it.labeledSamples }.associate { it.sample.face to it.truth }
            return placed.flatMap { p ->
                val rawTruth = byFace[p.sample.face] ?: return null
                ScanAssembler.rotatePlaced(p, rawTruth)
            }
        }
    }

    private class Stats {
        var runs = 0
        var baselineFullNoFallback = 0
        var afterFullNoFallback = 0
        var baselineSideFaces = 0
        var baselineSideKept = 0
        var afterSideKept = 0
        var resolvedRuns = 0
        var resolvedStickersTotal = 0
        var resolvedWrongRuns = 0
        var resolvedWrongStickers = 0
        val reasons = linkedMapOf<String, Int>()
        val rescuedDetails = mutableListOf<String>()
        val wrongDetails = mutableListOf<String>()
    }

    private fun loadGroups(): List<Group> {
        assumeTrue(FIXTURES.isDirectory, "Corner fixtures directory not present")
        val index =
            File(FIXTURES, CornerFixturePaths.GROUP_INDEX)
                .takeIf { it.isFile }
                ?.let { CornerFixtureJson.decodeIndex(it.readText()) }
        return FIXTURES
            .walkTopDown()
            .filter { dir -> dir.isDirectory && dir.listFiles().orEmpty().any { it.name.matches(FIXTURE) } }
            .sortedBy { it.path }
            .mapNotNull { dir ->
                val records =
                    dir
                        .listFiles()
                        .orEmpty()
                        .filter { it.name.matches(FIXTURE) }
                        .sortedBy { it.name }
                        .map { CornerFixtureJson.decode(it.readText()) }
                        .filter { it.label == CornerLabel.POSITIVE && it.labeledSamples.size == 3 }
                if (records.size != 2) return@mapNotNull null
                val manifest =
                    File(dir, CornerFixturePaths.MANIFEST)
                        .takeIf { it.isFile }
                        ?.let { CornerFixtureJson.decodeManifest(it.readText()) }
                val first = records.first()
                val name = dir.relativeTo(FIXTURES).path
                val split = index?.entry(name)?.split
                val bucket =
                    when (split) {
                        CornerSplit.TUNING -> Bucket.TUNING
                        CornerSplit.HELDOUT -> Bucket.HELDOUT
                        null -> Bucket.LEGACY
                    }
                Group(
                    name = name,
                    device = first.device.model,
                    cube = first.cube,
                    light = first.light,
                    direction = manifest?.lightDirection ?: LEGACY_DIRECTION[first.light],
                    bucket = bucket,
                    records = records,
                )
            }.toList()
    }

    private fun calibrations(
        group: Group,
        all: List<Group>,
        scenario: Scenario,
    ): List<CalibrationCenters?> =
        when (scenario) {
            Scenario.NONE -> {
                listOf(null)
            }

            Scenario.MATCHING -> {
                listOf(group.calibration)
            }

            Scenario.OTHER_LIGHT -> {
                all
                    .filter {
                        it.device == group.device && it.cube == group.cube && it.light != group.light
                    }.map { it.calibration }
            }

            Scenario.OTHER_CUBE -> {
                all.filter { it.device == group.device && it.cube != group.cube }.map { it.calibration }
            }
        }

    private fun evaluate(
        groups: List<Group>,
        allGroups: List<Group>,
        lWeight: Double,
    ): Map<Scenario, Stats> =
        Scenario.entries.associateWith { scenario ->
            val stats = Stats()
            groups.forEach { group ->
                val samples = group.samples
                val truth = group.netTruth() ?: return@forEach
                val sideFaces =
                    group.records.flatMap { r -> r.labeledSamples.drop(1).map { it.sample.face } }.toSet()
                calibrations(group, allGroups, scenario).forEach { saved ->
                    stats.runs++
                    val toGrid = QuickScanClassification.needGrid(samples, Face.entries, saved).toSet()
                    stats.baselineSideFaces += sideFaces.size
                    val baseKeptSide = sideFaces.count { it !in toGrid }
                    stats.baselineSideKept += baseKeptSide
                    if (toGrid.isEmpty()) {
                        stats.baselineFullNoFallback++
                        stats.afterFullNoFallback++
                        stats.afterSideKept += baseKeptSide
                        stats.reasons["ALREADY_FULL"] = (stats.reasons["ALREADY_FULL"] ?: 0) + 1
                        return@forEach
                    }

                    when (val outcome = CubieInvariantResolver.resolve(samples, saved, lWeight = lWeight)) {
                        is ResolutionOutcome.Resolved -> {
                            stats.resolvedRuns++
                            stats.resolvedStickersTotal += outcome.resolvedIndices.size
                            stats.afterFullNoFallback++
                            stats.afterSideKept += sideFaces.size
                            stats.reasons["RESOLVED"] = (stats.reasons["RESOLVED"] ?: 0) + 1
                            val wrongCount =
                                (0 until truth.size).count { i ->
                                    i % 9 != 4 && outcome.facelets.colors[i] != truth[i]
                                }
                            if (wrongCount > 0) {
                                stats.resolvedWrongRuns++
                                stats.resolvedWrongStickers += wrongCount
                                stats.wrongDetails +=
                                    "${group.name} [${scenario.title}]: $wrongCount wrong stickers (resolved ${outcome.resolvedIndices.size})"
                            } else {
                                stats.rescuedDetails +=
                                    "${group.name} [${scenario.title}]: rescued ${outcome.resolvedIndices.size} stickers, saved ${toGrid.size} grid faces"
                            }
                        }

                        is ResolutionOutcome.Unchanged -> {
                            stats.afterSideKept += baseKeptSide
                            val key = outcome.reason.name
                            stats.reasons[key] = (stats.reasons[key] ?: 0) + 1
                        }
                    }
                }
            }
            stats
        }

    @Test
    fun `spike replay evaluates L_WEIGHT on tuning split and enforces zero wrong resolved stickers across all splits`() {
        val all = loadGroups()
        val tuning = all.filter { it.bucket == Bucket.TUNING }
        val heldout = all.filter { it.bucket == Bucket.HELDOUT }
        val legacy = all.filter { it.bucket == Bucket.LEGACY }

        val report =
            buildString {
                appendLine("# Phase 1 Spike Report: CubieInvariantResolver Replay")
                appendLine()
                appendLine("Groups loaded: total=${all.size} (TUNING=${tuning.size}, HELDOUT=${heldout.size}, LEGACY=${legacy.size})")
                appendLine()

                // 1. Compare L_WEIGHT = 1.0 vs 0.5 on TUNING split only.
                appendLine("## Tuning Split: L_WEIGHT Comparison (1.0 vs 0.5)")
                listOf(1.0, 0.5).forEach { w ->
                    appendLine()
                    appendLine("### TUNING (lWeight = $w)")
                    appendSection(evaluate(tuning, all, w))
                }

                // 2. Evaluate default L_WEIGHT = 1.0 on LEGACY and untouched HELDOUT.
                appendLine()
                appendLine("## Legacy Split (lWeight = ${CubieInvariantResolver.DEFAULT_L_WEIGHT})")
                appendSection(evaluate(legacy, all, CubieInvariantResolver.DEFAULT_L_WEIGHT))

                appendLine()
                appendLine("## Held-Out Split — Untouched Evaluation Gate (lWeight = ${CubieInvariantResolver.DEFAULT_L_WEIGHT})")
                val heldoutResults = evaluate(heldout, all, CubieInvariantResolver.DEFAULT_L_WEIGHT)
                appendSection(heldoutResults)
            }

        println(report)
        REPORT_FILE.parentFile.mkdirs()
        REPORT_FILE.writeText(report)

        // Hard safety gate: 0 wrong stickers on ANY resolved run across ALL splits and ALL calibration scenarios.
        val allResults = evaluate(all, all, CubieInvariantResolver.DEFAULT_L_WEIGHT)
        val totalWrong = allResults.values.sumOf { it.resolvedWrongStickers }
        val wrongLines = allResults.values.flatMap { it.wrongDetails }
        assertEquals(0, totalWrong, "Resolver produced wrong stickers:\n${wrongLines.joinToString("\n")}")
    }

    private fun StringBuilder.appendSection(results: Map<Scenario, Stats>) {
        appendLine(
            "| Scenario | Runs | Full No-Fallback (before -> after) | Side Faces Kept (before -> after) | Rescued Runs (stickers) | Wrong Resolved Stickers | Reasons |",
        )
        appendLine("|---|---|---|---|---|---|---|")
        results.forEach { (scenario, s) ->
            val reasons =
                s.reasons.entries
                    .sortedByDescending { it.value }
                    .joinToString(", ") { "${it.key}=${it.value}" }
            appendLine(
                "| ${scenario.title} | ${s.runs} | ${s.baselineFullNoFallback} -> ${s.afterFullNoFallback} | " +
                    "${s.baselineSideKept}/${s.baselineSideFaces} -> ${s.afterSideKept}/${s.baselineSideFaces} | " +
                    "${s.resolvedRuns} (${s.resolvedStickersTotal}) | ${s.resolvedWrongStickers} | $reasons |",
            )
        }
        val rescues = results.values.flatMap { it.rescuedDetails }
        if (rescues.isNotEmpty()) {
            appendLine()
            appendLine("Rescued:")
            rescues.forEach { appendLine("- $it") }
        }
        val wrongs = results.values.flatMap { it.wrongDetails }
        if (wrongs.isNotEmpty()) {
            appendLine()
            appendLine("WRONG:")
            wrongs.forEach { appendLine("- $it") }
        }
    }

    private companion object {
        val FIXTURES = File("../../Fixtures/corner")
        val REPORT_FILE = File("build/reports/quick-scan/cubie-resolver-spike.md")
        val FIXTURE = Regex("""corner-\d+\.json""")
        val LEGACY_DIRECTION = mapOf("warm" to LightDirection.FRONT, "cool-front" to LightDirection.FRONT, "cool" to LightDirection.BACK)
    }
}
