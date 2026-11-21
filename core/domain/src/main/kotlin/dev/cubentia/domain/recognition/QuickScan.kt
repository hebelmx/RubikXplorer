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

    data class Fallback(
        val faces: List<Face>,
        val round: Int,
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

/** One analyzer-thread state machine over neutral readings and the Guided fallback controller. */
class QuickScanController(
    private val saved: CalibrationCenters? = null,
) {
    private val samples = linkedMapOf<Face, FaceSample>()
    private val gridFaces = mutableSetOf<Face>()
    private val corner = CornerHold()
    private var previous: List<Lab?>? = null
    var hold = 1
        private set
    val coaching: Coaching? get() = corner.coaching
    var fallback: GuidedScanController? = null
        private set
    var fallbackFaces: List<Face> = emptyList()
        private set
    var repromptFaces: List<Face> = emptyList()
        private set

    val captured: List<FaceSample> get() = samples.values.toList()
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
            if (cells.size == 9) {
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
        fallbackFaces = needGrid(captured, Face.entries)
        return listOf(capture, if (fallbackFaces.isEmpty()) validate() else startFallback(fallbackFaces, 1))
    }

    /** Each Guided capture replaces just its own face in the corner scan. */
    fun onGuidedEffects(effects: List<GuidedScanEffect>): QuickScanEffect? {
        val grid = fallback ?: return null
        val capture = effects.filterIsInstance<GuidedScanEffect.Capture>().firstOrNull() ?: return null
        samples[capture.sample.face] = capture.sample
        gridFaces += capture.sample.face
        if (effects.any { it is GuidedScanEffect.Done }) {
            fallback = null
            return finish()
        }
        return if (grid.repeating && grid.repromptIndex == 0) QuickScanEffect.Fallback(repromptFaces, 2) else null
    }

    /** Re-asked faces merge in the corner frame. Keep their named tops unless freeing them resolves an invalid merge. */
    private fun oriented(
        merged: List<FaceSample>,
        gridFaces: Set<Face>,
    ): List<FaceSample> {
        if (gridFaces.isEmpty()) return merged
        val calibration = SelfCalibration.from(merged, saved) as? SelfCalibration.Calibrated ?: return merged
        if (ScanAssembler.trace(merged, calibration.calibration).branch == ScanBranch.RESOLVED) return merged
        val unposed = merged.map { if (it.face in gridFaces) it.copy(upNeighborColor = null) else it }
        return if (ScanAssembler.trace(unposed, calibration.calibration).branch == ScanBranch.RESOLVED) unposed else merged
    }

    private fun involvedFaces(merged: List<FaceSample>): List<Face> {
        val calibration = SelfCalibration.from(merged, saved) as? SelfCalibration.Calibrated ?: return emptyList()
        return ScanAssembler.repromptFaces(merged, calibration.calibration, repromptPosed = true)
    }

    private fun validate(): QuickScanEffect {
        repromptFaces = involvedFaces(captured)
        return if (repromptFaces.isEmpty()) finish() else startFallback(repromptFaces, 2)
    }

    private fun startFallback(
        faces: List<Face>,
        round: Int,
    ): QuickScanEffect {
        fallback =
            GuidedScanController(
                plan = faces.map(GuidedScanPlan::reprompt),
                allowAdditionalReprompts = round == 1,
                known = samples.values.filter { it.face !in faces },
                initialDelayMs = if (round == 1) 2500L else 0L,
                reprompt = { replacements ->
                    if (round == 1) {
                        val merged = (samples + replacements.associateBy { it.face }).values.toList()
                        // Corner faces kept from the first pass are checked again once the merged scan can calibrate.
                        val retained = Face.entries.filter { it !in fallbackFaces }
                        // The callback runs before the last replacement reaches onGuidedEffects.
                        val orientedMerge = oriented(merged, gridFaces + replacements.map { it.face })
                        (needGrid(merged, retained) + involvedFaces(orientedMerge)).distinct().also { repromptFaces = it }
                    } else {
                        emptyList()
                    }
                },
            )
        return QuickScanEffect.Fallback(faces, round)
    }

    /** Faces among [faces] that the Guided grid must read: missing, not ACCEPTABLE, uncalibrated or with an UNCERTAIN sticker. */
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
                        Color.home(face) in calibration.colors
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

    private fun finish(): QuickScanEffect = QuickScanEffect.Done(oriented(captured, gridFaces))
}
