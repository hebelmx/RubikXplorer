package dev.rubikxplorer.app.render.gl

import dev.rubikxplorer.app.render.PresentationItem

/** Pure FIFO. One completion per frame preserves every intermediate committed geometry. */
class PresentationQueue(
    val model: CubieModel = CubieModel(),
) {
    private val waiting = ArrayDeque<PresentationItem>()
    private var current: PresentationItem.Animate? = null
    private var started = 0L
    private var duration = 0L

    @get:Synchronized
    val isBusy: Boolean get() = current != null || waiting.isNotEmpty()

    /** The item animating now; null between items and when idle. */
    @get:Synchronized
    val active: PresentationItem.Animate? get() = current

    @Synchronized
    fun submit(items: List<PresentationItem>) {
        waiting.addAll(items)
    }

    @Synchronized
    fun onFrame(nanos: Long) {
        if (current != null) {
            val progress = (nanos - started).toFloat() / duration
            model.setProgress(progress)
            if (progress >= 1f) finishCurrent()
            return
        }
        when (val item = waiting.removeFirstOrNull() ?: return) {
            is PresentationItem.Snap -> {
                model.reset(item.target)
            }

            is PresentationItem.Animate -> {
                current = item
                started = nanos
                duration = TURN_MILLIS * 1_000_000L / if (waiting.size >= FAST_FORWARD_AFTER) FAST_FORWARD_DIVISOR else 1
                model.begin(item.move)
            }
        }
    }

    /** Cancellation completes only the active item; waiting items survive recomposition. */
    @Synchronized
    fun finishCurrent() {
        if (current != null) model.commit()
        current = null
    }

    companion object {
        const val TURN_MILLIS = 200
        const val FAST_FORWARD_AFTER = 3
        const val FAST_FORWARD_DIVISOR = 4
    }
}
