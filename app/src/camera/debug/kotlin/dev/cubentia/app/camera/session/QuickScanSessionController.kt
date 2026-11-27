package dev.cubentia.app.camera.session

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.cubentia.app.camera.ContinuousCaptureLatency
import dev.cubentia.app.camera.ContinuousCaptureStatus
import dev.cubentia.app.camera.ContinuousFrameTap
import dev.cubentia.app.camera.ContinuousLatencyBaseline
import dev.cubentia.app.camera.ContinuousSessionCapture
import dev.cubentia.app.camera.ContinuousSessionSaved
import dev.cubentia.app.camera.ContinuousSessionSetup
import dev.cubentia.app.camera.ContinuousSessionTags
import dev.cubentia.app.camera.ContinuousSessionWriterFactory
import dev.cubentia.app.camera.QuickScanLeave
import dev.cubentia.app.camera.QuickScanSession
import dev.cubentia.app.camera.QuickScanSessionHost
import dev.cubentia.app.camera.StickerCenterDetection
import dev.cubentia.domain.recognition.continuous.ContinuousScanSnapshot
import dev.cubentia.domain.recognition.continuous.QuadObservation
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionLabels
import dev.cubentia.domain.recognition.continuous.session.SessionCountCheck
import dev.cubentia.domain.recognition.continuous.session.SessionOutcome
import dev.cubentia.domain.recognition.continuous.session.SessionPartition
import dev.cubentia.domain.recognition.continuous.session.SessionStopReason
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** The sheet's operator fields: free text cube and lighting (pre-filled with the last values), and the partition. */
internal data class SessionFields(
    val cube: String = "",
    val lighting: String = "",
    val partition: SessionPartition = SessionPartition.UNASSIGNED,
) {
    /** What the manifest records; a blank field is `unknown` (as the adb defaults). */
    fun tags(): ContinuousSessionTags =
        ContinuousSessionTags(cube.trim().ifEmpty { UNKNOWN }, lighting.trim().ifEmpty { UNKNOWN }, partition)

    companion object {
        const val UNKNOWN = "unknown"
    }
}

/** The last sheet fields of this app process, so the next sheet is pre-filled with them. */
internal object SessionFieldMemory {
    @Volatile var last = SessionFields()
}

/** The saved-session notice: the saved group, and the labeller's last result line. */
internal data class SavedNotice(
    val saved: ContinuousSessionSaved,
    val labelStatus: String? = null,
)

/** Where a Quick Scan session recording is (the story's state machine). */
internal sealed interface SessionPhase {
    /** No recording: Rec shows (beside the saved-session notice, when there is one). */
    data object Idle : SessionPhase

    /** Rec was tapped: the writer is being made and the map cleared. */
    data object Starting : SessionPhase

    data class Recording(
        val capture: ContinuousSessionCapture,
    ) : SessionPhase

    /** Stopped, waiting for Save or Discard…: the sheet (when the live scan shows). */
    data class Stopped(
        val capture: ContinuousSessionCapture,
        val reason: SessionStopReason,
        val error: String? = null,
        /** Reset stopped it (answer 5): the map is cleared after Save or Discard, whatever [reason] the writer kept first. */
        val resetRequested: Boolean = false,
    ) : SessionPhase

    /** The 30 s no-capture timing run started from the sheet; the stopped session waits aside. */
    data class Baseline(
        val stopped: Stopped,
        val tap: BaselineTimingTap,
        val startedAtMs: Long,
    ) : SessionPhase

    /** Saving, discarding, or starting or ending a baseline run: the sheet shows [what] with its actions disabled. */
    data class Busy(
        val stopped: Stopped,
        val what: String,
    ) : SessionPhase
}

/**
 * The baseline run's tap: records only each frame's analyzer time, into a preallocated array (no capture, no copy), so
 * the run is timed exactly like a recording without its capture work.
 */
