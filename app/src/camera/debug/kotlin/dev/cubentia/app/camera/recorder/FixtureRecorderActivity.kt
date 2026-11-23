package dev.cubentia.app.camera.recorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.cubentia.app.camera.CameraXFrameSource
import dev.cubentia.app.camera.CornerDetector
import dev.cubentia.app.camera.CornerFrame
import dev.cubentia.app.camera.CornerGuide
import dev.cubentia.app.camera.CornerLocator
import dev.cubentia.app.camera.CornerPoint
import dev.cubentia.app.camera.CornerReader
import dev.cubentia.app.camera.ExposureLocks
import dev.cubentia.app.camera.FrameReader
import dev.cubentia.app.camera.Settle
import dev.cubentia.app.camera.SettleTracker
import dev.cubentia.app.camera.ViewfinderGrid
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.CornerSettleDiagnostics
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.SettleOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Optional
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.math.pow
import kotlin.math.roundToInt

/** One analysis frame: the per-cell center-pixel samples (unchanged since 10.10) plus the center cell's calibration ring. */
internal data class FrameMeasurements(
    val lab: List<Lab>,
    val orientation: Int,
    val quality: CaptureQuality,
    val detail: String?,
    val ring: List<Lab> = emptyList(),
)

/** The frame a Capture tap asked for: its measurements and, when encoding worked, the downscaled JPEG of the same image. */
internal data class CapturedFrame(
    val measurements: FrameMeasurements,
    val jpeg: ByteArray?,
    val frameError: String?,
    val corner: CornerFrame? = null,
    val capturedAt: java.time.Instant = java.time.Instant.now(),
    /** Story 10.19 corner mode: the model-input crop as a PNG and where it sits on the frame. */
    val modelInput: ByteArray? = null,
    val crop: ModelCrop? = null,
    /** Story 10.20 corner mode: the 10.22 metering settle before this frame (null: no metering asked yet). */
    val settle: CornerSettleDiagnostics? = null,
    /** The torch state last applied physically (null: no flash unit, or none applied yet). */
    val torch: Boolean? = null,
)

/** What the recorder screen needs from the camera; implemented by [FixtureRecorderActivity]. */
internal interface RecorderCamera {
    /** Bumped on every automatic re-meter, so the screen can say so. */
    val autoRemeters: State<Int>

    /** Corner mode (Story 10.19): the live detected outline, as view fractions; empty when nothing is detected. */
    val detectedQuads: State<List<List<CornerPoint>>>

    /** Whether the bound back camera has a flash unit; null before a camera is bound. */
    val hasFlash: State<Boolean?> get() = mutableStateOf(null)

    /**
     * The torch the session records with (applied only on a camera with a flash unit). Story 10.20: applied as asked,
     * whatever the calibration state, so the screen lights it from the moment corner mode is set ([recorderTorchOn]).
     */
    fun torch(on: Boolean) = Unit

    /**
     * Story 10.20, corner mode: whether the latest 10.22 metering has settled (or hit the settle cap); false while it
     * settles, null when none was asked. Reading it locks the exposure once the metering settled, as Quick Scan does.
     */
    fun settled(): Boolean? = null

    /** Story 10.20: the recorder calibration the adb burst's light check uses (null: uncalibrated). */
    fun calibration(centers: CalibrationCenters?) = Unit

    fun bind(view: PreviewView)

    fun cornerMode(enabled: Boolean) = Unit

    fun latest(): FrameMeasurements?

    /** Measures the next analysis frame and encodes the same image as a JPEG; null if no frame arrives in time. */
    suspend fun capture(): CapturedFrame?

    /** Re-meters AF/AE/AWB on the grid now and takes the next frame as the settled reference. */
    fun remeter()

