package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Story 10.19 side-face replay: every recorded corner group in `Fixtures/corner/<group>/corner-<hold>.json` replays
 * through the production Quick Scan decision ([QuickScanClassification.needGrid]: quality plus the gated
 * classification) and readings ([QuickScanClassification.read]) under five calibration scenarios:
 * - **no saved** calibration (self-calibration plus the light-normalized agreement gate);
 * - **matching**: the group's own recorder calibration. It is taken flat (frontal), so it is also the **frontal
 *   calibration on angled side faces** scenario;
 * - **other light**: the same cube's calibration recorded in another light on the same device;
 * - **other cube**: another cube's calibration on the same device.
 *
 * A group is **scored** only when its `group.json` manifest says `"pipeline": "10.19"` (PO 2026-11-21 A), with its
 * light direction from the manifest. Pre-10.19 groups (no manifest) are reported only, their direction from the legacy
 * light labels (`warm`, `cool-front` front-lit; `cool` backlit; anything else unknown, never scored).
 *
 * The bar, enforced for every scored front-lit device×light group (cubes pooled): in the matching and no-saved
 * scenarios 0 confidently wrong side stickers and 0 Red<->Orange confusions on kept faces, at least [MIN_SIDE_CORRECT]
 * of side stickers correct and at least [MIN_SIDE_FACES_KEPT] of side faces kept (not sent to the Guided grid); in every
 * scenario 0 confidently wrong stickers on kept faces (a wrong sticker on a face sent to the grid is a safe fallback, PO
 * 2026-11-21 C). Scored backlit groups are reported and must trip [SideLight]. Pre-10.19 groups must trip [SideLight]
 * when backlit and pass it when front-lit (the threshold evidence for AC1); their replay is reported, not asserted.
 *
 * Story 10.20: a group whose manifest says `"pipeline": "10.22"` (10.19 lighting plus 10.22 settle-and-lock) is never
 * scored by this story and is excluded from every enforcing assertion; it gets the **baseline** section instead, per
 * device × light direction × light name × torch, in the no-saved and matching scenarios, printed and written to
 * `build/reports/quick-scan/baseline-10-20.md`. Story 10.21 enforces the bar on it. The group index
 * (`Fixtures/corner/groups.json`) names each group's split when it exists.
 */
class QuickScanSideFaceReplayTest {
    private class Tally {
        var correct = 0
        var uncertain = 0
        var wrong = 0
        var wrongKept = 0
        var sideWrongKept = 0
        var redOrangeKept = 0
        var sideFaces = 0
        var sideFacesKept = 0

        /** CONFIDENT judged stickers on kept faces and on faces sent to the grid, and the wrong ones among the latter. */
        var keptConfident = 0
        var gridConfident = 0
        var gridWrong = 0
        val byColor = Color.entries.associateWith { IntArray(3) }

        val cells get() = correct + uncertain + wrong

        operator fun plusAssign(other: Tally) {
            correct += other.correct
            uncertain += other.uncertain
            wrong += other.wrong
            wrongKept += other.wrongKept
            sideWrongKept += other.sideWrongKept
            redOrangeKept += other.redOrangeKept
            sideFaces += other.sideFaces
            sideFacesKept += other.sideFacesKept
            keptConfident += other.keptConfident
            gridConfident += other.gridConfident
            gridWrong += other.gridWrong
            byColor.forEach { (color, counts) -> other.byColor.getValue(color).forEachIndexed { i, n -> counts[i] += n } }
        }
    }

    private enum class Scenario(
        val title: String,
    ) {
        NONE("no saved calibration"),
        MATCHING("matching (frontal calibration on angled side faces)"),
        OTHER_LIGHT("other light, same cube"),
        OTHER_CUBE("other cube"),
    }

