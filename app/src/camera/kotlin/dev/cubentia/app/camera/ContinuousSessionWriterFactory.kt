package dev.cubentia.app.camera

import dev.cubentia.domain.recognition.continuous.ContinuousScanExport
import dev.cubentia.domain.recognition.continuous.session.SessionOutcome
import dev.cubentia.domain.recognition.continuous.session.SessionPartition
import dev.cubentia.domain.recognition.continuous.session.SessionStopReason

/*
 * Story 10.34 (AD-9 Frames): the neutral port of the debug-only continuous session recorder. Only interfaces and data
 * live here; the writer (queue, files, PNG) is bound by `ContinuousSessionWriterModule` from the debug camera source set
 * and is absent from every other variant (`Optional.empty()` through [ContinuousSessionWriterOptionalModule]).
 */

/** What the operator set before Start. */
internal data class ContinuousSessionSetup(
    val sessionId: String,
    val deviceModel: String,
    val deviceManufacturer: String,
    val cube: String,
    val lighting: String,
    val partition: SessionPartition,
    val model: ContinuousModelIdentity?,
)

/** One analysis frame's geometry, as the analyzer received it. */
internal data class ContinuousFrameGeometry(
    val rotationDegrees: Int,
    val analysisWidth: Int,
    val analysisHeight: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val cropRight: Int,
    val cropBottom: Int,
)

/** The live status line's numbers. */
internal data class ContinuousCaptureStatus(
    val recording: Boolean,
    val frames: Int,
    val bytes: Long,
    val skips: Int,
    val sidesMapped: Int,
    val stopReason: SessionStopReason?,
)

/** A saved session: its id, derived outcome and, when defective, why. */
internal data class ContinuousSessionSaved(
    val sessionId: String,
    val outcome: SessionOutcome,
    val defectReasons: List<String>,
    val path: String,
)

/** A device's no-capture analyzer latency. */
internal data class ContinuousLatencyBaseline(
    val deviceModel: String,
    val frames: Int,
    val p50Ms: Double,
    val p95Ms: Double,
)

/** One recording session; [ContinuousFrameTap] calls and [frameDone] come from the analyzer thread. */
internal interface ContinuousSessionCapture : ContinuousFrameTap {
    val status: ContinuousCaptureStatus

    /** Ends the frame the tap just saw: its sensor timestamp, the analyzer time it took and its geometry. */
    fun frameDone(
        timestampMs: Long,
        analyzerMs: Double,
        geometry: ContinuousFrameGeometry,
    )

    /** Stops capturing; nothing is written after it except by [save]. */
    fun stop()

    /** Drains the queue, writes the manifest last and renames the group complete; [export] is the engine's final export. */
    fun save(export: ContinuousScanExport): ContinuousSessionSaved

    /** Deletes the session's temp directory; false when it could not be deleted (it then stays `.tmp-*`). */
    fun discard(): Boolean
}

internal interface ContinuousSessionWriterFactory {
    fun create(setup: ContinuousSessionSetup): ContinuousSessionCapture

    /** Stores a no-capture baseline for [deviceModel] from [analyzerMs] (no session is written). */
    fun saveBaseline(
        deviceModel: String,
        analyzerMs: List<Double>,
    ): ContinuousLatencyBaseline?
}
