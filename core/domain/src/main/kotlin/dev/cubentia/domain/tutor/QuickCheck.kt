package dev.cubentia.domain.tutor

import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.PhysicalObservation

/** Plans home faces; larger coverage requires a Guided 6-face Scan. */
object QuickCheck {
    const val MAX_CAPTURES = PhysicalObservation.QUICK_CHECK_MAX_CAPTURES

    fun faces(mustCover: Set<Face>): Set<Face> =
        when (mustCover.size) {
            0 -> setOf(Face.U, Face.F)
            1 -> mustCover + Face.entries.first { it !in mustCover && it != mustCover.single().opposite }
            2 -> mustCover.toSet()
            else -> Face.entries.toSet()
        }
}