    private data class Group(
        val name: String,
        val device: String,
        val cube: String,
        val light: String,
        val direction: LightDirection?,
        val scored: Boolean,
        val records: List<CornerFixtureRecord>,
        /** The manifest's pipeline tag; null for a pre-10.19 group without a manifest. */
        val pipeline: String? = null,
        val torch: Boolean? = null,
        val split: CornerSplit? = null,
    ) {
        /** A 10.22-pipeline group (Story 10.20): reported in the baseline, never enforced here. */
        val baseline: Boolean get() = pipeline == CornerFixturePaths.PIPELINE_10_22

        val samples get() = records.flatMap { record -> record.labeledSamples.map { it.sample } }
        val calibration get() = records.first().calibration
        val stickered: Boolean? get() =
            if (cube.startsWith("stk")) {
                true
            } else if (cube.startsWith("stl")) {
                false
            } else {
                null
            }

        /** The light check per hold: uncalibrated, or calibrated against the group's own recorder calibration. */
        fun lightChecks(calibrated: Boolean): List<Pair<Int, SideLight.Verdict>> =
            records.map { record ->
                record.hold to
                    SideLight.checkSamples(
                        record.labeledSamples.map { labeled -> labeled.sample },
                        if (calibrated) record.calibration else null,
                    )
            }

        /** Both modes: whether every hold passes in each. */
        fun lightModes(): List<List<Pair<Int, SideLight.Verdict>>> = listOf(lightChecks(false), lightChecks(true))
    }

