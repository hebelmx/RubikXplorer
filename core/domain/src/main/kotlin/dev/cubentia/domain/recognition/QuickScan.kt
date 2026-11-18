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

/** One analyzer-thread state machine over neutral readings and the Guided fallback controller. */
class QuickScanController(
    private val saved: CalibrationCenters? = null,
) {
    private val samples = linkedMapOf<Face, FaceSample>()
    private var latest = List<FrameStats?>(3) { null }
    private var previous: List<Lab?>? = null
    private var unlocated = false
    private var meteredAt: Long? = null
    private var reference: List<Lab?>? = null
    private var steadySince = 0L
    var hold = 1
        private set
    var coaching: Coaching? = null
        private set
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
        require(faces.size == 3)
        latest = faces
        unlocated = !detected
        val values = values()
        if (detected) {
            previous?.let {
                if (!turnedOver(values, it)) return emptyList()
                previous = null
            }
        }
        val qualities = latest.map { it?.let(FrameQuality::assessShutter) }
        coaching =
            when {
                CaptureQuality.LOW_LIGHT in qualities -> Coaching.LOW_LIGHT
                CaptureQuality.GLARE in qualities -> Coaching.GLARE
                CaptureQuality.BLURRED in qualities -> Coaching.BLURRED
                else -> null
            }
        if (!detected) {
            if (hold == 2) latest = List(3) { null }
            meteredAt = null
            reference = null
            return emptyList()
        }
        val metered = meteredAt
        if (metered == null) {
            meteredAt = nowMs
            return listOf(QuickScanEffect.Remeter)
        }
        if (qualities.any { it != CaptureQuality.ACCEPTABLE } || nowMs - metered < GuidedScanController.SETTLE_MS) {
            reference = null
            return emptyList()
        }
        val baseline = reference
        if (baseline == null || changed(values, baseline, GuidedScanController.STEADY_DELTA_E)) {
            reference = values
            steadySince = nowMs
            return emptyList()
        }
        return if (nowMs - steadySince >= GuidedScanController.STEADY_MS) onShutter() else emptyList()
    }

    fun onShutter(): List<QuickScanEffect> {
        if (!inCorner || (previous != null && !unlocated)) return emptyList()
        val taken = hold++
        QuickScanPlan.holds[taken - 1].zip(latest).forEach { (step, stats) ->
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
                values().takeIf { it.any { value -> value != null } }
        }
        latest = List(3) { null }
        unlocated = false
        meteredAt = null
        reference = null
        coaching = null
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
        if (effects.any { it is GuidedScanEffect.Done }) {
            fallback = null
            return finish()
        }
        return if (grid.repeating && grid.repromptIndex == 0) QuickScanEffect.Fallback(repromptFaces, 2) else null
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
                reprompt = { replacements ->
                    if (round == 1) {
                        val merged = (samples + replacements.associateBy { it.face }).values.toList()
                        // Corner faces kept from the first pass are checked again once the merged scan can calibrate.
                        val retained = Face.entries.filter { it !in fallbackFaces }
                        (needGrid(merged, retained) + involvedFaces(merged)).distinct().also { repromptFaces = it }
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
                        sample.stickers
                            .filterIndexed { index, _ -> index != FaceSample.CENTER_INDEX || sample.centerPlain != false }
                            .any {
                                val reading = StickerClassifier.classify(it, calibration.calibration) as? StickerClassification.Classified
                                reading?.reading?.confidence != StickerConfidence.CONFIDENT
                            }
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
        return !comparable || changed(now, prior, threshold)
    }

    private fun finish(): QuickScanEffect = QuickScanEffect.Done(captured)

    private fun values(): List<Lab?> = latest.flatMap { face -> List(FrameQuality.CELLS) { face?.cells?.getOrNull(it)?.value } }

    private fun changed(
        now: List<Lab?>,
        prior: List<Lab?>,
        threshold: Double,
    ): Boolean = now.zip(prior).any { (a, b) -> a != null && b != null && StickerClassifier.deltaE(a, b) >= threshold }
}
