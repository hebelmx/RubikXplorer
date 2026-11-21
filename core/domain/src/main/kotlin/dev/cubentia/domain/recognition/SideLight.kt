package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import kotlin.math.hypot

/**
 * Story 10.19 light check for a Quick Scan corner: are both side faces lit about as well as the top face? With the
 * light behind the cube the shaded side faces lose their color separation (Red vs Orange), so a corner whose darker side
 * face is far darker than the top is held until the Learner turns toward the light (PO 2026-11-21 D: side faces against
 * the top face only, no background measurement). Numbers only: the camera adapter passes cell L* values, never pixels.
 *
 * Two modes (PO 2026-11-21 E):
 * - [Mode.CALIBRATED], when a saved colour calibration exists (PO 2026-11-21 F): a face's level is its **relative
 *   brightness**, the median over its stickers of (sticker L* divided by the saved L* of that sticker's nearest saved
 *   colour, matched on a*b* only so shading does not change the match). A red or blue side under a white top in good
 *   front light then reads about 1 like the top, so a solved cube is not held. The check fails when the top's relative
 *   brightness divided by the **mean** of the two side faces' relative brightness is above [MAX_TOP_OVER_SIDES_RATIO];
 *   averaging both sides keeps a side-lit hold (one side shaded, the other bright) from being held.
 * - [Mode.UNCALIBRATED], without one: a face's level is the [LEVEL_PERCENTILE] L* of its stickers (the brightest
 *   stickers of a scrambled face stand in for its white point); the check fails when the darker side face is more than
 *   [MAX_SIDE_DARKER_L] below the top face. This can hold a solved or nearly solved cube in front light (accepted by the
 *   PO; the device session measures it).
 */
object SideLight {
    /**
     * Uncalibrated cut, derived from the recorded Lenovo captures in `Fixtures/corner/`
     * (docs/evidence/10-19/light-check-and-replay.md): front-lit holds measure 13.8–25.0, backlit holds 28.6–36.3 (one
     * backlit hold 17.2). 27 passes every front-lit hold and trips at least one hold of every backlit group.
     */
    const val MAX_SIDE_DARKER_L = 27.0

    /**
     * Calibrated cut (PO 2026-11-21 F), derived from the same captures with each group's own recorder calibration
     * (docs/evidence/10-19/light-check-and-replay.md): front-lit holds measure 0.991–1.286, backlit holds 1.337–1.553
     * and every backlit group's worse hold at least 1.392. 1.34 is the midpoint of 1.286 and 1.392: it passes every
     * front-lit hold and trips at least one hold of every backlit group (7 of 8 backlit holds).
     */
    const val MAX_TOP_OVER_SIDES_RATIO = 1.34

    /** The fraction of a face's stickers at or below its uncalibrated light level. */
    const val LEVEL_PERCENTILE = 0.75

    enum class Mode { CALIBRATED, UNCALIBRATED }

    /**
     * One check: [passed] is false only when every face was measured and [score] is above the mode's cut: the darker side
     * face more than [MAX_SIDE_DARKER_L] L* below the top (uncalibrated), or the top's relative brightness over the mean
     * of both sides' above [MAX_TOP_OVER_SIDES_RATIO] (calibrated). The levels are L* (uncalibrated) or relative
     * brightness against the saved calibration (calibrated). [leftDifference] and [rightDifference] are top level minus
     * side level (positive when the side is darker), null when unmeasured; in calibrated mode they are informational only.
     */
    data class Verdict(
        val passed: Boolean,
        val topLevel: Double?,
        val leftLevel: Double?,
        val rightLevel: Double?,
        val mode: Mode = Mode.UNCALIBRATED,
    ) {
        val leftDifference: Double? get() = difference(leftLevel)
        val rightDifference: Double? get() = difference(rightLevel)

        /**
         * The mode's score, compared against its cut: the darker side's difference in L* (uncalibrated), or the top's
         * relative brightness divided by the mean of the two sides' (calibrated); null when a face is unmeasured.
         */
        val score: Double?
            get() =
                when (mode) {
                    Mode.UNCALIBRATED -> darkerSideDifference
                    Mode.CALIBRATED -> topOverSides(topLevel, leftLevel, rightLevel)
                }

        /** How far the darker side face is below the top face; null when a face is unmeasured. */
        val darkerSideDifference: Double?
            get() = listOf(leftDifference, rightDifference).takeIf { all -> all.all { it != null } }?.maxOf { it!! }

        private fun difference(side: Double?): Double? = if (topLevel != null && side != null) topLevel - side else null
    }

