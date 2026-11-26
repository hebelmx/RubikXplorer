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
