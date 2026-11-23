package dev.cubentia.domain.recognition

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Story 10.20 AC2: the group index `Fixtures/corner/groups.json` names every 10.22-pipeline corner group once with its
 * held-out or tuning split, and the splits cover what Stories 10.20 and 10.21 measure on. Until the device session
 * records the groups and writes the index, the check passes and says the index is absent.
 */
class CornerGroupIndexTest {
    /** One indexed group as the coverage check sees it. */
    private data class IndexedGroup(
        val name: String,
        val device: String,
        val direction: LightDirection,
        val light: String,
        val torch: Boolean?,
        val split: CornerSplit,
        val positives: Int,
        val negatives: Int,
    )

    /** Reads the index under [root] and its groups; problems with the index itself (a missing or non-10.22 folder, an unlisted 10.22 group) go to [problems]. */
    private fun load(
        root: File,
        problems: MutableList<String>,
    ): List<IndexedGroup>? {
        val file = File(root, CornerFixturePaths.GROUP_INDEX)
        if (!file.isFile) return null
        val index = CornerFixtureJson.decodeIndex(file.readText())
        val recorded =
            root
                .listFiles()
                .orEmpty()
                .filter { dir -> manifest(dir)?.pipeline == CornerFixturePaths.PIPELINE_10_22 }
                .map { it.name }
                .toSet()
        (recorded - index.groups.map { it.group }.toSet()).sorted().forEach { problems += "$it: a 10.22 group the index does not list" }
        return index.groups.mapNotNull { entry ->
            val dir = File(root, entry.group)
            val manifest = manifest(dir)
            when {
                !dir.isDirectory -> {
                    problems += "${entry.group}: listed in the index but no such folder"
                    null
                }

                manifest?.pipeline != CornerFixturePaths.PIPELINE_10_22 -> {
                    problems += "${entry.group}: listed in the index but has no 10.22 group.json"
                    null
                }

                else -> {
                    val labels =
                        File(dir, CornerFixturePaths.KEYPOINTS_DIR)
                            .listFiles()
                            .orEmpty()
                            .filter { it.extension == "json" }
                            .map { CornerFixtureJson.decodeKeypoints(it.readText()).label }
                    IndexedGroup(
                        name = entry.group,
                        device = manifest.device.model,
                        direction = manifest.lightDirection,
                        light = manifest.light,
                        torch = manifest.torch,
                        split = entry.split,
                        positives = labels.count { it == CornerLabel.POSITIVE },
                        negatives = labels.count { it == CornerLabel.NEGATIVE },
                    )
                }
            }
        }
    }

    private fun manifest(dir: File): CornerGroupManifest? =
        File(dir, CornerFixturePaths.MANIFEST).takeIf { it.isFile }?.let { CornerFixtureJson.decodeManifest(it.readText()) }

    /**
     * AC2 coverage of [groups]: per required device, held-out keypoint fixtures (at least [MIN_HELDOUT_POSITIVES]
     * positives and [MIN_HELDOUT_NEGATIVES] negatives) and a backlit group; per front-lit (device, light name, torch)
     * condition at least one held-out and one tuning group.
     */
    private fun coverage(groups: List<IndexedGroup>): List<String> =
        buildList {
            REQUIRED_DEVICES.forEach { (name, matches) ->
                val mine = groups.filter { matches(it.device) }
                val heldout = mine.filter { it.split == CornerSplit.HELDOUT }
                val positives = heldout.sumOf { it.positives }
                val negatives = heldout.sumOf { it.negatives }
                if (positives <
                    MIN_HELDOUT_POSITIVES
                ) {
                    add("$name: $positives held-out positive keypoint fixtures (need >= $MIN_HELDOUT_POSITIVES)")
                }
                if (negatives <
                    MIN_HELDOUT_NEGATIVES
                ) {
                    add("$name: $negatives held-out negative keypoint fixtures (need >= $MIN_HELDOUT_NEGATIVES)")
                }
                if (mine.none { it.direction == LightDirection.BACK }) add("$name: no backlit group")
            }
            groups
                .filter { it.direction == LightDirection.FRONT }
                .groupBy { Triple(it.device, it.light, it.torch) }
                .forEach { (condition, members) ->
                    val label = "${condition.first} × ${condition.second} × torch=${condition.third ?: "n/a"}"
                    if (members.none { it.split == CornerSplit.HELDOUT }) add("$label: no held-out group")
                    if (members.none { it.split == CornerSplit.TUNING }) add("$label: no tuning group")
                }
        }

    /** The whole check for [root]: null when there is no index, else every problem (empty when it holds). */
    private fun check(root: File): List<String>? {
        val problems = mutableListOf<String>()
        val groups = load(root, problems) ?: return null
        return problems + coverage(groups)
    }

