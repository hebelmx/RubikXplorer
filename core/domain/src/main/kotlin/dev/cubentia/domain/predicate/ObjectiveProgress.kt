package dev.cubentia.domain.predicate

/**
 * Progress toward an Objective: [done] of [total] steps. Only measures of the same [total] compare;
 * comparing different totals is a programmer error.
 */
data class ObjectiveProgress(
    val done: Int,
    val total: Int,
) : Comparable<ObjectiveProgress> {
    init {
        require(total > 0) { "total must be positive, was $total" }
        require(done in 0..total) { "done must be in 0..$total, was $done" }
    }

    override fun compareTo(other: ObjectiveProgress): Int {
        require(total == other.total) { "cannot compare progress out of $total with progress out of ${other.total}" }
        return done.compareTo(other.done)
    }
}
