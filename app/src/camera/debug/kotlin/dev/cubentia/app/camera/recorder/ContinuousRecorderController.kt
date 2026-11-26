package dev.cubentia.app.camera.recorder

import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import dev.cubentia.app.camera.ContinuousModelIdentity
import dev.cubentia.app.camera.ContinuousSessionCapture
import dev.cubentia.app.camera.ContinuousSessionSaved
import dev.cubentia.app.camera.ContinuousSessionSetup
import dev.cubentia.app.camera.ContinuousSessionWriterFactory
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.StickerTruth
import dev.cubentia.domain.recognition.continuous.ContinuousScanExport
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionFormat
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionJson
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionLabels
import dev.cubentia.domain.recognition.continuous.session.SessionPartition
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.Executor

/** Story 10.34: where a continuous recording is. */
internal enum class ContinuousPhase { IDLE, RECORDING, STOPPED, SAVING, SAVED, BASELINE }

/** What the continuous recorder screen shows. */
internal data class ContinuousRecorderUi(
    val phase: ContinuousPhase = ContinuousPhase.IDLE,
    val statusLine: String = "Fill in the cube and light, then Start.",
    val saved: ContinuousSessionSaved? = null,
    /** The session's last net (URFDLB, 54; null = unknown), the label pre-fill. */
    val lastNet: List<Color?> = List(54) { null },
)

/** The operator's fields before Start. */
internal data class ContinuousSessionMeta(
    val cube: String = "",
    val lighting: String = "",
    val partition: SessionPartition = SessionPartition.UNASSIGNED,
    val label: String? = null,
) {
    val ready: Boolean get() = cube.any { it.isLetterOrDigit() } && lighting.any { it.isLetterOrDigit() }
}

/**
 * Story 10.34: the continuous session's controls (screen buttons and the adb `RECORDER_SESSION` broadcast). Commands
 * are called on the main thread; engine work is posted to the analyzer thread ([onAnalyzer]) and file work to [io], so
 * the analyzer thread only ever copies and offers.
 */