internal class BaselineTimingTap(
    capacity: Int = CAPACITY,
) : ContinuousFrameTap {
    private val samples = LongArray(capacity)

    @Volatile private var count = 0

    override fun modelInputBuffer(frameIndex: Int): ByteArray? = null

    override fun onFrame(
        frameIndex: Int,
        observations: List<QuadObservation>,
        snapshot: ContinuousScanSnapshot,
        detection: StickerCenterDetection,
        modelInputFilled: Boolean,
    ) = Unit

    override fun onSkippedFrame(frameIndex: Int) = Unit

    override fun frameDone(
        sensorTimestampNs: Long,
        analyzerNs: Long,
        rotation: Int,
        imageWidth: Int,
        imageHeight: Int,
        cropLeft: Int,
        cropTop: Int,
        cropRight: Int,
        cropBottom: Int,
    ) {
        val n = count
        if (n < samples.size) {
            samples[n] = analyzerNs
            count = n + 1
        }
    }

    /** The analyzer times so far, in ms; read after the engine stopped using this tap. */
    fun samplesMs(): List<Double> = (0 until count).map { samples[it] / NANOS_PER_MS }

    companion object {
        /** 30 s at up to 60 frames a second, with room to spare. */
        const val CAPACITY = 4096
        private const val NANOS_PER_MS = 1_000_000.0
    }
}

/** An adb `RECORDER_SESSION` broadcast: [command] with the optional `--es` extras. */
internal data class QuickScanSessionCommand(
    val command: String,
    val cube: String? = null,
    val lighting: String? = null,
    val partition: String? = null,
    val label: String? = null,
)

/**
 * Story 10.34, debug only: the Quick Scan session recording of one Quick Scan screen, implementing the story's settled UI
 * decisions (answers 1-16, W1-W10). Lives in the screen's view model, so a rotation keeps the recording, the sheet, the
 * notice and the labeller. Every public member runs on the main thread; file work goes to [io], whose results come back
 * through [main]. Analyzer work is only the capture's own [ContinuousFrameTap] copy.
 */
