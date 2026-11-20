package dev.cubentia.app.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import dev.cubentia.app.ScanDiagnostics
import dev.cubentia.app.content.CatalogState
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.content.LessonCatalog
import dev.cubentia.app.preferences.PreferenceProvider
import dev.cubentia.app.preferences.PreferenceStore
import dev.cubentia.app.scan.CaseCaptureContract
import dev.cubentia.app.scan.QuickCheckContract
import dev.cubentia.app.scan.RunScanColorsExtra
import dev.cubentia.app.scan.ScanFlowContract
import dev.cubentia.app.scan.ScanOutcome
import dev.cubentia.app.ui.scan.ScanCoachMarkRecord
import dev.cubentia.app.ui.scan.ScanReviewScreen
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.application.port.CalibrationLoad
import dev.cubentia.application.port.CalibrationRepository
import dev.cubentia.application.port.CalibrationWrite
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.CaseCaptureController
import dev.cubentia.domain.recognition.CaseCaptureEffect
import dev.cubentia.domain.recognition.CornerView
import dev.cubentia.domain.recognition.FrameQuality
import dev.cubentia.domain.recognition.GuidedScanController
import dev.cubentia.domain.recognition.GuidedScanEffect
import dev.cubentia.domain.recognition.GuidedScanPlan
import dev.cubentia.domain.recognition.QuickScanController
import dev.cubentia.domain.recognition.QuickScanEffect
import dev.cubentia.domain.recognition.ScanReview
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

    @Inject lateinit var calibrationRepository: CalibrationRepository

    @Inject lateinit var updateCalibration: dev.cubentia.application.usecase.UpdateCalibration

    /** The system camera shutter sound played on each capture; loaded off main, released with the Activity. */
    private val shutterSound = ShutterSound()

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
    private var previewReady by mutableStateOf(false)

    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                phase = scan.capturePhase()
                clearDenialRecord()
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
        // MediaActionSound.load reads the disk: off the main thread (GH #158).
        lifecycleScope.launch(Dispatchers.IO) { shutterSound.load() }
        // Back and a destroyed task read as Cancelled.
        setResult(RESULT_CANCELED)
        if (phase == GuidedScanPhase.Rationale) phase = GuidedScanFlow.initialPhase(hasPermission())
        scan.calibrationMode = intent.getBooleanExtra(CALIBRATION_MODE, false)
        scan.quickScanMode = intent.getBooleanExtra(EXTRA_QUICK_SCAN, false)
        scan.caseCorners = intent.getIntExtra(EXTRA_CASE_CAPTURE, 0).takeIf { it in 1..2 }
        val solveScan =
            !scan.calibrationMode &&
                !intent.hasExtra(QuickCheckContract.EXTRA_FACES) &&
                !intent.getBooleanExtra(QuickCheckContract.EXTRA_FULL_QUICK_CHECK, false)
        if (phase is GuidedScanPhase.Viewfinder && (scan.calibrationMode || scan.quickScanMode) && !scan.calibrationLoaded) {
            phase = scan.capturePhase()
        }
        lifecycleScope.launch {
            if (!scan.calibrationLoaded) {
                scan.saved = (calibrationRepository.load() as? CalibrationLoad.Loaded)?.calibration
                // A saved calibration always wins; otherwise a Quick Check may use the run's start-scan colors (GH #172).
                if (scan.saved == null) scan.runScan = RunScanColorsExtra.get(intent)
                val faces = intent.getStringArrayExtra(QuickCheckContract.EXTRA_FACES)
                scan.quickFaces =
                    runCatching { faces?.map(Face::valueOf)?.toSet() }
                        .getOrNull()
                        ?.takeIf { it.isNotEmpty() && it.size < 6 && (scan.saved ?: scan.runScan) != null }
                withContext(scan.analyzerExecutor.asCoroutineDispatcher()) { scan.controller = scan.newController() }
                if (scan.quickScanMode && scan.quickScan == null) scan.quickScan = QuickScanController(scan.saved)
                val corners = scan.caseCorners
                val saved = scan.saved
                if (corners != null && saved != null) scan.caseCapture = CaseCaptureController(saved, corners)
                scan.calibrationLoaded = true
                if (phase is GuidedScanPhase.Viewfinder || phase is GuidedScanPhase.CornerViewfinder) phase = scan.capturePhase()
            }
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
                // Like Close, never leave while a calibration write is in flight.
                onEnterColors = { if (!scan.saving) finishWith(ScanOutcome.FellBackToEditor) },
                onOpenSettings = {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                    }
                },
                onCapture = {
                    when (phase) {
                        is GuidedScanPhase.Calibration -> {
                            scan.captureCalibration = true
                        }

                        is GuidedScanPhase.CornerViewfinder -> {
                            analyzerExecutor.execute {
                                if (scan.caseCorners != null) captureCase { it.onShutter() } else captureCorner { it.onShutter() }
                            }
                        }

                        else -> {
                            decide { now -> scan.guidedController()?.onShutter(now).orEmpty() }
                        }
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
                onUseAnyway = { decide { now -> scan.guidedController()?.onUseAnyway(now).orEmpty() } },
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
                    androidx.activity.compose.BackHandler(enabled = phase is GuidedScanPhase.Calibration) {
                        val calibration = phase as? GuidedScanPhase.Calibration
                        if (!scan.saving) {
                            if (calibration?.fromReview != null) phase = calibration.fromReview else finishWith(ScanOutcome.Cancelled)
                        }
                    }
                    androidx.activity.compose.BackHandler(enabled = phase !is GuidedScanPhase.Calibration) {
                        finishWith(ScanOutcome.Cancelled)
                    }
                    if (strings == null) return@CubentiaTheme
                    LaunchedEffect(scan.caseResult) {
                        scan.caseResult?.let { views ->
                            val (code, data) = CaseCaptureContract.encode(views)
                            setResult(code, data)
                            finish()
                        }
                    }
                    LaunchedEffect(scan.quickResult) {
                        scan.quickResult?.let { partial ->
                            val (code, data) = QuickCheckContract.encode(partial)
                            setResult(code, data)
                            finish()
                        }
                    }
                    LaunchedEffect(scan.calibrationFinished) {
                        if (scan.calibrationFinished) finishWith(ScanOutcome.Cancelled)
                    }
                    val ready = previewReady && scan.calibrationLoaded
                    LaunchedEffect(phase::class, ready) {
                        frameSource.stop()
                        cameraStarted = false
                        if (cameraPhase()) {
                            if (ready) startCamera()
                        } else {
                            previewReady = false
                        }
                    }
                    val reviewPhase = phase as? GuidedScanPhase.Review
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
                            onEnterColors = actions.onEnterColors.takeIf { solveScan },
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
                            onAccept = { finishWith(ScanOutcome.Accepted(it, scan.runScanColorsToReturn())) },
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
                        GuidedScanScreen(phase, strings, actions, scan.frozen, offerEnterColors = solveScan) {
                            AndroidView(
                                factory = { context ->
                                    frameSource.preview(context).also {
                                        previewReady = true
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

    private val isQuickCheck
        get() =
            intent.hasExtra(QuickCheckContract.EXTRA_FACES) ||
                intent.getBooleanExtra(QuickCheckContract.EXTRA_FULL_QUICK_CHECK, false)

    private fun finishWith(capture: ScanOutcome) {
        val review = (phase as? GuidedScanPhase.Review) ?: (phase as? GuidedScanPhase.Calibration)?.fromReview
        val outcome =
            when (capture) {
                is ScanOutcome.Accepted -> "ACCEPTED"
                ScanOutcome.Cancelled -> "CANCELLED"
                ScanOutcome.FellBackToEditor -> "FELL_BACK_TO_EDITOR"
            }
        if (isQuickCheck && !scan.guidedLogged) {
            scan.guidedLogged = true
            ScanDiagnostics.onQuickCheckCaptured(scan.quickCheckStartedAt, scan.captures)
        } else if (scan.isGuidedRun && !scan.guidedLogged) {
            scan.guidedLogged = true
            ScanDiagnostics.onGuidedScanRun(
                scan.guidedStartedAt,
                controller.repromptCount,
                review?.review,
                review?.initialStickers,
                review?.corrections,
                outcome,
            )
        }
        val quick = scan.quickScan
        val started = scan.quickStartedAt
        if (scan.quickScanMode && !scan.quickLogged) {
            scan.quickLogged = true
            dev.cubentia.app.ScanDiagnostics.onQuickScanRun(
                started,
                quick?.fallbackFaces?.size ?: 0,
                quick?.repromptFaces?.size ?: 0,
                review?.corrections,
                outcome,
                review?.review?.stickers,
                review?.initialStickers,
            )
        }
        val (code, data) =
            if (scan.caseCorners != null && capture == ScanOutcome.FellBackToEditor) {
                CaseCaptureContract.editor()
            } else {
                ScanFlowContract.encode(capture)
            }
        setResult(code, data)
        finish()
    }

    private fun cameraPhase() =
        phase is GuidedScanPhase.Viewfinder || phase is GuidedScanPhase.CornerViewfinder || phase is GuidedScanPhase.Calibration

    private fun startCamera() {
        if (!previewReady || cameraStarted || !hasPermission() || !scan.calibrationLoaded || !cameraPhase()) return
        cameraStarted = true
        frameSource.cornerMode(phase is GuidedScanPhase.CornerViewfinder)
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
            val coaching = scan.calibrationHold.coaching
            ContextCompat.getMainExecutor(this).execute {
                (phase as? GuidedScanPhase.Calibration)
                    ?.takeIf { it.session === calibrating.session && it.session.pending == null }
                    ?.let { phase = it.copy(coaching = coaching) }
            }
            if (GuidedScanFlow.remeters(effects)) ContextCompat.getMainExecutor(this).execute { meterOnGrid() }
            if (scan.captureCalibration || effects.any { it is GuidedScanEffect.Capture }) {
                scan.captureCalibration = false
                val quality =
                    FrameQuality
                        .assessShutter(stats)
                val ring =
                    if (quality ==
                        CaptureQuality.ACCEPTABLE
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
        if (scan.caseCorners != null) {
            val corner = frame.corner ?: return
            captureCase { it.onFrame(corner.detected, corner.faces, now) }
            return
        }
        val quick = scan.quickScan
        if (quick?.inCorner == true) {
            val corner = frame.corner ?: return
            if (scan.quickStartedAt == null) scan.quickStartedAt = ScanDiagnostics.startRun()
            dev.cubentia.app.ScanDiagnostics
                .onCornerFrame(now, corner.detected, corner.faces, corner.score)
            captureCorner { it.onFrame(corner.detected, corner.faces, now) }
            return
        }
        if (frame.corner != null) return
        val active = scan.guidedController() ?: return
        if (!isQuickCheck && scan.isGuidedRun && scan.guidedStartedAt == null) scan.guidedStartedAt = ScanDiagnostics.startRun()
        if (isQuickCheck && scan.quickCheckStartedAt == null) {
            scan.quickCheckStartedAt = ScanDiagnostics.startRun()
        }
        handle(scan.freeze.decide(now) { active.onFrame(stats, rotation, now) })
    }

    private fun captureCase(decision: (CaseCaptureController) -> List<CaseCaptureEffect>) {
        val controller = scan.caseCapture ?: return
        if (phase !is GuidedScanPhase.CornerViewfinder || controller.hold > controller.corners) return
        val effects = decision(controller)
        val done = effects.filterIsInstance<CaseCaptureEffect.Done>().firstOrNull()
        val next = if (done == null) GuidedScanFlow.phaseOf(controller) else null
        ContextCompat.getMainExecutor(this).execute {
            if (phase !is GuidedScanPhase.CornerViewfinder || isFinishing) return@execute
            if (effects.any { it is CaseCaptureEffect.CornerCaptured }) shutterSound.play()
            if (CaseCaptureEffect.Remeter in effects) meterOnGrid()
            if (next != null && phase != next) phase = next
            if (done != null) {
                ScanDiagnostics.onCaseCaptured()
                scan.caseResult = done.views
            }
        }
    }

    private fun captureCorner(decision: (QuickScanController) -> List<QuickScanEffect>) {
        val quick = scan.quickScan ?: return
        val now = SystemClock.elapsedRealtime()
        if (scan.freeze.isFrozen(now) || !quick.inCorner) return
        val effects = decision(quick)
        val capture = effects.filterIsInstance<QuickScanEffect.CornerCaptured>().firstOrNull()
        if (capture != null) scan.freeze.start(now)
        val next = GuidedScanFlow.phaseOf(quick)
        val review = quickReview(effects.filterIsInstance<QuickScanEffect.Done>().firstOrNull())
        ContextCompat.getMainExecutor(this).execute {
            if (phase !is GuidedScanPhase.CornerViewfinder) return@execute
            if (capture != null) {
                shutterSound.play()
                scan.frozen = FrozenCapture(++scan.captures, Face.U, frameSource.snapshot(), shrink = false)
            }
            if (next != null && phase != next) phase = next
            if (QuickScanEffect.Remeter in effects) meterOnGrid()
            if (review != null) scan.publish(review)
        }
    }

    private fun quickReview(done: QuickScanEffect.Done?): GuidedScanPhase.Review? =
        done?.let { GuidedScanFlow.reviewOf(listOf(GuidedScanEffect.Done(it.samples)), scan.saved) }

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
        val quick = scan.quickScan
        val quickDone = quick?.onGuidedEffects(effects) as? QuickScanEffect.Done
        val state = if (quick == null) GuidedScanFlow.phaseOf(controller, scan.quickFaces != null) else GuidedScanFlow.phaseOf(quick)
        val done = effects.filterIsInstance<GuidedScanEffect.Done>().firstOrNull()
        done?.takeIf { quick == null }?.let {
            dev.cubentia.app.ScanDiagnostics
                .onAssembly(if (scan.quickFaces == null) "final" else "quick", it.samples, scan.saved ?: scan.runScan)
        }
        val partial =
            if (scan.quickFaces != null &&
                done != null
            ) {
                GuidedScanFlow.quickCheckOf(done.samples, checkNotNull(scan.saved ?: scan.runScan), runScan = scan.saved == null)
            } else {
                null
            }
        // A guided run's own centers, measured with no saved calibration, go back with Accepted for the run (GH #172).
        if (done != null && quick == null && scan.isGuidedRun) {
            scan.measuredCenters = GuidedScanFlow.runScanColorsOf(done.samples, scan.saved)
        }
        val result =
            if (quick != null) {
                quickReview(quickDone)
            } else if (scan.quickFaces == null) {
                GuidedScanFlow.reviewOf(effects, scan.saved)
            } else {
                null
            }
        val captured = effects.filterIsInstance<GuidedScanEffect.Capture>().firstOrNull()
        ContextCompat.getMainExecutor(this).execute {
            if (captured != null) {
                // Capture feedback (PO 2026-11-16): shutter sound, then the frame frozen and shrunk into the net.
                shutterSound.play()
                vm.frozen = FrozenCapture(++vm.captures, captured.sample.face, frameSource.snapshot())
            }
            if (state != null && vm.phase is GuidedScanPhase.Viewfinder && vm.phase != state) vm.phase = state
            if (GuidedScanFlow.remeters(effects)) meterOnGrid()
            if (partial != null) {
                ScanDiagnostics.onQuickCheckCaptured(vm.quickCheckStartedAt, vm.captures)
                vm.quickResult = partial
            }
            if (result != null) vm.publish(result)
        }
    }

    private fun meterOnGrid() = frameSource.meter()

    companion object {
        const val EXTRA_CASE_CAPTURE = "dev.cubentia.app.scan.CASE_CAPTURE"
        const val EXTRA_QUICK_SCAN = "dev.cubentia.app.scan.QUICK_SCAN"
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

    @Volatile var saved: CalibrationCenters? = null
    var calibrationLoaded by mutableStateOf(false)
    var quickResult by mutableStateOf<dev.cubentia.domain.recognition.PhysicalObservation.Partial?>(null)
    var calibrationMode = false
    var quickScanMode = false

    @Volatile var caseCorners: Int? = null

    @Volatile var caseCapture: CaseCaptureController? = null
    var caseResult by mutableStateOf<List<CornerView>?>(null)

    @Volatile var quickScan: QuickScanController? = null

    @Volatile var quickStartedAt: Long? = null
    var quickLogged = false

    @Volatile var guidedStartedAt: Long? = null

    @Volatile var quickCheckStartedAt: Long? = null
    var guidedLogged = false
    val isGuidedRun get() = !quickScanMode && quickScan == null && caseCorners == null && quickFaces == null && !calibrationMode
    var saving by mutableStateOf(false)
    var calibrationFinished by mutableStateOf(false)

    val calibrationHold = CalibrationHold()
    private var corrections: Map<Int, dev.cubentia.domain.cube.Color> = emptyMap()

    var calibrationMeterColor: dev.cubentia.domain.cube.Color? = null

    @Volatile var captureCalibration = false

    /** The capture phase a camera grant opens: calibration, or the controller's current step (a Quick Check's first face). */
    fun capturePhase(): GuidedScanPhase =
        when {
            calibrationMode -> {
                GuidedScanPhase.Calibration()
            }

            caseCorners != null -> {
                caseCapture?.let(GuidedScanFlow::phaseOf)
                    ?: if (calibrationLoaded) GuidedScanPhase.Calibration() else GuidedScanFlow.phaseOf(controller)
            }

            quickScanMode -> {
                quickScan?.let(GuidedScanFlow::phaseOf) ?: GuidedScanPhase.CornerViewfinder(hold = 1)
            }

            else -> {
                GuidedScanFlow.phaseOf(controller, quickFaces != null)
            }
        }

    @Volatile var quickFaces: Set<Face>? = null

    /** The run's start-scan centers from the Quick Check intent (GH #172); only read when [saved] is null. */
    @Volatile var runScan: CalibrationCenters? = null

    /** This guided run's own six centers when no calibration was saved at its Done (GH #172); in memory only. */
    @Volatile var measuredCenters: CalibrationCenters? = null

    /** What Accepted carries back: a guided run's measured centers, only while no calibration is saved. */
    fun runScanColorsToReturn(): CalibrationCenters? = measuredCenters?.takeIf { isGuidedRun && saved == null }

    fun guidedController(): GuidedScanController? {
        val quick = quickScan
        return if (quick == null) controller else quick.fallback
    }

    fun newController(): GuidedScanController {
        val faces = quickFaces
        return if (faces == null) {
            GuidedScanController(
                reprompt = { samples ->
                    // debug only (no-op in release): what the first round read and why it re-prompts (PO 2026-11-17).
                    dev.cubentia.app.ScanDiagnostics
                        .onAssembly("first", samples, saved)
                    GuidedScanController.defaultReprompt(samples, saved)
                },
            )
        } else {
            GuidedScanController(
                plan =
                    Face.entries
                        .filter {
                            it in faces
                        }.map(GuidedScanPlan::reprompt),
                // A doubtful face (an UNCERTAIN sticker, or the wrong face held) is asked once more (PO 2026-11-17).
                reprompt = { samples ->
                    val reference = saved
                    (reference ?: runScan)
                        ?.let { GuidedScanFlow.quickCheckRetakes(samples, it, runScan = reference == null) }
                        .orEmpty()
                },
            )
        }
    }

    fun saveCalibration(update: dev.cubentia.application.usecase.UpdateCalibration) {
        val calibration = phase as? GuidedScanPhase.Calibration ?: return
        val centers = calibration.session.calibration ?: return
        if (saving) return
        saving = true
        viewModelScope.launch {
            try {
                if (update(centers) == CalibrationWrite.Saved) {
                    saved = centers
                    val corners = caseCorners
                    if (corners != null) {
                        caseCapture = CaseCaptureController(centers, corners)
                        phase = capturePhase()
                    } else if (calibration.fromReview == null) {
                        calibrationFinished = true
                    } else {
                        corrections = calibration.fromReview.corrections
                        withContext(analyzerExecutor.asCoroutineDispatcher()) {
                            controller = newController()
                            if (quickScanMode) quickScan = QuickScanController(saved)
                        }
                        phase = capturePhase()
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
    fun publish(capture: ScanReview) = publish(GuidedScanPhase.Review(capture))

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
