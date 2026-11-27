package dev.cubentia.domain.recognition.evaluation.continuous

import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionFormat
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionJson
import dev.cubentia.domain.recognition.continuous.session.SessionCountCheck
import dev.cubentia.domain.recognition.continuous.session.SessionOutcome
import dev.cubentia.domain.recognition.continuous.session.SyntheticSessionGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Story 10.34 golden state replay: the checked-in sessions under `Fixtures/continuous/` replay through a fresh mapper to
 * exactly their recorded snapshots and cue stream. A mapper change that moves them fails here; only
 * `./gradlew :core:domain:replayContinuousSession --args='--refresh-golden'` accepts it.
 */
class ContinuousSessionReplayTest {
    @TempDir lateinit var temp: File

    private val root: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "Fixtures/continuous") }
            .first { it.isDirectory }

    private fun session(id: String) = File(root, id)

    @Test
    fun `every checked-in session replays its recorded snapshots and cues exactly`() {
        val sessions = ContinuousSessionReplay.sessionsUnder(root)
        assertTrue(
            sessions.map { it.name }.containsAll(listOf(SyntheticSessionGenerator.COMPLETE_ID, SyntheticSessionGenerator.PARTIAL_ID)),
        )
        sessions.forEach { dir ->
            val report = ContinuousSessionReplay.replay(dir)
            assertEquals(emptyList<ContinuousSessionReplay.FrameDiff>(), report.diffs, dir.name)
            assertEquals(ContinuousSessionReplay.STARTING_STAGE, report.startingStage)
        }
    }

    @Test
    fun `the complete session reproduces the labelled net in canonical order`() {
        val report = ContinuousSessionReplay.replay(session(SyntheticSessionGenerator.COMPLETE_ID))
        val labels = checkNotNull(ContinuousSessionJson.readLabels(session(SyntheticSessionGenerator.COMPLETE_ID)))
        assertEquals(SessionOutcome.COMPLETE, report.outcome)
        assertEquals(labels.stickers.map { it.color }, report.net)
        assertTrue(report.labelMismatches.isEmpty())
        assertEquals(6, report.sidesMapped)
        assertEquals(54, report.committedStickers)
        assertTrue(report.manifest.synthetic)
    }

    @Test
    fun `the partial session reports its last confirmed net without filling unknowns`() {
        val report = ContinuousSessionReplay.replay(session(SyntheticSessionGenerator.PARTIAL_ID))
        assertEquals(SessionOutcome.PARTIAL, report.outcome)
        assertTrue(report.net.any { it == null })
        // Non-centre stickers are committed, so keeping the unknowns unfilled is a real check.
        assertTrue(report.net.indices.any { it % 9 != 4 && report.net[it] != null })
        assertEquals(report.net.count { it != null }, report.committedStickers)
        assertTrue(report.labelMismatches.isEmpty())
        assertEquals(report.net.count { it == null }, report.uncommittedLabelled)
        assertNotNull(report.lastCue)
        val text = ContinuousSessionReplay.markdown(listOf(report))
        assertTrue("Last confirmed net" in text, text)
        assertTrue("Starting stage: mapper (recorded observations)" in text, text)
    }

    @Test
    fun `a tampered snapshot is reported as a per-frame diff`() {
        val copy = File(temp, "tampered").also { session(SyntheticSessionGenerator.PARTIAL_ID).copyRecursively(it) }
        val snapshots = File(copy, ContinuousSessionFormat.SNAPSHOTS)
        val lines = snapshots.readLines().toMutableList()
        val recorded = ContinuousSessionJson.decodeSnapshot(lines[5])
        lines[5] =
            ContinuousSessionJson.encodeSnapshot(
                recorded.copy(committedCount = recorded.committedCount + 1, cue = recorded.cue.copy(type = "AllVerified", count = null)),
            )
        snapshots.writeText(lines.joinToString("\n"))
        val diffs = ContinuousSessionReplay.replay(copy).diffs
        assertEquals(listOf("cue", "committedCount"), diffs.map { it.field })
        assertTrue(diffs.all { it.frameIndex == recorded.frameIndex })
        assertTrue("frame ${recorded.frameIndex}: cue recorded=AllVerified" in diffs.first().toString(), diffs.first().toString())
        assertTrue(ContinuousSessionReplay.markdown(listOf(ContinuousSessionReplay.replay(copy))).contains("Snapshot/cue diffs: 2"))
    }

    @Test
    fun `refresh golden rewrites the snapshots and records the revision`() {
        val copy = File(temp, "golden").also { session(SyntheticSessionGenerator.PARTIAL_ID).copyRecursively(it) }
        File(
            copy,
            ContinuousSessionFormat.SNAPSHOTS,
        ).writeText(File(copy, ContinuousSessionFormat.SNAPSHOTS).readLines().drop(1).joinToString("\n"))
        assertFalse(ContinuousSessionReplay.replay(copy).diffs.isEmpty())
        val report = ContinuousSessionReplay.refreshGolden(copy, "a".repeat(40))
        assertTrue(report.diffs.isEmpty())
        assertEquals("a".repeat(40), ContinuousSessionJson.readManifest(copy).goldenRevision)
    }

    @Test
    fun `temp and manifest-less directories are ignored`() {
        session(SyntheticSessionGenerator.COMPLETE_ID).copyRecursively(File(temp, ".tmp-interrupted"))
        session(SyntheticSessionGenerator.COMPLETE_ID).copyRecursively(File(temp, "no-manifest"))
        File(temp, "no-manifest/manifest.json").delete()
        session(SyntheticSessionGenerator.PARTIAL_ID).copyRecursively(File(temp, "kept"))
        assertEquals(listOf("kept"), ContinuousSessionReplay.sessionsUnder(temp).map { it.name })
        assertEquals(listOf(File(temp, "kept")), ContinuousSessionReplay.sessionsUnder(File(temp, "kept")))
        assertTrue("No complete session" in ContinuousSessionReplay.markdown(emptyList()))
    }

    @Test
    fun `the checked-in observations are what the generator produces`() {
        listOf(SyntheticSessionGenerator.complete(), SyntheticSessionGenerator.partial()).forEach { generated ->
            val dir = session(generated.manifest.sessionId)
            assertEquals(generated.frames, ContinuousSessionJson.readFrames(dir))
            assertEquals(generated.labels, ContinuousSessionJson.readLabels(dir))
            assertEquals(generated.manifest.outcome, ContinuousSessionJson.readManifest(dir).outcome)
        }
    }

    @Test
    fun `a wrong label is a mismatch and an unknown label is reported, not compared`() {
        val copy = File(temp, "labels").also { session(SyntheticSessionGenerator.COMPLETE_ID).copyRecursively(it) }
        val labels = checkNotNull(ContinuousSessionJson.readLabels(copy))
        val truth0 = checkNotNull(labels.stickers[0].color)
        val wrong =
            dev.cubentia.domain.cube.Color.entries
                .first { it != truth0 }
        val edited =
            labels.copy(
                stickers =
                    labels.stickers.mapIndexed { i, s ->
                        when (i) {
                            0 -> s.copy(color = wrong)
                            1 -> s.copy(color = null)
                            else -> s
                        }
                    },
            )
        File(copy, ContinuousSessionFormat.LABELS).writeText(ContinuousSessionJson.encodeLabels(edited))
        val report = ContinuousSessionReplay.replay(copy)
        val mismatch = report.labelMismatches.single()
        assertEquals("U0", mismatch.slot)
        assertEquals(wrong, mismatch.truth)
        assertEquals(truth0, mismatch.predicted)
        assertEquals(1, report.unknownLabels)
        val text = ContinuousSessionReplay.markdown(listOf(report))
        assertTrue("Label mismatches: 1 (unknown labels, not compared: 1" in text, text)
        assertTrue("U0 truth=$wrong predicted=$truth0" in text, text)
    }

    @Test
    fun `refused labels are reported but never scored as mismatches`() {
        val copy = File(temp, "refused").also { session(SyntheticSessionGenerator.COMPLETE_ID).copyRecursively(it) }
        val labels = checkNotNull(ContinuousSessionJson.readLabels(copy))
        val truth0 = checkNotNull(labels.stickers[0].color)
        val wrong =
            dev.cubentia.domain.cube.Color.entries
                .first { it != truth0 }
        val edited =
            labels.copy(
                stickers =
                    labels.stickers.mapIndexed { i, s ->
                        when (i) {
                            0 -> s.copy(color = wrong)
                            1 -> s.copy(color = null)
                            else -> s
                        }
                    },
                countCheck = SessionCountCheck.REFUSED,
            )
        File(copy, ContinuousSessionFormat.LABELS).writeText(ContinuousSessionJson.encodeLabels(edited))
        val report = ContinuousSessionReplay.replay(copy)
        assertTrue(report.labelled)
        assertTrue(report.labelsRefused)
        assertTrue(report.labelMismatches.isEmpty())
        assertEquals(1, report.unknownLabels)
        val text = ContinuousSessionReplay.markdown(listOf(report))
        assertTrue("Label mismatches: not scored (count check REFUSED) (unknown labels: 1" in text, text)
        assertFalse("truth=$wrong" in text, text)
    }

    @Test
    fun `refresh golden refuses a session that is not exactly replayable and keeps the recorded outcome`() {
        val copy = File(temp, "gap-golden").also { session(SyntheticSessionGenerator.PARTIAL_ID).copyRecursively(it) }
        listOf(ContinuousSessionFormat.FRAMES, ContinuousSessionFormat.SNAPSHOTS).forEach { name ->
            val file = File(copy, name)
            file.writeText(file.readLines().filterIndexed { i, _ -> i != 10 }.joinToString("\n"))
        }
        val manifest = File(copy, ContinuousSessionFormat.MANIFEST).readText()
        val snapshots = File(copy, ContinuousSessionFormat.SNAPSHOTS).readText()
        val refused =
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
                ContinuousSessionReplay.refreshGolden(copy, "a".repeat(40))
            }
        assertTrue("not exactly replayable" in refused.message.orEmpty(), refused.message)
        assertEquals(manifest, File(copy, ContinuousSessionFormat.MANIFEST).readText())
        assertEquals(snapshots, File(copy, ContinuousSessionFormat.SNAPSHOTS).readText())
        // An exactly replayable session keeps its recorded outcome through a refresh.
        val kept = File(temp, "kept-outcome").also { session(SyntheticSessionGenerator.PARTIAL_ID).copyRecursively(it) }
        val recorded = ContinuousSessionJson.readManifest(kept)
        ContinuousSessionJson
            .readManifest(kept)
            .copy(outcome = SessionOutcome.LOST_TRACKING)
            .let { File(kept, ContinuousSessionFormat.MANIFEST).writeText(ContinuousSessionJson.encodeManifest(it)) }
        ContinuousSessionReplay.refreshGolden(kept, "b".repeat(40))
        assertEquals(SessionOutcome.LOST_TRACKING, ContinuousSessionJson.readManifest(kept).outcome)
        assertEquals(recorded.frameCount, ContinuousSessionJson.readManifest(kept).frameCount)
    }

    @Test
    fun `the replay command line rejects a missing value, an unknown flag and a second root`() {
        assertEquals(ReplayArgs(), parseReplayArgs(emptyArray()))
        assertEquals(
            ReplayArgs(File("x"), File("s"), File("r.md"), refresh = true),
            parseReplayArgs(arrayOf("x", "--session", "s", "--report", "r.md", "--refresh-golden")),
        )
        assertEquals(null, parseReplayArgs(arrayOf("--session")))
        assertEquals(null, parseReplayArgs(arrayOf("--report")))
        assertEquals(null, parseReplayArgs(arrayOf("--session", "--report", "r")))
        assertEquals(null, parseReplayArgs(arrayOf("--refresh")))
        assertEquals(null, parseReplayArgs(arrayOf("a", "b")))
    }

    @Test
    fun `a skipped frame is not fed to the mapper and is not a gap`() {
        val copy = File(temp, "skipped").also { session(SyntheticSessionGenerator.PARTIAL_ID).copyRecursively(it) }
        val frames = File(copy, ContinuousSessionFormat.FRAMES)
        val recorded = ContinuousSessionJson.readFrames(copy)
        val last = recorded.last()
        // The pipeline did not read this frame (e.g. the detector became unavailable): a frame line, no snapshot line.
        val skipped =
            dev.cubentia.domain.recognition.continuous.session.ContinuousSessionFrame(
                frameIndex = last.frameIndex + 1,
                timestampMs = last.timestampMs + 33,
                analyzerMs = 1.0,
                observations = emptyList(),
                skipped = true,
            )
        frames.appendText(ContinuousSessionJson.encodeFrame(skipped) + "\n")
        val report = ContinuousSessionReplay.replay(copy)
        assertEquals(emptyList<ContinuousSessionReplay.FrameDiff>(), report.diffs)
        assertEquals(1, report.skippedFrames)
        assertEquals(0, report.frameGaps)
        assertTrue(report.exactlyReplayable)
        assertEquals(recorded.size, report.replayed.size)
        assertTrue("1 skipped" in ContinuousSessionReplay.markdown(listOf(report)))
    }

    @Test
    fun `a session missing a frame is flagged as not exactly replayable`() {
        val copy = File(temp, "gap").also { session(SyntheticSessionGenerator.PARTIAL_ID).copyRecursively(it) }
        listOf(ContinuousSessionFormat.FRAMES, ContinuousSessionFormat.SNAPSHOTS).forEach { name ->
            val file = File(copy, name)
            file.writeText(file.readLines().filterIndexed { i, _ -> i != 10 }.joinToString("\n"))
        }
        val report = ContinuousSessionReplay.replay(copy)
        assertEquals(1, report.frameGaps)
        assertFalse(report.exactlyReplayable)
        val text = ContinuousSessionReplay.markdown(listOf(report))
        assertTrue("Not exactly replayable: 1 missing frames (defective capture)" in text, text)
        assertTrue(ContinuousSessionReplay.replay(session(SyntheticSessionGenerator.PARTIAL_ID)).exactlyReplayable)
        assertEquals(0, ContinuousSessionReplay.frameGaps(emptyList()))
    }
}