    /** A face's uncalibrated light level: the [LEVEL_PERCENTILE] L* (linear interpolation) of [lightness]; null when none is readable. */
    fun level(lightness: List<Double?>): Double? {
        val sorted = lightness.filterNotNull().filter { it.isFinite() }.sorted()
        if (sorted.isEmpty()) return null
        val position = LEVEL_PERCENTILE * (sorted.size - 1)
        val lower = position.toInt()
        val upper = minOf(lower + 1, sorted.size - 1)
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
    }

    /** The saved colour nearest [sticker] on a*b* only (lightness ignored); null when [saved] has no valid center. */
    fun nearestOnChroma(
        sticker: Lab,
        saved: CalibrationCenters,
    ): Color? =
        saved.centers
            .filterValues { it.isValid() }
            .minByOrNull { (_, center) -> hypot(sticker.a - center.a, sticker.b - center.b) }
            ?.key

    /**
     * A face's calibrated level (relative brightness): the median over its valid stickers of (sticker L* divided by the
     * saved L* of its nearest saved colour on a*b*); null when none is readable.
     */
    fun relativeBrightness(
        stickers: List<Lab?>,
        saved: CalibrationCenters,
    ): Double? {
        val offsets =
            stickers
                .filterNotNull()
                .filter { it.isValid() }
                .mapNotNull { sticker ->
                    nearestOnChroma(sticker, saved)
                        ?.let { saved.centers.getValue(it).l }
                        ?.takeIf { it > 0.0 }
                        ?.let { reference -> sticker.l / reference }
                }.sorted()
        if (offsets.isEmpty()) return null
        val middle = offsets.size / 2
        return if (offsets.size % 2 == 1) offsets[middle] else (offsets[middle - 1] + offsets[middle]) / 2
    }

    /** Whether [saved] can drive the calibrated mode. */
    fun calibrated(saved: CalibrationCenters?): Boolean = saved != null && StickerClassifier.isValid(saved)

    /** The uncalibrated check over per-face cell L* in hold order (top, left, right). A face with no readable cell never fails it. */
    fun check(
        top: List<Double?>,
        left: List<Double?>,
        right: List<Double?>,
    ): Verdict = verdict(level(top), level(left), level(right), Mode.UNCALIBRATED)

    /** The check over per-face stickers in hold order: calibrated against [saved] when it is valid, else uncalibrated. */
    fun checkLab(
        top: List<Lab?>,
        left: List<Lab?>,
        right: List<Lab?>,
        saved: CalibrationCenters?,
    ): Verdict =
        if (saved != null && calibrated(saved)) {
            verdict(relativeBrightness(top, saved), relativeBrightness(left, saved), relativeBrightness(right, saved), Mode.CALIBRATED)
        } else {
            check(top.map { it?.l }, left.map { it?.l }, right.map { it?.l })
        }

    /** The check over one corner frame's three faces in hold order (null faces are unmeasured). */
    fun check(
        faces: List<FrameStats?>,
        saved: CalibrationCenters? = null,
    ): Verdict {
        require(faces.size == 3)
        val stickers = faces.map { face -> face?.cells?.map { it?.value }.orEmpty() }
        return checkLab(stickers[0], stickers[1], stickers[2], saved)
    }

    /** The check over one recorded or captured corner's three samples in hold order. */
    fun checkSamples(
        samples: List<FaceSample?>,
        saved: CalibrationCenters? = null,
    ): Verdict {
        require(samples.size == 3)
        val stickers = samples.map { sample -> sample?.stickers.orEmpty() }
        return checkLab(stickers[0], stickers[1], stickers[2], saved)
    }

    private fun verdict(
        topLevel: Double?,
        leftLevel: Double?,
        rightLevel: Double?,
        mode: Mode,
    ): Verdict {
        val verdict = Verdict(true, topLevel, leftLevel, rightLevel, mode)
        val score = verdict.score ?: return verdict
        val cut = if (mode == Mode.CALIBRATED) MAX_TOP_OVER_SIDES_RATIO else MAX_SIDE_DARKER_L
        return verdict.copy(passed = score <= cut)
    }

    /** The top's level divided by the mean of the two sides' levels; null when one is unmeasured or the sides are not positive. */
    private fun topOverSides(
        topLevel: Double?,
        leftLevel: Double?,
        rightLevel: Double?,
    ): Double? {
        if (topLevel == null || leftLevel == null || rightLevel == null) return null
        val sides = (leftLevel + rightLevel) / 2
        return if (sides > 0.0) topLevel / sides else null
    }
}