    private fun groups(root: File = FIXTURES): List<Group> {
        assumeTrue(root.isDirectory, "Corner fixtures are recorded in the device session")
        val index =
            File(root, CornerFixturePaths.GROUP_INDEX).takeIf { it.isFile }?.let { CornerFixtureJson.decodeIndex(it.readText()) }
        return root
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
                if (records.isEmpty()) return@mapNotNull null
                val manifest =
                    File(
                        dir,
                        CornerFixturePaths.MANIFEST,
                    ).takeIf { it.isFile }?.let { CornerFixtureJson.decodeManifest(it.readText()) }
                val scored = manifest?.pipeline == CornerFixturePaths.PIPELINE_10_19
                val first = records.first()
                val name = dir.relativeTo(root).path
                Group(
                    name = name,
                    device = first.device.model,
                    cube = first.cube,
                    light = first.light,
                    direction = if (manifest != null) manifest.lightDirection else LEGACY_DIRECTION[first.light],
                    scored = scored,
                    records = records,
                    pipeline = manifest?.pipeline,
                    torch = manifest?.torch ?: first.torch,
                    split = index?.entry(name)?.split,
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

    private fun tally(
        group: Group,
        saved: CalibrationCenters?,
    ): Tally {
        val merged = group.samples
        val toGrid = QuickScanClassification.needGrid(merged, Face.entries.filter { face -> merged.any { it.face == face } }, saved).toSet()
        val tally = Tally()
        group.records.forEach { record ->
            record.labeledSamples.forEachIndexed { position, labeled ->
                val side = position != 0
                val kept = labeled.sample.face !in toGrid
                if (side) {
                    tally.sideFaces++
                    if (kept) tally.sideFacesKept++
                }
                val readings = QuickScanClassification.read(labeled.sample, merged, saved)
                labeled.truth.forEachIndexed { index, truth ->
                    val reading = readings[index]
                    val confident = reading?.confidence == StickerConfidence.CONFIDENT
                    val wrong = confident && reading.color != truth
                    val judged = index != FaceSample.CENTER_INDEX || labeled.sample.centerPlain != false
                    if (kept && wrong && judged) tally.wrongKept++
                    if (confident && judged) {
                        if (kept) {
                            tally.keptConfident++
                        } else {
                            tally.gridConfident++
                            if (wrong) tally.gridWrong++
                        }
                    }
                    if (!side || index == FaceSample.CENTER_INDEX) return@forEachIndexed
                    val counts = tally.byColor.getValue(truth)
                    when {
                        !confident -> {
                            tally.uncertain++
                            counts[1]++
                        }

                        !wrong -> {
                            tally.correct++
                            counts[0]++
                        }

                        else -> {
                            tally.wrong++
                            counts[2]++
                            if (kept) {
                                tally.sideWrongKept++
                                if (setOf(reading.color, truth) == setOf(Color.RED, Color.ORANGE)) tally.redOrangeKept++
                            }
                        }
                    }
                }
            }
        }
        return tally
    }

    private fun scenarioTally(
        group: Group,
        all: List<Group>,
        scenario: Scenario,
    ): Tally? {
        val calibrations = calibrations(group, all, scenario)
        if (calibrations.isEmpty()) return null
        return Tally().also { total -> calibrations.forEach { total += tally(group, it) } }
    }

    private fun Tally.row(label: String): String {
        val colors =
            Color.entries.joinToString(" ") { color ->
                val (c, u, w) = byColor.getValue(color).toList()
                val name = color.name.lowercase().replaceFirstChar { it.uppercase() }
                val mark = if (color == Color.YELLOW || color == Color.ORANGE) "**$name**" else name
                "$mark $c/$u/$w"
            }
        return "| $label | $correct/$uncertain/$wrong of $cells | $sideFacesKept/$sideFaces | $wrongKept | $redOrangeKept | $colors |"
    }

    private fun report(groups: List<Group>): String =
        buildString {
            appendLine("Story 10.19 Quick Scan side-face replay (QuickScanClassification; side cells correct/uncertain/wrong)")
            appendLine()
            appendLine(
                "Light check (SideLight) score per hold; uncalibrated: darker side face's 75th-percentile L* below the " +
                    "top face, cut ${SideLight.MAX_SIDE_DARKER_L} L*; calibrated: top's relative brightness over the mean " +
                    "of the two sides' (against the group's recorder calibration), cut ${SideLight.MAX_TOP_OVER_SIDES_RATIO}",
            )
            appendLine("| group | direction | scored | uncalibrated hold 1 | uncalibrated hold 2 | calibrated hold 1 | calibrated hold 2 |")
            appendLine("|---|---|---|---|---|---|---|")
            groups.forEach { group ->
                val holds =
                    group.lightModes().flatten().joinToString(" | ") { (_, verdict) ->
                        val pattern = if (verdict.mode == SideLight.Mode.CALIBRATED) "%.3f" else "%.1f"
                        val score = verdict.score?.let { pattern.format(java.util.Locale.ROOT, it) } ?: "n/a"
                        "$score ${if (verdict.passed) "pass" else "FAIL"}"
                    }
                appendLine("| ${group.name} | ${group.direction?.name?.lowercase() ?: "unknown"} | ${group.scored} | $holds |")
            }
            appendLine()
            appendLine(
                "| group / scenario | side correct/uncertain/wrong | side faces kept | wrong on kept faces | R<->O on kept | per color c/u/w |",
            )
            appendLine("|---|---|---|---|---|---|")
            groups.forEach { group ->
                Scenario.entries.forEach { scenario ->
                    val tally = scenarioTally(group, groups, scenario)
                    if (tally == null) {
                        appendLine("| ${group.name} / ${scenario.title} | n/a (no such calibration recorded) | | | | |")
                    } else {
                        appendLine(tally.row("${group.name} / ${scenario.title}"))
                    }
                }
            }
        }

    /** Required-but-missing scored groups (a failure once any group is scored; reported only while none is). */
    private fun missing(groups: List<Group>): List<String> {
        val front = groups.filter { it.scored && it.direction == LightDirection.FRONT }
        return REQUIRED_DEVICES.flatMap { (name, matches) ->
            val mine = front.filter { matches(it.device) }
            buildList {
                if (mine.none { it.stickered == true }) add("$name: a scored front-lit stickered cube group")
                if (mine.none { it.stickered == false }) add("$name: a scored front-lit stickerless cube group")
                val lights = mine.map { it.light }.toSet()
                if (lights.size < 2) add("$name: at least 2 front lights (has ${lights.size})")
                if (groups.none {
                        it.scored && it.direction == LightDirection.BACK &&
                            matches(
                                it.device,
                            )
                    }
                ) {
                    add("$name: a scored backlit group")
                }
            }
        }
    }

    private fun violations(groups: List<Group>): List<String> =
        buildList {
            val scored = groups.filter { it.scored }
            scored.filter { it.direction == LightDirection.BACK }.forEach { group ->
                group.lightModes().forEachIndexed { mode, checks ->
                    val name = if (mode == 0) "uncalibrated" else "calibrated"
                    if (checks.all { it.second.passed }) add("${group.name}: a scored backlit group passes $name SideLight on every hold")
                }
            }
            scored.filter { it.direction == LightDirection.FRONT }.groupBy { it.device to it.light }.forEach { (key, members) ->
                val label = "${key.first} × ${key.second}"
                Scenario.entries.forEach { scenario ->
                    val tally = Tally().also { total -> members.forEach { g -> scenarioTally(g, groups, scenario)?.let { total += it } } }
                    if (tally.wrongKept >
                        0
                    ) {
                        add("$label / ${scenario.title}: ${tally.wrongKept} confidently wrong stickers on kept faces (bar: 0)")
                    }
                    if (scenario == Scenario.NONE || scenario == Scenario.MATCHING) {
                        if (tally.redOrangeKept >
                            0
                        ) {
                            add("$label / ${scenario.title}: ${tally.redOrangeKept} Red<->Orange on kept faces (bar: 0)")
                        }
                        if (tally.correct < MIN_SIDE_CORRECT * tally.cells) {
                            add("$label / ${scenario.title}: side correct ${tally.correct}/${tally.cells} (bar: >= 90%)")
                        }
                        if (tally.sideFacesKept < MIN_SIDE_FACES_KEPT * tally.sideFaces) {
                            add("$label / ${scenario.title}: side faces kept ${tally.sideFacesKept}/${tally.sideFaces} (bar: >= 3 of 4)")
                        }
                    }
                }
            }
        }

    @Test
    fun `the light check scores of the legacy holds match the derivation in the evidence doc`() {
        // Per hold (1, 2): uncalibrated darker-side difference in L* and calibrated top over the mean of both sides,
        // each group against its own recorder calibration (docs/evidence/10-19/light-check-and-replay.md).
        val expected =
            mapOf(
                "lenovo-tb128xu_stka_cool" to listOf(32.4, 30.2, 1.392, 1.337),
                "lenovo-tb128xu_stka_cool-front" to listOf(23.0, 22.7, 1.109, 0.994),
                "lenovo-tb128xu_stka_warm" to listOf(23.3, 25.0, 1.177, 1.165),
                "lenovo-tb128xu_stkb_cool" to listOf(29.1, 28.7, 1.425, 1.352),
                "lenovo-tb128xu_stla_cool" to listOf(17.2, 36.3, 1.431, 1.394),
                "lenovo-tb128xu_stla_cool-front" to listOf(21.6, 20.9, 1.232, 1.286),
                "lenovo-tb128xu_stla_warm" to listOf(13.8, 24.6, 0.991, 1.161),
                "lenovo-tb128xu_stlb_cool" to listOf(32.8, 28.6, 1.553, 1.349),
            )
        val legacy = groups().filter { !it.scored && it.name in expected }
        assumeTrue(legacy.isNotEmpty(), "No pre-10.19 corner groups")
        legacy.forEach { group ->
            val (uncalibrated, calibrated) = group.lightModes()
            val values = expected.getValue(group.name)
            uncalibrated.sortedBy { it.first }.forEachIndexed { i, (hold, verdict) ->
                assertEquals(SideLight.Mode.UNCALIBRATED, verdict.mode)
                assertEquals(values[i], verdict.score!!, 0.05, "${group.name} hold $hold uncalibrated")
            }
            calibrated.sortedBy { it.first }.forEachIndexed { i, (hold, verdict) ->
                assertEquals(SideLight.Mode.CALIBRATED, verdict.mode)
                assertEquals(values[2 + i], verdict.score!!, 0.005, "${group.name} hold $hold calibrated")
            }
        }
    }

    @Test
    fun `both light check modes trip every legacy backlit group and pass every legacy front-lit hold`() {
        val legacy = groups().filter { it.pipeline == null }
        assumeTrue(legacy.isNotEmpty(), "No pre-10.19 corner groups")
        legacy.forEach { group ->
            group.lightModes().forEachIndexed { mode, checks ->
                val name = if (mode == 0) "uncalibrated" else "calibrated"
                when (group.direction) {
                    LightDirection.BACK -> {
                        assertTrue(checks.any { !it.second.passed }, "${group.name} is backlit and must trip $name SideLight")
                    }

                    LightDirection.FRONT -> {
                        assertTrue(checks.all { it.second.passed }, "${group.name} is front-lit and must pass $name SideLight")
                    }

                    LightDirection.SIDE, null -> {
                        Unit
                    }
                }
            }
        }
    }

    /** The groups this story enforces on: legacy and 10.19 groups; 10.22 groups are only reported (Story 10.20). */
    private fun enforced(groups: List<Group>) = groups.filter { !it.baseline }

    /** Every enforced group's confidently wrong stickers on kept faces, per calibration scenario. */
    private fun unsafe(groups: List<Group>): List<String> =
        groups.flatMap { group ->
            Scenario.entries.flatMap { scenario ->
                calibrations(group, groups, scenario).mapNotNull { saved ->
                    val wrong = tally(group, saved).wrongKept
                    if (wrong > 0) "${group.name} / ${scenario.title}: $wrong confidently wrong on kept faces" else null
                }
            }
        }

    @Test
    fun `no group in any scenario reads a confidently wrong sticker on a kept face`() {
        // Confidence calibration NFR (PO 2026-11-21 C): legacy and scored groups alike, every calibration scenario.
        val unsafe = unsafe(enforced(groups()))
        assertTrue(unsafe.isEmpty(), unsafe.joinToString("\n"))
    }

    @Test
    fun `the side-face bar holds on every scored front-lit device and light`() {
        val groups = enforced(groups())
        val violations = violations(groups)
        val missing = missing(groups)
        val scored = groups.count { it.scored }
        val report =
            report(groups) +
                "\nScored (10.19-pipeline) groups: $scored" +
                (if (scored == 0) " — no group is scored yet; the bar applies once the device session records them." else "") +
                "\nRequired but missing scored groups:\n" +
                missing.joinToString("\n") { "- $it" }.ifEmpty { "- none" } +
                "\nBar violations:\n" +
                violations.joinToString("\n") { "- $it" }.ifEmpty { "- none" }
        println(report)
        assertTrue(violations.isEmpty(), report)
        // AC6 coverage: once the device session records any scored group, partial coverage cannot pass the bar.
        if (scored > 0) assertTrue(missing.isEmpty(), report)
    }

    private fun format(
        value: Double?,
        mode: SideLight.Mode,
    ): String = value?.let { (if (mode == SideLight.Mode.CALIBRATED) "%.3f" else "%.1f").format(java.util.Locale.ROOT, it) } ?: "n/a"

    /** Per face of [group] under [saved]: kept or sent to the grid, with the close calls and disagreements of the gate. */
    private fun faceReasons(
        group: Group,
        saved: CalibrationCenters?,
    ): String {
        val merged = group.samples
        val faces = Face.entries.filter { face -> merged.any { it.face == face } }
        return QuickScanClassification.report(merged, faces, saved).joinToString(" ") { face ->
            "${face.face}:${if (face.grid) "grid" else "kept"}(closeCall=${face.closeCalls},disagree=${face.disagreements})"
        }
    }

    /**
     * The Story 10.20 baseline of the 10.22 groups among [groups], never enforced: per device × light direction × light
     * name × torch, in the no-saved and matching scenarios, each group's side stickers correct/uncertain/wrong, side faces
     * kept, confidently wrong among CONFIDENT stickers on kept and grid faces, and each face's keep or grid reason; per
     * backlit group the light check scores per hold, flagging a hold that passes.
     */
    private fun baseline(groups: List<Group>): String =
        buildString {
            val recorded = groups.filter { it.baseline }
            appendLine("# Story 10.20 baseline: 10.22-pipeline corner groups (reported, not enforced; Story 10.21 enforces)")
            appendLine()
            if (recorded.isEmpty()) {
                appendLine("no 10.22 groups recorded yet")
                return@buildString
            }
            val conditions =
                recorded
                    .groupBy { listOf(it.device, it.direction?.name?.lowercase() ?: "unknown", it.light, "torch=${it.torch ?: "n/a"}") }
                    .toSortedMap(compareBy { it.joinToString("|") })
            conditions.forEach { (key, members) ->
                appendLine("## ${key.joinToString(" × ")}")
                appendLine()
                appendLine(
                    "| group (split) / scenario | side correct/uncertain/wrong | side faces kept | kept: wrong/confident | grid: wrong/confident | faces |",
                )
                appendLine("|---|---|---|---|---|---|")
                listOf(Scenario.NONE, Scenario.MATCHING).forEach { scenario ->
                    val total = Tally()
                    members.forEach { group ->
                        val saved = calibrations(group, groups, scenario).single()
                        val tally = tally(group, saved).also { total += it }
                        val label = "${group.name} (${group.split?.name?.lowercase() ?: "unsplit"}) / ${scenario.title}"
                        appendLine(
                            "| $label | ${tally.correct}/${tally.uncertain}/${tally.wrong} | ${tally.sideFacesKept}/${tally.sideFaces} | " +
                                "${tally.wrongKept}/${tally.keptConfident} | ${tally.gridWrong}/${tally.gridConfident} | ${faceReasons(
                                    group,
                                    saved,
                                )} |",
                        )
                    }
                    appendLine(
                        "| all / ${scenario.title} | ${total.correct}/${total.uncertain}/${total.wrong} | " +
                            "${total.sideFacesKept}/${total.sideFaces} | " +
                            "${total.wrongKept}/${total.keptConfident} | ${total.gridWrong}/${total.gridConfident} | |",
                    )
                }
                appendLine()
                members.filter { it.direction == LightDirection.BACK }.forEach { group ->
                    val holds =
                        group.lightModes().flatten().joinToString("; ") { (hold, verdict) ->
                            val mode = verdict.mode.name.lowercase()
                            val flag = if (verdict.passed && verdict.score != null) " PASSES (backlit)" else ""
                            "hold $hold $mode ${format(verdict.score, verdict.mode)}$flag"
                        }
                    appendLine("Backlit light scores, ${group.name}: $holds")
                }
                appendLine()
            }
        }

