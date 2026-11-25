package dev.cubentia.domain.recognition.continuous

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.recognition.ScanReview
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Pure-JVM port of the 7 end-to-end continuous-scan stress scenarios (`pc_spike/run_spike.py`).
 */
class ContinuousStressScenariosTest {
    private data class ScenarioScore(
        val committed: Int,
        val correct: Int,
        val wrong: Int,
        val maxTransientWrong: Int,
        val lastSnapshot: ContinuousScanSnapshot,
        val sawGlareCue: Boolean,
        val sawPendingOnSymmetricFace: Boolean,
        val mapper: ContinuousFaceletMapper,
    )

    private fun runScenario(cfg: SyntheticCubeStream.ScenarioConfig): ScenarioScore {
        val frames = SyntheticCubeStream.generateSequence(cfg)
        val truth = SyntheticCubeStream.stateToNet(cfg.state)
        val mapper = ContinuousFaceletMapper()

        var maxTransientWrong = 0
        var sawGlareCue = false
        var sawPendingOnSymmetricFace = false
        var lastSnap: ContinuousScanSnapshot? = null

        for (fr in frames) {
            val snap = mapper.processFrame(fr.frameIndex, fr.detections)
            lastSnap = snap
            if (snap.cue is ContinuousScanCue.TiltAwayLight || snap.cue is ContinuousScanCue.TiltAwayLightRemaining) {
                sawGlareCue = true
            }
            if (snap.pendingFragmentFaces == 1 && snap.cue is ContinuousScanCue.TiltAdjacent) {
                sawPendingOnSymmetricFace = true
            }
            val (_, _, wrong) = scoreNet(snap.net, truth)
            if (wrong > maxTransientWrong) {
                maxTransientWrong = wrong
            }
        }

        val finalSnap = requireNotNull(lastSnap)
        val (committed, correct, wrong) = scoreNet(finalSnap.net, truth)
        return ScenarioScore(
            committed = committed,
            correct = correct,
            wrong = wrong,
            maxTransientWrong = maxTransientWrong,
            lastSnapshot = finalSnap,
            sawGlareCue = sawGlareCue,
            sawPendingOnSymmetricFace = sawPendingOnSymmetricFace,
            mapper = mapper,
        )
    }

    private fun scoreNet(
        pred: Map<Face, List<Color?>>,
        truth: Map<Face, List<Color>>,
    ): Triple<Int, Int, Int> {
        var committed = 0
        var correct = 0
        var wrong = 0
        for (f in Face.entries) {
            val pList = pred.getValue(f)
            val tList = truth.getValue(f)
            for (i in 0 until 9) {
                val p = pList[i]
                if (p != null) {
                    committed++
                    if (p == tList[i]) correct++ else wrong++
                }
            }
        }
        return Triple(committed, correct, wrong)
    }

    @Test
    fun `S1 scrambled stickered with regrip reaches 54 of 54 with 0 wrong and 0 transient wrong`() {
        val cfg = SyntheticCubeStream.buildScenarios()[0]
        val score = runScenario(cfg)
        assertEquals(54, score.correct)
        assertEquals(0, score.wrong)
        assertEquals(0, score.maxTransientWrong)
        assertTrue(score.mapper.reappearanceMergeCount >= 1)
        val complete = assertInstanceOf(ContinuousScanSnapshot.Complete::class.java, score.lastSnapshot)
        assertEquals(Orientation.HOME, complete.state.orientation)
        assertEquals(cfg.state.toFacelets(), complete.state.toFacelets())
    }

    @Test
    fun `S2 pastel stickerless with AWB drift reaches 54 of 54 with bounded zero-mean logAwb`() {
        val cfg = SyntheticCubeStream.buildScenarios()[1]
        val score = runScenario(cfg)
        assertEquals(54, score.correct)
        assertEquals(0, score.wrong)
        assertEquals(0, score.maxTransientWrong)
        val awb = score.mapper.masterLogAwb
        assertTrue(awb.all { it in -0.36..0.36 }, "logAwb out of bounds: ${awb.toList()}")
        assertTrue(abs(awb.average()) < 1e-6, "logAwb must be zero-mean: ${awb.toList()}")
        assertTrue(awb.maxOf { abs(it) } > 0.05, "Expected non-trivial AWB adaptation under S2 drift: ${awb.toList()}")
        assertInstanceOf(ContinuousScanSnapshot.Complete::class.java, score.lastSnapshot)
    }

