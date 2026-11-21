package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face

/**
 * Quick-Scan-only sticker classification with an agreement gate (Story 10.19). The Guided 6-face Scan never uses it;
 * [SelfCalibration] and [StickerClassifier] keep their semantics and are only reused.
 *
 * Every sticker gets two reads, and it is CONFIDENT only when both are CONFIDENT and name the same color; otherwise it is
 * UNCERTAIN (its face goes to the Guided grid). The gate only ever lowers confidence, so a mismatched calibration ends
 * in uncertainty, never in a confident wrong sticker that the self-calibration read would not also give.
 * - The **self-calibration read** is the one Quick Scan used before: [SelfCalibration.from] over the captured faces
 *   (with the saved calibration filling guarded or missing colors), or, while some colors have no sample yet, the
 *   captured faces' own trusted centers.
 * - With a **saved calibration**, the second read is a per-face correction against it: the face's own center sticker
 *   is a known color, so the L* gain that maps it to the saved center of that color is applied to all of the face's
 *   stickers, which are then read against the saved centers by the 10 ΔE close-call rule.
 * - With **no saved calibration**, the second read uses the same centers after per-face light normalization: each
 *   center's L* is scaled by this face's light level over the light level of the face that center came from
 *   ([SideLight.level]).
 */
object QuickScanClassification {
    /** The self-calibration the Quick Scan path reads with, null while some colors have no sample. */
    fun calibration(
        merged: List<FaceSample>,
        saved: CalibrationCenters?,
    ): CalibrationCenters? = (SelfCalibration.from(merged, saved) as? SelfCalibration.Calibrated)?.calibration

    /** One gated reading per sticker of [sample] (all nine), null where no read is possible. */
    fun read(
        sample: FaceSample,
        merged: List<FaceSample>,
        saved: CalibrationCenters?,
    ): List<StickerReading?> {
        val self = SelfCalibration.from(merged, saved)
        val centers: Map<Color, Lab> =
            when (self) {
                is SelfCalibration.Calibrated -> self.calibration.centers
                is SelfCalibration.NeedsCalibration -> SelfCalibration.trustedCenters(merged)
            }
        val first = readAll(sample.stickers, centers)
        val second =
            if (saved != null && StickerClassifier.isValid(saved)) {
                readAll(corrected(sample, saved), saved.centers)
            } else {
                readAll(sample.stickers, normalized(sample, merged, centers))
            }
        return first.zip(second).map { (a, b) -> gate(a, b) }
    }

    /** The readings the fallback decision judges: all but a guarded (not plain) center, which marks no face for the grid. */
    fun judged(
        sample: FaceSample,
        merged: List<FaceSample>,
        saved: CalibrationCenters?,
    ): List<StickerReading?> =
        read(sample, merged, saved).filterIndexed { index, _ -> index != FaceSample.CENTER_INDEX || sample.centerPlain != false }

    /**
     * Faces among [faces] that the Guided grid must read: missing, not ACCEPTABLE, whose own color has no usable center
     * yet, or with any judged sticker not CONFIDENT after the gate.
     */
    fun needGrid(
        merged: List<FaceSample>,
        faces: List<Face>,
        saved: CalibrationCenters?,
    ): List<Face> {
        val self = SelfCalibration.from(merged, saved)
        val byFace = merged.associateBy { it.face }
        return faces.filter { face ->
            val sample = byFace[face]
            sample == null ||
                sample.quality != CaptureQuality.ACCEPTABLE ||
                (self is SelfCalibration.NeedsCalibration && Color.home(face) in self.colors) ||
                judged(sample, merged, saved).any { it?.confidence != StickerConfidence.CONFIDENT }
        }
    }

    private fun gate(
        first: StickerReading?,
        second: StickerReading?,
    ): StickerReading? {
        first ?: return null
        val agree =
            second != null &&
                first.confidence == StickerConfidence.CONFIDENT &&
                second.confidence == StickerConfidence.CONFIDENT &&
                first.color == second.color
        return if (agree) first else first.copy(confidence = StickerConfidence.UNCERTAIN)
    }

    private fun readAll(
        stickers: List<Lab>,
        centers: Map<Color, Lab>,
    ): List<StickerReading?> =
        stickers.map { sticker ->
            if (centers.size >= 2 && sticker.isValid() &&
                centers.values.all { it.isValid() }
            ) {
                StickerClassifier.read(sticker, centers)
            } else {
                null
            }
        }

    /** The face's stickers with the L* gain that maps its own (plain) center onto the saved center of that color. */
    private fun corrected(
        sample: FaceSample,
        saved: CalibrationCenters,
    ): List<Lab> {
        val own = sample.center
        val target = saved.centers[sample.centerColor ?: Color.home(sample.face)]
        if (own == null || target == null || sample.centerPlain == false || !own.isValid() || own.l <= 0.0) return sample.stickers
        val gain = target.l / own.l
        return sample.stickers.map { it.copy(l = (it.l * gain).coerceIn(0.0, 100.0)) }
    }

    /** [centers] with each L* scaled by this face's light level over the level of the face the center came from. */
    private fun normalized(
        sample: FaceSample,
        merged: List<FaceSample>,
        centers: Map<Color, Lab>,
    ): Map<Color, Lab> {
        val level = SideLight.level(sample.stickers.map { it.l }) ?: return centers
        return centers.mapValues { (color, center) ->
            val source = merged.singleOrNull { it.centerColor == color && it.center == center }
            val sourceLevel = source?.let { SideLight.level(it.stickers.map { sticker -> sticker.l }) }
            if (sourceLevel == null ||
                sourceLevel <= 0.0
            ) {
                center
            } else {
                center.copy(l = (center.l * level / sourceLevel).coerceIn(0.0, 100.0))
            }
        }
    }
}
