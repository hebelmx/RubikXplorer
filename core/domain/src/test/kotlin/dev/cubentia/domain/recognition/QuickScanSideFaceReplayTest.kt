package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
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
    ) {
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

        /** The darker side face's L* below the top face, per hold. */
        val light10: List<Pair<Int, SideLight.Verdict>>
            get() = records.map { it.hold to SideLight.checkSamples(it.labeledSamples.map { labeled -> labeled.sample }) }
    }

    private fun groups(): List<Group> {
        val root = File("../../Fixtures/corner")
        assumeTrue(root.isDirectory, "Corner fixtures are recorded in the device session")
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
                Group(
                    name = dir.relativeTo(root).path,
                    device = first.device.model,
                    cube = first.cube,
                    light = first.light,
                    direction = if (manifest != null) manifest.lightDirection else LEGACY_DIRECTION[first.light],
                    scored = scored,
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
                "Light check (SideLight, cut ${SideLight.MAX_SIDE_DARKER_L} L*): darker side face's level below the top face per hold",
            )
            appendLine("| group | direction | scored | hold 1 | hold 2 |")
            appendLine("|---|---|---|---|---|")
            groups.forEach { group ->
                val holds =
                    group.light10.joinToString(" | ") { (_, verdict) ->
                        val diff = verdict.darkerSideDifference?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: "n/a"
                        "$diff ${if (verdict.passed) "pass" else "FAIL"}"
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

    /** Required-but-missing scored groups (reported, never a failure). */
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
                if (group.light10.all { it.second.passed }) add("${group.name}: a scored backlit group passes SideLight on every hold")
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
    fun `the light check trips every legacy backlit group and passes every legacy front-lit hold`() {
        val legacy = groups().filter { !it.scored }
        assumeTrue(legacy.isNotEmpty(), "No pre-10.19 corner groups")
        legacy.forEach { group ->
            when (group.direction) {
                LightDirection.BACK -> {
                    assertTrue(
                        group.light10.any { !it.second.passed },
                        "${group.name} is backlit and must trip SideLight",
                    )
                }

                LightDirection.FRONT -> {
                    assertTrue(
                        group.light10.all { it.second.passed },
                        "${group.name} is front-lit and must pass SideLight",
                    )
                }

                null -> {
                    Unit
                }
            }
        }
    }

    @Test
    fun `the side-face bar holds on every scored front-lit device and light`() {
        val groups = groups()
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
    }

    private companion object {
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