    /**
     * Corner mode (Story 10.19): meters like Quick Scan, on the next frame with a detected cube (and again on each new
     * detection): AF+AE on that frame's outline (the grid centre when unsupported) and AWB only when [includeAwb] (hold 1).
     * Story 10.20: with 10.22's settle-and-lock: the exposure lock is released for the metering, taken once it settled
     * ([settled]) and released again after a capture; the white balance locked at hold 1 is carried through hold 2.
     */
    fun remeterCorner(includeAwb: Boolean) = remeter()

    fun changeExposure(delta: Int): String
}

/**
 * Disposable, debug-only CameraX screen (Story 10.10, reworked in 10.12). It exports CIELAB measurements, operator truth
 * and metadata, plus one downscaled JPEG per captured face; frames are written only on a Capture tap.
 */
@AndroidEntryPoint
class FixtureRecorderActivity : ComponentActivity() {
    @Inject
    internal lateinit var cornerDetector: Optional<CornerDetector>

    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val latestFrame = AtomicReference<FrameMeasurements?>(null)
    private val captureRequest = AtomicReference<CompletableDeferred<CapturedFrame>?>(null)
    private val settleRequested = AtomicBoolean(false)
    private val remeterTrigger = RemeterTrigger()
    private val autoRemeterCount = mutableIntStateOf(0)
    private val detectedQuads = mutableStateOf<List<List<CornerPoint>>>(emptyList())
    private var camera: Camera? = null
    private var analysisUseCase: ImageAnalysis? = null

    /** Corner metering like Quick Scan's: pending until a frame detects the cube; AWB on hold 1 only. */
    private val cornerMeterPending = AtomicBoolean(false)

    @Volatile private var cornerMeterAwb = true

    @Volatile private var wasDetected = false

    private val flashState = mutableStateOf<Boolean?>(null)

    @Volatile private var torchWanted = false

    /** The torch state last applied physically; null on a camera without a flash unit or before one was applied. */
    @Volatile private var appliedTorch: Boolean? = null

    /** Story 10.20: the 10.22 settle signal of the latest corner metering and the exposure locks wanted now. */
    private val settle = SettleTracker()
    private val locks = AtomicReference(ExposureLocks.NONE)
    private val settleReported = AtomicBoolean(true)

    @Volatile private var meteredAwb = false

    /** The latest metering's settle as reported when it settled; null while it settles or before any. */
    @Volatile private var lastSettle: CornerSettleDiagnostics? = null

    /** The recorder calibration for the burst's light check; null while uncalibrated. */
    @Volatile private var burstCalibration: CalibrationCenters? = null

    @Volatile private var viewSize = 0 to 0

    @Volatile private var cornerMode = false

    /** The running adb-started burst ([RecorderBurst]); null when none. While it runs every frame is read as a corner. */
    @Volatile private var burst: RecorderBurst? = null

    private val exposure = AtomicReference<ExposureState?>(null)

