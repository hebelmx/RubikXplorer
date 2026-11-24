package dev.cubentia.domain.recognition

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Story 10.22 replays over the recorder bursts of 2026-11-22 ([RecordedBursts]). The numbers asserted here are the
 * ones `docs/evidence/10-22/README.md` derives the thresholds from.
 */
class QuickScanBurstReplayTest {
    private data class Replay(
        val windows: Int,
        val outlineRestarts: Int,
        val faceRestarts: Int,
        val colorRestarts: Int,
        val lost: Int,
        val driftRemeters: Int,
    )

    /**
     * [frames] through a Quick Scan [CornerHold] that is always allowed to capture and whose metering settles at once:
     * each capture counts as a completed steady window and the hold starts over. With [fill], a face the reader missed
     * is replaced by a neutral readable one (its centre not plain, its luma the mean of the faces read), so the replay
     * measures the steady rule alone and not the unchanged rule that all three faces must be readable.
     */
    private fun replay(
        frames: List<BurstFrame>,
        fill: Boolean = false,
    ): Replay {
        val hold = CornerHold(faceSteady = true)
        var windows = 0
        var outline = 0
        var face = 0
        var color = 0
        for (frame in frames) {
            val faces = if (fill && frame.detected) frame.faces.map { it ?: filler(frame) } else frame.faces
            val decision =
                hold.onFrame(
                    frame.detected,
                    faces,
                    frame.tMs,
                    clearWhenUnlocated = false,
                    settled = true,
                    outline = frame.outline,
                ) { true }
            when (hold.steadyRestart?.reason) {
                CornerSteadyRestart.Reason.OUTLINE -> outline++
                CornerSteadyRestart.Reason.FACE -> face++
                CornerSteadyRestart.Reason.COLOR -> color++
                null -> Unit
            }
            if (decision == CornerHold.Decision.Capture) {
                windows++
                hold.reset()
            }
        }
        val marks = hold.drainMarks()
        val drift = setOf(CornerMeterReason.OUTLINE.label, CornerMeterReason.BRIGHTNESS.label)
        return Replay(
            windows,
            outline,
            face,
            color,
            lost = marks.count { it.kind == CornerHoldEventKind.CUBE_LOST },
            driftRemeters = marks.count { it.kind == CornerHoldEventKind.METERING_REQUESTED && it.reason in drift },
        )
    }

    private fun filler(frame: BurstFrame): FrameStats {
        val luma =
            frame.faces
                .filterNotNull()
                .map { it.meanLuma }
                .average()
        return FrameStats(List(9) { CellReading(Lab(50.0, 0.0, 0.0), false) }, luma, 0.0, 100.0, 12)
    }

    /**
     * The outline rule at [fraction], alone or with the colour rule at [colorDeltaE] (frames with a face unread are
     * skipped then, as the hold waits on them): restarts and completed one-second windows over [frames]; a cube lost
     * for [CornerHold.LOST_MS] also ends the window.
     */
    private fun sweep(
        frames: List<BurstFrame>,
        fraction: Double,
        colorDeltaE: Double? = null,
    ): Pair<Int, Int> {
        var reference: List<OutlinePoint>? = null
        var referenceFaces: List<FrameStats?> = emptyList()
        var since = 0L
        var undetected: Long? = null
        var restarts = 0
        var windows = 0
        for (frame in frames) {
            val outline = frame.outline
            if (outline == null) {
                val from = undetected ?: frame.tMs.also { undetected = it }
                if (frame.tMs - from >= CornerHold.LOST_MS) reference = null
                continue
            }
            undetected = null
            if (colorDeltaE != null && frame.faces.any { it == null }) {
                reference = null
                continue
            }
            val moved = CornerOutline.displacement(outline, reference)
            val unstable = colorDeltaE != null && CornerHold.unstableFace(frame.faces, referenceFaces, colorDeltaE) != null
            when {
                moved == null -> {
                    reference = outline
                    referenceFaces = frame.faces
                    since = frame.tMs
                }

                moved >= fraction || unstable -> {
                    restarts++
                    reference = outline
                    referenceFaces = frame.faces
                    since = frame.tMs
                }

                frame.tMs - since >= CornerHold.STEADY_MS -> {
                    windows++
                    reference = null
                }
            }
        }
        return restarts to windows
    }

