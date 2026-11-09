package dev.cubentia.application.freecube

import dev.cubentia.domain.cube.MoveHistory

/** Unpersisted free-cube history and presentation revision, separate from Learning Sessions. */
data class FreeCubeState(
    val history: MoveHistory = MoveHistory(),
    val revision: Long = 0L,
)