    @Test
    fun `the recorded group index lists every 10-22 group once and covers AC2`() {
        val problems = check(FIXTURES)
        if (problems == null) {
            println("CornerGroupIndexTest: no group index (${File(FIXTURES, CornerFixturePaths.GROUP_INDEX).path}); nothing to check yet")
            return
        }
        println("CornerGroupIndexTest: group index found; problems: ${problems.ifEmpty { listOf("none") }.joinToString("; ")}")
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    private val lenovo = DeviceInfo("Lenovo TB128XU", "LENOVO", "13", "b", 33)
    private val s24 = DeviceInfo("SM-S721U1", "samsung", "15", "b", 35)

    private fun group(
        name: String,
        device: String,
        direction: LightDirection = LightDirection.FRONT,
        light: String = "warm",
        torch: Boolean? = null,
        split: CornerSplit = CornerSplit.HELDOUT,
        positives: Int = 0,
        negatives: Int = 0,
    ) = IndexedGroup(name, device, direction, light, torch, split, positives, negatives)

    private fun complete(): List<IndexedGroup> =
        listOf(
            group("l-warm-h", lenovo.model, positives = 20, negatives = 10),
            group("l-warm-t", lenovo.model, split = CornerSplit.TUNING),
            group("l-back", lenovo.model, LightDirection.BACK, light = "window"),
            group("s-warm-on-h", s24.model, torch = true, positives = 12, negatives = 4),
            group("s-warm-on-t", s24.model, torch = true, split = CornerSplit.TUNING),
            group("s-warm-off-h", s24.model, torch = false, positives = 8, negatives = 6),
            group("s-warm-off-t", s24.model, torch = false, split = CornerSplit.TUNING),
            group("s-back", s24.model, LightDirection.BACK, light = "window", torch = true, split = CornerSplit.TUNING),
        )

    @Test
    fun `coverage passes a complete split and names each gap`() {
        assertEquals(emptyList<String>(), coverage(complete()))
        val gaps =
            coverage(
                complete().filterNot { it.name in setOf("l-back", "s-warm-off-t") }.map {
                    if (it.name == "l-warm-h") it.copy(positives = 19, negatives = 9) else it
                },
            )
        assertEquals(
            listOf(
                "Lenovo TB128XU: 19 held-out positive keypoint fixtures (need >= 20)",
                "Lenovo TB128XU: 9 held-out negative keypoint fixtures (need >= 10)",
                "Lenovo TB128XU: no backlit group",
                "SM-S721U1 × warm × torch=false: no tuning group",
            ),
            gaps,
        )
        // Tuning-split keypoint fixtures never count toward the held-out minimum; a lone tuning condition lacks a held-out group.
        val tuningOnly = complete().map { if (it.name == "l-warm-h") it.copy(split = CornerSplit.TUNING) else it }
        assertTrue("Lenovo TB128XU: 0 held-out positive keypoint fixtures (need >= 20)" in coverage(tuningOnly))
        assertTrue("Lenovo TB128XU × warm × torch=n/a: no held-out group" in coverage(tuningOnly))
    }

    private fun writeGroup(
        root: File,
        name: String,
        device: DeviceInfo,
        pipeline: String = CornerFixturePaths.PIPELINE_10_22,
        positives: Int = 0,
        negatives: Int = 0,
    ) {
        val dir = File(root, name).also { it.mkdirs() }
        File(dir, CornerFixturePaths.MANIFEST)
            .writeText(CornerFixtureJson.encodeManifest(CornerGroupManifest(pipeline, LightDirection.FRONT, device, "stla", "warm")))
        val keypoints = File(dir, CornerFixturePaths.KEYPOINTS_DIR).also { it.mkdirs() }
        (1..positives + negatives).forEach { i ->
            val positive = i <= positives
            val fixture =
                CornerKeypointFixture(
                    frame = CornerFixturePaths.keypointFrameName(i),
                    label = if (positive) CornerLabel.POSITIVE else CornerLabel.NEGATIVE,
                    keypoints = if (positive) List(7) { listOf(0.5, 0.1 * (it + 1)) } else null,
                    device = device,
                    cube = "stla",
                    light = "warm",
                    lightDirection = LightDirection.FRONT,
                    capturedAt = "2026-11-23T10:00:00Z",
                )
            File(keypoints, CornerFixturePaths.keypointFileName(i)).writeText(CornerFixtureJson.encodeKeypoints(fixture))
        }
    }

    @Test
    fun `a temp index is read from disk and its folder problems are named`(
        @TempDir root: File,
    ) {
        assertEquals(null, check(root))
        writeGroup(root, "lenovo_warm_h", lenovo, positives = 2, negatives = 1)
        writeGroup(root, "lenovo_warm_t", lenovo)
        writeGroup(root, "lenovo_unlisted", lenovo)
        writeGroup(root, "lenovo_old", lenovo, pipeline = CornerFixturePaths.PIPELINE_10_19)
        val index =
            CornerGroupIndex(
                groups =
                    listOf(
                        CornerGroupIndexEntry("lenovo_warm_h", CornerSplit.HELDOUT),
                        CornerGroupIndexEntry("lenovo_warm_t", CornerSplit.TUNING),
                        CornerGroupIndexEntry("lenovo_old", CornerSplit.TUNING),
                        CornerGroupIndexEntry("lenovo_gone", CornerSplit.HELDOUT),
                    ),
            )
        File(root, CornerFixturePaths.GROUP_INDEX).writeText(CornerFixtureJson.encodeIndex(index))
        val problems = mutableListOf<String>()
        val groups = checkNotNull(load(root, problems))
        assertEquals(
            listOf(
                "lenovo_unlisted: a 10.22 group the index does not list",
                "lenovo_old: listed in the index but has no 10.22 group.json",
                "lenovo_gone: listed in the index but no such folder",
            ),
            problems,
        )
        assertEquals(listOf("lenovo_warm_h", "lenovo_warm_t"), groups.map { it.name })
        assertEquals(2 to 1, groups.first().positives to groups.first().negatives)
        val all = checkNotNull(check(root))
        assertTrue(all.containsAll(problems) && "Lenovo TB128XU: no backlit group" in all, all.joinToString("\n"))
    }

    private companion object {
        val FIXTURES = File("../../Fixtures/corner")
        const val MIN_HELDOUT_POSITIVES = 20
        const val MIN_HELDOUT_NEGATIVES = 10
        val REQUIRED_DEVICES: List<Pair<String, (String) -> Boolean>> =
            listOf(
                "Lenovo TB128XU" to { model -> model.contains("TB128XU", ignoreCase = true) },
                "Galaxy S24 FE" to
                    { model -> model.contains("S24 FE", ignoreCase = true) || model.startsWith("SM-S721", ignoreCase = true) },
            )
    }
}
