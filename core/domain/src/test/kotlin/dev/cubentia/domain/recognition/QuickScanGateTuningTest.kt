package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Story 10.21's tuning helper: fits the per-colour agreement gate ([QuickScanClassification.AGREEMENT_MARGIN]), the
 * calibration match's centre closeness ([QuickScanClassification.MAX_CENTRE_DELTA_E]), the confident-count wait ([QuickScanClassification.MIN_CONFIDENT]) and the light check's hold and clear cuts ([SideLight]) on
 * the 10.20 **tuning split only**, and asserts the committed constants are the fitted ones. Only the folders the group
 * index names `tuning` are ever decoded here: held-out groups are never read by fitting code. The fit is written to
 * `build/reports/quick-scan/tuning-10-21.md` (docs/evidence/10-21/tuning-and-replay.md quotes it).
 */
class QuickScanGateTuningTest {
    private class Group(
        val name: String,
        val device: String,
        val cube: String,
        val light: String,
        val direction: LightDirection,
        val torch: Boolean?,
        val records: List<CornerFixtureRecord>,
        /** The group index marks the cube pastel (Story 10.27 judges those against the cube's own colours). */
        val pastel: Boolean = false,
    ) {
        val samples get() = records.flatMap { record -> record.labeledSamples.map { it.sample } }

        /** The recorder calibration tagged with the group's torch, as Quick Scan would have saved it (no flash: off). */
        val tagged: CalibrationCenters get() = records.first().calibration.copy(torchOn = torch == true)
        val captureTorch: Map<Face, Boolean>
            get() = records.flatMap { record -> record.labeledSamples.map { it.sample.face to (record.torch == true) } }.toMap()
    }