internal class ContinuousRecorderController(
    private val analyzer: ContinuousRecorderAnalyzer,
    private val writers: ContinuousSessionWriterFactory,
    private val modelIdentity: ContinuousModelIdentity?,
    private val deviceModel: String,
    private val deviceManufacturer: String,
    private val onAnalyzer: Executor,
    private val io: Executor,
    private val onMain: Executor,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    private val ui = mutableStateOf(ContinuousRecorderUi())
    val state: State<ContinuousRecorderUi> get() = ui

    @Volatile var meta = ContinuousSessionMeta()

    /** Whether the Continuous mode screen is open (adb commands are ignored otherwise). */
    @Volatile var open = false

    @Volatile private var capture: ContinuousSessionCapture? = null

    @Volatile private var lastPostMs = 0L

    private fun update(change: (ContinuousRecorderUi) -> ContinuousRecorderUi) = onMain.execute { ui.value = change(ui.value) }

    private fun say(line: String) = update { it.copy(statusLine = line) }

    /** An adb `RECORDER_SESSION` command: `start|stop|save|discard|baseline` (discard without the dialog). */
    fun command(
        command: String,
        label: String?,
    ) {
        if (!open) {
            runCatching { Log.w(TAG, "RECORDER_SESSION $command ignored: Continuous mode is not open") }
            return
        }
        when (command) {
            "start" -> start(label)
            "stop" -> stop()
            "save" -> save()
            "discard" -> discard()
            "baseline" -> baseline()
            else -> runCatching { Log.w(TAG, "Unknown RECORDER_SESSION command $command") }
        }
    }

    fun start(label: String? = meta.label) {
        val phase = ui.value.phase
        if (phase != ContinuousPhase.IDLE && phase != ContinuousPhase.SAVED) return
        val current = meta
        if (!current.ready) return say("Fill in the cube and light before Start.")
        val setup =
            ContinuousSessionSetup(
                sessionId =
                    dev.cubentia.app.camera.recorder.session.SessionFiles
                        .sessionId(now(), deviceModel, label),
                deviceModel = deviceModel,
                deviceManufacturer = deviceManufacturer,
                cube = current.cube.trim(),
                lighting = current.lighting.trim(),
                partition = current.partition,
                model = modelIdentity,
            )
        val session = runCatching { writers.create(setup) }.getOrElse { return say("Start failed: ${it.message}") }
        capture = session
        ui.value = ContinuousRecorderUi(phase = ContinuousPhase.RECORDING, statusLine = continuousStatusLine(session.status))
        onAnalyzer.execute { analyzer.startSession(session) }
    }

    fun stop() {
        when (ui.value.phase) {
            ContinuousPhase.RECORDING -> {
                onAnalyzer.execute { analyzer.stop() }
                ui.value = ui.value.copy(phase = ContinuousPhase.STOPPED)
                capture?.let { session -> say(continuousStatusLine(session.status.copy(recording = false))) }
            }

            ContinuousPhase.BASELINE -> {
                onAnalyzer.execute { analyzer.stop() }
            }

            else -> {
                Unit
            }
        }
    }

    fun save() {
        val session = capture ?: return
        val phase = ui.value.phase
        if (phase != ContinuousPhase.RECORDING && phase != ContinuousPhase.STOPPED) return
        ui.value = ui.value.copy(phase = ContinuousPhase.SAVING, statusLine = "Saving…")
        onAnalyzer.execute {
            val export = analyzer.stop()
            // A complete export is canonical (Orientation.HOME), as replay compares labels against it; otherwise the last net.
            val net =
                (export as? ContinuousScanExport.Complete)?.facelets?.colors
                    ?: analyzer.lastSnapshot?.let { snapshot -> Face.entries.flatMap { snapshot.net[it] ?: List(9) { null } } }
                    ?: List(54) { null }
            analyzer.clear()
            io.execute {
                val result = runCatching { session.save(checkNotNull(export) { "no export" }) }
                capture = null
                result.fold(
                    onSuccess = { saved ->
                        update { ContinuousRecorderUi(ContinuousPhase.SAVED, continuousSavedLine(saved), saved, net) }
                        runCatching { Log.d(TAG, "saved ${saved.path} outcome=${saved.outcome} defects=${saved.defectReasons}") }
                    },
                    onFailure = { failure -> update { ContinuousRecorderUi(statusLine = "Save failed: ${failure.message}") } },
                )
            }
        }
    }

    fun discard() {
        val session = capture ?: return
        val phase = ui.value.phase
        if (phase != ContinuousPhase.RECORDING && phase != ContinuousPhase.STOPPED) return
        ui.value = ui.value.copy(phase = ContinuousPhase.SAVING, statusLine = "Discarding…")
        onAnalyzer.execute {
            analyzer.stop()
            analyzer.clear()
            io.execute {
                val deleted = runCatching { session.discard() }.getOrDefault(false)
                capture = null
                update {
                    ContinuousRecorderUi(
                        statusLine = if (deleted) "Discarded." else "Discard failed: the temp folder stays (never read as complete).",
                    )
                }
            }
        }
    }

    /** A no-capture baseline run (30 s or until Stop): stores this device's analyzer p50/p95, writes no session. */
    fun baseline() {
        val phase = ui.value.phase
        if (phase != ContinuousPhase.IDLE && phase != ContinuousPhase.SAVED) return
        ui.value = ContinuousRecorderUi(phase = ContinuousPhase.BASELINE, statusLine = "Baseline run · no capture · up to 30 s")
        onAnalyzer.execute { analyzer.startBaseline() }
    }

    /** Analyzer thread, after each continuous frame: finishes a baseline run and refreshes the status line. */
    fun onAnalyzed(nowMs: Long) {
        analyzer.takeBaseline()?.let { samples ->
            io.execute {
                val stored = runCatching { writers.saveBaseline(deviceModel, samples) }
                update {
                    ContinuousRecorderUi(
                        statusLine =
                            stored.fold(
                                onSuccess = { baseline ->
                                    baseline?.let {
                                        "Baseline ${it.deviceModel}: p50 %.1f ms · p95 %.1f ms · ${it.frames} frames".format(
                                            java.util.Locale.ROOT,
                                            it.p50Ms,
                                            it.p95Ms,
                                        )
                                    }
                                        ?: "Baseline run saw no frame."
                                },
                                onFailure = { "Baseline not saved: ${it.message}" },
                            ),
                    )
                }
            }
        }
        val session = capture ?: return
        val status = session.status
        if (nowMs - lastPostMs < STATUS_EVERY_MS && status.recording) return
        lastPostMs = nowMs
        update { current ->
            when (current.phase) {
                ContinuousPhase.RECORDING, ContinuousPhase.STOPPED -> {
                    current.copy(
                        phase = if (status.recording) ContinuousPhase.RECORDING else ContinuousPhase.STOPPED,
                        statusLine = continuousStatusLine(status),
                    )
                }

                else -> {
                    current
                }
            }
        }
    }

    /**
     * Writes the saved session's `labels.json` (`labels.json.tmp` → rename) with the [StickerTruthCheck][dev.cubentia.domain.recognition.StickerTruthCheck]
     * decision for [reason]; returns the labels written.
     */
    fun saveLabels(
        stickers: List<StickerTruth>,
        reason: String?,
    ): ContinuousSessionLabels {
        val saved = checkNotNull(ui.value.saved) { "No saved session" }
        val labels = ContinuousSessionLabels.of(stickers, reason)
        writeLabels(File(saved.path), labels)
        return labels
    }

    companion object {
        const val TAG = "CubentiaSession"
        private const val STATUS_EVERY_MS = 250L

        /** `labels.json` beside the session's predictions, written to a temp name and renamed. */
        fun writeLabels(
            dir: File,
            labels: ContinuousSessionLabels,
        ) {
            val target = File(dir, ContinuousSessionFormat.LABELS)
            val temp = File(dir, ContinuousSessionFormat.LABELS + ".tmp")
            temp.writeText(ContinuousSessionJson.encodeLabels(labels) + "\n")
            // Rename over the old labels first; delete them only when the platform cannot rename over a file.
            check(temp.renameTo(target) || (target.delete() && temp.renameTo(target))) { "Could not rename ${temp.path}" }
        }
    }
}
