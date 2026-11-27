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
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
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
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.isDarkTheme
import dev.cubentia.application.port.CalibrationLoad
import dev.cubentia.application.port.CalibrationRepository
import dev.cubentia.application.port.CalibrationWrite
import dev.cubentia.application.port.FeatureFlag
import dev.cubentia.application.port.FeatureFlags
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
import dev.cubentia.domain.recognition.ScanReview
import dev.cubentia.domain.recognition.continuous.ContinuousScanExport
import dev.cubentia.domain.recognition.continuous.ContinuousScanHandoff
import dev.cubentia.domain.recognition.continuous.ContinuousScanSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Optional
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

    /**
     * The system camera shutter sound played on each capture; loaded off main, released with the Activity. A var only so
     * route tests can swap in a [ShutterSound] over a fake [ShutterSound.Player].
     */
    @VisibleForTesting internal var shutterSound = ShutterSound()

    private val scan: GuidedScanViewModel by viewModels()
    private val analyzerExecutor get() = scan.analyzerExecutor
    private val controller get() = scan.controller
    private var phase: GuidedScanPhase
        get() = scan.phase
        set(value) {
            scan.phase = value
        }

    @Inject internal lateinit var frameSource: ScanFrameSource

    @Inject internal lateinit var continuousFactory: Optional<ContinuousScanEngineFactory>

    /** Story 10.34: the debug Quick Scan session recording; empty in every non-debuggable variant. */
    @Inject internal lateinit var sessionFactory: Optional<QuickScanSessionFactory>

    @Inject lateinit var featureFlags: FeatureFlags

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
        WindowCompat.enableEdgeToEdge(window)
        // MediaActionSound.load reads the disk: off the main thread (GH #158).
        lifecycleScope.launch(Dispatchers.IO) { shutterSound.loadOrSkip() }
        // Back and a destroyed task read as Cancelled.
        setResult(RESULT_CANCELED)
        if (phase == GuidedScanPhase.Rationale) phase = GuidedScanFlow.initialPhase(hasPermission())
        scan.calibrationMode = intent.getBooleanExtra(CALIBRATION_MODE, false)
        scan.quickScanMode = intent.getBooleanExtra(EXTRA_QUICK_SCAN, false)
        if (scan.quickScanMode && !scan.calibrationLoaded) {
            scan.engineFactory = continuousFactory.orElse(null)?.takeIf { featureFlags.isEnabled(FeatureFlag.QUICK_SCAN) }
            scan.engine = scan.engineFactory?.create()
            scan.quickStartedAt = ScanDiagnostics.startRun()
            scan.torchOn = false
            // Story 10.34: one session per Quick Scan screen, kept by the view model across a rotation (debug only).
            if (scan.engine != null && scan.session == null) scan.session = sessionFactory.orElse(null)?.create(scan)
        }
        scan.onEngineSwapped = { if (cameraStarted) bindContinuous() }
        scan.caseCorners = intent.getIntExtra(EXTRA_CASE_CAPTURE, 0).takeIf { it in 1..2 }
        val solveScan =
            !scan.calibrationMode &&
                !intent.hasExtra(QuickCheckContract.EXTRA_FACES) &&
                !intent.getBooleanExtra(QuickCheckContract.EXTRA_FULL_QUICK_CHECK, false)
        // GH #321: a Solve-tab scan seeds its Editor, so review *Enter colors instead* keeps the fixes without asking.
        val seedsEditor = intent.getBooleanExtra(ScanFlowContract.EXTRA_SEEDS_EDITOR, false)
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
                    val retakeFrom = (phase as? GuidedScanPhase.Viewfinder)?.fromReview
                    if (!scan.saving) {
                        when {
                            // Backing out of a retake restores the review exactly (Story 10.16, all or nothing).
                            retakeFrom != null -> scan.backOutOfRetake(retakeFrom)

                            calibration?.fromReview != null -> phase = calibration.fromReview

                            else -> closeOrConfirm()
                        }
                    }
                },
                onContinue = {
                    scan.rationaleBefore = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
                    permission.launch(Manifest.permission.CAMERA)
                },
                // Like Close, never leave while a calibration write is in flight.
                onEnterColors = {
                    if (phase is GuidedScanPhase.QuickScan) {
                        if (scan.session?.onLeaving(QuickScanLeave.ENTER_COLORS) != true) {
                            scan.exportContinuous { exported ->
                                scan.editorOutcome = ScanOutcome.FellBackToEditor(scan.continuous.fallback(exported.handoff))
                            }
                        }
                    } else if (!scan.saving) {
                        finishWith(fallBackToEditor())
                    }
                },
                onOpenSettings = {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                    }
                },
                onCapture = {
                    if (!scan.confirmDiscard) {
                        capture()
                    }
                },
                onKeep = {
                    // Story 10.21: never while the torch wanted is not yet the torch applied (the button is disabled too).
                    (phase as? GuidedScanPhase.Calibration)
                        ?.takeIf {
                            GuidedScanFlow.keepAllowed(it, scan.hasFlash, scan.torchOn, frameSource.torchApplied())
                        }?.let {
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
                // Story 10.21: the calibration is tagged with the torch actually lit when it is saved.
                onSave = { scan.saveCalibration(updateCalibration, frameSource.torchApplied()) },
                onUseAnyway = { decide { now -> scan.guidedController()?.onUseAnyway(now).orEmpty() } },
                onFreezeEnd = { id -> scan.endFreeze(id) },
                // Story 10.34: a recording session stops first (or, during its baseline run, these are disabled).
                onFaceByFace = { if (scan.session?.onLeaving(QuickScanLeave.FACE_BY_FACE) != true) scan.switchToFaceByFace() },
                onReset = { if (scan.session?.onReset() != true) scan.resetContinuous() },
                onReviewAndCorrect = { if (scan.session?.onLeaving(QuickScanLeave.REVIEW_AND_CORRECT) != true) scan.reviewAndCorrect() },
                // Story 10.32: Quick Scan's torch choice survives a Reset and a rotation; hand-over and background turn it off.
                // Story 10.21: locked in calibration from the first kept swatch until it is saved or left.
                onToggleTorch = { if (!GuidedScanFlow.torchLocked(phase)) scan.torchOn = !scan.torchOn },
                onDiscardConfirm = {
                    scan.confirmDiscard = false
                    finishWith(ScanOutcome.Cancelled)
                },
                onDiscardCancel = { scan.confirmDiscard = false },
            )
        setContent {
            val prefs by preferenceStore.preferences.collectAsState()
            val dark = isDarkTheme(prefs.theme, isSystemInDarkTheme())
            LaunchedEffect(dark) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            val catalog by lessonCatalog.state.collectAsState()
            // Content loads in a moment; until then show nothing rather than raw keys.
            val strings =
                when (val state = catalog) {
                    is CatalogState.Ready -> state.strings
                    is CatalogState.Failed -> ContentStrings(emptyMap())
                    CatalogState.Loading -> null
                }
            PreferenceProvider(preferenceStore) {
                CubentiaTheme(darkTheme = dark) {
                    androidx.activity.compose.BackHandler(enabled = phase is GuidedScanPhase.Calibration) {
                        val calibration = phase as? GuidedScanPhase.Calibration
                        if (!scan.saving) {
                            when {
                                calibration?.fromReview != null -> phase = calibration.fromReview
                                else -> finishWith(ScanOutcome.Cancelled)
                            }
                        }
                    }
                    androidx.activity.compose.BackHandler(enabled = phase !is GuidedScanPhase.Calibration) {
                        val viewfinder = phase as? GuidedScanPhase.Viewfinder
                        val retakeFrom = viewfinder?.fromReview
                        when {
                            retakeFrom != null -> scan.backOutOfRetake(retakeFrom)

                            // Back from Quick Scan's hand-over returns to Quick Scan with the map intact (Stories 10.17, 10.32).
                            viewfinder?.handOver == true -> scan.backOutOfHandOver()

                            // Story 10.32: an early review's Back is the review screen's own; this covers the moment
                            // before that screen is composed, so Back never ends the scan from an early review.
                            (phase as? GuidedScanPhase.Review)?.early == true -> scan.backOutOfEarlyReview()

                            else -> closeOrConfirm()
                        }
                    }
                    if (strings == null) {
                        Surface(Modifier.fillMaxSize(), color = RubikTheme.tokens.surfaceBase) {}
                        return@CubentiaTheme
                    }
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
                    LaunchedEffect(scan.editorOutcome) {
                        scan.editorOutcome?.let(::finishWith)
                    }
                    ContinuousQuickScanHaptics(scan.pulses)
                    val deadline = scan.continuous.reviewAtMs
                    LaunchedEffect(deadline, phase::class, scan.confirmDiscard, scan.busy) {
                        if (deadline != null && phase == GuidedScanPhase.Continuous && !scan.confirmDiscard && !scan.busy) {
                            // Story 10.34: a recording stops and its sheet opens first; the session releases the hand-off.
                            if (scan.session?.onCompleted() == true) return@LaunchedEffect
                            delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                            scan.completeContinuous()
                        }
                    }
                    val ready = previewReady && scan.calibrationLoaded
                    val torchLit = GuidedScanFlow.torchLit(phase, scan.hasFlash, scan.torchOn)
                    LaunchedEffect(torchLit) { frameSource.torch(torchLit) }
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
                                            // Story 10.21: a calibration opened from the review starts with the torch on.
                                            scan.torchOn = true
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
                            // GH #321: Close with no change keeps the scan for the Editor; Discard (after a change) cancels.
                            // An early review (Story 10.32) always asks on Close, so it only ever reaches onDiscard.
                            onClose = { finishWith(fallBackToEditor()) },
                            onDiscard = { finishWith(ScanOutcome.Cancelled) },
                            enterColorsAsks = !seedsEditor,
                            // Retake in a Guided 6-face Scan or Quick Scan review: never Quick Check, calibration or Case capture.
                            onRetake =
                                if ((scan.isGuidedRun || scan.quickScanMode) && !isQuickCheck) {
                                    { faces -> startRetake(reviewPhase, faces) }
                                } else {
                                    null
                                },
                            modifier = Modifier.fillMaxSize(),
                            coachMarkPending = scan.coachMarkPending,
                            edited = reviewPhase.edited,
                            onCoachMarkDismissed = {
                                scan.coachMarkPending = false
                                lifecycleScope.launch(Dispatchers.IO) { ScanCoachMarkRecord.markSeen(applicationContext) }
                            },
                            // Story 10.32: Back from an early review returns to the live scan; Close there always asks.
                            onBack = if (reviewPhase.early) scan::backOutOfEarlyReview else null,
                        )
                    } else {
                        GuidedScanScreen(
                            phase,
                            strings,
                            actions,
                            scan.frozen,
                            offerEnterColors = solveScan,
                            continuous = scan.continuous,
                            confirmDiscard = scan.confirmDiscard,
                            discardKind = if (plainGuidedViewfinder(phase)) DiscardKind.GuidedFaces else DiscardKind.Scan,
                            cornerTorch = GuidedScanFlow.torchControl(phase, scan.hasFlash, scan.torchOn),
                            keepEnabled = GuidedScanFlow.keepAllowed(phase, scan.hasFlash, scan.torchOn, scan.torchApplied),
                            sessionOverlay = scan.session?.let { session -> { session.Overlay(this) } },
                            quickScanActionsEnabled = scan.session?.locksActions != true,
                        ) {
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
        scan.onEngineSwapped = null
        frameSource.stop()
        shutterSound.release()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        frameSource.suspendTorch(false)
    }

    /** The torch is off while the app is in the background; a calibration's choice comes back, Quick Scan's stays off. */
    override fun onStop() {
        // Story 10.34: the background stops a recording or baseline run; the sheet shows on return.
        if (scan.quickScanMode && !isChangingConfigurations) scan.session?.onBackground()
        // Story 10.32: the background turns the torch off for good; a rotation keeps the Learner's choice.
        if (phase == GuidedScanPhase.Continuous && !isChangingConfigurations) {
            scan.torchOn = false
            frameSource.torch(false)
        }
        frameSource.suspendTorch(true)
        super.onStop()
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

    /** The shutter: a corner, Case or Guided capture decision on the analyzer thread, or a calibration sample. */
    private fun capture() {
        when (phase) {
            is GuidedScanPhase.Calibration -> {
                scan.captureCalibration = true
            }

            is GuidedScanPhase.CornerViewfinder -> {
                analyzerExecutor.execute {
                    captureCase { it.onShutter() }
                }
            }

            else -> {
                decide { now -> scan.guidedController()?.onShutter(now).orEmpty() }
            }
        }
    }

    /**
     * Close, and system Back when no retake, hand-over, calibration or early review handles it first: asks "Discard this
     * scan?" when it would drop at least one side Quick Scan mapped, or one face it kept or read in its hand-over (Story
     * 10.17), and "Discard the faces you captured?" when it would drop a face of a plain Guided 6-face viewfinder
     * (GH #325); otherwise the scan ends Cancelled at once. An early review (Story 10.32, *Review and Correct*) never
     * comes here: its Back returns to the live scan, and its *Close* asks in the review screen itself.
     */
    private fun closeOrConfirm() {
        val current = phase
        // Story 10.34: Close/Back stops a recording (or baseline run) first; the screen is left only if it ends here.
        if (current == GuidedScanPhase.Continuous && scan.session?.onLeaving(QuickScanLeave.CLOSE) == true) return
        val faces =
            when {
                plainGuidedViewfinder(current) -> {
                    (current as GuidedScanPhase.Viewfinder).captured.isNotEmpty()
                }

                !scan.quickScanMode -> {
                    false
                }

                current is GuidedScanPhase.QuickScan -> {
                    scan.continuous.confirmClose
                }

                // The hand-over viewfinder: when it would drop the map Back returns to (one or more mapped sides, as on
                // the continuous screen), a face Quick Scan kept or a face captured here.
                current is GuidedScanPhase.Viewfinder && current.handOver -> {
                    scan.continuous.confirmClose || scan.handoff?.faces?.isNotEmpty() == true || current.captured.isNotEmpty()
                }

                else -> {
                    false
                }
            }
        if (faces) scan.confirmDiscard = true else finishWith(ScanOutcome.Cancelled)
    }

    /**
     * The plain Guided 6-face viewfinder (GH #325): not a retake, Quick Scan, its hand-over, calibration, Case capture or
     * a partial Quick Check; Close (or Back) there asks "Discard the faces you captured?" once a face is captured. The
     * Quick Scan, calibration, Case capture and partial Quick Check exclusions come from [GuidedScanViewModel.isGuidedRun].
     */
    private fun plainGuidedViewfinder(current: GuidedScanPhase): Boolean =
        current is GuidedScanPhase.Viewfinder && current.fromReview == null && !current.handOver && scan.isGuidedRun

    private fun fallBackToEditor(): ScanOutcome.FellBackToEditor {
        if (scan.caseCorners != null) return ScanOutcome.FellBackToEditor(null)
        val current = phase
        val kept = scan.handoff
        if (current is GuidedScanPhase.Viewfinder && current.handOver && kept != null) {
            val stickers =
                if (current.captured.isEmpty()) {
                    kept.toFallbackStickers().takeIf { kept.faces.isNotEmpty() }
                } else {
                    handoverReview(kept, current.captured, scan.saved).review.stickers
                }
            return ScanOutcome.FellBackToEditor(stickers)
        }
        return ScanOutcome.FellBackToEditor(GuidedScanFlow.fallbackStickers(current, scan.saved))
    }

    private fun finishWith(capture: ScanOutcome) {
        val review =
            (phase as? GuidedScanPhase.Review)
                ?: (phase as? GuidedScanPhase.Calibration)?.fromReview
                ?: (phase as? GuidedScanPhase.Viewfinder)?.fromReview
        val outcome =
            when (capture) {
                is ScanOutcome.Accepted -> "ACCEPTED"
                ScanOutcome.Cancelled -> "CANCELLED"
                is ScanOutcome.FellBackToEditor -> "FELL_BACK_TO_EDITOR"
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
        } else if (scan.quickScanMode && !scan.quickLogged) {
            // Story 10.32: one run line per Quick Scan, for `evaluateQuickScan` and the device pass (debug only).
            scan.quickLogged = true
            ScanDiagnostics.onQuickScanRun(
                scan.quickStartedAt,
                scan.guidedFacesAsked,
                review?.corrections,
                review?.initialStickers,
                outcome,
                earlyReview = review?.early == true,
            )
        }
        val (code, data) =
            if (scan.caseCorners != null && capture is ScanOutcome.FellBackToEditor) {
                CaseCaptureContract.editor()
            } else {
                ScanFlowContract.encode(capture)
            }
        setResult(code, data)
        finish()
    }

    private fun cameraPhase() =
        phase == GuidedScanPhase.Continuous || phase is GuidedScanPhase.Viewfinder || phase is GuidedScanPhase.CornerViewfinder ||
            phase is GuidedScanPhase.Calibration

    private fun startCamera() {
        if (!previewReady || cameraStarted || !hasPermission() || !scan.calibrationLoaded || !cameraPhase()) return
        cameraStarted = true
        frameSource.cornerMode(phase is GuidedScanPhase.CornerViewfinder)
        // Story 10.21: the torch may be lit in the calibration phase too.
        frameSource.calibrationMode(phase is GuidedScanPhase.Calibration)
        bindContinuous()
        frameSource.start(this, analyzerExecutor, ::analyze) { if (cameraPhase()) phase = GuidedScanPhase.Unavailable }
    }

    /** The continuous engine the camera feeds, and when it analyses; again after a session swaps engines (Story 10.34). */
    private fun bindContinuous() {
        frameSource.continuousMode(scan.engine.takeIf { phase == GuidedScanPhase.Continuous }) {
            phase == GuidedScanPhase.Continuous && !scan.analysisHeld && !scan.busy && !scan.confirmDiscard &&
                scan.session?.holdsAnalysis != true
        }
    }

    /** Analyzer thread: read the frame into stats, drop it, and run the controller — unless a capture is frozen. */
    private fun analyze(frame: ScanFrame) {
        val now = SystemClock.elapsedRealtime()
        // No capture decision runs while the discard question is open (Story 10.17, GH #325).
        if (scan.freeze.isFrozen(now) || scan.confirmDiscard) return
        if (phase == GuidedScanPhase.Continuous) {
            val continuous = frame.continuous ?: return
            // The revision first: a Reset landing after the busy check then drops this frame on main (its revision is older).
            val revision = scan.revision
            // Story 10.34: nothing is analysed while a session's sheet, labeller or held hand-off is up.
            if (scan.analysisHeld || scan.busy || scan.session?.holdsAnalysis == true) return
            // GH #390: the detector cannot run, so the card replaces the live scan and this frame's snapshot is dropped
            // (the shown map stays). The state stays true, so a frame dropped here loses nothing: the card never replaces
            // an open discard question or interrupts an export, and shows on the next analysed frame instead.
            if (scan.engine?.detectorUnavailable == true) {
                ContextCompat.getMainExecutor(this).execute {
                    if (phase != GuidedScanPhase.Continuous || scan.revision != revision || isFinishing) return@execute
                    if (!scan.confirmDiscard && !scan.busy) {
                        scan.torchOn = false
                        scan.session?.onDetectorUnavailable()
                        phase = GuidedScanPhase.QuickScanUnavailable
                    }
                }
                return
            }
            // Story 10.34: the baseline run times its whole 30 s, so neither a twist nor a completion holds its analysis.
            scan.analysisHeld = scan.session?.locksActions != true &&
                (continuous.snapshot.twistDetected || continuous.snapshot is ContinuousScanSnapshot.Complete)
            val flash = frameSource.hasFlashUnit()
            ContextCompat.getMainExecutor(this).execute {
                if (phase == GuidedScanPhase.Continuous && scan.revision == revision && !isFinishing) {
                    if (flash != null) scan.hasFlash = flash
                    val before = scan.continuous
                    scan.continuous = before.next(continuous, now)
                    scan.continuous.pulsesSince(before).forEach { scan.pulses.trySend(it) }
                }
            }
            return
        }
        if (frame.continuous != null) return
        val rotation = frame.rotationDegrees
        val stats = frame.stats
        // One snapshot: main may leave calibration while this frame is read.
        val calibrating = phase as? GuidedScanPhase.Calibration
        if (calibrating != null) {
            // Story 10.21: the flash unit is known once a camera is bound, so the calibration phase can show the torch.
            val flash = frameSource.hasFlashUnit()
            if (flash != null && flash != scan.hasFlash) ContextCompat.getMainExecutor(this).execute { scan.hasFlash = flash }
            // Story 10.21: the torch the camera applied, so Keep waits for a pending torch change.
            val applied = frameSource.torchApplied()
            if (applied != scan.torchApplied) ContextCompat.getMainExecutor(this).execute { scan.torchApplied = applied }
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
        scan.retake?.let { run ->
            handleRetake(run, effects)
            return
        }
        val vm = scan
        val handoff = scan.handoff.takeIf { (phase as? GuidedScanPhase.Viewfinder)?.handOver == true }
        val active = scan.guidedController() ?: return
        val state =
            GuidedScanFlow.phaseOf(active, scan.quickFaces != null).let {
                if (handoff == null) it else it.copy(handOver = true, keptColors = handoff.faces.mapValues { entry -> entry.value.colors })
            }
        val done = effects.filterIsInstance<GuidedScanEffect.Done>().firstOrNull()
        done?.takeIf { handoff == null }?.let {
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
        if (done != null && handoff == null && scan.isGuidedRun) {
            scan.measuredCenters = GuidedScanFlow.runScanColorsOf(done.samples, scan.saved)
        }
        val result =
            if (handoff != null) {
                done?.let { handoverReview(handoff, it.samples, scan.saved) }
            } else if (scan.quickFaces == null) {
                GuidedScanFlow.reviewOf(effects, scan.saved)
            } else {
                null
            }
        val captured = effects.filterIsInstance<GuidedScanEffect.Capture>().firstOrNull()
        ContextCompat.getMainExecutor(this).execute {
            // Capture feedback (PO 2026-11-16): the frame frozen and shrunk into the net, with the shutter sound; none once
            // the viewfinder is gone (e.g. Unavailable), so a finished scan is never held behind a freeze nothing ends.
            if (handoff != null && (vm.handoff !== handoff || (vm.phase as? GuidedScanPhase.Viewfinder)?.handOver != true)) return@execute
            if (captured != null && vm.onCaptured(captured.sample.face) { frameSource.snapshot() }) shutterSound.play()
            // A finished retake's viewfinder stays until its held review is published or the Learner backs out.
            val current = vm.phase
            if (current is GuidedScanPhase.Viewfinder && current.fromReview == null && current != state) vm.phase = state
            if (GuidedScanFlow.remeters(effects)) meterOnGrid()
            if (partial != null) {
                ScanDiagnostics.onQuickCheckCaptured(vm.quickCheckStartedAt, vm.captures)
                vm.quickResult = partial
            }
            if (result != null) vm.publish(result)
        }
    }

    /** Main thread: starts a retake of [faces] from review [from] (Story 10.16); its controller is made on the analyzer thread. */
    private fun startRetake(
        from: GuidedScanPhase.Review,
        faces: Set<Face>,
    ) {
        if (faces.isEmpty()) return
        analyzerExecutor.execute {
            val run = GuidedScanFlow.retakeRun(faces, from)
            scan.retake = run
            val next = GuidedScanFlow.retakePhase(run)
            ContextCompat.getMainExecutor(this).execute {
                if (phase === from) {
                    phase = next
                } else {
                    analyzerExecutor.execute { if (scan.retake === run) scan.retake = null }
                }
            }
        }
    }

    /**
     * Analyzer thread: a retake's controller state and effects. Its Done builds the merged review directly (kept faces as
     * reviewed, corrections on retaken faces dropped), never through [GuidedScanViewModel.publish]'s re-application of
     * earlier corrections, but still held until the last capture's freeze ends. Nothing applies once the Learner backed
     * out.
     */
    private fun handleRetake(
        run: RetakeRun,
        effects: List<GuidedScanEffect>,
    ) {
        val vm = scan
        val state = GuidedScanFlow.retakePhase(run)
        val done = effects.filterIsInstance<GuidedScanEffect.Done>().firstOrNull()
        val result = done?.let { GuidedScanFlow.reviewAfterRetake(run.from, it.samples) }
        if (result != null) vm.retake = null
        val captured = effects.filterIsInstance<GuidedScanEffect.Capture>().firstOrNull()
        ContextCompat.getMainExecutor(this).execute {
            if ((vm.phase as? GuidedScanPhase.Viewfinder)?.fromReview !== run.from) return@execute
            if (captured != null) {
                shutterSound.play()
                vm.frozen = FrozenCapture(++vm.captures, captured.sample.face, frameSource.snapshot())
            }
            if (vm.phase != state) vm.phase = state
            if (GuidedScanFlow.remeters(effects)) meterOnGrid()
            if (result != null) {
                // A guided run's own centers follow the merged samples (GH #172).
                vm.measuredCenters = GuidedScanFlow.runScanColorsOf(result.samples, vm.saved)
                vm.publishRetake(result)
            }
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
internal class GuidedScanViewModel :
    ViewModel(),
    QuickScanSessionHost {
    val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile var saved: CalibrationCenters? = null
    var calibrationLoaded by mutableStateOf(false)
    var quickResult by mutableStateOf<dev.cubentia.domain.recognition.PhysicalObservation.Partial?>(null)
    var calibrationMode = false
    var quickScanMode = false
    var torchOn by mutableStateOf(true)

    /** Whether the bound camera has a flash unit; null until a camera reports it. */
    var hasFlash by mutableStateOf<Boolean?>(null)

    /** Story 10.21: the torch state the camera last applied, as the calibration phase's analyzer saw it; null before any. */
    var torchApplied by mutableStateOf<Boolean?>(null)

    @Volatile var caseCorners: Int? = null

    @Volatile var caseCapture: CaseCaptureController? = null
    var caseResult by mutableStateOf<List<CornerView>?>(null)

    @Volatile var engine: ContinuousScanEngine? = null

    /** The factory [engine] came from; the debug session recording's baseline run takes a fresh engine from it. */
    var engineFactory: ContinuousScanEngineFactory? = null

    /** Story 10.34: the debug Quick Scan session recording of this screen; null in every non-debuggable variant. */
    var session: QuickScanSession? = null

    /** The activity's camera rebinding after [startBaseline] or [endBaseline] swapped [engine]; null between activities. */
    var onEngineSwapped: (() -> Unit)? = null

    var continuous by mutableStateOf(ContinuousQuickScanFlow())
    val pulses = Channel<ContinuousQuickScanFlow.Pulse>(Channel.UNLIMITED)

    @Volatile var revision = 0L

    @Volatile var analysisHeld = false
    var busy by mutableStateOf(false)
    var editorOutcome by mutableStateOf<ScanOutcome.FellBackToEditor?>(null)

    @Volatile var handoff: ContinuousScanHandoff? = null

    @Volatile private var handoverController: GuidedScanController? = null

    fun resetContinuous() {
        // Busy first: the analyzer drops frames while busy, so none is published under the new revision before the reset.
        busy = true
        revision++
        val token = revision
        continuous = ContinuousQuickScanFlow()
        viewModelScope.launch {
            withContext(analyzerExecutor.asCoroutineDispatcher()) {
                engine?.reset()
                analysisHeld = false
            }
            if (revision == token) busy = false
        }
    }

    fun exportContinuous(consume: (ContinuousScanExport) -> Unit) {
        val active = engine ?: return
        val source = phase
        if (busy || source !is GuidedScanPhase.QuickScan) return
        busy = true
        val token = revision
        viewModelScope.launch {
            val exported = withContext(analyzerExecutor.asCoroutineDispatcher()) { active.export() }
            if (revision == token) {
                busy = false
                if (phase == source) consume(exported)
            }
        }
    }

    fun completeContinuous() {
        // GH #390: the detector-unavailable card exports too, but only the live scan completes into a review.
        if (phase != GuidedScanPhase.Continuous) return
        exportContinuous { exported ->
            if (exported is ContinuousScanExport.Complete) {
                torchOn = false
                publish(GuidedScanPhase.Review(ScanReview.from(exported.handoff), calibration = reviewCalibration(exported.handoff)))
            } else {
                continuous = continuous.copy(reviewAtMs = null)
                analysisHeld = false
            }
        }
    }

    /**
     * Main thread: *Review and Correct* (Story 10.32, Q8). Scan Review opens on a fresh export, with the torch off:
     * confirmed stickers as read, unread and conflicted ones blank, the shown snapshot's conflicted stickers first to
     * check. An export that is already complete opens the completed scan's review instead. The engine, the shown
     * [continuous] state and a latched twist are not touched, so [backOutOfEarlyReview] finds the scan as it was. The one
     * exception is a calibration saved from this review ([saveCalibration]), which restarts the scan with an empty map.
     */
    fun reviewAndCorrect() {
        // GH #390: never from the detector-unavailable card (a tap left over from the live scan it replaced).
        if (phase != GuidedScanPhase.Continuous || !continuous.reviewAndCorrectOffered()) return
        exportContinuous { exported ->
            torchOn = false
            val handoff = exported.handoff
            val early = exported !is ContinuousScanExport.Complete
            val review = if (early) ScanReview.from(handoff, continuous.conflictedReviewIndices) else ScanReview.from(handoff)
            publish(GuidedScanPhase.Review(review, calibration = reviewCalibration(handoff), early = early))
        }
    }

    /**
     * Main thread: Back from an early review (Story 10.32, Q8): the live continuous scan again, with its camera-built
     * map, count and any latched twist as they were, and the torch off. Nothing done in the review is kept: its colours
     * and retakes go with it (a held or frozen capture and a running retake included). A calibration saved from the
     * review never comes back here: [saveCalibration] restarts the scan and keeps the review's corrections.
     */
    fun backOutOfEarlyReview() {
        if ((phase as? GuidedScanPhase.Review)?.early != true) return
        held = null
        frozen = null
        torchOn = false
        phase = quickScanPhase()
        analyzerExecutor.execute { retake = null }
    }

    /** The calibration a review of [handoff] is read with: its own six centres when valid, else the saved calibration. */
    private fun reviewCalibration(handoff: ContinuousScanHandoff): CalibrationCenters? =
        handoff.calibration?.takeIf(dev.cubentia.domain.recognition.StickerClassifier::isValid) ?: saved

    /** When the Quick Scan screen opened, for the debug run line; null in release. */
    @Volatile var quickStartedAt: Long? = null

    /** How many faces the Guided hand-over was asked for (0: *Scan face by face* not used, or backed out of). */
    var guidedFacesAsked = 0
    var quickLogged = false

    fun switchToFaceByFace() =
        exportContinuous { exported ->
            torchOn = false
            val kept = continuous.keptForGuided(exported.handoff)
            val steps = GuidedScanPlan.steps.filter { it.face !in kept.faces }
            if (steps.isEmpty()) {
                publish(GuidedScanPhase.Review(ScanReview.from(kept), calibration = reviewCalibration(kept)))
            } else {
                // The kept faces are known to the hand-over, and its first automatic capture waits (Story 10.17's guards).
                val guided = GuidedScanController.handOver(steps, kept)
                guidedFacesAsked = steps.size
                handoverController = guided
                handoff = kept
                phase = GuidedScanFlow.phaseOf(guided).copy(handOver = true, keptColors = kept.faces.mapValues { it.value.colors })
            }
        }

    @Volatile var guidedStartedAt: Long? = null

    @Volatile var quickCheckStartedAt: Long? = null
    var guidedLogged = false

    /** A plain Guided 6-face run; also decides where Close asks "Discard the faces you captured?" (GH #325). */
    val isGuidedRun get() = !quickScanMode && caseCorners == null && quickFaces == null && !calibrationMode
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
                quickScanPhase()
            }

            else -> {
                GuidedScanFlow.phaseOf(controller, quickFaces != null)
            }
        }

    /**
     * Quick Scan's own phase, for opening it and for every return to it (a camera grant, Back from the hand-over or from
     * an early review, a calibration saved from a review): the camera-unavailable card with no engine, the
     * detector-unavailable card once the engine reports it (GH #390; nothing retries the detector), else the live scan.
     */
    private fun quickScanPhase(): GuidedScanPhase =
        when (engine?.detectorUnavailable) {
            null -> GuidedScanPhase.Unavailable
            true -> GuidedScanPhase.QuickScanUnavailable
            false -> GuidedScanPhase.Continuous
        }

    @Volatile var quickFaces: Set<Face>? = null

    /** The run's start-scan centers from the Quick Check intent (GH #172); only read when [saved] is null. */
    @Volatile var runScan: CalibrationCenters? = null

    /** This guided run's own six centers when no calibration was saved at its Done (GH #172); in memory only. */
    @Volatile var measuredCenters: CalibrationCenters? = null

    /** What Accepted carries back: a guided run's measured centers, only while no calibration is saved. */
    fun runScanColorsToReturn(): CalibrationCenters? = measuredCenters?.takeIf { isGuidedRun && saved == null }

    /** A retake from review in progress (Story 10.16); set and cleared on [analyzerExecutor]. */
    @Volatile var retake: RetakeRun? = null

    fun guidedController(): GuidedScanController? {
        retake?.let { return it.controller }
        return if (quickScanMode) handoverController else controller
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

    /** Saves the calibration tagged with the torch lit now (Story 10.21): wanted, on a flash unit, and [torchApplied]. */
    fun saveCalibration(
        update: dev.cubentia.application.usecase.UpdateCalibration,
        torchApplied: Boolean? = null,
    ) {
        val calibration = phase as? GuidedScanPhase.Calibration ?: return
        val centers =
            calibration.session.calibration?.copy(torchOn = GuidedScanFlow.savedTorchTag(hasFlash, torchOn, torchApplied)) ?: return
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
                        // Saved from a review: the scan restarts (Quick Scan with an empty map), keeping the review's corrections.
                        corrections = calibration.fromReview?.corrections.orEmpty()
                        // The controller first: the analyzer reads the hand-off, then the controller.
                        handoverController = null
                        handoff = null
                        guidedFacesAsked = 0
                        withContext(analyzerExecutor.asCoroutineDispatcher()) {
                            controller = newController()
                            engine?.reset()
                        }
                        if (quickScanMode) {
                            continuous = ContinuousQuickScanFlow()
                            analysisHeld = false
                            torchOn = false
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
    private var shownPhase by mutableStateOf<GuidedScanPhase>(GuidedScanPhase.Rationale)

    /** The shown phase; a move to another kind of phase dismisses the discard question (Story 10.17, GH #325). */
    var phase: GuidedScanPhase
        get() = shownPhase
        set(value) {
            if (value::class != shownPhase::class) confirmDiscard = false
            shownPhase = value
        }

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

    /** A pending phase transition (review or Guided fallback) held until the current capture freeze ends. */
    private var held: GuidedScanPhase? = null

    /**
     * Main thread: the capture feedback for [face]. Freezes the frame from [image] only while the viewfinder shows, the
     * only phase whose screen ends a freeze; returns whether it froze, so the caller plays the shutter sound only then.
     */
    fun onCaptured(
        face: Face,
        image: () -> androidx.compose.ui.graphics.ImageBitmap?,
    ): Boolean {
        if (phase !is GuidedScanPhase.Viewfinder) return false
        frozen = FrozenCapture(++captures, face, image())
        return true
    }

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

    /** Main thread: publishes a retake's merged review as is (no correction re-applied), now or when the freeze ends. */
    fun publishRetake(review: GuidedScanPhase.Review) {
        if (frozen != null) held = review else phase = review
    }

    /**
     * Main thread: leaves a retake before every face was taken (Story 10.16, all or nothing): the review it started
     * [from] comes back exactly, and nothing captured in the retake is kept.
     */
    fun backOutOfRetake(from: GuidedScanPhase.Review) {
        held = null
        frozen = null
        phase = from
        // A retake finished during the freeze may have set centers from its discarded samples (GH #172).
        measuredCenters = GuidedScanFlow.runScanColorsOf(from.samples, saved)
        analyzerExecutor.execute { retake = null }
    }

    /**
     * Main thread: leaves Quick Scan's hand-over before review (Stories 10.17, 10.32): Quick Scan again with its map and
     * count intact and the torch off; faces captured in the hand-over (and a held combined review) are dropped.
     */
    fun backOutOfHandOver() {
        held = null
        frozen = null
        // The controller first: the analyzer reads the hand-off, then the controller.
        handoverController = null
        handoff = null
        guidedFacesAsked = 0
        torchOn = false
        phase = quickScanPhase()
    }

    /** Main thread: Close asked the discard question (Story 10.17, GH #325). */
    var confirmDiscard by mutableStateOf(false)

    /** Main thread: the freeze of capture [id] ended; live view resumes, or a held result is published. */
    fun endFreeze(id: Long) {
        if (frozen?.id != id) return
        frozen = null
        held?.let {
            held = null
            phase = it
        }
    }

    // ---- Story 10.34: QuickScanSessionHost (main thread) ----

    override val liveScanShown: Boolean get() = phase == GuidedScanPhase.Continuous

    override val hostDialogShown: Boolean get() = confirmDiscard

    override val modelIdentity: ContinuousModelIdentity? get() = engineFactory?.modelIdentity

    /** The engine, shown scan and completion hold set aside while a baseline run's fresh engine analyses. */
    private var keptEngine: ContinuousScanEngine? = null
    private var keptFlow: ContinuousQuickScanFlow? = null

    @Volatile private var keptHeld = false

    override fun startCapture(
        tap: ContinuousFrameTap,
        started: () -> Unit,
    ): Boolean {
        val active = engine ?: return false
        if (busy || phase != GuidedScanPhase.Continuous) return false
        // As Reset (answer 3): busy first, so no frame is published under the new revision before the engine records.
        busy = true
        revision++
        val token = revision
        continuous = ContinuousQuickScanFlow()
        viewModelScope.launch {
            withContext(analyzerExecutor.asCoroutineDispatcher()) {
                active.record(tap)
                analysisHeld = false
            }
            started()
            if (revision == token) busy = false
        }
        return true
    }

    override fun export(consume: (ContinuousScanExport) -> Unit): Boolean {
        if (engine == null || busy || phase !is GuidedScanPhase.QuickScan) return false
        exportContinuous(consume)
        return true
    }

    override fun resetMap() = resetContinuous()

    override fun startBaseline(
        tap: ContinuousFrameTap,
        started: () -> Unit,
    ): Boolean {
        val factory = engineFactory ?: return false
        if (busy || phase != GuidedScanPhase.Continuous || keptFlow != null) return false
        busy = true
        revision++
        val token = revision
        keptFlow = continuous
        continuous = ContinuousQuickScanFlow()
        viewModelScope.launch {
            withContext(analyzerExecutor.asCoroutineDispatcher()) {
                keptEngine = engine
                keptHeld = analysisHeld
                engine = factory.create().also { it.record(tap) }
                analysisHeld = false
            }
            onEngineSwapped?.invoke()
            started()
            if (revision == token) busy = false
        }
        return true
    }

    override fun endBaseline(ended: () -> Unit) {
        busy = true
        revision++
        val token = revision
        // At once, on main: a Close that ended the run judges the kept map, not the baseline run's.
        keptFlow?.let { continuous = it }
        keptFlow = null
        viewModelScope.launch {
            withContext(analyzerExecutor.asCoroutineDispatcher()) {
                keptEngine?.let { engine = it }
                keptEngine = null
                analysisHeld = keptHeld
            }
            onEngineSwapped?.invoke()
            ended()
            if (revision == token) busy = false
        }
    }

    override fun releaseHandOff() = completeContinuous()

    override fun onCleared() {
        session?.release()
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
