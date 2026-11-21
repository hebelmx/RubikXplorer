package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face

/** The named top, front-left and front-right poses, in the order the adapter returns them. */
object QuickScanPlan {
    val holds =
        listOf(
            listOf(ScanStep(Color.WHITE, Color.BLUE), ScanStep(Color.GREEN, Color.WHITE), ScanStep(Color.RED, Color.WHITE)),
            listOf(ScanStep(Color.YELLOW, Color.RED), ScanStep(Color.ORANGE, Color.YELLOW), ScanStep(Color.BLUE, Color.YELLOW)),
        )
}

sealed interface QuickScanEffect {
    data object Remeter : QuickScanEffect

    data class CornerCaptured(
        val hold: Int,
    ) : QuickScanEffect

    /** The hand-over to the Guided viewfinder started: it asks [faces], in [GuidedScanPlan.steps] order. */
    data class Fallback(
        val faces: List<Face>,
    ) : QuickScanEffect

    data class Done(
        val samples: List<FaceSample>,
    ) : QuickScanEffect
}

/**
 * How Quick Scan reads a corner face once the frame is unwarped: every sticker against one calibration, by the shared
 * close-call rule. A face with any judged reading that is null or not CONFIDENT goes to the Guided grid (the 2D
 * fallback). The D2 replay (`QuickScanSideFaceReplayTest`) measures recorded corner fixtures through this same path.
 */
object QuickScanReadout {
    /**
     * The judged readings against the capture's own [centers] when no full calibration exists yet (Story 10.17): only
     * the colors seen compete, so a sticker is CONFIDENT only when it clearly matches one of them; null for an invalid
     * sticker or fewer than two centers.
     */
    fun judgedAgainst(
        sample: FaceSample,
        centers: Map<Color, Lab>,
    ): List<StickerReading?> =
        sample.stickers
            .map { sticker -> if (centers.size >= 2 && sticker.isValid()) StickerClassifier.read(sticker, centers) else null }
            .filterIndexed { index, _ -> index != FaceSample.CENTER_INDEX || sample.centerPlain != false }

    /** One reading per sticker, null where the classifier is unavailable. */
    fun read(
        sample: FaceSample,
        calibration: CalibrationCenters,
    ): List<StickerReading?> =
        sample.stickers.map { (StickerClassifier.classify(it, calibration) as? StickerClassification.Classified)?.reading }

    /** The readings the fallback decision judges: all but a guarded (not plain) center, which marks no face for the grid. */
    fun judged(
        sample: FaceSample,
        calibration: CalibrationCenters,
    ): List<StickerReading?> =
        read(sample, calibration).filterIndexed { index, _ -> index != FaceSample.CENTER_INDEX || sample.centerPlain != false }
}

/**
 * One analyzer-thread state machine over neutral readings and the Guided hand-over controller (Story 10.17).
 *
 * The hand-over has one path for both entries, the *Scan face by face* switch ([switchToFaceByFace]) and the automatic
 * hand-over after the second corner: the captured faces that pass [needGrid] are kept ([keptFaces]; once kept a face
 * stays kept until the scan ends), the other corner samples are dropped, and a Guided controller asks only the other
 * faces in [GuidedScanPlan.steps] order, any rotation, never a named top and never a second round. Its Done is
 * [QuickScanEffect.Done] over [kept] + [added]: the kept samples keep their captured (named-top) pose, so
 * `ScanAssembler` places them once and searches each new face's quarter turns. [backOut] returns to the corner view.
 */
