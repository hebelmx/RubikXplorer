package dev.cubentia.app.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaActionSound
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import dev.cubentia.app.content.CatalogState
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.content.LessonCatalog
import dev.cubentia.app.preferences.PreferenceProvider
import dev.cubentia.app.preferences.PreferenceStore
import dev.cubentia.app.scan.ScanFlowContract
import dev.cubentia.app.scan.ScanOutcome
import dev.cubentia.app.ui.scan.ScanCoachMarkRecord
import dev.cubentia.app.ui.scan.ScanReviewScreen
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.domain.recognition.GuidedScanController
import dev.cubentia.domain.recognition.GuidedScanEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * The Guided 6-face Scan (Story 10.2): camera rationale and permission, then a CameraX viewfinder whose analyzer feeds
 * [GuidedScanController]; finishes with a [ScanOutcome] encoded by [ScanFlowContract]. Frames are read on the
 * analyzer thread and dropped; nothing is written, kept or sent.
 */
@AndroidEntryPoint
class GuidedScanActivity : ComponentActivity() {
    @Inject
    lateinit var lessonCatalog: LessonCatalog

    @Inject
    lateinit var preferenceStore: PreferenceStore

    @Inject lateinit var calibrationRepository: dev.cubentia.application.port.CalibrationRepository

    @Inject lateinit var updateCalibration: dev.cubentia.application.usecase.UpdateCalibration

    /** The system camera shutter sound played on each capture; released with the Activity. */
    private val shutterSound = MediaActionSound()

    private val scan: GuidedScanViewModel by viewModels()
    private val analyzerExecutor get() = scan.analyzerExecutor
    private val controller get() = scan.controller
    private var phase: GuidedScanPhase
        get() = scan.phase
        set(value) {
            scan.phase = value
        }

