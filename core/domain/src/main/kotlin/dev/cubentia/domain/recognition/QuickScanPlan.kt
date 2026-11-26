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