internal class QuickScanSessionController(
    private val host: QuickScanSessionHost,
    private val writers: ContinuousSessionWriterFactory,
    private val deviceModel: String,
    private val deviceManufacturer: String,
    private val io: Executor,
    private val main: Executor,
    private val clockMs: () -> Long,
    private val now: () -> LocalDateTime = LocalDateTime::now,
    private val log: (String) -> Unit = {},
    private val onRelease: () -> Unit = {},
) : QuickScanSession {
    var phase by mutableStateOf<SessionPhase>(SessionPhase.Idle)
        private set

    var fields by mutableStateOf(SessionFieldMemory.last)

    var notice by mutableStateOf<SavedNotice?>(null)
        private set

    var labeller by mutableStateOf<LabellerState?>(null)
        private set

    /** The sheet's "Discard this recording?" question. */
    var confirmDiscard by mutableStateOf(false)
        private set

    var handOffHeld by mutableStateOf(false)
        private set

    /** This device's stored no-capture baseline; null until one is read or run. */
    var baseline by mutableStateOf<ContinuousLatencyBaseline?>(null)
        private set

    var status by mutableStateOf<ContinuousCaptureStatus?>(null)
        private set

    var latency by mutableStateOf(ContinuousCaptureLatency(null, null))
        private set

    /** The stopped session's derived outcome, shown read-only on the sheet (W3); null until known. */
    var outcome by mutableStateOf<SessionOutcome?>(null)
        private set

    /** The baseline run's seconds left, for the indicator. */
    var baselineSecondsLeft by mutableStateOf(0)
        private set

    /** The id suffix an adb `start` asked for (W8), used by the next recording. */
    private var pendingLabel: String? = null
    private var outcomeAsked: ContinuousSessionCapture? = null
    private var baselineLoaded = false
    private var released = false

    /** The pending Save's export arrived and its io save was submitted (so release lets it finish). */
    private var saveSubmitted = false

    @Volatile private var holdsAnalysisFlag = false

    // ---- QuickScanSession ----

    override val holdsAnalysis: Boolean get() = holdsAnalysisFlag

    override val holdsHandOff: Boolean get() = handOffHeld

    override val locksActions: Boolean get() = phase is SessionPhase.Baseline

    /** Whether the Rec control shows (and an adb `start` applies): idle on the live scan, no held hand-off or labeller. */
    val recAvailable: Boolean get() =
        phase == SessionPhase.Idle && !handOffHeld && labeller == null && host.liveScanShown

    /** Whether the sheet shows: a stopped session on the live scan, not behind the screen's own discard question. */
    val sheetShown: Boolean get() =
        (phase is SessionPhase.Stopped || phase is SessionPhase.Busy) && host.liveScanShown && !host.hostDialogShown

    override fun onCompleted(): Boolean =
        when (val current = phase) {
            is SessionPhase.Recording -> {
                // Answer 4: the recording stops by itself; the sheet opens before the Scan Review hand-off.
                handOffHeld = true
                stopTo(current.capture, SessionStopReason.COMPLETE)
                true
            }

            // Answer 14: the baseline scan never hands off; the pending session's own hold comes back with it.
            is SessionPhase.Baseline -> {
                true
            }

            else -> {
                handOffHeld.also { sync() }
            }
        }

    override fun onReset(): Boolean =
        when (val current = phase) {
            is SessionPhase.Baseline -> {
                true
            }

            is SessionPhase.Recording -> {
                // Answer 5: the map is cleared only after Save or Discard.
                stopTo(current.capture, SessionStopReason.RESET, resetRequested = true)
                true
            }

            else -> {
                // A saved completed scan's held hand-off goes with the map Reset clears: the live scan runs again, the
                // notice stays, and its Dismiss hands nothing off.
                if (phase == SessionPhase.Idle && handOffHeld) {
                    handOffHeld = false
                    sync()
                }
                false
            }
        }

    override fun onLeaving(kind: QuickScanLeave): Boolean {
        when (val current = phase) {
            is SessionPhase.Baseline -> {
                // Answer 14: only Close/Back leaves during the run; it ends without storing a value.
                if (kind != QuickScanLeave.CLOSE) return true
                endBaseline(current, store = false)
            }

            is SessionPhase.Recording -> {
                stopTo(current.capture, SessionStopReason.OPERATOR)
            }

            else -> {
                Unit
            }
        }
        // Leaving drops a completed scan's held hand-off: the screen goes elsewhere instead. Close/Back keeps it, since its
        // "Discard this scan?" can be cancelled back to the notice (answer 13); a screen that really closes releases it.
        if (handOffHeld && phase == SessionPhase.Idle && kind != QuickScanLeave.CLOSE) handOffHeld = false
        sync()
        return false
    }

    override fun onBackground(): Boolean =
        when (val current = phase) {
            is SessionPhase.Recording -> {
                stopTo(current.capture, SessionStopReason.OPERATOR)
                true
            }

            is SessionPhase.Baseline -> {
                endBaseline(current, store = false)
                true
            }

            else -> {
                false
            }
        }

    override fun onDetectorUnavailable(): Boolean {
        val capture =
            when (val current = phase) {
                is SessionPhase.Recording -> {
                    current.capture
                }

                is SessionPhase.Stopped -> {
                    current.capture
                }

                is SessionPhase.Busy -> {
                    current.stopped.capture
                }

                is SessionPhase.Baseline -> {
                    host.endBaseline {}
                    current.stopped.capture
                }

                else -> {
                    null
                }
            } ?: return false
        // The card replaces the live scan for good: the screen is left with the sheet unanswered.
        discardQuietly(capture)
        phase = SessionPhase.Idle
        handOffHeld = false
        confirmDiscard = false
        sync()
        return true
    }

    override fun release() {
        if (released) return
        released = true
        val capture =
            when (val current = phase) {
                is SessionPhase.Recording -> current.capture

                is SessionPhase.Stopped -> current.capture

                // A save already handed to io finishes; one still waiting for its export never starts, so discard it.
                is SessionPhase.Busy -> current.stopped.capture.takeIf { current.what != SAVING || !saveSubmitted }

                is SessionPhase.Baseline -> current.stopped.capture

                else -> null
            }
        capture?.let(::discardQuietly)
        phase = SessionPhase.Idle
        sync()
        onRelease()
    }

    @Composable
    override fun Overlay(scope: BoxScope) = QuickScanSessionOverlay(this, scope)

    // ---- Controls (the screen's buttons and the adb commands) ----

    /** Rec: clears the map (answer 3), dismisses the notice and starts recording. */
    fun rec() {
        if (!recAvailable) return ignored("start")
        notice = null
        val label = pendingLabel
        pendingLabel = null
        val setup =
            ContinuousSessionSetup(SessionFiles.sessionId(now(), deviceModel, label), deviceModel, deviceManufacturer, host.modelIdentity)
        phase = SessionPhase.Starting
        sync()
        io.execute {
            val made = runCatching { writers.create(setup) }
            main.execute {
                val capture =
                    made.getOrElse { failure ->
                        log("start failed: ${failure.message}")
                        if (phase == SessionPhase.Starting) phase = SessionPhase.Idle
                        sync()
                        return@execute
                    }
                if (released || phase != SessionPhase.Starting) {
                    discardQuietly(capture)
                    return@execute
                }
                val started =
                    host.startCapture(capture) {
                        if (phase == SessionPhase.Starting) {
                            phase = SessionPhase.Recording(capture)
                            status = capture.status
                            log("recording ${setup.sessionId}")
                            sync()
                        }
                    }
                if (!started) {
                    log("start ignored: the screen is busy")
                    discardQuietly(capture)
                    phase = SessionPhase.Idle
                    sync()
                }
            }
        }
    }

    /** Stop (the Rec control while recording, an adb `stop`): the sheet opens. */
    fun stop() {
        val current = phase as? SessionPhase.Recording ?: return ignored("stop")
        stopTo(current.capture, SessionStopReason.OPERATOR)
    }

    /** Save on the sheet ([overrides] from an adb `save` extras). An adb save while recording stops first (answer 16). */
    fun save(overrides: QuickScanSessionCommand? = null) {
        (phase as? SessionPhase.Recording)?.let { if (sheetAvailableWhenStopped()) stopTo(it.capture, SessionStopReason.OPERATOR) }
        val stopped = phase as? SessionPhase.Stopped
        if (stopped == null || !sheetShown) return ignored("save")
        overrides?.let(::applyExtras)
        val tags = fields.tags()
        SessionFieldMemory.last = fields
        confirmDiscard = false
        phase = SessionPhase.Busy(stopped, SAVING)
        saveSubmitted = false
        sync()
        val asked =
            host.export { export ->
                saveSubmitted = true
                io.execute {
                    val result = runCatching { stopped.capture.save(export, tags, writers.baseline(deviceModel)?.p50Ms) }
                    main.execute {
                        result.fold(
                            onSuccess = { saved ->
                                log("saved ${saved.path} outcome=${saved.outcome} defects=${saved.defectReasons}")
                                phase = SessionPhase.Idle
                                notice = SavedNotice(saved)
                                answered(stopped, saved = true)
                            },
                            onFailure = { failure -> phase = stopped.copy(error = "Save failed: ${failure.message}") },
                        )
                        sync()
                    }
                }
            }
        if (!asked) {
            phase = stopped.copy(error = "The scan is busy; try Save again.")
            sync()
        }
    }

    /** Discard… on the sheet: asks "Discard this recording?" first. */
    fun askDiscard() {
        if (phase !is SessionPhase.Stopped) return
        confirmDiscard = true
        sync()
    }

    fun cancelDiscard() {
        confirmDiscard = false
        sync()
    }

    /** The discard question's Discard, or an adb `discard` (no question; while recording it stops first). */
    fun discard() {
        (phase as? SessionPhase.Recording)?.let { if (sheetAvailableWhenStopped()) stopTo(it.capture, SessionStopReason.OPERATOR) }
        val stopped = phase as? SessionPhase.Stopped
        if (stopped == null || !sheetShown) return ignored("discard")
        confirmDiscard = false
        phase = SessionPhase.Busy(stopped, "Discarding…")
        sync()
        io.execute {
            val deleted = runCatching { stopped.capture.discard() }.getOrDefault(false)
            main.execute {
                log(if (deleted) "discarded" else "discard failed: the temp folder stays (never read as complete)")
                phase = SessionPhase.Idle
                answered(stopped, saved = false)
                sync()
            }
        }
    }

    /** Run baseline on the sheet (answer 8): a 30 s no-capture timing run on the live screen; the session waits aside. */
    fun runBaseline() {
        val stopped = phase as? SessionPhase.Stopped
        if (stopped == null || !sheetShown) return ignored("baseline")
        val tap = BaselineTimingTap()
        confirmDiscard = false
        phase = SessionPhase.Busy(stopped, "Starting the baseline run…")
        sync()
        val started =
            host.startBaseline(tap) {
                if ((phase as? SessionPhase.Busy)?.stopped === stopped) {
                    phase = SessionPhase.Baseline(stopped, tap, clockMs())
                    baselineSecondsLeft = BASELINE_SECONDS
                    log("baseline run started")
                    sync()
                }
            }
        if (!started) {
            phase = stopped.copy(error = "The scan is busy; try the baseline run again.")
            sync()
        }
    }

    /** About 4 Hz from the overlay (W1): refreshes the counts, notices a cap stop, and ends a 30 s baseline run. */
    fun tick() {
        when (val current = phase) {
            is SessionPhase.Recording -> {
                status = current.capture.status
                // Answer 7: a frame or size cap stopped the writer by itself.
                if (!current.capture.recording) stopTo(current.capture, current.capture.status.stopReason ?: SessionStopReason.OPERATOR)
            }

            is SessionPhase.Baseline -> {
                val elapsed = clockMs() - current.startedAtMs
                baselineSecondsLeft = ((BASELINE_MS - elapsed + MS_PER_S - 1) / MS_PER_S).toInt().coerceAtLeast(0)
                if (elapsed >= BASELINE_MS) endBaseline(current, store = true)
            }

            is SessionPhase.Stopped -> {
                status = current.capture.status
                latency = current.capture.latency()
                askOutcome(current.capture)
            }

            else -> {
                Unit
            }
        }
    }

    fun updateFields(change: (SessionFields) -> SessionFields) {
        fields = change(fields)
    }

    /** The notice's Dismiss: a completed scan's held hand-off continues (answer 13). */
    fun dismissNotice() {
        notice = null
        labeller = null
        releaseHandOff()
        sync()
    }

    /** The notice's Label: the labeller, pre-filled from the saved session's net. */
    fun openLabeller() {
        val saved = notice?.saved ?: return
        labeller = LabellerState(sessionPrefill(saved.net))
        sync()
    }

    fun updateLabeller(state: LabellerState) {
        if (labeller != null) labeller = state
    }

    /** Back or Close in the labeller: back to the notice; a completed scan's held hand-off continues (answer 13). */
    fun closeLabeller(status: String? = null) {
        labeller = null
        notice = notice?.copy(labelStatus = status ?: notice?.labelStatus)
        releaseHandOff()
        sync()
    }

    /** Save labels: locked until every pre-fill is tapped; a refused count offers Override… or Keep as refused. */
    fun saveLabels() {
        val state = labeller ?: return
        val check = state.check
        labelLock(check)?.let { lock ->
            labeller = state.copy(message = lock, refusal = null)
            return
        }
        if (check.passed) writeLabels(null) else labeller = state.copy(refusal = check, message = null)
    }

    /** Keep as refused (no reason) or Override… with a non-empty [reason]; [StickerTruthCheck][dev.cubentia.domain.recognition.StickerTruthCheck] decides. */
    fun writeLabels(reason: String?) {
        val state = labeller ?: return
        val saved = notice?.saved ?: return
        val labels = ContinuousSessionLabels.of(sessionTruth(state.faces), reason)
        labeller = state.copy(saving = true, overriding = false)
        io.execute {
            val result = runCatching { writers.writeLabels(saved, labels) }
            main.execute {
                result.fold(
                    onSuccess = {
                        val line =
                            when (labels.countCheck) {
                                SessionCountCheck.PASSED -> "Labels saved · 9 of each colour."
                                SessionCountCheck.OVERRIDDEN -> "Labels saved with an override."
                                SessionCountCheck.REFUSED -> "Labels kept as refused; tuning and scoring skip them."
                            }
                        log("labels ${labels.countCheck} for ${saved.sessionId}")
                        closeLabeller(line)
                    },
                    onFailure = { failure -> labeller = labeller?.copy(saving = false, message = "Labels not saved: ${failure.message}") },
                )
            }
        }
    }

    /** An adb `RECORDER_SESSION` command (answers 12, 16, W8): mirrors the on-screen controls; unavailable ones are ignored. */
    fun command(command: QuickScanSessionCommand) {
        when (command.command) {
            "start" -> {
                if (!recAvailable) return ignored("start")
                applyExtras(command)
                pendingLabel = command.label
                rec()
            }

            "stop" -> {
                stop()
            }

            "save" -> {
                save(command)
            }

            "discard" -> {
                discard()
            }

            "baseline" -> {
                runBaseline()
            }

            else -> {
                log("unknown command ${command.command}")
            }
        }
    }

    // ---- Internals ----

    /** For an adb save or discard while recording: the sheet would show once stopped (the live scan is on screen). */
    private fun sheetAvailableWhenStopped() = host.liveScanShown && !host.hostDialogShown

    private fun ignored(command: String) = log("$command ignored: its control is not available (${describe()})")

    private fun describe(): String =
        when (val current = phase) {
            SessionPhase.Idle -> if (host.liveScanShown) "idle" else "the live scan is not shown"
            SessionPhase.Starting -> "starting"
            is SessionPhase.Recording -> "recording"
            is SessionPhase.Stopped -> if (sheetShown) "the sheet is open" else "stopped, the sheet is not shown"
            is SessionPhase.Baseline -> "the baseline run"
            is SessionPhase.Busy -> current.what
        } + (if (labeller != null) ", labeller open" else "") + (if (handOffHeld) ", hand-off held" else "")

    private fun applyExtras(command: QuickScanSessionCommand) {
        val partition =
            command.partition?.let { name ->
                SessionPartition.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }.also {
                    if (it == null) log("unknown partition $name ignored")
                }
            }
        fields =
            fields.copy(
                cube = command.cube ?: fields.cube,
                lighting = command.lighting ?: fields.lighting,
                partition = partition ?: fields.partition,
            )
    }

    private fun stopTo(
        capture: ContinuousSessionCapture,
        reason: SessionStopReason,
        resetRequested: Boolean = false,
    ) {
        capture.stop(reason)
        val stopReason = capture.status.stopReason ?: reason
        phase = SessionPhase.Stopped(capture, stopReason, resetRequested = resetRequested)
        status = capture.status
        latency = capture.latency()
        outcome = null
        outcomeAsked = null
        log("stopped: ${stopLine(stopReason)}")
        loadBaseline()
        sync()
    }

    /** Once per stopped session, with the sheet on the live scan: its derived outcome from the engine's export (W3). */
    private fun askOutcome(capture: ContinuousSessionCapture) {
        if (outcomeAsked === capture || !sheetShown) return
        outcomeAsked = capture
        val last = (capture as? ContinuousSessionWriter)?.lastSnapshot
        val asked =
            host.export { export ->
                if ((phase as? SessionPhase.Stopped)?.capture ===
                    capture
                ) {
                    outcome = SessionOutcome.derive(export, last)
                }
            }
        if (!asked) outcomeAsked = null
    }

    private fun loadBaseline() {
        if (baselineLoaded) return
        baselineLoaded = true
        io.execute {
            val stored = runCatching { writers.baseline(deviceModel) }.getOrNull()
            main.execute { if (baseline == null) baseline = stored }
        }
    }

    /** The baseline run ends: the kept engine and shown scan come back with the sheet; [store] keeps its p50/p95. */
    private fun endBaseline(
        run: SessionPhase.Baseline,
        store: Boolean,
    ) {
        phase = SessionPhase.Busy(run.stopped, if (store) "Storing the baseline…" else "Ending the baseline run…")
        sync()
        host.endBaseline {
            val samples = run.tap.samplesMs()
            if (!store || samples.isEmpty()) {
                log(if (store) "baseline run saw no frame" else "baseline run stopped; nothing stored")
                if ((phase as? SessionPhase.Busy)?.stopped === run.stopped) phase = run.stopped
                sync()
                return@endBaseline
            }
            io.execute {
                val stored = runCatching { writers.saveBaseline(deviceModel, samples) }
                main.execute {
                    stored.getOrNull()?.let {
                        baseline = it
                        log("baseline ${it.deviceModel}: p50 ${ms(it.p50Ms)} ms, p95 ${ms(it.p95Ms)} ms, ${it.frames} frames")
                    }
                    if ((phase as? SessionPhase.Busy)?.stopped === run.stopped) {
                        phase =
                            stored.exceptionOrNull()?.let { run.stopped.copy(error = "Baseline not saved: ${it.message}") } ?: run.stopped
                    }
                    sync()
                }
            }
        }
    }

    /** After Save or Discard: a Reset's map clear (answer 5); a discarded completion continues at once (answer 13). */
    private fun answered(
        stopped: SessionPhase.Stopped,
        saved: Boolean,
    ) {
        confirmDiscard = false
        // Keyed on what the operator did, not on the writer's stop reason: a cap may have stopped the writer first.
        if (stopped.resetRequested) host.resetMap()
        if (!saved) releaseHandOff()
    }

    private fun releaseHandOff() {
        if (!handOffHeld) return
        handOffHeld = false
        sync()
        host.releaseHandOff()
    }

    private fun discardQuietly(capture: ContinuousSessionCapture) {
        capture.stop(SessionStopReason.OPERATOR)
        // After release() shut io down (a writer made late), discard here rather than throw on main.
        try {
            io.execute { runCatching { capture.discard() } }
        } catch (_: RejectedExecutionException) {
            runCatching { capture.discard() }
        }
    }

    /** Mirrors what the analyzer thread reads. */
    private fun sync() {
        holdsAnalysisFlag =
            when (phase) {
                is SessionPhase.Stopped, is SessionPhase.Busy, SessionPhase.Starting -> true

                // The baseline run analyses live; the pending session's hold comes back with it.
                is SessionPhase.Baseline -> false

                else -> labeller != null || handOffHeld
            }
    }

    companion object {
        const val TAG = "QuickScanSession"
        const val BASELINE_MS = 30_000L
        const val BASELINE_SECONDS = 30
        private const val MS_PER_S = 1_000L
        private const val SAVING = "Saving…"

        fun ms(value: Double): String = "%.1f".format(Locale.ROOT, value)

        /** The sheet's first line (W2). */
        fun stopLine(reason: SessionStopReason): String =
            "Stopped: " +
                when (reason) {
                    SessionStopReason.OPERATOR -> "operator"
                    SessionStopReason.COMPLETE -> "complete"
                    SessionStopReason.RESET -> "reset"
                    SessionStopReason.FRAME_CAP -> "frame cap"
                    SessionStopReason.SIZE_CAP -> "size cap"
                }

        /** The recording indicator (W1): `● REC <frames> · <skips> skips`. */
        fun recIndicator(status: ContinuousCaptureStatus?): String = "● REC ${status?.frames ?: 0} · ${status?.skips ?: 0} skips"

        /** The baseline run's indicator (W1): `● Baseline <seconds left> s`. */
        fun baselineIndicator(secondsLeft: Int): String = "● Baseline $secondsLeft s"

        /** The saved-session notice (answer 9): `Saved <id>`, with ` · defective: <reasons>` when it applies. */
        fun noticeLine(saved: ContinuousSessionSaved): String =
            "Saved ${saved.sessionId}" +
                if (saved.defectReasons.isEmpty()) "" else " · defective: ${saved.defectReasons.joinToString("; ")}"

        /** The sheet's numbers line (W4): `<frames> frames · <skips> skips · <MB> MB`. */
        fun countsLine(status: ContinuousCaptureStatus?): String =
            "${status?.frames ?: 0} frames · ${status?.skips ?: 0} skips · " +
                "%.1f MB".format(Locale.ROOT, (status?.bytes ?: 0L) / 1_000_000.0)

        fun latencyLine(latency: ContinuousCaptureLatency): String =
            "Capture p50 ${latency.p50Ms?.let(::ms) ?: "–"} ms · p95 ${latency.p95Ms?.let(::ms) ?: "–"} ms"

        fun baselineLine(baseline: ContinuousLatencyBaseline?): String =
            baseline?.let { "Baseline p50 ${ms(it.p50Ms)} ms" } ?: "No baseline yet"

        /** `Will be marked defective: <reasons>` when it already applies (W4), else null. */
        fun defectPreview(
            status: ContinuousCaptureStatus?,
            latency: ContinuousCaptureLatency,
            baseline: ContinuousLatencyBaseline?,
        ): String? =
            SessionLatency
                .defectReasons(status?.skips ?: 0, latency.p50Ms, baseline?.p50Ms)
                .takeIf { it.isNotEmpty() }
                ?.let { "Will be marked defective: ${it.joinToString("; ")}" }

        fun outcomeLine(outcome: SessionOutcome?): String = "Outcome: " + (outcome?.name?.lowercase()?.replace('_', ' ') ?: "–")
    }
}
