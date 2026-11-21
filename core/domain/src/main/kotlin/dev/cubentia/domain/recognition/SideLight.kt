package dev.cubentia.domain.recognition

/**
 * Story 10.19 light check for a Quick Scan corner: are both side faces lit about as well as the top face? With the
 * light behind the cube the shaded side faces lose their color separation (Red vs Orange), so a corner whose darker side
 * face is far darker than the top is held until the Learner turns toward the light (PO 2026-11-21 D: side faces against
 * the top face only, no background measurement).
 *
 * A face's light level is the 75th-percentile L* of its readable stickers (the brightest stickers of a scrambled face
 * stand in for its white point). The check fails when the darker side face is more than [MAX_SIDE_DARKER_L] below the
 * top face. Numbers only: the camera adapter passes cell L* values, never pixels.
 */
object SideLight {
    /**
     * Derived from the recorded Lenovo captures in `Fixtures/corner/` (docs/evidence/10-19/light-check-and-replay.md):
     * front-lit holds measure 13.8–25.0, backlit holds 28.6–36.3 (one backlit hold 17.2). 27 passes every front-lit hold
     * and trips at least one hold of every backlit group.
     */
    const val MAX_SIDE_DARKER_L = 27.0

    /** The fraction of a face's stickers at or below its light level. */
    const val LEVEL_PERCENTILE = 0.75

    /**
     * One check: [passed] is false only when every face was measured and the darker side is too dark; [leftDifference]
     * and [rightDifference] are top level minus side level (positive when the side is darker), null when unmeasured.
     */
    data class Verdict(
        val passed: Boolean,
        val topLevel: Double?,
        val leftLevel: Double?,
        val rightLevel: Double?,
    ) {
        val leftDifference: Double? get() = difference(leftLevel)
        val rightDifference: Double? get() = difference(rightLevel)

        /** How far the darker side face is below the top face; null when a face is unmeasured. */
        val darkerSideDifference: Double?
            get() = listOf(leftDifference, rightDifference).takeIf { all -> all.all { it != null } }?.maxOf { it!! }

        private fun difference(side: Double?): Double? = if (topLevel != null && side != null) topLevel - side else null
    }

    /** A face's light level: the [LEVEL_PERCENTILE] L* (linear interpolation) of [lightness]; null when none is readable. */
    fun level(lightness: List<Double?>): Double? {
        val sorted = lightness.filterNotNull().filter { it.isFinite() }.sorted()
        if (sorted.isEmpty()) return null
        val position = LEVEL_PERCENTILE * (sorted.size - 1)
        val lower = position.toInt()
        val upper = minOf(lower + 1, sorted.size - 1)
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
    }

    /** The check over per-face cell L* in hold order (top, left, right). A face with no readable cell never fails it. */
    fun check(
        top: List<Double?>,
        left: List<Double?>,
        right: List<Double?>,
    ): Verdict {
        val topLevel = level(top)
        val leftLevel = level(left)
        val rightLevel = level(right)
        val darker = listOfNotNull(leftLevel, rightLevel).minOrNull()
        val passed = topLevel == null || leftLevel == null || rightLevel == null || topLevel - darker!! <= MAX_SIDE_DARKER_L
        return Verdict(passed, topLevel, leftLevel, rightLevel)
    }

    /** The check over one corner frame's three faces in hold order (null faces are unmeasured). */
    fun check(faces: List<FrameStats?>): Verdict {
        require(faces.size == 3)
        val lightness = faces.map { face -> face?.cells?.map { it?.value?.l }.orEmpty() }
        return check(lightness[0], lightness[1], lightness[2])
    }

    /** The check over one recorded or captured corner's three samples in hold order. */
    fun checkSamples(samples: List<FaceSample?>): Verdict {
        require(samples.size == 3)
        val lightness = samples.map { sample -> sample?.stickers?.map { it.l }.orEmpty() }
        return check(lightness[0], lightness[1], lightness[2])
    }
}
