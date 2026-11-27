package dev.cubentia.domain.recognition.evaluation.continuous

import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionFormat
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionJson
import dev.cubentia.domain.recognition.continuous.session.SessionOutcome
import dev.cubentia.domain.recognition.continuous.session.SessionSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * GitHub #411: golden parity between `pc_spike/mapper.py` and [dev.cubentia.domain.recognition.continuous.ContinuousFaceletMapper].
 *
 * Each session under `Fixtures/continuous/mapper-parity/` was exported by `pc_spike/export_parity_sessions.py` (part a, three
 * run_spike scenarios) or `tools/mapper-parity/export_deviation_sessions.py` (part b, one scenario per deviation): its
 * `frames.jsonl` holds the cell samples mapper.py itself computed and its `snapshots.jsonl` holds mapper.py's snapshots
 * (Python predictions). Replaying it through a fresh Kotlin mapper with [ContinuousSessionReplay.replay] therefore yields
 * the parity differences directly. The set of (session, frame, field) differences on [COMPARED_FIELDS] must equal, exactly,
 * the list in `expected-differences.json`, and every listed difference must name a catalogued deviation (whose ids the
 * mapper's KDoc names). Not compared: `kind` and `state` (mapper.py has no validated complete state), `trackedQuads`,
 * the counts and `pendingFragmentFaces`.
 *
 * Part (b): each of [VERDICT_DEVIATIONS] carries a `verdict` (`keep` | `reverted`), a `reason` and `evidence` sessions with
 * their true outcome. A kept deviation must cite at least one listed difference in its evidence sessions (it bites there),
 * and on every evidence session Kotlin must be not worse than mapper.py against the `labels.json` truth: no more wrong
 * committed stickers in the last snapshot, and the true outcome whenever mapper.py reaches it.
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

    /** One evidence session of a verdict and the outcome its scenario truly has (FAILED for a real layer twist). */
    data class Evidence(
        val session: String,
        val trueOutcome: SessionOutcome,
    )

    /** A part (b) verdict: [verdict] is `keep` or `reverted`. */
    data class Verdict(
        val verdict: String,
        val reason: String,
        val evidence: List<Evidence>,
    )

    data class Expected(
        val comparedFields: Set<String>,
        val deviations: Map<String, Boolean>,
        val differences: Map<Key, String>,
        val problems: List<String>,
        val verdicts: Map<String, Verdict> = emptyMap(),
    )

    /** One side's last-snapshot score against the truth labels. */
    data class Score(
        val wrong: Int,
        val outcome: SessionOutcome,
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
        val verdicts = LinkedHashMap<String, Verdict>()
        root.getValue("deviations").jsonArray.forEach { element ->
            val d = element.jsonObject
            val id = d.getValue("id").jsonPrimitive.content
            if (id in deviations) problems += "deviation $id is catalogued twice"
            if (d["summary"]?.jsonPrimitive?.content.isNullOrBlank()) problems += "deviation $id has no summary"
            deviations[id] = d.getValue("deliberate").jsonPrimitive.boolean
            readVerdict(id, d, problems)?.let { verdicts[id] = it }
        }
        (VERDICT_DEVIATIONS - verdicts.keys).forEach { problems += "deviation $it has no complete verdict" }
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
        return Expected(compared, deviations, differences, problems, verdicts)
    }

    /** The verdict of [d]; null when it has none, or (with a problem) when `verdict`, `reason` or `evidence` is invalid. */
    private fun readVerdict(
        id: String,
        d: JsonObject,
        problems: MutableList<String>,
    ): Verdict? {
        val verdict = d["verdict"]?.jsonPrimitive?.content
        val reason = d["reason"]?.jsonPrimitive?.content
        val evidence = d["evidence"]?.jsonArray
        if (verdict == null && reason == null && evidence == null) return null
        val issues = mutableListOf<String>()
        if (verdict !in VERDICTS) issues += "verdict $verdict is not one of $VERDICTS"
        if (reason.isNullOrBlank()) issues += "no reason"
        if (evidence.isNullOrEmpty()) issues += "no evidence"
        val sessions =
            evidence.orEmpty().mapNotNull { element ->
                val e = element.jsonObject
                val session = e["session"]?.jsonPrimitive?.content
                val trueOutcome = TRUE_OUTCOMES.firstOrNull { it.name == e["trueOutcome"]?.jsonPrimitive?.content }
                if (session.isNullOrBlank() || trueOutcome == null) {
                    issues += "evidence $e needs a session and a trueOutcome in $TRUE_OUTCOMES"
                    null
                } else {
                    Evidence(session, trueOutcome)
                }
            }
        if (issues.isNotEmpty() || verdict == null || reason == null) {
            problems += "deviation $id verdict: " + issues.joinToString("; ")
            return null
        }
        return Verdict(verdict, reason, sessions)
    }

    /** Wrong committed stickers of [net] (54 URFDLB characters, `.` uncommitted) against the truth labels of [dir]. */
    private fun wrongStickers(
        dir: File,
        net: String,
    ): Int {
        val labels = requireNotNull(ContinuousSessionJson.readLabels(dir)) { "${dir.name} has no labels.json" }
        val truth = SessionSnapshot.netString(labels.stickers.map { it.color })
        return net.indices.count { net[it] != '.' && truth[it] != '.' && net[it] != truth[it] }
    }

    /** Kotlin's and mapper.py's last-snapshot scores on the session [dir]. */
    private fun scores(dir: File): Pair<Score, Score> {
        val report = ContinuousSessionReplay.replay(dir)
        val kotlin = Score(wrongStickers(dir, report.replayed.last().net), report.outcome)
        val python = Score(wrongStickers(dir, ContinuousSessionJson.readSnapshots(dir).last().net), report.manifest.outcome)
        return kotlin to python
    }

    /**
     * The part (b) decision rule: Kotlin is not worse when it commits no more wrong stickers than mapper.py and reaches
     * [trueOutcome] whenever mapper.py does. Null when not worse, else the failure message.
     */
    private fun notWorse(
        id: String,
        session: String,
        trueOutcome: SessionOutcome,
        kotlin: Score,
        python: Score,
    ): String? {
        val pythonCorrect = python.outcome == trueOutcome
        val kotlinCorrect = kotlin.outcome == trueOutcome
        if (kotlin.wrong <= python.wrong && (!pythonCorrect || kotlinCorrect)) return null
        return "$id is worse than mapper.py on $session (true outcome $trueOutcome): Kotlin wrong ${kotlin.wrong} / " +
            "${kotlin.outcome}, mapper.py wrong ${python.wrong} / ${python.outcome}"
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
    fun `the parity sessions are nine decodable synthetic sessions`() {
        val sessions = ContinuousSessionFormat.sessions(parityRoot)
        assertEquals(SESSION_COUNT, sessions.size, sessions.map { it.name }.toString())
        sessions.forEach { dir ->
            assertTrue(dir.name.matches(Regex("pyspike-[a-z0-9]+-[a-z0-9-]+-v1")), dir.name)
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
    fun `every named deviation has a verdict with a reason and evidence sessions that exist`() {
        val expected = readExpected(File(parityRoot, EXPECTED_FILE))
        assertEquals(emptyList<String>(), expected.problems)
        val sessions = ContinuousSessionFormat.sessions(parityRoot).map { it.name }.toSet()
        VERDICT_DEVIATIONS.forEach { id ->
            val verdict = expected.verdicts[id]
            assertTrue(verdict != null, "$id has no verdict")
            verdict!!.evidence.forEach { assertTrue(it.session in sessions, "$id evidence ${it.session} is not a parity session") }
        }
    }

    @Test
    fun `every kept deviation bites in its evidence sessions`() {
        val expected = readExpected(File(parityRoot, EXPECTED_FILE))
        val kept = expected.verdicts.filterValues { it.verdict == KEEP }
        assertTrue(kept.isNotEmpty())
        val unbitten =
            kept.filter { (id, v) ->
                val evidence = v.evidence.map { it.session }.toSet()
                expected.differences.none { (key, cause) -> cause == id && key.session in evidence }
            }
        assertEquals(emptyMap<String, Verdict>(), unbitten, "kept deviations no listed difference cites in their evidence sessions")
    }

    @Test
    fun `every kept deviation is not worse than mapper py against the truth labels`() {
        val expected = readExpected(File(parityRoot, EXPECTED_FILE))
        val failures =
            expected.verdicts
                .filterValues { it.verdict == KEEP }
                .flatMap { (id, v) ->
                    v.evidence.mapNotNull { e ->
                        val (kotlin, python) = scores(File(parityRoot, e.session))
                        notWorse(id, e.session, e.trueOutcome, kotlin, python)
                    }
                }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `a worse kept deviation and an incomplete verdict are reported`() {
        val worse =
            notWorse("D9", "s", SessionOutcome.COMPLETE, Score(2, SessionOutcome.PARTIAL), Score(1, SessionOutcome.COMPLETE))
        assertEquals(
            "D9 is worse than mapper.py on s (true outcome COMPLETE): Kotlin wrong 2 / PARTIAL, mapper.py wrong 1 / COMPLETE",
            worse,
        )
        assertTrue(
            notWorse("D9", "s", SessionOutcome.FAILED, Score(0, SessionOutcome.PARTIAL), Score(0, SessionOutcome.FAILED)) != null,
        )
        assertNull(notWorse("D9", "s", SessionOutcome.FAILED, Score(0, SessionOutcome.FAILED), Score(3, SessionOutcome.PARTIAL)))
        assertNull(notWorse("D9", "s", SessionOutcome.COMPLETE, Score(1, SessionOutcome.PARTIAL), Score(1, SessionOutcome.PARTIAL)))
        val file = File(temp, EXPECTED_FILE)
        file.writeText(
            """
            {"comparedFields": ["net"], "deviations": [
              {"id": "D1", "summary": "s", "deliberate": true, "verdict": "keep", "reason": "r"},
              {"id": "D2", "summary": "s", "deliberate": true, "verdict": "maybe", "reason": "r",
               "evidence": [{"session": "x", "trueOutcome": "COMPLETE"}]}],
             "differences": []}
            """.trimIndent(),
        )
        val problems = readExpected(file).problems
        assertTrue(problems.any { "D1 verdict: no evidence" in it }, problems.toString())
        assertTrue(problems.any { "D2 verdict: verdict maybe" in it }, problems.toString())
        assertTrue(problems.any { "deviation I402 has no complete verdict" in it }, problems.toString())
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

        /** The deviations part (b) decides (keep or revert) with truth-scored evidence. */
        val VERDICT_DEVIATIONS = REQUIRED_DEVIATIONS
        const val KEEP = "keep"
        val VERDICTS = setOf(KEEP, "reverted")
        val TRUE_OUTCOMES = listOf(SessionOutcome.COMPLETE, SessionOutcome.FAILED)

        /** Three part (a) run_spike sessions plus one part (b) session per named deviation. */
        const val SESSION_COUNT = 9
        const val SESSION_MAX_BYTES = 15_000_000L
        const val MAPPER_SOURCE =
            "core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt"
    }
}