    /** Prints the baseline and writes it to the build reports. */
    private fun writeBaseline(groups: List<Group>): String =
        baseline(groups).also { text ->
            println(text)
            BASELINE_REPORT.parentFile.mkdirs()
            BASELINE_REPORT.writeText(text)
        }

    @Test
    fun `the 10-22 baseline is reported and never enforced`() {
        val groups = if (FIXTURES.isDirectory) groups() else emptyList()
        val text = writeBaseline(groups)
        if (groups.none { it.baseline }) assertTrue("no 10.22 groups" in text, text)
    }

    @Test
    fun `a 10-22 group gets the baseline section with its counts and no assertion fails on it`(
        @TempDir root: File,
    ) {
        val front = File(FIXTURES, "lenovo-tb128xu_stka_warm")
        val back = File(FIXTURES, "lenovo-tb128xu_stka_cool")
        assumeTrue(front.isDirectory && back.isDirectory, "The legacy Lenovo groups are needed as copies")
        listOf(front to LightDirection.FRONT, back to LightDirection.BACK).forEach { (source, direction) ->
            val target = File(root, source.name)
            source.copyRecursively(target)
            val first = CornerFixtureJson.decode(File(target, CornerFixturePaths.fileName(1)).readText())
            val manifest =
                CornerGroupManifest(
                    CornerFixturePaths.PIPELINE_10_22,
                    direction,
                    first.device,
                    first.cube,
                    first.light,
                    calibration = first.calibration,
                    holds = listOf(CornerHoldManifest(1), CornerHoldManifest(2)),
                )
            File(target, CornerFixturePaths.MANIFEST).writeText(CornerFixtureJson.encodeManifest(manifest))
        }
        val index = CornerGroupIndex(groups = listOf(CornerGroupIndexEntry(front.name, CornerSplit.HELDOUT)))
        File(root, CornerFixturePaths.GROUP_INDEX).writeText(CornerFixtureJson.encodeIndex(index))

        val groups = groups(root)
        assertEquals(2, groups.count { it.baseline })
        assertTrue(groups.none { it.scored })
        val text = baseline(groups)
        val warm = groups.single { it.name == front.name }
        assertEquals(CornerSplit.HELDOUT, warm.split)
        listOf(Scenario.NONE to null, Scenario.MATCHING to warm.calibration).forEach { (scenario, saved) ->
            val tally = tally(warm, saved)
            val row =
                "| ${warm.name} (heldout) / ${scenario.title} | ${tally.correct}/${tally.uncertain}/${tally.wrong} | " +
                    "${tally.sideFacesKept}/${tally.sideFaces} | ${tally.wrongKept}/${tally.keptConfident} | ${tally.gridWrong}/${tally.gridConfident} |"
            assertTrue(row in text, "$row\n$text")
            // The kept/grid confident counters, checked against an independent count of CONFIDENT judged stickers.
            val merged = warm.samples
            val confident =
                merged.sumOf { sample ->
                    QuickScanClassification.read(sample, merged, saved).withIndex().count { (index, reading) ->
                        (index != FaceSample.CENTER_INDEX || sample.centerPlain != false) &&
                            reading?.confidence == StickerConfidence.CONFIDENT
                    }
                }
            assertEquals(confident, tally.keptConfident + tally.gridConfident, scenario.title)
            assertTrue(tally.wrongKept <= tally.keptConfident, scenario.title)
            assertTrue(tally.gridWrong <= tally.gridConfident, scenario.title)
            assertTrue(tally.keptConfident > 0, "${scenario.title}: the front-lit group keeps some confident stickers")
        }
        assertTrue("## ${warm.device} × front × ${warm.light} × torch=n/a" in text, text)
        assertTrue("(unsplit) / no saved calibration" in text, text)
        assertTrue("Backlit light scores, lenovo-tb128xu_stka_cool: hold 1 uncalibrated" in text, text)
        // Nothing enforces on 10.22 groups: no bar violation, missing-group failure or unsafe-kept failure comes from them.
        val enforced = enforced(groups)
        assertTrue(enforced.isEmpty())
        assertTrue(violations(enforced).isEmpty())
        assertTrue(unsafe(enforced).isEmpty())
    }

    private companion object {
        val FIXTURES = File("../../Fixtures/corner")
        val BASELINE_REPORT = File("build/reports/quick-scan/baseline-10-20.md")
        val FIXTURE = Regex("""corner-\d+\.json""")
        const val MIN_SIDE_CORRECT = 0.90
        const val MIN_SIDE_FACES_KEPT = 0.75
        val LEGACY_DIRECTION = mapOf("warm" to LightDirection.FRONT, "cool-front" to LightDirection.FRONT, "cool" to LightDirection.BACK)
        val REQUIRED_DEVICES: List<Pair<String, (String) -> Boolean>> =
            listOf(
                "Lenovo TB128XU" to { model -> model.contains("TB128XU", ignoreCase = true) },
                "Galaxy S24 FE" to
                    { model -> model.contains("S24 FE", ignoreCase = true) || model.startsWith("SM-S721", ignoreCase = true) },
            )
    }
}
