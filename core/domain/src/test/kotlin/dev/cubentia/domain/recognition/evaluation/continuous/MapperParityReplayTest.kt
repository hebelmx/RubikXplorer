package dev.cubentia.domain.recognition.evaluation.continuous

import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionFormat
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionJson
import dev.cubentia.domain.recognition.continuous.session.SessionOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * GitHub #411 (part a): golden parity between `pc_spike/mapper.py` and
 * [dev.cubentia.domain.recognition.continuous.ContinuousFaceletMapper].
 *
 * Each session under `Fixtures/continuous/mapper-parity/` was exported by `pc_spike/export_parity_sessions.py`: its
 * `frames.jsonl` holds the cell samples mapper.py itself computed and its `snapshots.jsonl` holds mapper.py's snapshots
 * (Python predictions). Replaying it through a fresh Kotlin mapper with [ContinuousSessionReplay.replay] therefore yields
 * the parity differences directly. The set of (session, frame, field) differences on [COMPARED_FIELDS] must equal, exactly,
 * the list in `expected-differences.json`, and every listed difference must name a catalogued deviation (whose ids the
 * mapper's KDoc names). Not compared: `kind` and `state` (mapper.py has no validated complete state), `trackedQuads`,
 * the counts and `pendingFragmentFaces`.
 */
class MapperParityReplayTest {
    @TempDir lateinit var temp: File

    private val continuousRoot: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "Fixtures/continuous") }
            .first { it.isDirectory }

    private val parityRoot = File(continuousRoot, PARITY_DIR)

    /** One expected or observed difference; [frame] is -1 for the session-level `outcome` and `snapshotCount`. */
    data class Key(
        val session: String,
        val frame: Int,
        val field: String,
    ) {
        override fun toString(): String = "$session frame $frame $field"
    }

    data class Expected(
        val comparedFields: Set<String>,
        val deviations: Map<String, Boolean>,
        val differences: Map<Key, String>,
        val problems: List<String>,
    )

    private fun readExpected(file: File): Expected {
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val problems = mutableListOf<String>()
        val compared =
            root
                .getValue("comparedFields")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .toSet()
        val deviations = LinkedHashMap<String, Boolean>()
        root.getValue("deviations").jsonArray.forEach { element ->
            val d = element.jsonObject
            val id = d.getValue("id").jsonPrimitive.content
            if (id in deviations) problems += "deviation $id is catalogued twice"
            if (d["summary"]?.jsonPrimitive?.content.isNullOrBlank()) problems += "deviation $id has no summary"
            deviations[id] = d.getValue("deliberate").jsonPrimitive.boolean
        }
        val differences = LinkedHashMap<Key, String>()
        root.getValue("differences").jsonArray.forEach { element ->
            val d: JsonObject = element.jsonObject
            val session = d.getValue("session").jsonPrimitive.content
            val field = d.getValue("field").jsonPrimitive.content
            val deviation = d.getValue("deviation").jsonPrimitive.content
            if (deviation !in deviations) problems += "$session $field cites unknown deviation $deviation"
            if (d["reason"]?.jsonPrimitive?.content.isNullOrBlank()) problems += "$session $field has no reason"
            if (field !in compared) problems += "$session $field is not a compared field"
            d.getValue("frames").jsonArray.forEach { range ->
                val bounds = range.jsonArray.map { it.jsonPrimitive.int }
                if (bounds.size != 2) {
                    problems += "$session $field has a range that is not [from, to]: $bounds"
                    return@forEach
                }
                val (from, to) = bounds
                if (from > to) problems += "$session $field has an empty range [$from, $to]"
                for (frame in from..to) {
                    val key = Key(session, frame, field)
                    if (differences.put(key, deviation) != null) problems += "$key is listed twice"
                }
            }
        }
        return Expected(compared, deviations, differences, problems)
    }

    /** The compared-field differences of every parity session under [root], with their recorded and replayed values. */
    private fun observed(root: File): Map<Key, ContinuousSessionReplay.FrameDiff> =
        ContinuousSessionFormat
            .sessions(root)
            .flatMap { dir ->
                ContinuousSessionReplay
                    .replay(dir)
                    .diffs
                    .filter { it.field in COMPARED_FIELDS }
                    .map { Key(dir.name, it.frameIndex, it.field) to it }
            }.toMap()

    /** Every way [observed] differs from [expected]: unlisted entries (new drift) and stale ones (no longer occurring). */
    private fun mismatches(
        observed: Map<Key, ContinuousSessionReplay.FrameDiff>,
        expected: Expected,
    ): List<String> {
        val unlisted =
            (observed.keys - expected.differences.keys).map { key ->
                val d = observed.getValue(key)
                "unlisted: $key recorded (mapper.py)=${d.recorded} replayed (Kotlin)=${d.replayed}"
            }
        val stale =
            (expected.differences.keys - observed.keys).map { "stale: $it (${expected.differences.getValue(it)}) no longer occurs" }
        return unlisted + stale
    }

    @Test
    fun `the parity sessions differ from mapper py exactly as the expected-difference list says`() {
        val expected = readExpected(File(parityRoot, EXPECTED_FILE))
        assertEquals(emptyList<String>(), expected.problems)
        assertEquals(COMPARED_FIELDS, expected.comparedFields)
        val problems = mismatches(observed(parityRoot), expected)
        assertTrue(problems.isEmpty(), "Mapper parity drift (${problems.size}):\n" + problems.sorted().joinToString("\n"))
    }

    @Test
    fun `the catalogue names every known deviation and every listed difference cites one`() {
        val expected = readExpected(File(parityRoot, EXPECTED_FILE))
        assertTrue(expected.deviations.keys.containsAll(REQUIRED_DEVIATIONS), "missing: ${REQUIRED_DEVIATIONS - expected.deviations.keys}")
        assertTrue(expected.differences.values.all { it in expected.deviations }, expected.problems.joinToString())
        // Every catalogued id is named in the mapper's KDoc.
        val mapperSource =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, MAPPER_SOURCE) }
                .first { it.isFile }
                .readText()
        val unnamed = expected.deviations.keys.filter { "**$it**" !in mapperSource }
        assertEquals(emptyList<String>(), unnamed, "catalogue ids missing from the ContinuousFaceletMapper KDoc")
    }

    @Test
    fun `the parity sessions are three decodable synthetic sessions`() {
        val sessions = ContinuousSessionFormat.sessions(parityRoot)
        assertEquals(3, sessions.size, sessions.map { it.name }.toString())
        sessions.forEach { dir ->
            assertTrue(dir.name.matches(Regex("pyspike-\\d+-[a-z0-9-]+-v1")), dir.name)
            val manifest = ContinuousSessionJson.readManifest(dir)
            assertTrue(manifest.synthetic, dir.name)
            assertEquals("synthetic (pc_spike synth_video.py)", manifest.device.model)
            val frames = ContinuousSessionJson.readFrames(dir)
            assertEquals(manifest.frameCount, frames.size)
            assertEquals(frames.indices.toList(), frames.map { it.frameIndex })
            assertTrue(frames.none { it.skipped })
            assertTrue(frames.flatMap { it.observations }.all { it.toDomain().cellRgbs.size == 9 })
            assertEquals(frames.indices.toList(), ContinuousSessionJson.readSnapshots(dir).map { it.frameIndex })
            assertTrue(ContinuousSessionJson.readLabels(dir) != null, dir.name)
            val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            assertTrue(bytes <= SESSION_MAX_BYTES, "${dir.name}: $bytes B")
            if (manifest.outcome == SessionOutcome.COMPLETE) {
                val report = ContinuousSessionReplay.replay(dir)
                assertTrue(report.labelMismatches.isEmpty(), report.labelMismatches.toString())
                assertEquals(0, report.uncommittedLabelled, dir.name)
            }
        }
    }

    @Test
    fun `the nested parity folder is not a session of the golden replay`() {
        val names = ContinuousSessionReplay.sessionsUnder(continuousRoot).map { it.name }
        assertFalse(PARITY_DIR in names, names.toString())
        assertTrue(names.none { it.startsWith("pyspike-") }, names.toString())
    }

    @Test
    fun `a changed python snapshot fails naming its frame`() {
        val copy = File(temp, PARITY_DIR).also { parityRoot.copyRecursively(it) }
        val expected = readExpected(File(copy, EXPECTED_FILE))
        val dir = ContinuousSessionFormat.sessions(copy).first()
        val snapshots = File(dir, ContinuousSessionFormat.SNAPSHOTS)
        val lines = snapshots.readLines().toMutableList()
        // A frame with no listed compared-field difference, so the edit adds one diff rather than removing one.
        val index =
            lines.indices.first { i ->
                val frame = ContinuousSessionJson.decodeSnapshot(lines[i]).frameIndex
                i > 10 && COMPARED_FIELDS.none { Key(dir.name, frame, it) in expected.differences }
            }
        val recorded = ContinuousSessionJson.decodeSnapshot(lines[index])
        lines[index] = ContinuousSessionJson.encodeSnapshot(recorded.copy(globalAnchored = !recorded.globalAnchored))
        snapshots.writeText(lines.joinToString("\n"))
        val problems = mismatches(observed(copy), expected)
        assertEquals(1, problems.size, problems.toString())
        assertTrue("${dir.name} frame ${recorded.frameIndex} globalAnchored" in problems.single(), problems.single())
        assertTrue("recorded (mapper.py)=${!recorded.globalAnchored}" in problems.single(), problems.single())
    }

    @Test
    fun `a stale entry and an unknown deviation id are reported`() {
        val file = File(temp, EXPECTED_FILE)
        file.writeText(
            """
            {"comparedFields": ["net"], "deviations": [{"id": "D1", "summary": "s", "deliberate": true}],
             "differences": [{"session": "s", "field": "net", "frames": [[3, 4]], "deviation": "X9", "reason": "r"}]}
            """.trimIndent(),
        )
        val expected = readExpected(file)
        assertTrue(expected.problems.any { "unknown deviation X9" in it }, expected.problems.toString())
        val problems = mismatches(emptyMap(), expected)
        assertEquals(listOf("stale: s frame 3 net (X9) no longer occurs", "stale: s frame 4 net (X9) no longer occurs"), problems)
    }

    companion object {
        const val PARITY_DIR = "mapper-parity"
        const val EXPECTED_FILE = "expected-differences.json"
        val COMPARED_FIELDS = setOf("net", "globalAnchored", "twistFrame", "conflictedSlots", "cue", "outcome", "snapshotCount")
        val REQUIRED_DEVIATIONS = setOf("D1", "D2", "D3", "D4", "I401", "I402")
        const val SESSION_MAX_BYTES = 15_000_000L
        const val MAPPER_SOURCE =
            "core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt"
    }
}