    @Test
    fun `the outline threshold separates resting and hand-held bursts from fast movement`() {
        val bursts = RecordedBursts.all.associateWith(RecordedBursts::load)
        // A resting cube: the detector's own jitter restarts the window up to 0.06 on still burst 1, never from 0.07.
        assertTrue(sweep(bursts.getValue(RecordedBursts.STILL), 0.06).first > 0)
        listOf(0.07, 0.08, CornerHold.OUTLINE_STEADY_FRACTION, 0.15).forEach { fraction ->
            assertEquals(0, sweep(bursts.getValue(RecordedBursts.STILL), fraction).first, "still at $fraction")
            assertEquals(0, sweep(bursts.getValue(RecordedBursts.STILL_LONG), fraction).first, "still-long at $fraction")
        }
        // Hand-held: the first burst never restarts from 0.08; the second restarts twice from 0.08 to 0.15.
        listOf(0.08, CornerHold.OUTLINE_STEADY_FRACTION, 0.15).forEach { fraction ->
            assertEquals(0 to 8, sweep(bursts.getValue(RecordedBursts.HANDHELD), fraction), "handheld at $fraction")
            assertEquals(2 to 7, sweep(bursts.getValue(RecordedBursts.HANDHELD_2), fraction), "handheld-2 at $fraction")
        }
        // Fast movement restarts at every candidate, and completes no window up to the chosen fraction.
        listOf(0.07, 0.08, CornerHold.OUTLINE_STEADY_FRACTION, 0.15, 0.20).forEach { fraction ->
            assertTrue(sweep(bursts.getValue(RecordedBursts.FAST), fraction).first > 0, "fast at $fraction")
        }
        assertEquals(0, sweep(bursts.getValue(RecordedBursts.FAST), CornerHold.OUTLINE_STEADY_FRACTION).second)
        // The chosen fraction sits above every resting value and below the fast burst's frame-to-frame p90.
        assertTrue(CornerHold.OUTLINE_STEADY_FRACTION > 0.07 && CornerHold.OUTLINE_STEADY_FRACTION <= 0.111)
    }

    @Test
    fun `the colour threshold keeps a resting cube steady through the exposure swing and stops fast movement`() {
        val bursts = RecordedBursts.all.associateWith(RecordedBursts::load)
        val table =
            listOf(8.0, 10.0, 12.0, 15.0).associateWith { deltaE ->
                RecordedBursts.all.map { sweep(bursts.getValue(it), CornerHold.OUTLINE_STEADY_FRACTION, deltaE) }
            }
        // Restarts to completed windows per burst: still, still-long, handheld, handheld-2 (as recorded), fast.
        // ΔE 8 and 10 restart on still burst 1's exposure swing; 12 is the smallest candidate that does not.
        assertEquals(listOf(2 to 3, 1 to 11, 13 to 2, 2 to 0, 22 to 0), table[8.0])
        assertEquals(listOf(2 to 3, 0 to 11, 3 to 6, 2 to 0, 21 to 0), table[10.0])
        assertEquals(listOf(0 to 4, 0 to 11, 3 to 6, 2 to 0, 21 to 0), table[CornerHold.COLOR_STEADY_DELTA_E])
        assertEquals(listOf(0 to 4, 0 to 11, 0 to 8, 2 to 0, 21 to 0), table[15.0])
    }

    @Test
    fun `a resting cube never restarts the steady window and never re-meters, through an exposure swing`() {
        // Still burst 1 alternates between two exposure levels (ΔE 5–8 on every sticker): the pre-10.22 colour rule (ΔE 5) restarted; the 10.22 one (ΔE 12) does not.
        val still = replay(RecordedBursts.load(RecordedBursts.STILL))
        val long = replay(RecordedBursts.load(RecordedBursts.STILL_LONG))
        assertEquals(Replay(windows = 4, outlineRestarts = 0, faceRestarts = 0, colorRestarts = 0, lost = 0, driftRemeters = 0), still)
        assertEquals(Replay(windows = 10, outlineRestarts = 0, faceRestarts = 0, colorRestarts = 0, lost = 0, driftRemeters = 0), long)
    }

