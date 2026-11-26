package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color

/** The named top, front-left and front-right poses, in the order the adapter returns them. */
object QuickScanPlan {
    val holds =
        listOf(
            listOf(ScanStep(Color.WHITE, Color.BLUE), ScanStep(Color.GREEN, Color.WHITE), ScanStep(Color.RED, Color.WHITE)),
            listOf(ScanStep(Color.YELLOW, Color.RED), ScanStep(Color.ORANGE, Color.YELLOW), ScanStep(Color.BLUE, Color.YELLOW)),
        )
}

data class CenterCheck(
    val hold: Int,
    val deltaE: List<Double?>,
) {
    val blocks: Boolean
        get() {
            val compared = deltaE.filterNotNull()
            return compared.size >= MIN_CENTRES && compared.all { it < POSE_MATCH_DELTA_E }
        }

    companion object {
        /** Two centre cells closer than this (CIE76) are the same sticker seen again: the pose did not change. */
        const val POSE_MATCH_DELTA_E = 11.0

        /** The fewest comparable (plain) centres the check needs to block. */
        const val MIN_CENTRES = 2

        /** The check of [faces] at [hold] against [other], the other hold's captured centres; null when none was captured. */
        fun of(
            hold: Int,
            faces: List<FrameStats?>,
            other: List<Lab?>?,
        ): CenterCheck? {
            other ?: return null
            val now = centers(faces)
            return CenterCheck(
                hold,
                now.zip(other).map { (a, b) -> if (a != null && b != null) StickerClassifier.deltaE(a, b) else null },
            )
        }

        /** The centre cell of each of [faces], null where missing, invalid or not plain (a logo). */
        fun centers(faces: List<FrameStats?>): List<Lab?> =
            faces.map { stats ->
                stats
                    ?.cells
                    ?.getOrNull(FaceSample.CENTER_INDEX)
                    ?.takeIf { it.plain }
                    ?.value
                    ?.takeIf { it.isValid() }
            }
    }
}
