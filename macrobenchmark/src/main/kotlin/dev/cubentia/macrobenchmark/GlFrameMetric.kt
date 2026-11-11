package dev.cubentia.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor
import kotlin.math.ceil

/**
 * GL frame rate and input-to-frame latency of the production `Cube3DRenderer` (Story 12.2, NFR-1/2),
 * read from the trace sections the app emits: `Cube3D.drawFrame` around every GL-thread
 * `onDrawFrame`, the async `Cube3D.turn` from a turn becoming active until it finishes, and
 * `Cube3D.turnVisible` at the first publish that draws the turn partway (progress > 0). Input is the
 * target process's main-thread `deliverInputEvent…` slices. `FrameTimingMetric` cannot see
 * `GLSurfaceView` frames, so this metric reads them directly.
 *
 * Windows per [Mode]:
 * - [Mode.FACE_TURNS]: each `Cube3D.turn` section is one window; its frames are the `drawFrame`
 *   slices starting inside it. Latency runs from the tap's input UP (the last input slice before the
 *   turn starts) to the end of the first `drawFrame` that starts after the turn's `turnVisible`
 *   marker. The activation frame draws progress 0 (no visible change), so this is conservative by
 *   one vsync.
 * - [Mode.VIEW_ROTATION]: each drag (input slices with gaps under [GESTURE_GAP_NS]) is one window,
 *   from its first to its last MOVE (every slice between the DOWN and the UP). Latency runs from the
 *   first MOVE to the end of the first `drawFrame` that starts after it.
 *
 * An input slice's time is the `eventTimeNano` in its name (CLOCK_MONOTONIC), mapped to the trace
 * clock through the trace's clock snapshot; when the name has none, or the mapped time is not within
 * 1 s before the slice start, the slice start is used and counted in `inputTimeFromSliceStart`.
 *
 * Per iteration it reports `glFrameCount`, `glFps` ((frames − 1) ÷ span over every window, pooled),
 * `glFpsWorstWindow`, frame-start interval p50/p90/p99/max, `glFramesOver33ms` (intervals above
 * 33.3 ms), `inputToFrameP50Ms` / `inputToFrameMaxMs`, plus the pooled samples `glFrameIntervalMs`
 * and `inputToFrameMs`. It throws, naming the missing slice, when a required slice is absent, so no
 * iteration is ever counted silently.
 */
