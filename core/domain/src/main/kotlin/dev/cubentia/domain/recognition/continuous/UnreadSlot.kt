package dev.cubentia.domain.recognition.continuous

/**
 * GH #405: why one non-centre canonical net slot of [ContinuousFaceletMapper] holds no colour after its latest colour
 * solve. Numbers and the slot only (AD-13): never pixels or frames. Read through [ContinuousFaceletMapper.unreadSlots].
 */
sealed interface UnreadSlot {
    val slot: FaceSlot

    /**
     * The slot's master cell has no robust reading: its face was never seen, the cell has no observations, or the cell's
     * two strongest readings contradict each other (more than 18 apart), so no robust value is defined.
     */
    data class NotObserved(
        override val slot: FaceSlot,
    ) : UnreadSlot

    /** A robust reading with too little evidence: [qualitySum] below [MIN_READY_QUALITY_SUM] or [count] below [MIN_READY_COUNT]. */
    data class NotReady(
        override val slot: FaceSlot,
        val qualitySum: Double,
        val count: Int,
    ) : UnreadSlot

    /**
     * Ready, read by the per-cell branch (fewer than 48 ready cells), but its nearest prototype distance [bestD] is not
     * below [MAX_PER_CELL_DISTANCE] or its [margin] to the second nearest is below [MIN_PER_CELL_MARGIN].
     */
    data class BelowMargin(
        override val slot: FaceSlot,
        val bestD: Double,
        val margin: Double,
    ) : UnreadSlot

    /** Ready, read by the balanced 8-per-colour branch (48 ready cells), but its [chosenD] is not below [MAX_BALANCED_DISTANCE]. */
    data class AboveDistance(
        override val slot: FaceSlot,
        val chosenD: Double,
    ) : UnreadSlot

    /** Committed by the solve, then cleared by the local-invariant reopening loop. */
    data class Reopened(
        override val slot: FaceSlot,
    ) : UnreadSlot

    /** The thresholds the colour solve applies; the solve reads these very constants. */
    companion object {
        /** A cell is ready only with at least this robust quality sum. */
        const val MIN_READY_QUALITY_SUM = 0.55

        /** A cell is ready only with at least this many robust observations. */
        const val MIN_READY_COUNT = 2

        /** Balanced branch: the assigned colour's distance must be below this. */
        const val MAX_BALANCED_DISTANCE = 55.0

        /** Per-cell branch: the nearest colour's distance must be below this. */
        const val MAX_PER_CELL_DISTANCE = 36.0

        /** Per-cell branch: the second nearest colour must be at least this much farther. */
        const val MIN_PER_CELL_MARGIN = 8.0
    }
}
