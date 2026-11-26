package dev.cubentia.app.camera.recorder

import dev.cubentia.app.camera.ContinuousFrame
import dev.cubentia.app.camera.ContinuousFrameGeometry
import dev.cubentia.app.camera.ContinuousScanEngine
import dev.cubentia.app.camera.ContinuousScanEngineFactory
import dev.cubentia.app.camera.ContinuousSessionCapture
import dev.cubentia.app.camera.CornerDetector
import dev.cubentia.app.camera.CornerFrame
import dev.cubentia.app.camera.CornerGuide
import dev.cubentia.app.camera.CornerLocator
import dev.cubentia.app.camera.CornerReader
import dev.cubentia.app.camera.YuvFrame
import dev.cubentia.domain.recognition.continuous.ContinuousScanExport
import dev.cubentia.domain.recognition.continuous.ContinuousScanSnapshot
import java.util.Optional

/** Which recorder flow reads the analysis frame (Story 10.34 adds [CONTINUOUS]). */
internal enum class RecorderMode { FACE, CORNER, CONTINUOUS }

/** What one analysis frame produced for the recorder. */
internal sealed interface RecorderFrame {
    /** Face mode: the activity measures the grid itself. */
    data object Face : RecorderFrame

    /** Corner mode (and the adb burst): the corner reader's frame, null without a guide. */
    data class Corner(
        val frame: CornerFrame?,
    ) : RecorderFrame

    /** Continuous mode: the engine's frame (null while no session or baseline runs) and the analyzer time it took. */
    data class Continuous(
        val frame: ContinuousFrame?,
        val analyzerMs: Double,
    ) : RecorderFrame
}

/**
 * The recorder's per-frame branch (Story 10.34), on the analyzer thread only. Continuous mode runs exactly the injected
 * [ContinuousScanEngineFactory] pipeline (detector, lattice fitter, sampler, stateful mapper) and never touches the
 * [CornerDetector] provider or [CornerLocator]; corner mode reads with the provider exactly as before.
 */
