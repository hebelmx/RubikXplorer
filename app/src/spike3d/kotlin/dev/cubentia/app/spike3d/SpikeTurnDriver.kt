package dev.cubentia.app.spike3d

import dev.cubentia.app.render.PresentationItem
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Facelets

/** Frame-interval statistics for one finished turn (timing only: no Moves, no facelets). */
data class TurnStats(
    val fastForward: Boolean,
    val elapsedMillis: Double,
    val frames: Int,
    val p50: Double,
    val p90: Double,
    val p99: Double,
    val max: Double,
    val over33: Int,
    val readbackMatchesTarget: Boolean,
)

/**
 * OQ-3 spike, throwaway: the presentation queue both prototypes share, with `Net2DRenderer`'s
 * semantics (FIFO, nothing dropped, fast-forward while at least [FAST_FORWARD_AFTER] items wait
 * behind the current one), driven by the caller's frame clock through [onFrame]. A face-turn
 * [PresentationItem.Animate] runs over [TURN_MILLIS]; any other item snaps the model to its target.
 * Thread-safe: renderers call [onFrame] on their render thread, the UI calls [submit].
 */
class SpikeTurnDriver(
    val model: SpikeCubeModel = SpikeCubeModel(),
    private val onTurnFinished: (TurnStats) -> Unit = {},
) {
    private val queue = ArrayDeque<PresentationItem>()
    private var current: PresentationItem.Animate? = null
    private var fast = false
    private var startNanos = 0L
    private var durationNanos = 0L
    private var lastFrameNanos = 0L
    private val intervals = ArrayList<Double>()

    @Synchronized
    fun submit(items: List<PresentationItem>) {
        queue.addAll(items)
    }

    /** Facelets read back from the model's committed geometry. */
    @Synchronized
    fun facelets(): Facelets = model.facelets()

    /** Runs [block] on the model under the driver's lock (for drawing). */
    @Synchronized
    fun <T> withModel(block: (SpikeCubeModel) -> T): T = block(model)

    /** True while a turn is animating or items are queued. */
    @Synchronized
    fun isBusy(): Boolean = current != null || queue.isNotEmpty()

    /** Advances the queue to frame time [nowNanos]; call once per drawn frame. */
    @Synchronized
    fun onFrame(nowNanos: Long) {
        val active = current
        if (active != null) {
            intervals += (nowNanos - lastFrameNanos) / NANOS_PER_MILLI
            lastFrameNanos = nowNanos
            val progress = ((nowNanos - startNanos).toDouble() / durationNanos).toFloat()
            if (progress >= 1f) finish(active, nowNanos) else model.setProgress(progress)
            if (current != null) return
        }
        startNext(nowNanos)
    }

    private fun startNext(nowNanos: Long) {
        while (queue.isNotEmpty()) {
            val item = queue.removeFirst()
            val move = (item as? PresentationItem.Animate)?.move
            if (move is FaceTurn) {
                current = item
                fast = queue.size >= FAST_FORWARD_AFTER
                durationNanos = (if (fast) TURN_MILLIS / FAST_FORWARD_DIVISOR else TURN_MILLIS) * NANOS_PER_MILLI.toLong()
                startNanos = nowNanos
                lastFrameNanos = nowNanos
                intervals.clear()
                model.beginTurn(move)
                return
            }
            // Snap, or a Move the spike does not animate: jump to the committed target.
            model.reset(item.target)
        }
    }

    private fun finish(
        active: PresentationItem.Animate,
        nowNanos: Long,
    ) {
        model.setProgress(1f)
        model.commit()
        current = null
        val sorted = intervals.sorted()
        onTurnFinished(
            TurnStats(
                fastForward = fast,
                elapsedMillis = (nowNanos - startNanos) / NANOS_PER_MILLI,
                frames = sorted.size,
                p50 = sorted.percentile(P50),
                p90 = sorted.percentile(P90),
                p99 = sorted.percentile(P99),
                max = sorted.lastOrNull() ?: 0.0,
                over33 = sorted.count { it > FRAME_BUDGET_MILLIS },
                readbackMatchesTarget = model.facelets() == active.target,
            ),
        )
    }

    companion object {
        /** Epic 5 budget for one quarter turn. */
        const val TURN_MILLIS = 250L
        const val FAST_FORWARD_AFTER = 3
        const val FAST_FORWARD_DIVISOR = 4
        const val FRAME_BUDGET_MILLIS = 1000.0 / 30.0
        private const val NANOS_PER_MILLI = 1_000_000.0
        private const val P50 = 0.50
        private const val P90 = 0.90
        private const val P99 = 0.99

        /** Nearest-rank percentile of an ascending list; 0 when empty. */
        fun List<Double>.percentile(p: Double): Double {
            if (isEmpty()) return 0.0
            val rank =
                kotlin.math
                    .ceil(p * size)
                    .toInt()
                    .coerceIn(1, size)
            return this[rank - 1]
        }
    }
}
