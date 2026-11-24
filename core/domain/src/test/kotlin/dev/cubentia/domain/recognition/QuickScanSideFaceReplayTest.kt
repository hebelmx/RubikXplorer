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
 * `build/reports/quick-scan/baseline-10-20.md`. The group index (`Fixtures/corner/groups.json`) names each group's split
 * when it exists.
 *
 * Story 10.21 replays every face at the torch lit at its hold's capture, and tags each scenario's calibration with the
 * torch of the group it was recorded in (the matching calibration takes the group's own torch). On the 10.22 held-out
 * groups it enforces the safety half of AC6 (PO decision 2026-11-23, option (a)) in every scenario: 0 confidently wrong
 * stickers and 0 Red<->Orange on kept faces, and every backlit group trips the light check. The coverage half (>= 90%
 * side stickers correct, >= 3 of 4 side faces kept) is reported per device × light × torch against the bar, not enforced,
 * in `build/reports/quick-scan/replay-10-21.md` with the label-set commit and the label-exclusions hash. Legacy groups
 * (no manifest) are reported only. Tuning happens only in [QuickScanGateTuningTest], on the tuning split.
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
        var blanksKept = 0

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
            blanksKept += other.blanksKept
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

        /** Story 10.21: the group's recorder calibration tagged with the group's torch (no flash: off). */
        val tagged: CalibrationCenters get() = calibration.copy(torchOn = torch == true)

        /** Story 10.21: whether the torch was lit at each face's capture (its hold's torch; unknown: off). */
        val captureTorch: Map<Face, Boolean>
            get() = records.flatMap { record -> record.labeledSamples.map { it.sample.face to (record.torch == true) } }.toMap()

        /** A 10.22 held-out group (Story 10.21 enforces the safety half of AC6 on it). */
        val heldOut: Boolean get() = baseline && split == CornerSplit.HELDOUT
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
                listOf(group.tagged)
            }

            Scenario.OTHER_LIGHT -> {
                all
                    .filter {
                        it.device == group.device && it.cube == group.cube && it.light != group.light
                    }.map { it.tagged }
            }

            Scenario.OTHER_CUBE -> {
                all.filter { it.device == group.device && it.cube != group.cube }.map { it.tagged }
            }
        }

    private fun tally(
        group: Group,
        saved: CalibrationCenters?,
    ): Tally {
        val merged = group.samples
        val torch = group.captureTorch
        val toGrid =
            QuickScanClassification.needGrid(merged, Face.entries.filter { face -> merged.any { it.face == face } }, saved, torch).toSet()
        val tally = Tally()
        group.records.forEach { record ->
            record.labeledSamples.forEachIndexed { position, labeled ->
                val side = position != 0
                val kept = labeled.sample.face !in toGrid
                if (side) {
                    tally.sideFaces++
                    if (kept) tally.sideFacesKept++
                }
                val readings = QuickScanClassification.read(labeled.sample, merged, saved, torch)
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
                    // Story 10.21: a kept face's judged sticker left blank (not CONFIDENT) by the 1-blank rule.
                    if (kept && judged && !confident) tally.blanksKept++
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

    /** The groups the 10.19 bar test enforces on: 10.19-pipeline groups (and legacy groups, reported only); never 10.22 ones. */
    private fun enforced(groups: List<Group>) = groups.filter { !it.baseline }

    /** Story 10.21: the 10.22 held-out groups, on which the safety half of AC6 is enforced. */
    private fun heldOut(groups: List<Group>) = groups.filter { it.heldOut }

    /**
     * Every group's confidently wrong stickers and Red<->Orange mix-ups on kept faces, per calibration scenario, with
     * calibrations drawn from [all]; scenarios [skip] returns true for are left out.
     */
    private fun unsafe(
        groups: List<Group>,
        all: List<Group> = groups,
        skip: (Group, Scenario) -> Boolean = { _, _ -> false },
    ): List<String> =
        groups.flatMap { group ->
            Scenario.entries.filterNot { skip(group, it) }.flatMap { scenario ->
                calibrations(group, all, scenario).mapNotNull { saved ->
                    val tally = tally(group, saved)
                    val wrong = tally.wrongKept
                    val mixed = tally.redOrangeKept
                    if (wrong > 0 || mixed > 0) {
                        "${group.name} / ${scenario.title}: $wrong confidently wrong, $mixed Red<->Orange on kept faces"
                    } else {
                        null
                    }
                }
            }
        }

    @Test
    fun `no held-out group in any scenario reads a confidently wrong sticker on a kept face`() {
        // Story 10.21, AC6 safety half (PO 2026-11-23 (a)): every held-out 10.22 group, the off-face ones included, under
        // every calibration scenario (none, matching, other light, other cube), any device calibration as the source.
        // On an off-face group (PO 2026-11-23) the replay asserts the safety the code achieves: every scenario but the
        // ones listed in OFF_FACE_UNSAFE, which are reported (replay-10-21.md) and carried to Story 10.27 and the BETA call.
        val groups = groups()
        val heldOut = heldOut(groups)
        assumeTrue(heldOut.isNotEmpty(), "No held-out 10.22 corner groups")
        val unsafe = unsafe(heldOut, groups) { group, scenario -> scenario in OFF_FACE_UNSAFE[group.name].orEmpty() }
        assertTrue(unsafe.isEmpty(), unsafe.joinToString("\n"))
        // The listed exceptions are real (a fix shows up here and the list must shrink), and only off-face groups have any.
        assertTrue(OFF_FACE_UNSAFE.keys.all { it in OFF_FACE })
        OFF_FACE_UNSAFE.forEach { (name, scenarios) ->
            val group = heldOut.firstOrNull { it.name == name } ?: return@forEach
            scenarios.forEach { scenario ->
                assertTrue(
                    unsafe(listOf(group), groups) { _, other -> other != scenario }.isNotEmpty(),
                    "$name / ${scenario.title} is safe now",
                )
            }
        }
    }

    @Test
    fun `no other cube's or other light's calibration matches a held-out group`() {
        // Story 10.21 AC1 (PO 2026-11-24): the centre closeness check fails every foreign calibration on the held-out groups.
        val groups = groups()
        val heldOut = heldOut(groups)
        assumeTrue(heldOut.isNotEmpty(), "No held-out 10.22 corner groups")
        val matched = foreignMatches(heldOut, groups, QuickScanClassification.MAX_CENTRE_DELTA_E)
        assertTrue(matched.isEmpty(), matched.joinToString("\n"))
    }

    /** Each OTHER_CUBE and OTHER_LIGHT calibration that matches a group of [heldOut], with the centre closeness [closeness]. */
    private fun foreignMatches(
        heldOut: List<Group>,
        all: List<Group>,
        closeness: Map<Color, Double>?,
    ): List<String> =
        heldOut.flatMap { group ->
            listOf(Scenario.OTHER_CUBE, Scenario.OTHER_LIGHT).flatMap { scenario ->
                calibrations(group, all, scenario)
                    .filter { QuickScanClassification.matchAll(group.samples, it, group.captureTorch, closeness) == CalibrationMatch.Match }
                    .map { "${group.name} / ${scenario.title}: a foreign calibration matches" }
            }
        }

    @Test
    fun `every held-out backlit group trips the refit light check`() {
        // Story 10.21: uncalibrated (no or mismatched calibration) and, when the group's own calibration matches its
        // captured faces, calibrated as the scan would run it.
        val backlit = heldOut(groups()).filter { it.direction == LightDirection.BACK }
        assumeTrue(backlit.isNotEmpty(), "No held-out backlit groups")
        val failures =
            backlit.flatMap { group ->
                lightTrips(group).filterValues { !it }.keys.map { "${group.name}: $it passes every hold" }
            }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /** Per light-check mode the scan could run on [group], whether some hold trips it (uncalibrated always; calibrated on a match). */
    private fun lightTrips(group: Group): Map<String, Boolean> =
        buildMap {
            put("uncalibrated", group.lightChecks(false).any { !it.second.passed })
            if (QuickScanClassification.matchAll(group.samples, group.tagged, group.captureTorch) == CalibrationMatch.Match) {
                put("calibrated", group.lightChecks(true).any { !it.second.passed })
            }
        }

    @Test
    fun `the tuned gate turns no uncertain sticker into a confidently wrong one`() {
        // Story 10.21 AC4: on every tuning and held-out group, in every scenario, the per-colour gate against the 10.19 one.
        val groups = groups()
        val split = groups.filter { it.baseline && it.split != null }
        assumeTrue(split.isNotEmpty(), "No 10.22 corner groups")
        val promoted =
            split.flatMap { group ->
                Scenario.entries.flatMap { scenario ->
                    calibrations(group, groups, scenario).flatMap { saved ->
                        promotedWrong(group, saved).map { "${group.name} / ${scenario.title}: $it" }
                    }
                }
            }
        assertTrue(promoted.isEmpty(), promoted.joinToString("\n"))
    }

    /** Stickers of [group] the 10.19 gate leaves UNCERTAIN that the tuned gate reads CONFIDENT and wrong, under [saved]. */
    private fun promotedWrong(
        group: Group,
        saved: CalibrationCenters?,
    ): List<String> {
        val merged = group.samples
        val torch = group.captureTorch
        return group.records.flatMap { record ->
            record.labeledSamples.flatMap { labeled ->
                val strict = QuickScanClassification.decide(labeled.sample, merged, saved, torch, QuickScanClassification.STRICT_MARGIN)
                val tuned = QuickScanClassification.decide(labeled.sample, merged, saved, torch)
                labeled.truth.indices.mapNotNull { index ->
                    val before = strict.readings[index]
                    val after = tuned.readings[index]
                    val wrong = after?.confidence == StickerConfidence.CONFIDENT && after.color != labeled.truth[index]
                    if (before?.confidence != StickerConfidence.CONFIDENT && wrong) "${labeled.sample.face}${index + 1}" else null
                }
            }
        }
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
        return QuickScanClassification.report(merged, faces, saved, group.captureTorch).joinToString(" ") { face ->
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
        // The 10.19 bar never enforces on 10.22 groups; Story 10.21's safety half applies to the held-out one only.
        assertEquals(listOf(warm.name), heldOut(groups).map { it.name })
        val enforced = enforced(groups)
        assertTrue(enforced.isEmpty())
        assertTrue(violations(enforced).isEmpty())
        assertTrue(unsafe(enforced).isEmpty())
    }

    /** The label-set commit (the last commit that touched the fixtures folder); `unknown` when git cannot tell. */
    private fun labelCommit(root: File = FIXTURES): String =
        runCatching {
            val process =
                ProcessBuilder("git", "-C", root.path, "log", "-1", "--format=%H", "--", ".").redirectErrorStream(true).start()
            val out =
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            out.takeIf { process.waitFor() == 0 && it.matches(Regex("[0-9a-f]{40}")) }
        }.getOrNull() ?: "unknown"

    /** The SHA-256 of `label-exclusions.json`, or `absent` while Story 10.27 has not landed it. */
    private fun exclusionsHash(root: File = FIXTURES): String {
        val file = File(root, LABEL_EXCLUSIONS).takeIf { it.isFile } ?: return "absent"
        return java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * The CONFIDENT stickers of each hold of [group] under its scan-time (matching, torch-tagged) calibration, read with
     * the holds before it, as the confident-count wait counts them on the captured frame.
     */
    private fun confidentCounts(group: Group): List<Pair<Int, Int>> {
        var prior = emptyList<FaceSample>()
        val torch = group.captureTorch
        return group.records.map { record ->
            val hold = record.labeledSamples.map { it.sample }
            val merged = prior.filter { old -> hold.none { it.face == old.face } } + hold
            prior = merged
            record.hold to
                hold.sumOf { sample -> QuickScanClassification.confidentJudged(sample, merged, group.tagged, torch) ?: 0 }
        }
    }

    private fun percent(
        part: Int,
        whole: Int,
    ): String = if (whole == 0) "n/a" else "%.1f%%".format(java.util.Locale.ROOT, 100.0 * part / whole)

    /**
     * Story 10.21's held-out report: the coverage half of AC6 per device × light × torch against the bar (reported, not
     * enforced), the safety half per group and scenario, the backlit light checks, the calibration match, holds the
     * confident-count wait would delay, and the label-set commit and exclusions hash. Tuning and legacy groups are
     * reported for reference.
     */
    private fun heldOutReport(groups: List<Group>): String =
        buildString {
            val heldOut = heldOut(groups)
            appendLine("# Story 10.21 held-out replay (safety enforced, coverage reported)")
            appendLine()
            appendLine("- label-set commit: ${labelCommit()}")
            appendLine("- label-exclusions.json SHA-256: ${exclusionsHash()}")
            appendLine("- agreement margins: ${QuickScanClassification.AGREEMENT_MARGIN.entries.joinToString { "${it.key}=${it.value}" }}")
            appendLine("- confident-count wait N: ${QuickScanController.MIN_CONFIDENT}")
            appendLine(
                "- light cuts: uncalibrated hold ${SideLight.MAX_SIDE_DARKER_L} / clear ${SideLight.CLEAR_SIDE_DARKER_L}; " +
                    "calibrated hold ${SideLight.MAX_TOP_OVER_SIDES_RATIO} / clear ${SideLight.CLEAR_TOP_OVER_SIDES_RATIO}",
            )
            appendLine()
            appendLine("## Coverage per device × light × torch (bar: >= 90% side stickers correct, >= 3/4 side faces kept; not enforced)")
            appendLine()
            appendLine(
                "| condition | scenario | groups | side correct | side faces kept | blanks on kept faces | wrong / R<->O on kept | bar |",
            )
            appendLine("|---|---|---|---|---|---|---|---|")
            heldOut
                .filter { it.direction == LightDirection.FRONT }
                .groupBy { listOf(it.device, it.light, "torch=${it.torch ?: "n/a"}") }
                .toSortedMap(compareBy { it.joinToString("|") })
                .forEach { (key, members) ->
                    listOf(Scenario.NONE, Scenario.MATCHING).forEach { scenario ->
                        val total = Tally()
                        members.forEach { group -> calibrations(group, groups, scenario).forEach { total += tally(group, it) } }
                        val met =
                            total.correct >= MIN_SIDE_CORRECT * total.cells && total.sideFacesKept >= MIN_SIDE_FACES_KEPT * total.sideFaces
                        val names =
                            members.joinToString(", ") { group ->
                                group.name + (OFF_FACE[group.name]?.let { " (unscored: off-face)" } ?: "")
                            }
                        appendLine(
                            "| ${key.joinToString(" × ")} | ${scenario.title} | $names | ${total.correct}/${total.cells} " +
                                "(${percent(
                                    total.correct,
                                    total.cells,
                                )}) | ${total.sideFacesKept}/${total.sideFaces} | ${total.blanksKept} | " +
                                "${total.wrongKept} / ${total.redOrangeKept} | ${if (met) "met" else "below bar"} |",
                        )
                    }
                }
            appendLine()
            appendLine(
                "## Safety per held-out group and scenario (enforced: 0 wrong, 0 R<->O on kept faces; " +
                    "off-face exceptions reported: ${OFF_FACE_UNSAFE.entries.joinToString { (k, v) ->
                        "$k " + v.joinToString("/") { it.name.lowercase() }
                    }})",
            )
            appendLine()
            appendLine(
                "| group | scenario | calibrations | match | side correct/uncertain/wrong | faces kept | wrong on kept | R<->O on kept |",
            )
            appendLine("|---|---|---|---|---|---|---|---|")
            heldOut.forEach { group ->
                Scenario.entries.forEach { scenario ->
                    val calibrations = calibrations(group, groups, scenario)
                    val total = Tally().also { sum -> calibrations.forEach { sum += tally(group, it) } }
                    val matches =
                        calibrations.joinToString(",") { saved ->
                            QuickScanClassification.matchAll(group.samples, saved, group.captureTorch)?.label ?: "none"
                        }
                    appendLine(
                        "| ${group.name} | ${scenario.title} | ${calibrations.size} | ${matches.ifEmpty { "n/a" }} | " +
                            "${total.correct}/${total.uncertain}/${total.wrong} | ${total.sideFacesKept}/${total.sideFaces} | " +
                            "${total.wrongKept} | ${total.redOrangeKept} |",
                    )
                }
            }
            appendLine()
            appendLine("Calibrations that match, by the centre read alone -> with the centre closeness (PO 2026-11-24):")
            appendLine()
            heldOut.forEach { group ->
                Scenario.entries.drop(1).forEach { scenario ->
                    val calibrations = calibrations(group, groups, scenario)

                    fun count(closeness: Map<Color, Double>?) =
                        calibrations.count {
                            QuickScanClassification.matchAll(group.samples, it, group.captureTorch, closeness) == CalibrationMatch.Match
                        }
                    val after = count(QuickScanClassification.MAX_CENTRE_DELTA_E)
                    appendLine("- ${group.name} / ${scenario.title}: ${count(null)}/${calibrations.size} -> $after/${calibrations.size}")
                }
            }
            appendLine()
            appendLine("Confidently wrong stickers on kept faces (hold, face and cell, label->read), no-saved and matching scenarios:")
            appendLine()
            heldOut.forEach { group ->
                listOf(Scenario.NONE, Scenario.MATCHING).forEach { scenario ->
                    val wrong = calibrations(group, groups, scenario).flatMap { wrongKept(group, it) }
                    if (wrong.isNotEmpty()) appendLine("- ${group.name} / ${scenario.title}: ${wrong.joinToString()}")
                }
            }
            appendLine()
            appendLine("## Backlit held-out groups (enforced: the light check trips)")
            appendLine()
            heldOut.filter { it.direction == LightDirection.BACK }.forEach { group ->
                val holds =
                    group.lightModes().flatten().joinToString("; ") { (hold, verdict) ->
                        "hold $hold ${verdict.mode.name.lowercase()} ${format(
                            verdict.score,
                            verdict.mode,
                        )} ${if (verdict.passed) "pass" else "trips"}"
                    }
                appendLine("- ${group.name}: $holds; trips by scan mode: ${lightTrips(group)}")
            }
            appendLine()
            appendLine("## Confident-count wait on the captured frames (N = ${QuickScanController.MIN_CONFIDENT}, scan-time calibration)")
            appendLine()
            groups.filter { it.baseline && it.split != null }.forEach { group ->
                val counts = confidentCounts(group)
                val delayed = counts.filter { it.second < QuickScanController.MIN_CONFIDENT }.map { it.first }
                appendLine(
                    "- ${group.name} (${group.split?.name?.lowercase()}): " + counts.joinToString { "hold ${it.first} ${it.second}/27" } +
                        "; delayed by the wait: ${delayed.ifEmpty { listOf("none") }.joinToString()}",
                )
            }
            appendLine()
            appendLine("## Unscored off-face groups (PO 2026-11-23; routed to Story 10.27, #315, #338 and the BETA call)")
            appendLine()
            OFF_FACE.forEach { (name, sample) -> appendLine("- $name: $sample") }
            appendLine()
            appendLine("## Reference only: tuning and legacy groups (not enforced)")
            appendLine()
            appendLine("| group | scenario | side correct/uncertain/wrong | faces kept | wrong / R<->O on kept |")
            appendLine("|---|---|---|---|---|")
            groups.filter { !it.heldOut }.forEach { group ->
                Scenario.entries.forEach { scenario ->
                    val total = Tally().also { sum -> calibrations(group, groups, scenario).forEach { sum += tally(group, it) } }
                    appendLine(
                        "| ${group.name} (${group.split?.name?.lowercase() ?: "legacy"}) | ${scenario.title} | " +
                            "${total.correct}/${total.uncertain}/${total.wrong} | ${total.sideFacesKept}/${total.sideFaces} | " +
                            "${total.wrongKept} / ${total.redOrangeKept} |",
                    )
                }
            }
        }

    /** Each confidently wrong judged sticker on a kept face of [group] under [saved], as `<face><cell> <truth>-><read>`. */
    private fun wrongKept(
        group: Group,
        saved: CalibrationCenters?,
    ): List<String> {
        val merged = group.samples
        val torch = group.captureTorch
        val toGrid = QuickScanClassification.needGrid(merged, merged.map { it.face }, saved, torch).toSet()
        return group.records.flatMap { record ->
            record.labeledSamples.filter { it.sample.face !in toGrid }.flatMap { labeled ->
                val readings = QuickScanClassification.read(labeled.sample, merged, saved, torch)
                labeled.truth.indices.mapNotNull { index ->
                    val reading = readings[index]
                    val judged = index != FaceSample.CENTER_INDEX || labeled.sample.centerPlain != false
                    if (judged && reading?.confidence == StickerConfidence.CONFIDENT && reading.color != labeled.truth[index]) {
                        "h${record.hold} ${labeled.sample.face}${index + 1} ${labeled.truth[index]}->${reading.color}"
                    } else {
                        null
                    }
                }
            }
        }
    }

    @Test
    fun `the held-out coverage is reported per device, light and torch against the bar`() {
        // Story 10.21 AC6 coverage half (PO 2026-11-23 (a)): reported, not enforced.
        val groups = groups()
        assumeTrue(heldOut(groups).isNotEmpty(), "No held-out 10.22 corner groups")
        val text = heldOutReport(groups)
        println(text)
        HELD_OUT_REPORT.parentFile.mkdirs()
        HELD_OUT_REPORT.writeText(text)
        assertTrue("label-set commit: " in text && "label-exclusions.json SHA-256: " in text, text)
        OFF_FACE.keys.forEach { assertTrue(it in text, it) }
        heldOut(groups).filter { it.direction == LightDirection.FRONT }.forEach { assertTrue(it.name in text, it.name) }
    }

    private companion object {
        val FIXTURES = File("../../Fixtures/corner")
        val HELD_OUT_REPORT = File("build/reports/quick-scan/replay-10-21.md")
        const val LABEL_EXCLUSIONS = "label-exclusions.json"

        /**
         * Held-out groups the PO left unscored (2026-11-23): a face sampled off the face. Safety is still enforced on them;
         * the standing rule adds any other group with the same defect here.
         */
        val OFF_FACE =
            mapOf(
                "sm-s721u1_stla_bar-warm" to
                    "hold 2 L face sampled off the face: its orange centre reads blue Lab(43, 9, -38), like the B centre Lab(42, 6, -33)",
                "lenovo-tb128xu_stla_bar-cool" to
                    "hold 2 L face sampled off the face: cells 1, 2, 3, 6 and 9 read Yellow, Orange, Yellow, Blue and White in both " +
                    "reads against labels White, Yellow, Blue, White and Orange",
                "lenovo-tb128xu_stla_bar-warm" to
                    "hold 2 L face sampled off the face: cells 1, 2, 4, 8 and 9 read Yellow, Blue, Orange, Orange and White against " +
                    "labels White, Yellow, Yellow, Blue and Orange",
            )

        /**
         * The scenarios in which an off-face group keeps a confidently wrong sticker with the fitted gate: the bad hold-2
         * `L` sample gives the self-calibration a wrong orange centre, so orange stickers on other faces (F, R, D, B) read
         * confidently red in both reads, and a matching calibration reads the off-face `L` sample itself as the face. No
         * gate the ACs allow avoids it; reported, not enforced (PO 2026-11-23).
         */
        val OFF_FACE_UNSAFE =
            mapOf(
                "lenovo-tb128xu_stla_bar-cool" to Scenario.entries.toSet(),
                "sm-s721u1_stla_bar-warm" to Scenario.entries.toSet(),
            )
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