class QuickScanController(
    private val saved: CalibrationCenters? = null,
) {
    private val samples = linkedMapOf<Face, FaceSample>()
    private val keptSet = linkedSetOf<Face>()
    private val addedSamples = linkedMapOf<Face, FaceSample>()
    private val corner = CornerHold()
    private var previous: List<Lab?>? = null

    /** The corner hold the hand-over returns to on [backOut]; null when no hand-over was started since the last one. */
    private var resumeHold: Int? = null
    var hold = 1
        private set
    val coaching: Coaching? get() = corner.coaching
    var fallback: GuidedScanController? = null
        private set
    var fallbackFaces: List<Face> = emptyList()
        private set
    var repromptFaces: List<Face> = emptyList()
        private set

    /** The corner samples held now: before a hand-over every face read, during and after one only the kept faces. */
    val captured: List<FaceSample> get() = samples.values.toList()

    /** The faces a hand-over kept; empty before the first hand-over. */
    val keptFaces: Set<Face> get() = keptSet.toSet()

    /** The kept faces' corner samples, exactly as captured. */
    val kept: List<FaceSample> get() = samples.values.filter { it.face in keptSet }

    /** The faces captured in the running (or just finished) hand-over viewfinder. */
    val added: List<FaceSample> get() = addedSamples.values.toList()

    val inCorner: Boolean get() = hold <= QuickScanPlan.holds.size

    fun onFrame(
        detected: Boolean,
        faces: List<FrameStats?>,
        nowMs: Long,
    ): List<QuickScanEffect> {
        if (!inCorner) return emptyList()
        val decision =
            corner.onFrame(detected, faces, nowMs, clearWhenUnlocated = hold == 2) { values ->
                val prior = previous
                if (prior != null && !turnedOver(values, prior)) {
                    false
                } else {
                    previous = null
                    true
                }
            }
        return when (decision) {
            CornerHold.Decision.Wait -> emptyList()
            CornerHold.Decision.Remeter -> listOf(QuickScanEffect.Remeter)
            CornerHold.Decision.Capture -> onShutter()
        }
    }

    fun onShutter(): List<QuickScanEffect> {
        if (!inCorner || (previous != null && !corner.unlocated)) return emptyList()
        val taken = hold++
        QuickScanPlan.holds[taken - 1].zip(corner.latest).forEach { (step, stats) ->
            val cells = stats?.cells?.filterNotNull() ?: return@forEach
            // Fill only (PO Q2): a kept face never changes until review, where *Retake faces* fixes it.
            if (cells.size == 9 && step.face !in keptSet) {
                samples[step.face] =
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
        }
        if (inCorner) {
            previous =
                corner.values().takeIf { it.any { value -> value != null } }
        }
        corner.reset()
        val capture = QuickScanEffect.CornerCaptured(taken)
        if (inCorner) return listOf(capture)
        // Faces kept by an earlier hand-over are never asked again.
        repromptFaces = emptyList()
        fallbackFaces = needGrid(captured, Face.entries.filter { it !in keptSet })
        val asked =
            fallbackFaces.ifEmpty {
                repromptFaces = involvedFaces(captured).filter { it !in keptSet }
                repromptFaces
            }
        return listOf(capture, if (asked.isEmpty()) QuickScanEffect.Done(captured) else handOver(asked.toSet(), resumeAt = taken))
    }

    /**
     * *Scan face by face*: valid in the corner view (hold 1 or 2, also during a capture freeze). Keeps the captured faces
     * that [needGrid] passes, plus faces already kept, and hands the others over; null (no-op) once a hand-over runs or
     * the scan is done.
     */
    fun switchToFaceByFace(): QuickScanEffect? {
        if (!inCorner) return null
        val judged = captured.map { it.face }.filter { it !in keptSet }
        val doubtful = needGrid(captured, judged).toSet()
        val keep = keptSet + judged.filter { it !in doubtful }
        val asked = Face.entries.filter { it !in keep }.toSet()
        fallbackFaces = asked.toList()
        repromptFaces = emptyList()
        return handOver(asked, resumeAt = hold)
    }

    /** Each Guided capture is added beside the kept faces; the hand-over's Done is the combined scan. */
    fun onGuidedEffects(effects: List<GuidedScanEffect>): QuickScanEffect? {
        fallback ?: return null
        val capture = effects.filterIsInstance<GuidedScanEffect.Capture>().firstOrNull() ?: return null
        addedSamples[capture.sample.face] = capture.sample
        if (effects.none { it is GuidedScanEffect.Done }) return null
        fallback = null
        return QuickScanEffect.Done(kept + added)
    }

    /**
     * Back from the hand-over before review (PO Q1): the corner view at the hold it started from (hold 2 once both corners
     * were captured), the kept faces intact and every face captured in the hand-over dropped (all or nothing). False when
     * no hand-over is running or just finished.
     */
    fun backOut(): Boolean {
        val resume = resumeHold ?: return false
        if (inCorner) return false
        fallback = null
        addedSamples.clear()
        hold = resume.coerceAtMost(QuickScanPlan.holds.size)
        resumeHold = null
        corner.reset()
        return true
    }

    /** Keeps every held face not in [asked], drops the others and starts the Guided viewfinder over the rest. */
    private fun handOver(
        asked: Set<Face>,
        resumeAt: Int,
    ): QuickScanEffect {
        keptSet += samples.keys.filter { it !in asked }
        samples.keys.retainAll(keptSet)
        addedSamples.clear()
        resumeHold = resumeAt
        hold = QuickScanPlan.holds.size + 1
        corner.reset()
        val plan = GuidedScanPlan.steps.filter { it.face !in keptSet }
        if (plan.isEmpty()) return QuickScanEffect.Done(kept)
        fallback =
            GuidedScanController(
                plan = plan,
                allowAdditionalReprompts = false,
                reprompt = { emptyList() },
                known = kept,
                initialDelayMs = HAND_OVER_DELAY_MS,
            )
        return QuickScanEffect.Fallback(plan.map { it.face })
    }

    private fun involvedFaces(merged: List<FaceSample>): List<Face> {
        val calibration = SelfCalibration.from(merged, saved) as? SelfCalibration.Calibrated ?: return emptyList()
        return ScanAssembler.repromptFaces(merged, calibration.calibration, repromptPosed = true)
    }

    /**
     * Faces among [faces] that the Guided grid must read: missing, not ACCEPTABLE, uncalibrated or with an UNCERTAIN
     * sticker. With no calibration yet, a face's stickers are judged against the captured faces' own centers, so only a
     * face with all nine stickers read is kept (AC1; the colors are still produced once, in the combined assembly).
     */
    private fun needGrid(
        merged: List<FaceSample>,
        faces: List<Face>,
    ): List<Face> {
        val calibration = SelfCalibration.from(merged, saved)
        val byFace = merged.associateBy { it.face }
        return faces.filter { face ->
            val sample = byFace[face]
            sample == null || sample.quality != CaptureQuality.ACCEPTABLE ||
                when (calibration) {
                    is SelfCalibration.NeedsCalibration -> {
                        Color.home(face) in calibration.colors ||
                            QuickScanReadout
                                .judgedAgainst(sample, SelfCalibration.trustedCenters(merged))
                                .any { it?.confidence != StickerConfidence.CONFIDENT }
                    }

                    is SelfCalibration.Calibrated -> {
                        QuickScanReadout.judged(sample, calibration.calibration).any { it?.confidence != StickerConfidence.CONFIDENT }
                    }
                }
        }
    }

    /**
     * True once the view shows another corner than the one just taken: the top center (white vs yellow) changed, or,
     * when either frame lacks it, any comparable cell changed or no cell can be compared at all.
     */
    private fun turnedOver(
        now: List<Lab?>,
        prior: List<Lab?>,
    ): Boolean {
        val threshold = GuidedScanController.FACE_CHANGE_DELTA_E
        val top = now[FaceSample.CENTER_INDEX]
        val priorTop = prior[FaceSample.CENTER_INDEX]
        if (top != null && priorTop != null) return StickerClassifier.deltaE(top, priorTop) >= threshold
        val comparable = now.zip(prior).any { (a, b) -> a != null && b != null }
        if (!comparable) return true
        // Never clear the prior hold on a single noisy edge sticker; require >= 2 other face centers to differ.
        val centerIndices =
            listOf(
                FaceSample.CENTER_INDEX,
                FrameQuality.CELLS + FaceSample.CENTER_INDEX,
                2 * FrameQuality.CELLS + FaceSample.CENTER_INDEX,
            )
        val changedCenters =
            centerIndices.count { idx ->
                val a = now.getOrNull(idx)
                val b = prior.getOrNull(idx)
                a != null && b != null && StickerClassifier.deltaE(a, b) >= threshold
            }
        return changedCenters >= 2
    }

    companion object {
        /** The hand-over viewfinder waits this long before its first auto-capture, so the Learner can turn the cube. */
        const val HAND_OVER_DELAY_MS = 2500L
    }
}
