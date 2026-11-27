package dev.cubentia.app.camera

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.recognition.continuous.ContinuousScanExport
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionLabels
import dev.cubentia.domain.recognition.continuous.session.SessionOutcome
import dev.cubentia.domain.recognition.continuous.session.SessionPartition
import dev.cubentia.domain.recognition.continuous.session.SessionStopReason

/*
 * Story 10.34 (AD-9 Frames): the neutral port of the debug-only Quick Scan session recording's writer. Only interfaces
 * and data live here; the writer (slots, queue, files, PNG) is bound by `QuickScanSessionModule` from the debug camera
 * source set and is absent from every other variant (`Optional.empty()` through [QuickScanSessionOptionalModule]).
 */

/** What a recording knows when Rec starts it. */
internal data class ContinuousSessionSetup(
    val sessionId: String,
    val deviceModel: String,
    val deviceManufacturer: String,
    val model: ContinuousModelIdentity?,
)

/** What the operator sets on the session sheet before Save. */
internal data class ContinuousSessionTags(
    val cube: String,
    val lighting: String,
    val partition: SessionPartition,
)

/** The recording indicator's and the sheet's numbers; read by the UI poller only, never on the analyzer thread. */
internal data class ContinuousCaptureStatus(
    val recording: Boolean,
    val frames: Int,
    val bytes: Long,
    val skips: Int,
    val stopReason: SessionStopReason?,
)

/** The analyzer time of the frames written so far (the sheet's capture p50/p95); null before any frame. */
internal data class ContinuousCaptureLatency(
    val p50Ms: Double?,
    val p95Ms: Double?,
)

/** A saved session: its id, derived outcome, why it is defective (empty when it is not), its directory and net. */
internal data class ContinuousSessionSaved(
    val sessionId: String,
    val outcome: SessionOutcome,
    val defectReasons: List<String>,
    val path: String,
    /** The labeller's pre-fill: a complete export's canonical net, else the last written snapshot's (54, URFDLB; null = unknown). */
    val net: List<Color?> = List(54) { null },
)

/** A device's no-capture analyzer latency. */
internal data class ContinuousLatencyBaseline(
    val deviceModel: String,
    val frames: Int,
    val p50Ms: Double,
    val p95Ms: Double,
)

/**
 * One recording; its [ContinuousFrameTap] calls come from the analyzer thread and only copy into a preallocated slot. The
 * rest is called off the analyzer thread: [status], [recording] and [latency] by the UI, [save] and [discard] off main.
 */
internal interface ContinuousSessionCapture : ContinuousFrameTap {
    val status: ContinuousCaptureStatus

    /** False once stopped, by [stop] or by a frame or size cap. */
    val recording: Boolean

    fun latency(): ContinuousCaptureLatency

    /** Stops capturing with [reason] (the first reason sticks); nothing is written after it except by [save]. */
    fun stop(reason: SessionStopReason)

    /**
     * Drains the queue, writes the manifest last with [tags] and the device's no-capture [baselineP50Ms] (null: none yet,
     * judged on skips only) and renames the group complete; [export] is the engine's export at the stop.
     */
    fun save(
        export: ContinuousScanExport,
        tags: ContinuousSessionTags,
        baselineP50Ms: Double?,
    ): ContinuousSessionSaved

    /** Deletes the session's temp directory; false when it could not be deleted (it then stays `.tmp-*`). */
    fun discard(): Boolean
}

/** Off the main thread: makes recordings, keeps per-device baselines and writes labels beside a saved session. */
internal interface ContinuousSessionWriterFactory {
    fun create(setup: ContinuousSessionSetup): ContinuousSessionCapture

    /** The stored no-capture baseline of [deviceModel], or null before any run. */
    fun baseline(deviceModel: String): ContinuousLatencyBaseline?

    /** Stores a no-capture baseline for [deviceModel] from [analyzerMs] (no session is written); null without a sample. */
    fun saveBaseline(
        deviceModel: String,
        analyzerMs: List<Double>,
    ): ContinuousLatencyBaseline?

    /** Writes [labels] as `labels.json` beside (never inside) the predictions of [saved]. */
    fun writeLabels(
        saved: ContinuousSessionSaved,
        labels: ContinuousSessionLabels,
    )
}
