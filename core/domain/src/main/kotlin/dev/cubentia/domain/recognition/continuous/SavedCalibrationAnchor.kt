package dev.cubentia.domain.recognition.continuous

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.QuickScanClassification
import dev.cubentia.domain.recognition.StickerClassifier
import dev.cubentia.domain.recognition.StickerConfidence
import dev.cubentia.domain.recognition.StickerReading

/**
 * Story 10.37 E9 (parity id I1037): the saved-calibration fallback that may anchor the continuous Quick Scan's global
 * orientation from 3-5 centres when the built-in [ContinuousColorModel.REF_CENTER_LAB] anchor fails (a pastel or
 * off-palette cube). Pure and stateless; [ContinuousFaceletMapper] owns when it is consulted (torch changes, six-centre
 * authority, revocation).
 *
 * The centres are compared in CIELAB `standardLab` (the AWB-scaled BT.601 Lab of the robust centre), the saved
 * calibration's own space only approximately (it is unscaled float BT.601). For the best of the 24 orientations:
 * - the average ΔE of the observed centres to the saved centres of the colours that orientation gives them is below
 *   [MAX_MEAN_DELTA_E], and the second-best orientation's average is at least [MIN_MARGIN] higher;
 * - every observed centre reads CONFIDENT as its colour against the saved centres ([StickerClassifier] close-call rule)
 *   and lies within [QuickScanClassification.MAX_CENTRE_DELTA_E] of that colour's saved centre.
 *
 * The torch rule is the Story 10.21 one: the calibration's torch tag must equal the torch lit now, an untagged
 * calibration counting as torch-off.
 */
internal object SavedCalibrationAnchor {
    /** The best orientation's average centre ΔE must be below this. */
    const val MAX_MEAN_DELTA_E = 20.0

    /** The second-best orientation's average ΔE must exceed the best one's by at least this. */
    const val MIN_MARGIN = 8.0

    /**
     * The orientation index (as [ContinuousCubeGeometry.rotateFace]) that [saved] gives the observed [centres] (fragment
     * face to its centre's `standardLab`, null where unreadable), or null when it may not anchor: no or an invalid
     * calibration, a torch-tag mismatch with [torchLit], no centre, an unreadable centre, or a centre or orientation rule
     * failing.
     */
    fun orientation(
        centres: Map<Face, Lab?>,
        saved: CalibrationCenters?,
        torchLit: Boolean,
    ): Int? {
        if (saved == null || !StickerClassifier.isValid(saved)) return null
        if ((saved.torchOn == true) != torchLit) return null
        if (centres.isEmpty()) return null
        val labs = LinkedHashMap<Face, Lab>(centres.size)
        for ((face, lab) in centres) {
            if (lab == null || !lab.isValid()) return null
            labs[face] = lab
        }
        val savedCentres = saved.centers
        var bestCost = Double.POSITIVE_INFINITY
        var secondCost = Double.POSITIVE_INFINITY
        var bestRot = -1
        for (rotIdx in 0 until ContinuousCubeGeometry.SO3_COUNT) {
            var cost = 0.0
            for ((face, lab) in labs) {
                val color = Color.home(ContinuousCubeGeometry.rotateFace(face, rotIdx))
                cost += StickerClassifier.deltaE(lab, savedCentres.getValue(color))
            }
            val avg = cost / labs.size
            if (avg < bestCost) {
                secondCost = bestCost
                bestCost = avg
                bestRot = rotIdx
            } else if (avg < secondCost) {
                secondCost = avg
            }
        }
        if (bestRot < 0 || bestCost >= MAX_MEAN_DELTA_E || secondCost - bestCost < MIN_MARGIN) return null
        val everyCentre =
            labs.all { (face, lab) ->
                val color = Color.home(ContinuousCubeGeometry.rotateFace(face, bestRot))
                val limit = QuickScanClassification.MAX_CENTRE_DELTA_E[color]
                StickerClassifier.read(lab, savedCentres) == StickerReading(color, StickerConfidence.CONFIDENT) &&
                    limit != null &&
                    StickerClassifier.deltaE(lab, savedCentres.getValue(color)) <= limit
            }
        return if (everyCentre) bestRot else null
    }
}
