package dev.cubentia.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor

/** Rejects captures with too few UI frames from the target process's main thread. */
@OptIn(ExperimentalMetricApi::class)
class MinFrameCountMetric(
    private val minFrames: Int,
) : TraceMetric() {
    override fun getMeasurements(
        captureInfo: CaptureInfo,
        traceSession: TraceProcessor.Session,
    ): List<Measurement> {
        val pkg = captureInfo.targetPackageName
        val count =
            traceSession
                .query(
                    """
                    SELECT COUNT(*) AS frame_count FROM slice
                    JOIN thread_track ON slice.track_id = thread_track.id
                    JOIN thread USING(utid)
                    JOIN process USING(upid)
                    WHERE process.name = '$pkg' AND thread.tid = process.pid
                      AND slice.name LIKE 'Choreographer#doFrame%'
                      AND slice.name NOT LIKE '%- resynced to%'
                    """.trimIndent(),
                ).single()
                .long("frame_count")
        check(count >= minFrames) { "Captured $count main-thread UI frames for $pkg; minimum is $minFrames" }
        return listOf(Measurement("uiFrameCount", count.toDouble()))
    }
}