    private val burstReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) = startBurst(intent)
        }

    private val recorderCamera =
        object : RecorderCamera {
            override val autoRemeters: State<Int> get() = autoRemeterCount

            override val detectedQuads: State<List<List<CornerPoint>>> get() = this@FixtureRecorderActivity.detectedQuads

            override val hasFlash: State<Boolean?> get() = flashState

            override fun torch(on: Boolean) {
                // Story 10.22: a white balance locked under the other torch state is stale; the next metering redoes it.
                if (on != torchWanted) moveLocks(change = ExposureLocks::onTorchChanged)
                torchWanted = on
                applyTorch()
            }

            override fun settled(): Boolean? = settledNow()

            override fun calibration(centers: CalibrationCenters?) {
                burstCalibration = centers
            }

            override fun bind(view: PreviewView) = startCamera(view)

            override fun cornerMode(enabled: Boolean) {
                cornerMode = enabled
                // Story 10.22: leaving corner mode releases every lock; the grid meters as before.
                if (!enabled) moveLocks(change = ExposureLocks::cleared)
            }

            override fun latest(): FrameMeasurements? = latestFrame.get()

            override suspend fun capture(): CapturedFrame? {
                val request = CompletableDeferred<CapturedFrame>()
                captureRequest.getAndSet(request)?.cancel()
                return withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { request.await() }.also { captureRequest.compareAndSet(request, null) }
            }

            override fun remeter() = this@FixtureRecorderActivity.remeter()

            override fun remeterCorner(includeAwb: Boolean) {
                cornerMeterAwb = includeAwb
                cornerMeterPending.set(true)
                settleRequested.set(true)
            }

            override fun changeExposure(delta: Int): String = this@FixtureRecorderActivity.changeExposure(delta)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { FixtureRecorderScreen(recorderCamera) } } }
        ContextCompat.registerReceiver(this, burstReceiver, IntentFilter(RecorderBurst.ACTION), ContextCompat.RECEIVER_EXPORTED)
    }

    /** Starts a burst from the adb broadcast's extras; a burst already running is replaced. */
    private fun startBurst(intent: Intent) {
        val frames = intent.getIntExtra("frames", RecorderBurst.DEFAULT_FRAMES).coerceIn(1, RecorderBurst.MAX_FRAMES)
        val jpegEvery = intent.getIntExtra("jpegEvery", 0).coerceAtLeast(0)
        val label = intent.getStringExtra("label").orEmpty().filter { it.isLetterOrDigit() || it == '-' }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val root = getExternalFilesDir(null) ?: return
        val directory = File(root, "bursts/$stamp" + if (label.isEmpty()) "" else "-$label")
        if (!directory.isDirectory && !directory.mkdirs()) return
        if (intent.getBooleanExtra("meter", false)) {
            cornerMeterAwb = true
            cornerMeterPending.set(true)
        }
        Log.d(RecorderBurst.TAG, "recorderBurst start frames=$frames jpegEvery=$jpegEvery dir=${directory.absolutePath}")
        burst = RecorderBurst(directory, frames, jpegEvery, SystemClock.elapsedRealtime())
    }

    private fun remeter() {
        meterOnGrid()
        settleRequested.set(true)
    }

    /** Meter exposure, focus and white balance on the grid, not the whole frame, so a dark background cannot wash out the stickers. */
    private fun meterOnGrid() {
        val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f, 0.5f)
        val action =
            FocusMeteringAction
                .Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB)
                .disableAutoCancel()
                .build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    /**
     * Story 10.20, analyzer thread: one corner metering request with 10.22's settle-and-lock, as Quick Scan's
     * `meterCube`: a new settle request, the exposure lock released (and the white balance when it is metered: at hold 1
     * or when stale), then the metering itself on the main thread.
     */
    private fun requestCubeMetering(
        quads: List<List<CornerPoint>>,
        width: Int,
        height: Int,
        square: dev.cubentia.app.camera.GridSquare,
        includeAwb: Boolean,
    ) {
        val generation =
            synchronized(this) {
                lastSettle = null
                settleReported.set(false)
                settle.request(SystemClock.elapsedRealtime())
            }
        val awb = ExposureLocks.metersAwb(locks.get(), includeAwb)
        moveLocks(post = false) { ExposureLocks.onMeter(it, includeAwb) }
        meteredAwb = awb
        ContextCompat.getMainExecutor(this).execute {
            applyLatestLocks()
            meterOnCube(quads, width, height, square, awb, generation)
        }
    }

    /**
     * Story 10.19, main thread: meters exactly as Quick Scan does (same region, fallback, flags and auto-cancel, through
     * [CameraXFrameSource]'s shared helpers) on [quads], the outline detected on the frame that asked for it (never an
     * older one), falling back to the grid [square]'s centre when the camera cannot meter the outline. Story 10.20: the
     * metering future's completion feeds settle request [generation]; nothing to meter completes it at once.
     */
    private fun meterOnCube(
        quads: List<List<CornerPoint>>,
        width: Int,
        height: Int,
        square: dev.cubentia.app.camera.GridSquare,
        includeAwb: Boolean,
        generation: Int,
    ) {
        fun skip() = settle.onFutureDone(generation, SystemClock.elapsedRealtime())
        val bound = camera ?: return skip()
        val analysis = analysisUseCase ?: return skip()
        runCatching {
            val factory = SurfaceOrientedMeteringPointFactory(width.toFloat(), height.toFloat(), analysis)
            val outline = CameraXFrameSource.cubeMeteringRegion(quads, width, height)
            val centre = CameraXFrameSource.centreMeteringRegion(square, width, height)
            val (region, _) =
                CameraXFrameSource.chooseMetering(outline, centre) { candidate ->
                    bound.cameraInfo.isFocusMeteringSupported(CameraXFrameSource.meteringAction(factory, candidate, includeAwb))
                }
            val future = bound.cameraControl.startFocusAndMetering(CameraXFrameSource.meteringAction(factory, region, includeAwb))
            future.addListener(
                { settle.onFutureDone(generation, SystemClock.elapsedRealtime()) },
                ContextCompat.getMainExecutor(this),
            )
        }.onFailure { skip() }
    }

    /**
     * Story 10.20, any thread: whether the latest corner metering settled (or hit the cap); null before any. The first
     * time it has, the exposure (and at hold 1 the white balance) is locked where the camera can, and the settle is kept
     * for the next capture's diagnostics.
     */
    @Synchronized
    private fun settledNow(): Boolean? {
        val now = SystemClock.elapsedRealtime()
        val outcome = settle.outcome(now) ?: return null
        if (outcome != Settle.SETTLING && settleReported.compareAndSet(false, true)) {
            val lock = lockExposure()
            lastSettle =
                CornerSettleDiagnostics(
                    settleMs = settle.elapsed(now),
                    outcome = if (outcome == Settle.TIMEOUT) SettleOutcome.TIMEOUT else SettleOutcome.SETTLED,
                    lock = lock,
                    futureMs = settle.futureMs(),
                )
        }
        return outcome != Settle.SETTLING
    }

    /**
     * The settle a capture now records: the latest metering's (reported and locked first if it just finished), or that it
     * is still settling (lock word `none`: the metering released the lock); null before any metering.
     */
    @Synchronized
    private fun settleAtCapture(): CornerSettleDiagnostics? {
        val settled = settledNow() ?: return null
        if (settled) return lastSettle
        val now = SystemClock.elapsedRealtime()
        return CornerSettleDiagnostics(settle.elapsed(now), SettleOutcome.SETTLING, LOCK_NONE, settle.futureMs())
    }

    /** After a corner capture: no metering counts until the next request, so settled() and the next capture report none. */
    @Synchronized
    private fun forgetSettle() {
        settle.reset()
        lastSettle = null
        settleReported.set(true)
    }

    /** Locks the exposure (and the white balance when this metering included it) where the camera can; returns the lock word. */
    private fun lockExposure(): String {
        val bound = camera ?: return CameraXFrameSource.LOCK_UNSUPPORTED
        val (ae, awb) = CameraXFrameSource.lockSupport(bound)
        val metered = meteredAwb
        // Never once corner mode is off: the lock would outlive it.
        val wanted = moveLocks { if (cornerMode) ExposureLocks.onSettled(it, metered, aeSupported = ae, awbSupported = awb) else it }
        return ExposureLocks.label(wanted)
    }

    /** One atomic transition of the wanted locks; with [post] the latest state is applied on the main thread when it changed. */
    private fun moveLocks(
        post: Boolean = true,
        change: (ExposureLocks) -> ExposureLocks,
    ): ExposureLocks {
        val before = locks.getAndUpdate(change)
        val after = change(before)
        if (post && (before.ae != after.ae || before.awb != after.awb)) {
            ContextCompat.getMainExecutor(this).execute { applyLatestLocks() }
        }
        return after
    }

    /** Main thread: sets the camera to the locks wanted now; outside corner mode that is none. */
    private fun applyLatestLocks() {
        val bound = camera ?: return
        CameraXFrameSource.applyLocks(bound, if (cornerMode) locks.get() else moveLocks(post = false, change = ExposureLocks::cleared))
    }

    /**
     * Main thread: the torch as the screen wants it (Story 10.20: in corner mode from the moment it is set, calibration
     * included) on a camera with a flash unit; the applied state is kept once the camera confirmed it.
     */
    private fun applyTorch() {
        val bound = camera ?: return
        if (!bound.cameraInfo.hasFlashUnit()) {
            appliedTorch = null
            return
        }
        val lit = torchWanted
        val future = bound.cameraControl.enableTorch(lit)
        future.addListener({ if (runCatching { future.get() }.isSuccess) appliedTorch = lit }, ContextCompat.getMainExecutor(this))
    }

    /** Steps exposure compensation by [delta] (0 re-meters on the grid); returns a status line. */
    private fun changeExposure(delta: Int): String {
        val current = camera ?: return "Camera not ready."
        if (delta == 0) {
            remeter()
            return "Re-metered on the grid."
        }
        val state = current.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return "Exposure compensation is not supported on this camera."
        val range = state.exposureCompensationRange
        val next = (state.exposureCompensationIndex + delta).coerceIn(range.lower, range.upper)
        current.cameraControl.setExposureCompensationIndex(next)
        // An EV step shifts the whole grid; take the new exposure as the reference instead of reading it as realignment.
        settleRequested.set(true)
        return "Exposure %+.1f EV".format(next * state.exposureCompensationStep.toFloat())
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(burstReceiver)
        burst = null
        captureRequest.getAndSet(null)?.cancel()
        analyzerExecutor.shutdown()
    }

    private fun startCamera(view: PreviewView) {
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> viewSize = v.width to v.height }
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val analysis =
                ImageAnalysis
                    .Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageRotationEnabled(true)
                    .also(::observeExposure)
                    .build()
            analysis.setAnalyzer(analyzerExecutor) { image ->
                try {
                    analyze(image)
                } finally {
                    image.close()
                }
            }
            analysisUseCase = analysis

            fun bind(): Boolean {
                val viewPort = view.viewPort ?: return false
                val useCases =
                    UseCaseGroup
                        .Builder()
                        .setViewPort(viewPort)
                        .addUseCase(preview)
                        .addUseCase(analysis)
                        .build()
                provider.unbindAll()
                // A new camera session numbers its capture results from scratch, and holds no lock.
                settle.reset()
                moveLocks(post = false, change = ExposureLocks::cleared)
                camera = provider.bindToLifecycle(this@FixtureRecorderActivity, CameraSelector.DEFAULT_BACK_CAMERA, useCases)
                flashState.value = camera?.cameraInfo?.hasFlashUnit()
                applyTorch()
                remeter()
                return true
            }
            // Story 10.19: bind at once when the preview is already laid out (e.g. right after the permission grant);
            // only wait for a layout pass when it is not.
            if (!bind()) {
                view.viewTreeObserver.addOnGlobalLayoutListener(
                    object : ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            if (bind()) view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        }
                    },
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Keeps the latest capture result's exposure and white-balance state for a running [RecorderBurst] and the settle signal. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun observeExposure(builder: ImageAnalysis.Builder) {
        Camera2Interop.Extender(builder).setSessionCaptureCallback(
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
                    val state =
                        ExposureState(
                            aeState = result.get(CaptureResult.CONTROL_AE_STATE),
                            awbState = result.get(CaptureResult.CONTROL_AWB_STATE),
                            exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                            iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                            gains = gains?.let { listOf(it.red, it.greenEven, it.greenOdd, it.blue) },
                        )
                    exposure.set(state)
                    // Story 10.20: the 10.22 settle signal's exposure half, as Quick Scan reads it.
                    settle.onCaptureResult(result.frameNumber, state.aeState, state.exposureNs, state.iso, SystemClock.elapsedRealtime())
                }
            },
        )
    }

    /** Analyzer thread: measure, answer a pending capture with the same image, and decide on an automatic re-meter. */
    private fun analyze(image: ImageProxy) {
        val frame = measure(image)
        latestFrame.set(frame)
        val bursting = burst
        val isCorner = cornerMode || bursting != null
        val crop = image.cropRect
        val rotation = image.imageInfo.rotationDegrees
        // Story 10.19: corner mode detects on every frame, so the operator sees the live outline over the Y anchor.
        val guide =
            if (isCorner) {
                CornerGuide(
                    ViewfinderGrid.inFrame(0.0, 0.0, crop.left, crop.top, crop.right, crop.bottom, rotation),
                    rotation,
                )
            } else {
                null
            }
        val yuv = if (guide != null) with(FrameReader) { image.asYuvFrame() } else null
        val corner = if (guide != null && yuv != null) CornerReader.read(yuv, guide, cornerDetector.orElse(CornerLocator)) else null
        publishOutline(corner, crop, rotation)
        val detectedNow = corner?.detected == true
        // Story 10.19: meter like Quick Scan, on the first detected frame after a request or of a new detection episode.
        if (guide != null && corner != null && detectedNow && (cornerMeterPending.getAndSet(false) || !wasDetected)) {
            val quads = corner.frameQuads
            val square = ViewfinderGrid.inFrame(0.0, 0.0, crop.left, crop.top, crop.right, crop.bottom, rotation)
            val (width, height) = image.width to image.height
            requestCubeMetering(quads, width, height, square, cornerMeterAwb)
        }
        wasDetected = detectedNow
        // Story 10.20: lock once the metering settled, as Quick Scan does on its frames.
        if (cornerMode) settledNow()
        if (bursting != null) {
            val jpeg = if (bursting.wantsJpeg()) runCatching { encodeFrame(image, true) }.getOrNull() else null
            val done =
                bursting.add(
                    SystemClock.elapsedRealtime(),
                    corner,
                    image.width,
                    image.height,
                    rotation,
                    exposure.get(),
                    jpeg,
                    burstCalibration,
                )
            if (done) burst = null
        }
        captureRequest.getAndSet(null)?.let { request ->
            val encoded = runCatching { encodeFrame(image, isCorner) }
            val modelCrop = guide?.let(ModelCrop::of)
            val modelInput = if (modelCrop != null && yuv != null) runCatching { encodeModelInput(yuv, modelCrop) }.getOrNull() else null
            request.complete(
                CapturedFrame(
                    frame,
                    encoded.getOrNull(),
                    encoded.exceptionOrNull()?.let { it.message ?: it.toString() },
                    corner,
                    modelInput = modelInput,
                    crop = modelCrop,
                    settle = if (cornerMode) settleAtCapture() else null,
                    torch = appliedTorch,
                ),
            )
            if (cornerMode) {
                // Story 10.22: a capture frees the exposure (the hold-1 white balance stays); the next capture meters again.
                moveLocks(change = ExposureLocks::onCaptured)
                forgetSettle()
                // AWB at Hold 1 only: the next metering keeps the hold-1 white balance (a new group meters it again).
                cornerMeterAwb = false
                cornerMeterPending.set(true)
            }
        }
        val now = SystemClock.elapsedRealtime()
        val grid = frame.lab.takeIf { it.size == 9 }
        if (settleRequested.getAndSet(false)) {
            remeterTrigger.settle(grid, now)
        } else if (!cornerMode && grid != null && remeterTrigger.onFrame(grid, now)) {
            // Corner mode meters on the cube and holds its exposure lock (Story 10.20); only the grid re-meters itself.
            ContextCompat.getMainExecutor(this).execute {
                meterOnGrid()
                autoRemeterCount.intValue++
            }
        }
    }

    /** Shows the detected outline as view fractions; cleared when nothing is detected or not in corner mode. */
    private fun publishOutline(
        corner: CornerFrame?,
        crop: android.graphics.Rect,
        rotation: Int,
    ) {
        val quads = corner?.takeIf { it.detected }?.frameQuads.orEmpty()
        val (viewWidth, viewHeight) = viewSize
        val view =
            quads.map { quad ->
                quad.map { p ->
                    val (vx, vy) =
                        ViewfinderGrid.frameToViewFraction(
                            p.x,
                            p.y,
                            viewWidth.toDouble(),
                            viewHeight.toDouble(),
                            crop.left,
                            crop.top,
                            crop.right,
                            crop.bottom,
                            rotation,
                        )
                    CornerPoint(vx, vy)
                }
            }
        if (view != detectedQuads.value) ContextCompat.getMainExecutor(this).execute { detectedQuads.value = view }
    }

    /**
     * The model-input crop (Story 10.19) as a PNG: [MODEL_CROP_SIZE] square, upright, sampled from the same YUV frame and
     * in the same way (nearest pixel, BT.601) as `CornerModelTransform.fillRgbInput`, so the evaluator sees what the model
     * saw.
     */
    private fun encodeModelInput(
        frame: dev.cubentia.app.camera.YuvFrame,
        crop: ModelCrop,
    ): ByteArray {
        val size = MODEL_CROP_SIZE
        val pixels = IntArray(size * size)
        for (row in 0 until size) {
            for (column in 0 until size) {
                val point = crop.toFrame((column + 0.5) / size, (row + 0.5) / size)
                val px = point.x.roundToInt().coerceIn(frame.left, (frame.right - 1).coerceAtLeast(frame.left))
                val py = point.y.roundToInt().coerceIn(frame.top, (frame.bottom - 1).coerceAtLeast(frame.top))
                val yy = frame.y.at(px, py).toFloat()
                val uu = frame.u.at(px shr 1, py shr 1).toFloat() - 128f
                val vv = frame.v.at(px shr 1, py shr 1).toFloat() - 128f
                val r = (yy + 1.402f * vv).toInt().coerceIn(0, 255)
                val g = (yy - 0.344136f * uu - 0.714136f * vv).toInt().coerceIn(0, 255)
                val b = (yy + 1.772f * uu).toInt().coerceIn(0, 255)
                pixels[row * size + column] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG encoding failed" }
            output.toByteArray()
        }
    }

    /**
     * The analysis image as a JPEG: cropped to the same crop rect the samples use and kept in the same pixel orientation
     * (the sample's orientationDegrees applies to both), scaled so the long edge is at most [FRAME_MAX_EDGE].
     */
    private fun encodeFrame(
        image: ImageProxy,
        corner: Boolean,
    ): ByteArray {
        val full = image.toBitmap()
        val crop = image.cropRect
        val cropped =
            if (full.width == image.width &&
                full.height == image.height &&
                !crop.isEmpty &&
                (crop.width() != full.width || crop.height() != full.height)
            ) {
                Bitmap.createBitmap(full, crop.left, crop.top, crop.width(), crop.height())
            } else {
                full
            }
        val source =
            if (corner) {
                val side = minOf(cropped.width, cropped.height)
                val matrix = android.graphics.Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
                Bitmap.createBitmap(cropped, (cropped.width - side) / 2, (cropped.height - side) / 2, side, side, matrix, true)
            } else {
                cropped
            }
        val (width, height) = scaledSize(source.width, source.height)
        val scaled =
            if (width == source.width &&
                height == source.height
            ) {
                source
            } else {
                Bitmap.createScaledBitmap(source, width, height, true)
            }
        return ByteArrayOutputStream().use { output ->
            check(scaled.compress(Bitmap.CompressFormat.JPEG, FRAME_JPEG_QUALITY, output)) { "JPEG encoding failed" }
            output.toByteArray()
        }
    }

    private fun measure(image: ImageProxy): FrameMeasurements {
        val crop = image.cropRect
        if (crop.isEmpty) {
            return FrameMeasurements(
                emptyList(),
                image.imageInfo.rotationDegrees,
                CaptureQuality.INCOMPLETE,
                "No visible frame area; recapture.",
            )
        }
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        fun byte(
            plane: ImageProxy.PlaneProxy,
            x: Int,
            y: Int,
        ): Int {
            val buffer = plane.buffer
            val index = y * plane.rowStride + x * plane.pixelStride
            return buffer.get(index).toInt() and 0xff
        }

        fun rgb(
            x: Int,
            y: Int,
        ): IntArray {
            val yy = byte(yPlane, x, y).toDouble()
            val uu = byte(uPlane, x / 2, y / 2).toDouble() - 128.0
            val vv = byte(vPlane, x / 2, y / 2).toDouble() - 128.0
            val red = (yy + 1.402 * vv).toInt().coerceIn(0, 255)
            val green = (yy - 0.344136 * uu - 0.714136 * vv).toInt().coerceIn(0, 255)
            val blue = (yy + 1.772 * uu).toInt().coerceIn(0, 255)
            return intArrayOf(red, green, blue)
        }
        val samples = mutableListOf<Lab>()
        val gridSide = minOf(crop.width(), crop.height()) * 0.8
        val gridLeft = crop.left + (crop.width() - gridSide) / 2.0
        val gridTop = crop.top + (crop.height() - gridSide) / 2.0
        for (row in 0..2) {
            for (column in 0..2) {
                val x = (gridLeft + (column + 0.5) * gridSide / 3).toInt().coerceIn(crop.left, crop.right - 1)
                val y = (gridTop + (row + 0.5) * gridSide / 3).toInt().coerceIn(crop.top, crop.bottom - 1)
                val (red, green, blue) = rgb(x, y)
                samples += rgbToLab(red, green, blue)
            }
        }
        // Calibration ring around the center cell's middle, so a center logo is excluded; each point is a 3×3-pixel mean.
        val cell = gridSide / 3
        val middleX = gridLeft + 1.5 * cell
        val middleY = gridTop + 1.5 * cell
        val ring =
            ringOffsets().map { (dx, dy) ->
                val cx = (middleX + dx * cell).toInt()
                val cy = (middleY + dy * cell).toInt()
                val sum = IntArray(3)
                var count = 0
                for (oy in -1..1) {
                    for (ox in -1..1) {
                        val pixel = rgb((cx + ox).coerceIn(crop.left, crop.right - 1), (cy + oy).coerceIn(crop.top, crop.bottom - 1))
                        for (channel in 0..2) sum[channel] += pixel[channel]
                        count++
                    }
                }
                rgbToLab(sum[0] / count, sum[1] / count, sum[2] / count)
            }
        val meanLight = samples.map { it.l }.average()
        val bright = samples.count { it.l > 99.0 }
        val quality =
            when {
                meanLight < 18.0 -> CaptureQuality.LOW_LIGHT
                bright >= 3 -> CaptureQuality.GLARE
                else -> CaptureQuality.ACCEPTABLE
            }
        val detail =
            when (quality) {
                CaptureQuality.LOW_LIGHT -> "Too dark; add light and recapture."
                CaptureQuality.GLARE -> "Glare in multiple grid cells; tilt the cube and recapture."
                else -> null
            }
        return FrameMeasurements(samples, image.imageInfo.rotationDegrees, quality, detail, ring)
    }

    private fun rgbToLab(
        red: Int,
        green: Int,
        blue: Int,
    ): Lab {
        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        val r = linear(red)
        val g = linear(green)
        val b = linear(blue)
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
        val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883

        fun f(value: Double) = if (value > 0.0088564517) value.pow(1.0 / 3.0) else 7.787037 * value + 16.0 / 116.0
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return Lab((116.0 * fy - 16.0).coerceIn(0.0, 100.0), 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    private companion object {
        const val CAPTURE_TIMEOUT_MS = 3000L

        /** The settle lock word of a capture taken while the metering still settles (no lock is held then). */
        const val LOCK_NONE = "none"
    }
}
