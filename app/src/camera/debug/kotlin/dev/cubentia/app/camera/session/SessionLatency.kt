package dev.cubentia.app.camera.session

import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionFormat
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Story 10.34: analyzer-latency statistics and the defective-session rule of a Quick Scan session recording. */
internal object SessionLatency {
    /** Nearest-rank percentile ([p] in 0..100) of [values]; null when empty. */
    fun percentile(
        values: List<Double>,
        p: Double,
    ): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val rank = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    fun p50(values: List<Double>): Double? = percentile(values, 50.0)

    fun p95(values: List<Double>): Double? = percentile(values, 95.0)

    /**
     * Frames the camera dropped before the analyzer saw them (`STRATEGY_KEEP_ONLY_LATEST`), estimated from the sensor
     * [timestampsMs] of consecutive analysed frames: a gap of k median intervals hides k - 1 frames.
     */
    fun droppedFrames(timestampsMs: List<Long>): Int {
        val intervals = timestampsMs.zipWithNext { a, b -> (b - a).toDouble() }.filter { it > 0 }
        val median = percentile(intervals, 50.0) ?: return 0
        if (median <= 0.0) return 0
        return intervals.sumOf { gap -> ((gap / median).roundToInt() - 1).coerceAtLeast(0) }
    }

    /**
     * Why a session is defective: any capture skip, or (with a no-capture [baselineP50Ms] for this device model) a
     * capture p50 more than 10% over it. Empty when it is not.
     */
    fun defectReasons(
        captureSkips: Int,
        p50Ms: Double?,
        baselineP50Ms: Double?,
    ): List<String> =
        listOfNotNull(
            "captureSkips=$captureSkips".takeIf { captureSkips > 0 },
            if (p50Ms != null && baselineP50Ms != null && p50Ms > ContinuousSessionFormat.LATENCY_DEFECT_FACTOR * baselineP50Ms) {
                "capture p50 %.2f ms > 1.10 × baseline p50 %.2f ms".format(java.util.Locale.ROOT, p50Ms, baselineP50Ms)
            } else {
                null
            },
        )
}
