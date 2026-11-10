package dev.cubentia.domain.content

import kotlin.time.Instant

data class AlgorithmStatusEvent(
    val algorithmId: AlgorithmId,
    val status: AlgorithmStatus,
    val at: Instant,
)

/** Events are oldest first; the last event for each Algorithm wins. */
object AlgorithmStatuses {
    fun current(events: List<AlgorithmStatusEvent>): Map<AlgorithmId, AlgorithmStatus> = events.associate { it.algorithmId to it.status }
}