@OptIn(ExperimentalMetricApi::class)
class GlFrameMetric(
    private val mode: Mode,
    private val expectedWindows: Int,
) : TraceMetric() {
    enum class Mode { FACE_TURNS, VIEW_ROTATION }

    override fun getMeasurements(
        captureInfo: CaptureInfo,
        traceSession: TraceProcessor.Session,
    ): List<Measurement> {
        val pkg = captureInfo.targetPackageName
        val frames =
            threadSlices(traceSession, pkg, "name = '$DRAW_FRAME'", mainOnly = false)
                .filter { it.dur >= 0 }
                .map { Span(it.ts, it.ts + it.dur) }
        check(frames.isNotEmpty()) { "No \"$DRAW_FRAME\" slice in the trace of $pkg" }
        val offset = monotonicToTraceOffset(traceSession)
        var fallbacks = 0
        val inputs =
            threadSlices(traceSession, pkg, "name LIKE 'deliverInputEvent%'", mainOnly = true).map { slice ->
                val mapped = eventTime(slice.name)?.let { mono -> offset?.let { mono + it } }
                val time =
                    if (mapped != null && mapped <= slice.ts && slice.ts - mapped <= MAX_EVENT_LAG_NS) {
                        mapped
                    } else {
                        fallbacks++
                        slice.ts
                    }
                Input(slice.ts, time)
            }
        check(inputs.isNotEmpty()) { "No main-thread \"deliverInputEvent\" slice in the trace of $pkg" }
        val result =
            when (mode) {
                Mode.FACE_TURNS -> faceTurns(traceSession, pkg, frames, inputs)
                Mode.VIEW_ROTATION -> viewRotations(frames, inputs)
            }
        check(result.windows.size == expectedWindows) {
            "Expected $expectedWindows ${mode.name} windows, found ${result.windows.size}"
        }
        return report(result, fallbacks)
    }

    private fun faceTurns(
        session: TraceProcessor.Session,
        pkg: String,
        frames: List<Span>,
        inputs: List<Input>,
    ): Windows {
        val turns =
            session
                .query(
                    """
                    SELECT slice.ts AS ts, slice.dur AS dur FROM slice
                    JOIN process_track ON slice.track_id = process_track.id
                    JOIN process USING(upid)
                    WHERE slice.name = '$TURN' AND process.name LIKE '$pkg%'
                    ORDER BY slice.ts
                    """.trimIndent(),
                ).map { Span(it.long("ts"), it.long("ts") + it.long("dur")) }
                .toList()
        check(turns.isNotEmpty()) { "No \"$TURN\" async slice in the trace of $pkg" }
        turns.forEach { check(it.end >= it.start) { "Unfinished \"$TURN\" slice at ${it.start}" } }
        val visible = threadSlices(session, pkg, "name = '$TURN_VISIBLE'", mainOnly = true).map { it.ts }
        val windows = mutableListOf<List<Long>>()
        val latencies = mutableListOf<Long>()
        turns.forEachIndexed { index, turn ->
            val previous = turns.getOrNull(index - 1)?.start ?: Long.MIN_VALUE
            val up =
                inputs.lastOrNull { it.sliceStart < turn.start && it.sliceStart > previous }
                    ?: error("No input slice before \"$TURN\" #$index at ${turn.start}")
            val marker =
                visible.firstOrNull { it in turn.start..turn.end }
                    ?: error("No \"$TURN_VISIBLE\" slice inside \"$TURN\" #$index at ${turn.start}")
            val acknowledged =
                frames.firstOrNull { it.start > marker }
                    ?: error("No \"$DRAW_FRAME\" slice after \"$TURN_VISIBLE\" of \"$TURN\" #$index")
            latencies += acknowledged.end - up.time
            windows += frames.filter { it.start in turn.start..turn.end }.map { it.start }
        }
        return Windows(windows, latencies)
    }

    private fun viewRotations(
        frames: List<Span>,
        inputs: List<Input>,
    ): Windows {
        val gestures = mutableListOf(mutableListOf(inputs.first()))
        inputs.zipWithNext().forEach { (a, b) ->
            if (b.sliceStart - a.sliceStart > GESTURE_GAP_NS) gestures += mutableListOf(b) else gestures.last() += b
        }
        val windows = mutableListOf<List<Long>>()
        val latencies = mutableListOf<Long>()
        gestures.forEachIndexed { index, gesture ->
            check(gesture.size >= 3) { "Drag #$index has ${gesture.size} input slices; a drag needs DOWN, MOVE and UP" }
            val moves = gesture.subList(1, gesture.size - 1)
            val first = moves.first()
            val acknowledged =
                frames.firstOrNull { it.start > first.sliceStart }
                    ?: error("No \"$DRAW_FRAME\" slice after the first MOVE of drag #$index")
            latencies += acknowledged.end - first.time
            windows += frames.filter { it.start in first.sliceStart..moves.last().sliceStart }.map { it.start }
        }
        return Windows(windows, latencies)
    }

    private fun report(
        result: Windows,
        fallbacks: Int,
    ): List<Measurement> {
        val intervals = mutableListOf<Double>()
        var frameIntervals = 0
        var span = 0L
        var worstFps = Double.MAX_VALUE
        result.windows.forEachIndexed { index, starts ->
            check(starts.size >= 2) { "Window #$index has ${starts.size} \"$DRAW_FRAME\" slice(s); FPS needs at least 2" }
            val windowSpan = starts.last() - starts.first()
            frameIntervals += starts.size - 1
            span += windowSpan
            worstFps = minOf(worstFps, fps(starts.size, windowSpan))
            starts.zipWithNext { a, b -> intervals += (b - a) / NS_PER_MS }
        }
        val latencies = result.latencies.map { it / NS_PER_MS }
        val stats = FrameStats.of(intervals)
        return listOf(
            Measurement("glFrameCount", result.windows.sumOf { it.size }.toDouble()),
            Measurement("glFps", frameIntervals / (span / NS_PER_S)),
            Measurement("glFpsWorstWindow", worstFps),
            Measurement("glFrameIntervalP50Ms", stats.p50),
            Measurement("glFrameIntervalP90Ms", stats.p90),
            Measurement("glFrameIntervalP99Ms", stats.p99),
            Measurement("glFrameIntervalMaxMs", stats.max),
            Measurement("glFramesOver33ms", stats.over33.toDouble()),
            Measurement("inputToFrameP50Ms", FrameStats.nearestRank(latencies.sorted(), 50.0)),
            Measurement("inputToFrameMaxMs", latencies.max()),
            Measurement("inputTimeFromSliceStart", fallbacks.toDouble()),
            Measurement("glFrameIntervalMs", intervals),
            Measurement("inputToFrameMs", latencies),
        )
    }

    private fun threadSlices(
        session: TraceProcessor.Session,
        pkg: String,
        where: String,
        mainOnly: Boolean,
    ): List<NamedSlice> =
        session
            .query(
                """
                SELECT slice.name AS name, slice.ts AS ts, slice.dur AS dur FROM slice
                JOIN thread_track ON slice.track_id = thread_track.id
                JOIN thread USING(utid)
                JOIN process USING(upid)
                WHERE process.name LIKE '$pkg%' AND slice.$where ${if (mainOnly) "AND thread.tid = process.pid" else ""}
                ORDER BY slice.ts
                """.trimIndent(),
            ).map { NamedSlice(it.string("name"), it.long("ts"), it.long("dur")) }
            .toList()

    /** Trace-clock time minus CLOCK_MONOTONIC at the last clock snapshot; null when the trace has none. */
    private fun monotonicToTraceOffset(session: TraceProcessor.Session): Long? =
        session
            .query("SELECT ts, clock_value FROM clock_snapshot WHERE clock_name = 'MONOTONIC' ORDER BY ts DESC LIMIT 1")
            .firstOrNull()
            ?.let { it.long("ts") - it.long("clock_value") }

    private fun eventTime(name: String): Long? =
        EVENT_TIME
            .find(name)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()

    private data class NamedSlice(
        val name: String,
        val ts: Long,
        val dur: Long,
    )

    private data class Span(
        val start: Long,
        val end: Long,
    )

    private data class Input(
        val sliceStart: Long,
        val time: Long,
    )

    private class Windows(
        val windows: List<List<Long>>,
        val latencies: List<Long>,
    )

    companion object {
        const val DRAW_FRAME = "Cube3D.drawFrame"
        const val TURN = "Cube3D.turn"
        const val TURN_VISIBLE = "Cube3D.turnVisible"
        private const val NS_PER_MS = 1_000_000.0
        private const val NS_PER_S = 1_000_000_000.0
        private const val MAX_EVENT_LAG_NS = 1_000_000_000L
        private const val GESTURE_GAP_NS = 150_000_000L
        private val EVENT_TIME = Regex("""eventTimeNano=(\d+)""")

        /** (frames − 1) ÷ span in seconds: the Design Notes' FPS over one window. */
        fun fps(
            frames: Int,
            spanNanos: Long,
        ): Double = (frames - 1) / (spanNanos / NS_PER_S)
    }
}

/** Frame-interval statistics: nearest-rank percentiles, as `spike3d_stats.py` and the Story 1.11 spike log. */
data class FrameStats(
    val p50: Double,
    val p90: Double,
    val p99: Double,
    val max: Double,
    val over33: Int,
) {
    companion object {
        /** Intervals above this (two 60 Hz vsyncs) count as `glFramesOver33ms`. */
        const val OVER_MS = 33.3

        fun of(intervalsMs: List<Double>): FrameStats {
            require(intervalsMs.isNotEmpty()) { "No frame intervals" }
            val sorted = intervalsMs.sorted()
            return FrameStats(
                nearestRank(sorted, 50.0),
                nearestRank(sorted, 90.0),
                nearestRank(sorted, 99.0),
                sorted.last(),
                sorted.count { it > OVER_MS },
            )
        }

        /** The value at rank ⌈p/100 · n⌉ (1-based) of [sorted]. */
        fun nearestRank(
            sorted: List<Double>,
            percentile: Double,
        ): Double = sorted[(ceil(percentile / 100.0 * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]
    }
}