    @Inject internal lateinit var frameSource: ScanFrameSource
    private var cameraStarted = false
    private var previewReady = false

    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                phase = scan.capturePhase()
                clearDenialRecord()
                startCamera()
                return@registerForActivityResult
            }
            val rationaleAfter = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            val rationaleBefore = scan.rationaleBefore
            lifecycleScope.launch {
                // SharedPreferences reads the disk: off the main thread.
                val deniedBefore = withContext(Dispatchers.IO) { CameraDenialRecord.readAndMark(applicationContext) }
                phase = GuidedScanFlow.afterPermission(false, rationaleBefore, rationaleAfter, deniedBefore)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)
        // Back and a destroyed task read as Cancelled.
        setResult(RESULT_CANCELED)
        if (phase == GuidedScanPhase.Rationale) phase = GuidedScanFlow.initialPhase(hasPermission())
        scan.calibrationMode = intent.getBooleanExtra(CALIBRATION_MODE, false)
        if (phase is GuidedScanPhase.Viewfinder && scan.calibrationMode) phase = scan.capturePhase()
        lifecycleScope.launch {
            if (!scan.calibrationLoaded) {
                scan.saved = (calibrationRepository.load() as? dev.cubentia.application.port.CalibrationLoad.Loaded)?.calibration
                scan.calibrationLoaded = true
            }
            startCamera()
        }
        lifecycleScope.launch { lessonCatalog.load() }
        lifecycleScope.launch {
            // SharedPreferences reads the disk: off the main thread.
            scan.coachMarkPending = withContext(Dispatchers.IO) { ScanCoachMarkRecord.pending(applicationContext) }
        }
        val actions =
            GuidedScanActions(
                onClose = {
                    val calibration = phase as? GuidedScanPhase.Calibration
                    if (!scan.saving) {
                        if (calibration?.fromReview != null) phase = calibration.fromReview else finishWith(ScanOutcome.Cancelled)
                    }
                },
                onContinue = {
                    scan.rationaleBefore = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
                    permission.launch(Manifest.permission.CAMERA)
                },
                onEnterColors = { finishWith(ScanOutcome.FellBackToEditor) },
                onOpenSettings = {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                    }
                },
                onCapture = {
                    if (phase is GuidedScanPhase.Calibration) {
                        scan.captureCalibration = true
                    } else {
                        decide { now -> controller.onShutter(now) }
                    }
                },
                onKeep = {
                    (phase as? GuidedScanPhase.Calibration)?.let {
                        phase =
                            it.copy(
                                session = it.session.keep(),
                                saveFailed = false,
                            )
                    }
                },
                onRetake = {
                    (phase as? GuidedScanPhase.Calibration)?.let {
                        phase =
                            it.copy(session = it.session.retake(), saveFailed = false)
                    }
                },
                onSave = { scan.saveCalibration(updateCalibration) },
                onUseAnyway = { decide { now -> controller.onUseAnyway(now) } },
                onFreezeEnd = { id -> scan.endFreeze(id) },
            )
        setContent {
            val catalog by lessonCatalog.state.collectAsState()
            // Content loads in a moment; until then show nothing rather than raw keys.
            val strings =
                when (val state = catalog) {
                    is CatalogState.Ready -> state.strings
                    is CatalogState.Failed -> ContentStrings(emptyMap())
                    CatalogState.Loading -> null
                }
            PreferenceProvider(preferenceStore) {
                CubentiaTheme {
                    if (strings == null) return@CubentiaTheme
                    androidx.activity.compose.BackHandler(enabled = phase is GuidedScanPhase.Calibration) {
                        val calibration = phase as? GuidedScanPhase.Calibration
                        if (!scan.saving) {
                            if (calibration?.fromReview != null) phase = calibration.fromReview else finishWith(ScanOutcome.Cancelled)
                        }
                    }
                    LaunchedEffect(scan.calibrationFinished) {
                        if (scan.calibrationFinished) finishWith(ScanOutcome.Cancelled)
                    }
                    LaunchedEffect(phase::class) {
                        if (cameraPhase()) {
                            frameSource.stop()
                            cameraStarted = false
                            startCamera()
                        }
                    }
                    val reviewPhase = phase as? GuidedScanPhase.Review
                    LaunchedEffect(reviewPhase != null) {
                        if (reviewPhase != null) {
                            frameSource.stop()
                            cameraStarted = false
                            previewReady = false
                        }
                    }
                    if (reviewPhase != null) {
                        ScanReviewScreen(
                            reviewPhase.review,
                            strings,
                            onReview = {
                                phase =
                                    reviewPhase.copy(
                                        review = it,
                                        corrections =
                                            reviewPhase.corrections +
                                                it.stickers.indices
                                                    .filter { i ->
                                                        it.stickers[i] != reviewPhase.review.stickers[i]
                                                    }.mapNotNull { i ->
                                                        it.stickers[i]?.let { c ->
                                                            i to
                                                                c
                                                        }
                                                    },
                                        edited = reviewPhase.edited || it.stickers != reviewPhase.review.stickers,
                                    )
                            },
                            onCalibrate =
                                if (reviewPhase.offerCalibration) {
                                    (
                                        {
                                            // A Capture tap left over from an earlier calibration never samples this one.
                                            scan.captureCalibration = false
                                            scan.calibrationMeterColor = null
                                            phase =
                                                GuidedScanPhase.Calibration(
                                                    fromReview = reviewPhase,
                                                )
                                        }
                                    )
                                } else {
                                    null
                                },
                            onAccept = { finishWith(ScanOutcome.Accepted(it)) },
                            onClose = { finishWith(ScanOutcome.Cancelled) },
                            modifier = Modifier.fillMaxSize(),
                            coachMarkPending = scan.coachMarkPending,
                            edited = reviewPhase.edited,
                            onCoachMarkDismissed = {
                                scan.coachMarkPending = false
                                lifecycleScope.launch(Dispatchers.IO) { ScanCoachMarkRecord.markSeen(applicationContext) }
                            },
                        )
                    } else {
                        GuidedScanScreen(phase, strings, actions, scan.frozen) {
                            AndroidView(
                                factory = { context ->
                                    frameSource.stop()
                                    cameraStarted = false
                                    frameSource.preview(context).also {
                                        previewReady = true
                                        startCamera()
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        frameSource.stop()
        shutterSound.release()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Back from Settings with the permission now granted.
        if (phase is GuidedScanPhase.Denied && hasPermission()) {
            phase = scan.capturePhase()
            clearDenialRecord()
        }
    }

    /** A grant ends the denial history, so a later revoke and first dismissal is not read as permanent. */
    private fun clearDenialRecord() {
        lifecycleScope.launch { withContext(Dispatchers.IO) { CameraDenialRecord.clear(applicationContext) } }
    }

    private fun hasPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun finishWith(capture: ScanOutcome) {
        val (code, data) = ScanFlowContract.encode(capture)
        setResult(code, data)
        finish()
    }

    private fun cameraPhase() = phase is GuidedScanPhase.Viewfinder || phase is GuidedScanPhase.Calibration

    private fun startCamera() {
        if (!previewReady || cameraStarted || !hasPermission() || !scan.calibrationLoaded || !cameraPhase()) return
        cameraStarted = true
        frameSource.start(this, analyzerExecutor, ::analyze) { if (cameraPhase()) phase = GuidedScanPhase.Unavailable }
    }

    /** Analyzer thread: read the frame into stats, drop it, and run the controller — unless a capture is frozen. */
    private fun analyze(frame: ScanFrame) {
        val now = SystemClock.elapsedRealtime()
        if (scan.freeze.isFrozen(now)) return
        val rotation = frame.rotationDegrees
        val stats = frame.stats
        // One snapshot: main may leave calibration while this frame is read.
        val calibrating = phase as? GuidedScanPhase.Calibration
        if (calibrating != null) {
            val color = calibrating.session.current
            if (scan.calibrationMeterColor != color) {
                scan.calibrationMeterColor = color
                ContextCompat.getMainExecutor(this).execute { meterOnGrid() }
            }
            val effects = scan.calibrationHold.onFrame(calibrating.session, stats, rotation, now)
            if (GuidedScanFlow.remeters(effects)) ContextCompat.getMainExecutor(this).execute { meterOnGrid() }
            if (scan.captureCalibration || effects.any { it is GuidedScanEffect.Capture }) {
                scan.captureCalibration = false
                val quality =
                    dev.cubentia.domain.recognition.FrameQuality
                        .assessShutter(stats)
                val ring =
                    if (quality ==
                        dev.cubentia.domain.recognition.CaptureQuality.ACCEPTABLE
                    ) {
                        frame.ring()
                    } else {
                        null
                    }
                val requested = calibrating.session
                ContextCompat.getMainExecutor(this).execute {
                    // A reading applies only to the session that asked for it, never to a later step or a reopened flow.
                    (phase as? GuidedScanPhase.Calibration)?.takeIf { it.session === requested }?.let {
                        phase = GuidedScanFlow.calibrationCapture(it, stats, ring)
                    }
                }
            }
            return
        }
        handle(scan.freeze.decide(now) { controller.onFrame(stats, rotation, now) })
    }

    /** A shutter or override decision on the analyzer thread; none runs while a capture is frozen. */
    private fun decide(decision: (Long) -> List<GuidedScanEffect>) {
        analyzerExecutor.execute {
            val now = SystemClock.elapsedRealtime()
            handle(scan.freeze.decide(now) { decision(now) })
        }
    }

    /**
     * Analyzer thread: publish the controller state and run its effects on main. State and result go to the ViewModel,
     * so an Activity recreated meanwhile still shows them and finishes with the result.
     */
    private fun handle(effects: List<GuidedScanEffect>) {
        val vm = scan
        val state = GuidedScanFlow.phaseOf(controller)
        val result = GuidedScanFlow.reviewOf(effects, scan.saved)
        val captured = effects.filterIsInstance<GuidedScanEffect.Capture>().firstOrNull()
        ContextCompat.getMainExecutor(this).execute {
            if (captured != null) {
                // Capture feedback (PO 2026-11-16): shutter sound, then the frame frozen and shrunk into the net.
                shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                vm.frozen = FrozenCapture(++vm.captures, captured.sample.face, frameSource.snapshot())
            }
            if (vm.phase is GuidedScanPhase.Viewfinder && vm.phase != state) vm.phase = state
            if (GuidedScanFlow.remeters(effects)) meterOnGrid()
            if (result != null) vm.publish(result)
        }
    }

    private fun meterOnGrid() = frameSource.meter()

    companion object {
        const val CALIBRATION_MODE = "dev.cubentia.calibration"
        const val METERING_LOCK_SECONDS = 3L
    }
}

/**
 * What survives a configuration change: the controller (touched only on [analyzerExecutor]), its analyzer thread, the
 * shown phase, the rationale state before the last permission request and a finished result. Holds no frame, except the
 * preview image of a face just captured for the few hundred milliseconds of its freeze.
 */
internal class GuidedScanViewModel : ViewModel() {
    val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile var saved: dev.cubentia.domain.recognition.CalibrationCenters? = null
    var calibrationLoaded = false
    var calibrationMode = false
    var saving by mutableStateOf(false)
    var calibrationFinished by mutableStateOf(false)

    val calibrationHold = CalibrationHold()
    private var corrections: Map<Int, dev.cubentia.domain.cube.Color> = emptyMap()

    var calibrationMeterColor: dev.cubentia.domain.cube.Color? = null

    @Volatile var captureCalibration = false

    fun capturePhase(): GuidedScanPhase = if (calibrationMode) GuidedScanPhase.Calibration() else GuidedScanPhase.Viewfinder()

    fun newController() = GuidedScanController(reprompt = { GuidedScanController.defaultReprompt(it, saved) })

    fun saveCalibration(update: dev.cubentia.application.usecase.UpdateCalibration) {
        val calibration = phase as? GuidedScanPhase.Calibration ?: return
        val centers = calibration.session.calibration ?: return
        if (saving) return
        saving = true
        viewModelScope.launch {
            try {
                if (update(centers) == dev.cubentia.application.port.CalibrationWrite.Saved) {
                    saved = centers
                    if (calibration.fromReview == null) {
                        calibrationFinished = true
                    } else {
                        corrections = calibration.fromReview.corrections
                        withContext(analyzerExecutor.asCoroutineDispatcher()) { controller = newController() }
                        phase = GuidedScanPhase.Viewfinder()
                    }
                } else {
                    phase = calibration.copy(saveFailed = true)
                }
            } finally {
                saving = false
            }
        }
    }

    var controller = newController()
    var phase by mutableStateOf<GuidedScanPhase>(GuidedScanPhase.Rationale)

    /** Whether the system would have shown a rationale just before the last permission request. */
    var rationaleBefore = false

    /** Whether the review's one-time coach mark is still to be shown; false until the record is read. */
    var coachMarkPending by mutableStateOf(false)

    /** No capture decision runs while a capture is frozen (touched on [analyzerExecutor] only). */
    val freeze = CaptureFreeze()

    /** The face just captured, frozen on screen until the screen ends its freeze; set and cleared on main. */
    var frozen by mutableStateOf<FrozenCapture?>(null)

    /** Captures so far, numbering each [FrozenCapture]; main thread only. */
    var captures = 0L

    /** A finished scan held until the last face's freeze ends, so its freeze, shrink and shutter sound play out. */
    private var held: GuidedScanPhase.Review? = null

    /** Main thread: publishes the finished scan now, or when the current freeze ends. */
    fun publish(capture: dev.cubentia.domain.recognition.ScanReview) = publish(GuidedScanPhase.Review(capture))

    fun publish(capture: GuidedScanPhase.Review) {
        val corrected =
            capture.copy(
                review = GuidedScanFlow.withCorrections(capture.review, corrections),
                corrections = corrections,
                edited = corrections.isNotEmpty(),
            )
        if (frozen != null) held = corrected else phase = corrected
    }

    /** Main thread: the freeze of capture [id] ended; live view resumes, or a held result is published. */
    fun endFreeze(id: Long) {
        if (frozen?.id != id) return
        frozen = null
        held?.let {
            held = null
            phase = it
        }
    }

    override fun onCleared() {
        analyzerExecutor.shutdown()
    }
}

/**
 * Whether the camera permission was ever denied in an earlier scan, so an already-permanent denial (the system dialog
 * no longer shows) still offers *Open settings*. A private preference holding this one boolean and nothing else; call
 * it off the main thread.
 */
internal object CameraDenialRecord {
    private const val FILE = "camera_permission"
    private const val KEY_DENIED = "denied_before"

    fun denied(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_DENIED, false)

    /** Returns whether a denial was recorded before this one, and records this one. */
    fun readAndMark(context: Context): Boolean {
        val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val before = preferences.getBoolean(KEY_DENIED, false)
        preferences.edit().putBoolean(KEY_DENIED, true).apply()
        return before
    }

    /** Forgets the recorded denial (the permission was granted). */
    fun clear(context: Context) {
        context
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_DENIED)
            .apply()
    }
}