    @Test
    fun `the first hand-held burst completes steady windows and fast movement completes none`() {
        val handheld = replay(RecordedBursts.load(RecordedBursts.HANDHELD))
        // Hand-held burst 2 read its right face in 21 of 120 frames only; the steady rule is replayed with it filled in.
        val second = replay(RecordedBursts.load(RecordedBursts.HANDHELD_2), fill = true)
        val fast = replay(RecordedBursts.load(RecordedBursts.FAST), fill = true)
        // The colour rule (PO 2026-11-22) costs hand-held windows: 5 where the outline rule alone completed 8.
        assertEquals(Replay(windows = 5, outlineRestarts = 0, faceRestarts = 0, colorRestarts = 5, lost = 0, driftRemeters = 0), handheld)
        assertEquals(Replay(windows = 2, outlineRestarts = 0, faceRestarts = 0, colorRestarts = 33, lost = 0, driftRemeters = 0), second)
        // Fast movement: the window restarts 33 times, the cube is lost once, one re-meter follows it, nothing captures.
        assertEquals(Replay(windows = 0, outlineRestarts = 9, faceRestarts = 0, colorRestarts = 24, lost = 1, driftRemeters = 1), fast)
        // The unchanged rule that all three faces must be readable completes no window on hand-held burst 2 as recorded.
        assertEquals(0, replay(RecordedBursts.load(RecordedBursts.HANDHELD_2)).windows)
    }