    /**
     * The tuning groups (10.22 pipeline, split `tuning` in the group index's used groups); nothing else is decoded. Story
     * 10.28: a group [CornerGroupUse] does not mark used (unsaved, or overridden and not included by the PO) is skipped.
     */
    private fun tuningGroups(root: File = FIXTURES): List<Group> {
        assumeTrue(root.isDirectory, "Corner fixtures are recorded in the device session")
        val indexFile = File(root, CornerFixturePaths.GROUP_INDEX)
        assumeTrue(indexFile.isFile, "No corner group index")
        val index = CornerFixtureJson.decodeIndex(indexFile.readText())
        return index
            .used()
            .filter { it.split == CornerSplit.TUNING }
            .sortedBy { it.group }
            .mapNotNull { entry ->
                val dir = File(root, entry.group)
                val manifest = CornerFixtureJson.decodeManifest(File(dir, CornerFixturePaths.MANIFEST).readText())
                if (CornerGroupUse.of(entry.group, manifest, index) != CornerGroupUse.USED) return@mapNotNull null
                val records =
                    dir
                        .listFiles()
                        .orEmpty()
                        .filter { it.name.matches(FIXTURE) }
                        .sortedBy { it.name }
                        .map { CornerFixtureJson.decode(it.readText()) }
                        .filter { it.label == CornerLabel.POSITIVE && it.labeledSamples.size == 3 }
                if (records.isEmpty()) return@mapNotNull null
                Group(
                    entry.group,
                    records.first().device.model,
                    records.first().cube,
                    records.first().light,
                    manifest.lightDirection,
                    manifest.torch ?: records.first().torch,
                    records,
                    entry.pastel,
                )
            }
    }

/** Story 10.28: how many recorded groups under [root] [CornerGroupUse] does not mark used, as the reports' line. */
    private fun skippedLine(root: File = FIXTURES): String {
        val index =
            File(root, CornerFixturePaths.GROUP_INDEX).takeIf { it.isFile }?.let { CornerFixtureJson.decodeIndex(it.readText()) }
        val uses =
            root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
                File(dir, CornerFixturePaths.MANIFEST).takeIf { it.isFile }?.let {
                    CornerGroupUse.of(dir.name, CornerFixtureJson.decodeManifest(it.readText()), index)
                }
            }
        return "Skipped: ${uses.count { it == CornerGroupUse.OVERRIDE_SKIPPED }} overridden (not included by the PO), " +
            "${uses.count { it == CornerGroupUse.UNSAVED }} unsaved"
    }

    @Test
    fun `story 10-28 only used tuning groups are tuned and the skipped ones are counted`(
        @TempDir root: File,
    ) {
        val source = File(FIXTURES, "lenovo-tb128xu_stka_warm")
        assumeTrue(source.isDirectory, "The legacy Lenovo group is needed as copies")
        val first = CornerFixtureJson.decode(File(source, CornerFixturePaths.fileName(1)).readText())
        val base = CornerGroupManifest(CornerFixturePaths.PIPELINE_10_22, LightDirection.FRONT, first.device, first.cube, first.light)
        mapOf(
            "passed" to base.copy(countCheck = CornerCountCheck.PASSED),
            "pending" to base.copy(countCheck = CornerCountCheck.PENDING),
            "refused" to base.copy(countCheck = CornerCountCheck.REFUSED),
            "over_in" to base.copy(countCheck = CornerCountCheck.OVERRIDDEN, overrideReason = "reason"),
            "over_out" to base.copy(countCheck = CornerCountCheck.OVERRIDDEN, overrideReason = "reason"),
        ).forEach { (name, manifest) ->
            val target = File(root, name)
            source.copyRecursively(target)
            File(target, CornerFixturePaths.MANIFEST).writeText(CornerFixtureJson.encodeManifest(manifest))
        }
        val index =
            CornerGroupIndex(
                groups =
                    listOf(
                        CornerGroupIndexEntry("passed", CornerSplit.TUNING),
                        // Listed by mistake: an unsaved group is still never tuned.
                        CornerGroupIndexEntry("pending", CornerSplit.TUNING),
                    ),
                overridden =
                    listOf(
                        CornerOverriddenEntry("over_in", CornerSplit.TUNING, "reason", included = true),
                        CornerOverriddenEntry("over_out", CornerSplit.TUNING, "reason"),
                    ),
            )
        File(root, CornerFixturePaths.GROUP_INDEX).writeText(CornerFixtureJson.encodeIndex(index))
        assertEquals(listOf("over_in", "passed"), tuningGroups(root).map { it.name })
        assertEquals("Skipped: 1 overridden (not included by the PO), 2 unsaved", skippedLine(root))
    }

    /** Every calibration the scan could hold for [group]: none, its own, and every other tuning group's of the same device. */
    private fun calibrations(
        group: Group,
        all: List<Group>,
    ): List<Pair<String, CalibrationCenters?>> =
        listOf<Pair<String, CalibrationCenters?>>("none" to null, "own" to group.tagged) +
            all.filter { it !== group && it.device == group.device }.map { "other:${it.name}" to it.tagged }

    private class Read(
        val group: String,
        val calibration: String,
        val sticker: String,
        val truth: Color,
        val reads: QuickScanClassification.StickerReads,
    )

    /** The 0.1 step strictly above [value] (a margin a sticker read at [value] fails), rounded to one decimal. */
    private fun stepAbove(value: Double): Double = Math.round((floor(value * 10 + 1e-9) + 1)) / 10.0

    /**
     * The per-colour gate fit, from the 10.19 rule ([StickerClassifier.CLOSE_CALL_DELTA_E] for every colour) upwards:
     * while some tuning face the keep rule keeps (under any calibration the scan could hold) carries a confidently wrong
     * sticker, each colour such a sticker reads as gets the margin just above the largest min(first, second) margin among
     * them. Margins only rise, so the loop ends; a colour no kept face ever reads wrongly keeps the 10.19 rule.
     */
    private fun fitMargins(groups: List<Group>): Map<Color, Double> {
        val margin = QuickScanClassification.STRICT_MARGIN.toMutableMap()
        while (true) {
            val wrong = wrongKept(groups, margin)
            if (wrong.isEmpty()) return margin
            wrong.groupBy { it.reads.first!!.color }.forEach { (color, reads) ->
                margin[color] =
                    maxOf(
                        margin.getValue(color),
                        stepAbove(reads.maxOf { minOf(it.reads.firstMargin ?: 0.0, it.reads.secondMargin ?: 0.0) }),
                    )
            }
        }
    }

    /** Confidently wrong judged stickers on faces the keep rule keeps with the gate's [margin], over every tuning group and calibration. */
    private fun wrongKept(
        groups: List<Group>,
        margin: Map<Color, Double>,
    ): List<Read> =
        groups.flatMap { group ->
            calibrations(group, groups).flatMap { (label, saved) ->
                group.records.flatMap { record ->
                    record.labeledSamples.flatMap { labeled ->
                        val decision = QuickScanClassification.decide(labeled.sample, group.samples, saved, group.captureTorch, margin)
                        if (!decision.keep) return@flatMap emptyList()
                        val reads = QuickScanClassification.stickerReads(labeled.sample, group.samples, saved, group.captureTorch)
                        labeled.truth.indices
                            .filter { it != FaceSample.CENTER_INDEX || labeled.sample.centerPlain != false }
                            .filter { index ->
                                val reading = decision.readings[index]
                                reading?.confidence == StickerConfidence.CONFIDENT && reading.color != labeled.truth[index]
                            }.map { index ->
                                Read(
                                    group.name,
                                    label,
                                    "h${record.hold} ${labeled.sample.face}${index + 1}",
                                    labeled.truth[index],
                                    reads[index],
                                )
                            }
                    }
                }
            }
        }

    /** Each plain captured centre's colour and ΔE to [saved]'s centre of that colour, as [QuickScanClassification.match] judges it. */
    private fun centreDistances(
        group: Group,
        saved: CalibrationCenters,
    ): List<Pair<Color, Double>> =
        group.samples
            .filter { it.centerPlain != false && (group.captureTorch[it.face] == true) == (saved.torchOn == true) }
            .mapNotNull { sample ->
                val color = sample.centerColor ?: Color.home(sample.face)
                sample.center?.let { color to StickerClassifier.deltaE(it, saved.centers.getValue(color)) }
            }

    /**
     * The centre closeness fit (PO 2026-11-24): over the tuning groups whose own (torch-tagged) calibration passes the
     * centre read alone, per colour the 0.1 step above the largest ΔE of a captured centre to its own calibration, so no
     * such same-cube match is lost; a colour with no such centre takes the strictest (smallest) fitted value (PO 2026-11-24).
     */
    private fun fitCentreDeltaE(groups: List<Group>): Map<Color, Double> {
        val fitted =
            groups
                .filter { QuickScanClassification.matchAll(it.samples, it.tagged, it.captureTorch, null) == CalibrationMatch.Match }
                .flatMap { centreDistances(it, it.tagged) }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, distances) -> stepAbove(distances.max()) }
        val fallback = fitted.values.min()
        return Color.entries.associateWith { fitted[it] ?: fallback }
    }

    /** How many of every tuning group's own and other calibrations match, by the centre read alone ([closeness] null) or with it. */
    private fun matchCounts(
        groups: List<Group>,
        closeness: Map<Color, Double>?,
    ): Pair<String, String> {
        val own =
            groups.count {
                QuickScanClassification.matchAll(it.samples, it.tagged, it.captureTorch, closeness) ==
                    CalibrationMatch.Match
            }
        val others = groups.flatMap { group -> calibrations(group, groups).drop(2).map { group to it.second } }
        val other =
            others.count { (group, saved) ->
                QuickScanClassification.matchAll(group.samples, saved, group.captureTorch, closeness) == CalibrationMatch.Match
            }
        return "$own/${groups.size}" to "$other/${others.size}"
    }

    /** Each hold's CONFIDENT judged stickers under the scan-time (own, torch-tagged) calibration, read with the holds before it. */
    private fun confidentCounts(
        group: Group,
        margin: Map<Color, Double>,
    ): List<Int> {
        var prior = emptyList<FaceSample>()
        return group.records.map { record ->
            val hold = record.labeledSamples.map { it.sample }
            val merged = prior.filter { old -> hold.none { it.face == old.face } } + hold
            prior = merged
            // The judged stickers, as the wait counts them (a guarded centre is not judged).
            hold.sumOf { sample ->
                QuickScanClassification
                    .decide(sample, merged, group.tagged, group.captureTorch, margin)
                    .readings
                    .filterIndexed { index, _ -> index != FaceSample.CENTER_INDEX || sample.centerPlain != false }
                    .count { it?.confidence == StickerConfidence.CONFIDENT }
            }
        }
    }

    /** The largest N up to [MAX_WAIT_N] that every positive tuning hold's captured frame reaches. */
    private fun fitWait(
        groups: List<Group>,
        margin: Map<Color, Double>,
    ): Int = minOf(MAX_WAIT_N, groups.flatMap { confidentCounts(it, margin) }.min())

    /** The front-lit tuning holds' largest light score per mode (uncalibrated; calibrated against the hold's own recorder calibration). */
    private fun frontMax(
        groups: List<Group>,
        calibrated: Boolean,
    ): Double =
        groups
            .filter { it.direction == LightDirection.FRONT }
            .flatMap { group ->
                group.records.mapNotNull { record ->
                    SideLight.checkSamples(record.labeledSamples.map { it.sample }, record.calibration.takeIf { calibrated }).score
                }
            }.max()

    /**
     * The hysteresis cuts per mode from the tuning front-lit maximum: the hold cut [LIGHT_MARGIN] above it (on the score
     * itself uncalibrated, on its excess over 1 calibrated), rounded up; the clear cut midway between, rounded down.
     */
    private fun fitCuts(
        max: Double,
        calibrated: Boolean,
    ): Pair<Double, Double> {
        val scale = if (calibrated) 100.0 else 10.0
        val raw = if (calibrated) 1 + (max - 1) * (1 + LIGHT_MARGIN) else max * (1 + LIGHT_MARGIN)
        val hold = ceil(raw * scale - 1e-9) / scale
        val clear = floor((max + hold) / 2 * scale + 1e-9) / scale
        return hold to clear
    }

    private class Outcome(
        var correct: Int = 0,
        var uncertain: Int = 0,
        var wrong: Int = 0,
        var faces: Int = 0,
        var kept: Int = 0,
        var wrongKept: Int = 0,
        var blanks: Int = 0,
    )

    /** Judged stickers and faces of every tuning group under every calibration, with the gate's [margin]. */
    private fun outcome(
        groups: List<Group>,
        margin: Map<Color, Double>,
    ): Outcome =
        Outcome().also { out ->
            groups.forEach { group ->
                calibrations(group, groups).forEach { (_, saved) ->
                    group.records.forEach { record ->
                        record.labeledSamples.forEach { labeled ->
                            val decision = QuickScanClassification.decide(labeled.sample, group.samples, saved, group.captureTorch, margin)
                            out.faces++
                            if (decision.keep) out.kept++
                            if (decision.keep) out.blanks += decision.blanks.size
                            labeled.truth.indices
                                .filter { it != FaceSample.CENTER_INDEX || labeled.sample.centerPlain != false }
                                .forEach { index ->
                                    val reading = decision.readings[index]
                                    when {
                                        reading?.confidence != StickerConfidence.CONFIDENT -> {
                                            out.uncertain++
                                        }

                                        reading.color == labeled.truth[index] -> {
                                            out.correct++
                                        }

                                        else -> {
                                            out.wrong++
                                            if (decision.keep) out.wrongKept++
                                        }
                                    }
                                }
                        }
                    }
                }
            }
        }

    private fun Outcome.row(label: String): String = "| $label | $correct | $uncertain | $wrong | $kept/$faces | $blanks | $wrongKept |"

    private fun format(value: Double): String = "%.3f".format(Locale.ROOT, value)

    @Test
    fun `the committed gate, wait and light cuts are the tuning split's fit`() {
        val groups = tuningGroups()
        assumeTrue(groups.isNotEmpty(), "No tuning corner groups")
        // The gate is fitted on the plain tuning groups: a pastel cube (group index `pastel`) is Story 10.27's, judged
        // against its own calibrated colours; tuning every cube's gate on it would make Red never CONFIDENT (see below).
        val plain = groups.filter { !it.pastel }
        val centreDeltaE = fitCentreDeltaE(groups)
        val margins = fitMargins(plain)
        val wait = fitWait(plain, margins)
        val uncalibratedMax = frontMax(groups, calibrated = false)
        val calibratedMax = frontMax(groups, calibrated = true)
        val (uncalibratedHold, uncalibratedClear) = fitCuts(uncalibratedMax, calibrated = false)
        val (calibratedHold, calibratedClear) = fitCuts(calibratedMax, calibrated = true)
        val before = outcome(plain, QuickScanClassification.STRICT_MARGIN)
        val after = outcome(plain, margins)
        val pastel = groups.filter { it.pastel }
        val text =
            buildString {
                appendLine("# Story 10.21 tuning fit (tuning split only)")
                appendLine()
                appendLine("- ${skippedLine()}")
                appendLine("- plain groups (fit): ${plain.joinToString { it.name }}")
                appendLine("- pastel groups (reported, Story 10.27): ${groups.filter { it.pastel }.joinToString { it.name }}")
                appendLine("- agreement margins (fit): ${margins.entries.joinToString { "${it.key}=${it.value}" }}")
                wrongKept(plain, QuickScanClassification.STRICT_MARGIN)
                    .groupBy { it.reads.first!!.color }
                    .forEach { (color, wrong) ->
                        val worst = wrong.maxBy { minOf(it.reads.firstMargin ?: 0.0, it.reads.secondMargin ?: 0.0) }
                        appendLine(
                            "  - $color: worst wrong sticker on a kept face under the 10.19 gate is " +
                                "${worst.group} ${worst.sticker} (${worst.calibration}): label ${worst.truth}, margins ${format(
                                    worst.reads.firstMargin ?: 0.0,
                                )} / ${format(worst.reads.secondMargin ?: 0.0)}",
                        )
                    }
                appendLine(
                    "- centre closeness ΔE (fit, every tuning group's own calibration): ${centreDeltaE.entries.joinToString {
                        "${it.key}=${it.value}"
                    }}",
                )
                groups.forEach { group ->
                    val distances = centreDistances(group, group.tagged).joinToString { "${it.first}=${format(it.second)}" }
                    val read = QuickScanClassification.matchAll(group.samples, group.tagged, group.captureTorch, null)?.label
                    appendLine("  - ${group.name} own calibration (centre read alone: $read): $distances")
                }
                val (ownBefore, otherBefore) = matchCounts(groups, null)
                val (ownAfter, otherAfter) = matchCounts(groups, centreDeltaE)
                appendLine(
                    "  - tuning matches, centre read alone -> with closeness: own $ownBefore -> $ownAfter, " +
                        "other calibrations of the device $otherBefore -> $otherAfter",
                )
                appendLine("- confident-count wait N (fit): $wait (largest N <= $MAX_WAIT_N every tuning hold reaches)")
                groups.forEach { appendLine("  - ${it.name}: ${confidentCounts(it, margins).joinToString(" / ")} of 27") }
                appendLine(
                    "- uncalibrated front-lit max ${format(uncalibratedMax)}: hold cut $uncalibratedHold, clear cut $uncalibratedClear",
                )
                appendLine("- calibrated front-lit max ${format(calibratedMax)}: hold cut $calibratedHold, clear cut $calibratedClear")
                appendLine()
                appendLine("Before (10.19 gate) and after (fitted gate), every plain tuning group under every calibration:")
                appendLine()
                appendLine("| gate | correct | uncertain | confidently wrong | faces kept | blanks on kept | wrong on kept |")
                appendLine("|---|---|---|---|---|---|---|")
                appendLine(before.row("10.19 (both CONFIDENT)"))
                appendLine(after.row("10.21 per colour"))
                appendLine(outcome(pastel, QuickScanClassification.STRICT_MARGIN).row("pastel groups, 10.19 gate (reported)"))
                appendLine(outcome(pastel, margins).row("pastel groups, fitted gate (reported)"))
                appendLine()
                appendLine("Pastel stickers kept confidently wrong with the fitted gate (carried to Story 10.27):")
                appendLine()
                wrongKept(pastel, margins).forEach {
                    appendLine("- ${it.group} ${it.sticker} (${it.calibration}): label ${it.truth}, read ${it.reads.first?.color}")
                }
                val all = fitMargins(groups)
                appendLine()
                appendLine(
                    "Reference only, not committed: the same fit with the pastel group(s) included gives ${all.entries.joinToString {
                        "${it.key}=${it.value}"
                    }}:",
                )
                appendLine()
                appendLine("| gate | correct | uncertain | confidently wrong | faces kept | blanks on kept | wrong on kept |")
                appendLine("|---|---|---|---|---|---|---|")
                appendLine(outcome(groups, all).row("with pastel, on every tuning group"))
            }
        println(text)
        TUNING_REPORT.parentFile.mkdirs()
        TUNING_REPORT.writeText(text)
        Color.entries.forEach { color ->
            assertEquals(centreDeltaE.getValue(color), QuickScanClassification.MAX_CENTRE_DELTA_E.getValue(color), 1e-9, "$color centre ΔE")
            assertEquals(margins.getValue(color), QuickScanClassification.AGREEMENT_MARGIN.getValue(color), 1e-9, "$color margin")
        }
        assertEquals(wait, QuickScanClassification.MIN_CONFIDENT)
        assertEquals(uncalibratedHold, SideLight.MAX_SIDE_DARKER_L, 1e-9)
        assertEquals(uncalibratedClear, SideLight.CLEAR_SIDE_DARKER_L, 1e-9)
        assertEquals(calibratedHold, SideLight.MAX_TOP_OVER_SIDES_RATIO, 1e-9)
        assertEquals(calibratedClear, SideLight.CLEAR_TOP_OVER_SIDES_RATIO, 1e-9)
        // The fitted gate keeps no face with a confidently wrong sticker on the tuning split; every front-lit hold clears.
        assertEquals(0, after.wrongKept, text)
        assertTrue(uncalibratedClear > uncalibratedMax && calibratedClear > calibratedMax)
    }

    private companion object {
        val FIXTURES = File("../../Fixtures/corner")
        val FIXTURE = Regex("""corner-\d+\.json""")
        val TUNING_REPORT = File("build/reports/quick-scan/tuning-10-21.md")

        /** The wait never asks for more CONFIDENT stickers than this (Story 10.21: start at 6). */
        const val MAX_WAIT_N = 6

        /** The light hold cut's margin over the tuning front-lit maximum. */
        const val LIGHT_MARGIN = 0.10
    }
}
