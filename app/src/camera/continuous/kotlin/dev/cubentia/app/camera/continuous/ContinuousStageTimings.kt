package dev.cubentia.app.camera.continuous

import dev.cubentia.app.ScanDiagnostics
import java.util.Locale

/** GH #406: a monotonic nanosecond clock; a `fun interface` so reading it never boxes (a `() -> Long` would). */
internal fun interface NanoClock {
    fun nanoTime(): Long

    companion object {
        val SYSTEM = NanoClock { System.nanoTime() }
    }
}

/**
 * GH #406 (debug diagnostics): per-stage timings of the continuous Quick Scan pipeline. [record] takes one analysed frame
 * as primitives (-1 for a stage that did not run) into rings preallocated at construction, so the analyzer thread does
 * not allocate per frame; sorting and string building happen only when a line is built. Once [intervalNs] of clock have
 * passed since the current window's first frame, the window's body goes to [onWindow] (on the recording thread) and the
 * window restarts. [runLine] covers every frame since construction, keeping the latest [runCapacity] samples per stage
 * for its percentiles while its counts and fps cover every frame.
 *
 * Body, fixed field order (numbers and fixed words only, AD-13): `frames= read= fps= detectorMs= inferenceMs= fitMs=
 * sampleMs= mapMs= analyzerMs= fitted= observations=`; stage values are nearest-rank `p50/p90/max` in ms with one
 * decimal, `-` for a stage with no samples. Access is synchronized: the run line is read on the main thread.
 */
internal class ContinuousStageTimings(
    val clock: NanoClock,
    private val onWindow: (String) -> Unit,
    private val intervalNs: Long = WINDOW_INTERVAL_NS,
    runCapacity: Int = RUN_CAPACITY,
) {
    private val window = Span(runCapacity)
    private val run = Span(runCapacity)

    /**
     * One analysed frame: [startNs]/[endNs] bound the whole `processFrame` call (the analyzer stage); the stage
     * durations are nanoseconds, -1 when the stage did not run; [fitted] and [observations] count only when the frame
     * reached the mapper ([mapNs] >= 0).
     */
    fun record(
        startNs: Long,
        endNs: Long,
        detectorNs: Long,
        inferenceNs: Long,
        fitNs: Long,
        sampleNs: Long,
        mapNs: Long,
        fitted: Int,
        observations: Int,
    ) {
        val body: String
        synchronized(this) {
            run.add(startNs, endNs, detectorNs, inferenceNs, fitNs, sampleNs, mapNs, fitted, observations)
            window.add(startNs, endNs, detectorNs, inferenceNs, fitNs, sampleNs, mapNs, fitted, observations)
            if (endNs - window.firstStartNs < intervalNs) return
            body = window.body()
            window.clear()
        }
        onWindow(body)
    }

    /** The body of the whole pipeline life's timing line; null when no frame was analysed. */
    fun runLine(): String? = synchronized(this) { if (run.frames == 0L) null else run.body() }

    /** The samples and counts of one span of frames: rings of the latest [capacity] samples per stage. */
    private class Span(
        private val capacity: Int,
    ) {
        private val samples = Array(STAGES) { LongArray(capacity) }
        private val counts = LongArray(STAGES)
        var frames = 0L
        private var read = 0L
        private var fitted = 0L
        private var observations = 0L
        var firstStartNs = 0L
        private var lastEndNs = 0L

        fun add(
            startNs: Long,
            endNs: Long,
            detectorNs: Long,
            inferenceNs: Long,
            fitNs: Long,
            sampleNs: Long,
            mapNs: Long,
            fittedFaces: Int,
            observed: Int,
        ) {
            if (frames == 0L) firstStartNs = startNs
            frames++
            lastEndNs = endNs
            put(DETECTOR, detectorNs)
            put(INFERENCE, inferenceNs)
            put(FIT, fitNs)
            put(SAMPLE, sampleNs)
            put(MAP, mapNs)
            put(ANALYZER, endNs - startNs)
            if (mapNs >= 0) {
                read++
                fitted += fittedFaces
                observations += observed
            }
        }

        private fun put(
            stage: Int,
            ns: Long,
        ) {
            if (ns < 0) return
            samples[stage][(counts[stage] % capacity).toInt()] = ns
            counts[stage]++
        }

        fun clear() {
            counts.fill(0)
            frames = 0
            read = 0
            fitted = 0
            observations = 0
            firstStartNs = 0
            lastEndNs = 0
        }

        fun body(): String {
            val spanNs = lastEndNs - firstStartNs
            val fps = if (spanNs > 0) format(frames * 1e9 / spanNs) else "-"
            return listOf(
                "frames=$frames",
                "read=$read",
                "fps=$fps",
                "detectorMs=${percentiles(DETECTOR)}",
                "inferenceMs=${percentiles(INFERENCE)}",
                "fitMs=${percentiles(FIT)}",
                "sampleMs=${percentiles(SAMPLE)}",
                "mapMs=${percentiles(MAP)}",
                "analyzerMs=${percentiles(ANALYZER)}",
                "fitted=$fitted",
                "observations=$observations",
            ).joinToString(" ")
        }

        /** Nearest rank over the kept samples: sorted ascending, pQ = sorted[ceil(Q·n) − 1], max = last. */
        private fun percentiles(stage: Int): String {
            val n = minOf(counts[stage], capacity.toLong()).toInt()
            if (n == 0) return "-"
            val sorted = samples[stage].copyOf(n).apply { sort() }
            return listOf(50, 90).joinToString("/", postfix = "/") { q -> ms(sorted[(q * n + 99) / 100 - 1]) } + ms(sorted[n - 1])
        }

        private fun ms(ns: Long): String = format(ns / 1e6)

        private fun format(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
    }

    companion object {
        /** A window line at most once per 2 s of clock. */
        const val WINDOW_INTERVAL_NS = 2_000_000_000L

        /** The run line's percentiles cover the latest 4096 samples per stage. */
        const val RUN_CAPACITY = 4096

        private const val DETECTOR = 0
        private const val INFERENCE = 1
        private const val FIT = 2
        private const val SAMPLE = 3
        private const val MAP = 4
        private const val ANALYZER = 5
        private const val STAGES = 6

        /** The build's accumulator: debug logs through [ScanDiagnostics]; release creates none. */
        fun forBuild(): ContinuousStageTimings? =
            if (ScanDiagnostics.QUICK_SCAN_TIMINGS) {
                ContinuousStageTimings(NanoClock.SYSTEM, ScanDiagnostics::onQuickScanTimingWindow)
            } else {
                null
            }
    }
}