    @Test
    fun `hold 2 never captures an unturned cube whose logo top centre flickers`() {
        val frames = RecordedBursts.load(RecordedBursts.STILL)
        // The burst's top centre carries a logo: plain in some frames only, and read far apart between frames (F10, F15).
        val tops = frames.map { it.faces[0]!!.cells[FaceSample.CENTER_INDEX]!! }
        assertTrue(tops.any { it.plain } && tops.any { !it.plain })
        assertTrue(
            tops.maxOf { StickerClassifier.deltaE(it.value, tops.first().value) } >= GuidedScanController.FACE_CHANGE_DELTA_E,
            "the top centre alone would have passed the old turn check",
        )
        // Hold 1 captured on any frame of the burst, the same frames then shown as hold 2 with the cube not turned.
        for (taken in frames.indices) {
            val scan = QuickScanController(lightCheck = false)
            val first = frames[taken]
            scan.onFrame(true, first.faces, 0, outline = first.outline)
            assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onShutter(1), "hold 1 on frame $taken")
            for (frame in frames) {
                val now = 1000 + frame.tMs
                val effects = scan.onFrame(frame.detected, frame.faces, now, settled = true, outline = frame.outline)
                assertTrue(effects.isEmpty(), "hold 1 on frame $taken: $effects at ${frame.tMs}")
                assertTrue(scan.onShutter(now).isEmpty(), "hold 1 on frame $taken: the shutter at ${frame.tMs}")
            }
            assertEquals(2, scan.hold)
        }
    }

    @Test
    fun `after a back-out the resting cube in view is captured again within the steady second`() {
        val frames = RecordedBursts.load(RecordedBursts.STILL)
        val scan = QuickScanController(lightCheck = false)
        // Hold 1 is captured from the burst, the Learner switches to face by face and comes back.
        var captured: Long? = null
        for (frame in frames) {
            val effects =
                scan.onFrame(
                    frame.detected,
                    frame.faces,
                    frame.tMs,
                    settled = frame.tMs > frames.first().tMs,
                    outline = frame.outline,
                )
            if (QuickScanEffect.CornerCaptured(1) in effects) {
                captured = frame.tMs
                break
            }
        }
        val first = checkNotNull(captured) { "hold 1 auto-captures on the resting burst" }
        assertTrue(first <= frames.first().tMs + CornerHold.STEADY_MS + 300, "captured at $first")
        assertFalse(scan.lastCapture!!.manual)
        checkNotNull(scan.switchToFaceByFace())
        assertTrue(scan.backOut())
        assertEquals(2, scan.hold)
        // The second corner (the long burst's cube, turned: its centres are not hold 1's) captures on its own.
        val turned =
            RecordedBursts.load(RecordedBursts.STILL_LONG).map {
                it.copy(
                    faces =
                        it.faces.map { face ->
                            face?.let(::recoloured)
                        },
                )
            }
        var second: Long? = null
        for (frame in turned) {
            val now = 10_000 + frame.tMs
            val effects = scan.onFrame(frame.detected, frame.faces, now, settled = frame.tMs > turned.first().tMs, outline = frame.outline)
            if (effects.any { it == QuickScanEffect.CornerCaptured(2) }) {
                second = frame.tMs
                break
            }
        }
        val again = checkNotNull(second) { "the corner in view after the back-out captures" }
        assertTrue(again <= turned.first().tMs + CornerHold.STEADY_MS + 300, "captured at $again")
    }

    /**
     * CONFIDENT judged stickers of a frame's hold-1 faces as the Story 10.21 wait counts them (no saved calibration); null
     * when no face has a colour reference (the wait does not hold such a frame).
     */
    private fun confidentCount(frame: BurstFrame): Int? {
        val samples =
            QuickScanPlan.holds[0].zip(frame.faces).mapNotNull { (step, stats) ->
                val cells = stats?.cells?.filterNotNull()?.takeIf { it.size == 9 } ?: return@mapNotNull null
                FaceSample(
                    face = step.face,
                    orientationDegrees = 0,
                    stickers = cells.map { it.value },
                    quality = FrameQuality.assessShutter(stats),
                    centerColor = step.centerColor,
                    upNeighborColor = step.upNeighborColor,
                    centerPlain = cells[FaceSample.CENTER_INDEX].plain,
                )
            }
        val counts = samples.mapNotNull { QuickScanClassification.confidentJudged(it, samples, null) }
        return if (samples.isNotEmpty() && counts.isEmpty()) null else counts.sum()
    }

    @Test
    fun `the confident-count wait is replayed over the Lenovo bursts and every resting or hand-held hold still captures within 10 s`() {
        val report =
            buildString {
                appendLine(
                    "# Story 10.21 confident-count wait over the 2026-11-22 Lenovo bursts (N = ${QuickScanController.MIN_CONFIDENT})",
                )
                appendLine()
                appendLine("| burst | detected frames | below N | longest run below N (ms) | auto capture since detected (ms) |")
                appendLine("|---|---|---|---|---|")
                RecordedBursts.all.forEach { name ->
                    val frames = RecordedBursts.load(name)
                    val detected = frames.filter { it.detected }
                    var longest = 0L
                    var since: Long? = null
                    var below = 0
                    for (frame in detected) {
                        if ((confidentCount(frame) ?: Int.MAX_VALUE) < QuickScanController.MIN_CONFIDENT) {
                            below++
                            val start = since ?: frame.tMs.also { since = it }
                            longest = maxOf(longest, frame.tMs - start)
                        } else {
                            since = null
                        }
                    }
                    val scan = QuickScanController(lightCheck = false)
                    val captured =
                        frames.firstNotNullOfOrNull { frame ->
                            scan.onFrame(frame.detected, frame.faces, frame.tMs, settled = true, outline = frame.outline)
                            scan.lastCapture
                        }
                    appendLine("| $name | ${detected.size} | $below | $longest | ${captured?.sinceDetectedMs ?: "none"} |")
                    if (name in setOf(RecordedBursts.STILL, RecordedBursts.STILL_LONG, RecordedBursts.HANDHELD)) {
                        val ms = checkNotNull(captured?.sinceDetectedMs) { "$name captures automatically" }
                        assertTrue(ms <= 10_000, "$name captured $ms ms after detection")
                    }
                }
            }
        println(report)
        java.io
            .File("build/reports/quick-scan/burst-wait-10-21.md")
            .apply { parentFile.mkdirs() }
            .writeText(report)
    }

    /** The face with a* and b* negated: every centre reads as another colour, as on the opposite corner. */
    private fun recoloured(face: FrameStats): FrameStats =
        face.copy(cells = face.cells.map { cell -> cell?.copy(value = cell.value.copy(a = -cell.value.a, b = -cell.value.b)) })
}