    @Test
    fun `S3 logo center with overhead glare reaches 54 of 54 and emits glare cue`() {
        val cfg = SyntheticCubeStream.buildScenarios()[2]
        val score = runScenario(cfg)
        assertEquals(54, score.correct)
        assertEquals(0, score.wrong)
        assertEquals(0, score.maxTransientWrong)
        assertTrue(score.sawGlareCue, "Expected glare guidance cue during overhead glare")
        assertInstanceOf(ContinuousScanSnapshot.Complete::class.java, score.lastSnapshot)
    }

    @Test
    fun `S4 mid-solve F2L symmetric regrip holds pending on solid yellow D and merges cleanly`() {
        val cfg = SyntheticCubeStream.buildScenarios()[3]
        val score = runScenario(cfg)
        assertEquals(54, score.correct)
        assertEquals(0, score.wrong)
        assertEquals(0, score.maxTransientWrong)
        assertTrue(score.sawPendingOnSymmetricFace, "Expected pending hold with TiltAdjacent on symmetric D face")
        assertTrue(score.mapper.reappearanceMergeCount >= 1)
        assertInstanceOf(ContinuousScanSnapshot.Complete::class.java, score.lastSnapshot)
    }

    @Test
    fun `S5 finger occlusion jitter clutter drops and blur reaches 54 of 54 with 0 wrong`() {
        val cfg = SyntheticCubeStream.buildScenarios()[4]
        val score = runScenario(cfg)
        assertEquals(54, score.correct)
        assertEquals(0, score.wrong)
        assertEquals(0, score.maxTransientWrong)
        assertInstanceOf(ContinuousScanSnapshot.Complete::class.java, score.lastSnapshot)
    }

    @Test
    fun `S6 start without White on YOB tour canonicalizes to Orientation HOME for ScanReview`() {
        val cfg = SyntheticCubeStream.buildScenarios()[5]
        val score = runScenario(cfg)
        assertEquals(54, score.correct)
        assertEquals(0, score.wrong)
        assertEquals(0, score.maxTransientWrong)
        val exported = assertInstanceOf(ContinuousScanExport.Complete::class.java, score.mapper.export())
        assertEquals(Orientation.HOME, exported.state.orientation)
        val review = ScanReview.from(exported.handoff)
        assertEquals(cfg.state.toFacelets(), review.accepted?.toFacelets())
    }

    @Test
    fun `S7 mid-scan U layer twist is detected with 0 wrong committed`() {
        val cfg = SyntheticCubeStream.buildScenarios()[6]
        val score = runScenario(cfg)
        val twistSnap = assertInstanceOf(ContinuousScanSnapshot.TwistDetected::class.java, score.lastSnapshot)
        assertTrue(twistSnap.twistDetected)
        assertEquals(ContinuousScanCue.LayerTwistDetected, twistSnap.cue)
        assertEquals(0, score.wrong)
        assertEquals(0, score.maxTransientWrong)
        val exported = assertInstanceOf(ContinuousScanExport.TwistDetected::class.java, score.mapper.export())
        assertTrue((exported.twistFrame ?: 0) >= 55)
        // Post-twist frames must freeze state (no further mutations to twistFrame or masterLogAwb)
        val awbBefore = score.mapper.masterLogAwb.toList()
        val afterExtra = score.mapper.processFrame(999, emptyList())
        val afterTwist = assertInstanceOf(ContinuousScanSnapshot.TwistDetected::class.java, afterExtra)
        assertEquals(exported.twistFrame, afterTwist.twistFrame)
        assertEquals(awbBefore, score.mapper.masterLogAwb.toList())
    }
}
