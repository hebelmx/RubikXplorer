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
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.CameraXConfig
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ExperimentalCameraProviderConfiguration
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
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
import dev.cubentia.app.scan.ScanFlowContract
import dev.cubentia.app.scan.ScanOutcome
import dev.cubentia.app.ui.scan.ScanCoachMarkRecord
import dev.cubentia.app.ui.scan.ScanReviewScreen
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.domain.recognition.GuidedScanController
import dev.cubentia.domain.recognition.GuidedScanEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var previewView: PreviewView? = null
    private var cameraStarted = false

    /** The preview view's size in pixels, read on the analyzer thread to place the grid; 0 until laid out. */
    @Volatile private var viewSize = 0 to 0

    /** The last analysed grid, as an AF/AE/AWB point on the analysis frame; null before the first frame. */
    @Volatile private var meteringGrid: MeteringGrid? = null

    private var analysisUseCase: ImageAnalysis? = null

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
                    LaunchedEffect(phase is GuidedScanPhase.Viewfinder || phase is GuidedScanPhase.Calibration) {
                        if (cameraPhase()) startCamera()
                    }
                    val reviewPhase = phase as? GuidedScanPhase.Review
                    LaunchedEffect(reviewPhase != null) {
                        if (reviewPhase != null) {
                            analysisUseCase?.clearAnalyzer()
                            cameraProvider?.unbindAll()
                            camera = null
                            cameraStarted = false
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
                                        edited = reviewPhase.edited || it.stickers != reviewPhase.review.stickers,
                                    )
                            },
                            onCalibrate =
                                if (reviewPhase.offerCalibration) {
                                    (
                                        {
                                            // A Capture tap left over from an earlier calibration never samples this one.
                                            scan.captureCalibration = false
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
                                    PreviewView(context).also {
                                        it.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                        // The scaled preview never draws outside its own pane.
                                        it.outlineProvider = ViewOutlineProvider.BOUNDS
                                        it.clipToOutline = true
                                        it.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                                            viewSize = view.width to view.height
                                        }
                                        previewView = it
                                        // A new preview (calibration ↔ viewfinder swap their panes) needs a fresh bind.
                                        if (cameraStarted) {
                                            analysisUseCase?.clearAnalyzer()
                                            cameraProvider?.unbindAll()
                                            camera = null
                                            cameraStarted = false
                                        }
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

    /** Obtains the provider off the main thread, then binds on main once the preview has a viewport. */
    private fun startCamera() {
        val view = previewView ?: return
        if (cameraStarted || !hasPermission() || !scan.calibrationLoaded || !cameraPhase()) return
        cameraStarted = true
        lifecycleScope.launch {
            val provider =
                try {
                    withContext(Dispatchers.Default) {
                        configureBackCameraOnly()
                        ProcessCameraProvider.getInstance(applicationContext).get()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (ignored: Exception) {
                    if (cameraPhase()) phase = GuidedScanPhase.Unavailable
                    return@launch
                }
            cameraProvider = provider
            if (cameraPhase()) bindWhenLaidOut(provider, view)
        }
    }

    /** Limits CameraX to the back camera; a process that already configured CameraX keeps its configuration. */
    @OptIn(ExperimentalCameraProviderConfiguration::class)
    private fun configureBackCameraOnly() {
        try {
            ProcessCameraProvider.configureInstance(
                CameraXConfig.Builder
                    .fromConfig(Camera2Config.defaultConfig())
                    .setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA)
                    .build(),
            )
        } catch (ignored: IllegalStateException) {
            // Already configured in this process.
        }
    }

    private fun bindWhenLaidOut(
        provider: ProcessCameraProvider,
        view: PreviewView,
    ) {
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysisUseCase = analysis
        analysis.setAnalyzer(analyzerExecutor) { image ->
            try {
                analyze(image)
            } finally {
                image.close()
            }
        }

        fun bind(): Boolean {
            if (!cameraPhase()) return true
            val viewPort = view.viewPort ?: return false
            val useCases =
                UseCaseGroup
                    .Builder()
                    .setViewPort(viewPort)
                    .addUseCase(preview)
                    .addUseCase(analysis)
                    .build()
            try {
                provider.unbindAll()
                camera =
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, useCases).also { bound ->
                        // Bound but failed to open (in use, disabled, fatal error): the camera-unavailable card,
                        // never remembered.
                        bound.cameraInfo.cameraState.observe(this) { state ->
                            val closed = state.type == CameraState.Type.CLOSED || state.type == CameraState.Type.PENDING_OPEN
                            if (state.error != null && closed && cameraPhase()) phase = GuidedScanPhase.Unavailable
                        }
                    }
            } catch (ignored: IllegalArgumentException) {
                phase = GuidedScanPhase.Unavailable
            } catch (ignored: IllegalStateException) {
                phase = GuidedScanPhase.Unavailable
            } catch (ignored: UnsupportedOperationException) {
                phase = GuidedScanPhase.Unavailable
            }
            return true
        }
        if (bind()) return
        view.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (bind()) view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            },
        )
    }

    /** Analyzer thread: read the frame into stats, drop it, and run the controller — unless a capture is frozen. */
    private fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (scan.freeze.isFrozen(now)) return
        val rotation = image.imageInfo.rotationDegrees
        val crop = image.cropRect
        val (width, height) = viewSize
        // The analysed grid is exactly the region under the drawn grid, whatever the pane's shape (PO 2026-11-16).
        val grid = ViewfinderGrid.inFrame(width.toDouble(), height.toDouble(), crop.left, crop.top, crop.right, crop.bottom, rotation)
        meteringGrid = MeteringGrid(image.width, image.height, grid)
        val reading = FrameReader.locate(image, grid, rotation)
        val stats = reading.stats
        // Debug builds log why a face in the grid is not auto-captured, and where its lattice was found (device session
        // B2); a no-op otherwise.
        val lattice = reading.lattice
        ScanDiagnostics.onFrame(stats, now, lattice.scale, lattice.dx, lattice.dy, lattice.score)
        // One snapshot: main may leave calibration while this frame is read.
        val calibrating = phase as? GuidedScanPhase.Calibration
        if (calibrating != null) {
            val color = calibrating.session.current
            if (scan.calibrationMeterColor != color) {
                scan.calibrationMeterColor = color
                ContextCompat.getMainExecutor(this).execute { meterOnGrid() }
            }
            if (scan.captureCalibration) {
                scan.captureCalibration = false
                val quality =
                    dev.cubentia.domain.recognition.FrameQuality
                        .assessShutter(stats)
                val ring =
                    if (quality ==
                        dev.cubentia.domain.recognition.CaptureQuality.ACCEPTABLE
                    ) {
                        FrameReader.centerRing(image, lattice.square(grid))
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
                vm.frozen = FrozenCapture(++vm.captures, captured.sample.face, previewView?.bitmap?.asImageBitmap())
            }
            if (vm.phase is GuidedScanPhase.Viewfinder && vm.phase != state) vm.phase = state
            if (GuidedScanFlow.remeters(effects)) meterOnGrid()
            if (result != null) vm.publish(result)
        }
    }

    /**
     * AF/AE/AWB on the analysed grid (the drawn grid's region of the analysis frame), not the whole frame, so a dark
     * background cannot wash out the stickers. The point's size is a share of the frame's longer side, so the metered
     * region stays inside the grid. The lock cancels after [METERING_LOCK_SECONDS] so continuous AF resumes when the
     * cube moves nearer or farther (PO 2026-11-16).
     */
    private fun meterOnGrid() {
        val grid = meteringGrid ?: return
        val analysis = analysisUseCase ?: return
        val square = grid.square
        val point =
            try {
                SurfaceOrientedMeteringPointFactory(grid.width.toFloat(), grid.height.toFloat(), analysis).createPoint(
                    (square.left + square.side / 2).toFloat(),
                    (square.top + square.side / 2).toFloat(),
                    (square.side / maxOf(grid.width, grid.height)).toFloat().coerceIn(0f, 1f),
                )
            } catch (ignored: IllegalStateException) {
                // The analysis use case is no longer bound: nothing to meter.
                return
            }
        val action =
            FocusMeteringAction
                .Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB)
                .setAutoCancelDuration(METERING_LOCK_SECONDS, TimeUnit.SECONDS)
                .build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    companion object {
        const val CALIBRATION_MODE = "dev.cubentia.calibration"
        const val METERING_LOCK_SECONDS = 3L
    }
}

/** The analysed grid [square] on a [width]×[height] analysis frame, kept for metering on the main thread. */
private class MeteringGrid(
    val width: Int,
    val height: Int,
    val square: GridSquare,
)

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
        if (frozen != null) held = capture else phase = capture
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