internal class ContinuousRecorderAnalyzer(
    private val cornerDetector: Optional<CornerDetector>,
    private val engines: Optional<ContinuousScanEngineFactory>,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    /** Whether Continuous mode can be offered: only when `QUICK_SCAN` composes the continuous engine (AD-18). */
    val continuousAvailable: Boolean get() = engines.isPresent

    private var engine: ContinuousScanEngine? = null
    private var capture: ContinuousSessionCapture? = null
    private var stoppedExport: ContinuousScanExport? = null
    private var baseline: MutableList<Double>? = null
    private var baselineStartMs: Long? = null
    private var baselineDone: List<Double>? = null

    /** The latest continuous snapshot (for the status line and the label pre-fill). */
    var lastSnapshot: ContinuousScanSnapshot? = null
        private set

    val recording: Boolean get() = engine != null && capture != null

    val baselineRunning: Boolean get() = baseline != null

    /** Starts a capture session: a new engine whose frame tap is [session]. */
    fun startSession(session: ContinuousSessionCapture) {
        engine = engines.get().create(session)
        capture = session
        stoppedExport = null
        baseline = null
        lastSnapshot = null
    }

    /** Starts a no-capture baseline run (no tap, nothing written) of up to [durationMs]. */
    fun startBaseline(durationMs: Long = BASELINE_MS) {
        engine = engines.get().create(null)
        capture = null
        baselineLimitMs = durationMs
        baseline = mutableListOf()
        baselineStartMs = null
        baselineDone = null
        lastSnapshot = null
    }

    private var baselineLimitMs = BASELINE_MS

    /** Stops the session and returns the engine's final export (kept for a later [stop] after an automatic stop). */
    fun stop(): ContinuousScanExport? {
        val active = engine ?: return stoppedExport
        capture?.stop()
        val export = active.export()
        engine = null
        if (capture != null) stoppedExport = export
        baseline?.let { finishBaseline(it) }
        return export
    }

    /** Ends the session after Save or Discard. */
    fun clear() {
        engine = null
        capture = null
        stoppedExport = null
    }

    /** The finished baseline run's analyzer times, once; null while none finished. */
    fun takeBaseline(): List<Double>? = baselineDone.also { baselineDone = null }

    private fun finishBaseline(samples: List<Double>) {
        baselineDone = samples.toList()
        baseline = null
        baselineStartMs = null
        engine = null
    }

    fun analyze(
        mode: RecorderMode,
        yuv: YuvFrame?,
        guide: CornerGuide?,
        rotationDegrees: Int,
        viewWidth: Int,
        viewHeight: Int,
        timestampMs: Long,
        geometry: ContinuousFrameGeometry,
    ): RecorderFrame =
        when (mode) {
            RecorderMode.FACE -> RecorderFrame.Face
            RecorderMode.CORNER -> RecorderFrame.Corner(if (guide != null && yuv != null) readCorner(yuv, guide) else null)
            RecorderMode.CONTINUOUS -> continuous(yuv, rotationDegrees, viewWidth, viewHeight, timestampMs, geometry)
        }

    /** Corner mode: the corner reader with the injected detector, else the classic locator (unchanged since 10.19). */
    fun readCorner(
        yuv: YuvFrame,
        guide: CornerGuide,
    ): CornerFrame = CornerReader.read(yuv, guide, cornerDetector.orElse(CornerLocator))

    private fun continuous(
        yuv: YuvFrame?,
        rotationDegrees: Int,
        viewWidth: Int,
        viewHeight: Int,
        timestampMs: Long,
        geometry: ContinuousFrameGeometry,
    ): RecorderFrame.Continuous {
        val active = engine
        if (active == null || yuv == null) return RecorderFrame.Continuous(null, 0.0)
        val start = nanoTime()
        val frame = active.processFrame(yuv, rotationDegrees, viewWidth, viewHeight)
        val analyzerMs = (nanoTime() - start) / NANOS_PER_MS
        lastSnapshot = frame.snapshot
        capture?.let { session ->
            session.frameDone(timestampMs, analyzerMs, geometry)
            // A frame or size cap stopped the writer: stop the engine too and keep its export for Save.
            if (!session.status.recording) stop()
        }
        baseline?.let { samples ->
            samples += analyzerMs
            val first = baselineStartMs ?: timestampMs.also { baselineStartMs = it }
            if (timestampMs - first >= baselineLimitMs) finishBaseline(samples)
        }
        return RecorderFrame.Continuous(frame, analyzerMs)
    }

    companion object {
        /** A baseline run lasts 30 s unless stopped sooner. */
        const val BASELINE_MS = 30_000L
        private const val NANOS_PER_MS = 1_000_000.0
    }
}

/** `Recording · frame N/600 · X.X MB · skips K · sides M/6`, with the stop reason after an automatic stop. */
internal fun continuousStatusLine(status: dev.cubentia.app.camera.ContinuousCaptureStatus): String {
    val head = if (status.recording) "Recording" else "Stopped"
    val line =
        "$head · frame ${status.frames}/${dev.cubentia.domain.recognition.continuous.session.ContinuousSessionFormat.FRAME_CAP} · " +
            "%.1f MB · skips ${status.skips} · sides ${status.sidesMapped}/6".format(java.util.Locale.ROOT, status.bytes / 1_000_000.0)
    val reason =
        when (status.stopReason) {
            dev.cubentia.domain.recognition.continuous.session.SessionStopReason.FRAME_CAP -> " · frame cap"
            dev.cubentia.domain.recognition.continuous.session.SessionStopReason.SIZE_CAP -> " · size cap"
            else -> ""
        }
    return line + reason
}

/** `Saved <sessionId> · <outcome>[ · DEFECTIVE: reasons]`. */
internal fun continuousSavedLine(saved: dev.cubentia.app.camera.ContinuousSessionSaved): String =
    "Saved ${saved.sessionId} · ${saved.outcome}" +
        if (saved.defectReasons.isEmpty()) "" else " · DEFECTIVE: ${saved.defectReasons.joinToString("; ")}"
