package dev.cubentia.app.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.camera.camera2.Camera2Config
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.CameraXConfig
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.TorchState
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ExperimentalCameraProviderConfiguration
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import dev.cubentia.app.ScanDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Optional
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

private val EMPTY_STATS =
    dev.cubentia.domain.recognition
        .FrameStats(emptyList(), 0.0, 0.0, 0.0, 0)

internal data class ScanFrame(
    val stats: dev.cubentia.domain.recognition.FrameStats,
    val rotationDegrees: Int,
    val corner: CornerFrame? = null,
    val ring: () -> dev.cubentia.domain.recognition.CellReading? = { null },
)

internal interface ScanFrameSource {
    fun preview(context: Context): android.view.View

    fun start(
        owner: ComponentActivity,
        executor: ExecutorService,
        onFrame: (ScanFrame) -> Unit,
        onUnavailable: () -> Unit,
    )

    fun cornerMode(enabled: Boolean) = Unit

    fun stop()

    fun meter()

    fun meter(includeAwb: Boolean) = meter()

    fun snapshot(): androidx.compose.ui.graphics.ImageBitmap? = null

    /**
     * Story 10.19: AF+AE (and AWB when [includeAwb]) on the detected cube outline of the last corner frame, falling back
     * to the grid centre when the camera cannot meter that region. Safe on the analyzer thread: the settle signal resets
     * at once, so [settled] reads false for the very next frame. Story 10.22: the exposure lock of the previous metering
     * is released first (and the white-balance lock with it when [includeAwb]), and once this metering has settled the
     * exposure is locked until the next [meterCube], [releaseExposure] or the end of the corner view.
     */
    fun meterCube(includeAwb: Boolean) = meter(includeAwb)

    /** Whether the last metering change has settled (null: this source reports no settle signal). */
    fun settled(): Boolean? = null

    /**
     * Story 10.22: a corner was captured. The exposure lock is released; a white-balance lock taken at the first hold
     * stays, so the second hold is read under the same white balance.
     */
    fun releaseExposure() = Unit

    /**
     * The torch state last applied physically (Story 10.19): set when the camera's `enableTorch` future completed, so a
     * torch change re-meters only on the new exposure; null before any was applied.
     */
    fun torchApplied(): Boolean? = null

    /** Whether the bound camera has a flash unit; null before a camera is bound. */
    fun hasFlashUnit(): Boolean? = null

    /** The torch the corner view wants (Story 10.19); applied only on a camera with a flash unit. */
    fun torch(on: Boolean) = Unit

    /** The app went to the background (true) or came back (false): the torch is off while suspended. */
    fun suspendTorch(suspended: Boolean) = Unit
}

internal class CameraXFrameSource
    @Inject
    constructor(
        private val cornerDetector: Optional<CornerDetector>,
        private val capturedFaceObserver: Optional<CapturedFaceObserver>,
    ) : ScanFrameSource {
        @VisibleForTesting
        internal constructor() : this(Optional.empty(), Optional.empty())

        private lateinit var context: Context
        private lateinit var owner: ComponentActivity
        private lateinit var analyzerExecutor: ExecutorService
        private var deliver: (ScanFrame) -> Unit = {}
        private var unavailable: () -> Unit = {}
        private var active = false
        private var startJob: Job? = null

        private fun cameraPhase() = active

        private var cameraProvider: ProcessCameraProvider? = null
        private val cameraStateObserver =
            Observer<CameraState> { state ->
                if (cameraUnavailable(state) && cameraPhase()) unavailable()
            }
        private var camera: Camera? = null
        private var previewView: PreviewView? = null
        private var cameraStarted = false

        /** The preview view's size in pixels, read on the analyzer thread to place the grid; 0 until laid out. */
        @Volatile private var viewSize = 0 to 0

        /** The last analysed grid, as an AF/AE/AWB point on the analysis frame; null before the first frame. */
        @Volatile private var meteringGrid: MeteringGrid? = null

        /** The last detected cube outline in analysis-frame pixels (corner mode); empty when none was detected. */
        @Volatile private var meteringQuads: List<List<CornerPoint>> = emptyList()

        /** Settle tracking for the latest metering change (Story 10.19). */
        private val settle = SettleTracker()

        @Volatile private var settleReported = true

        /** The exposure and white-balance locks wanted now (Story 10.22); changed atomically, applied on the main thread. */
        private val locks = AtomicReference(ExposureLocks.NONE)

        /** When the exposure lock now wanted was asked for, and whether a capture result has reported AE LOCKED since. */
        @Volatile private var lockRequestedAt: Long? = null

        @Volatile private var lockSeen = false

        /** Whether the latest [meterCube] metered white balance too (hold 1). */
        @Volatile private var meteredAwb = false

        @Volatile private var appliedTorch: Boolean? = null

        @Volatile private var torchWanted = false

        @Volatile private var torchSuspended = false

        @Volatile private var torchAppliedAt = 0L

        @Volatile private var flashReported: Boolean? = null

        private var analysisUseCase: ImageAnalysis? = null

        /** The Preview this source bound; released with its analysis on stop (GH #276). */
        private var previewUseCase: Preview? = null

        override fun preview(context: Context): android.view.View {
            this.context = context
            return PreviewView(context).also {
                it.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                it.outlineProvider = ViewOutlineProvider.BOUNDS
                it.clipToOutline = true
                it.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> viewSize = view.width to view.height }
                previewView = it
            }
        }

        override fun start(
            owner: ComponentActivity,
            executor: ExecutorService,
            onFrame: (ScanFrame) -> Unit,
            onUnavailable: () -> Unit,
        ) {
            this.owner = owner
            analyzerExecutor = executor
            deliver = onFrame
            unavailable = onUnavailable
            active = true
            startCamera()
        }

        override fun stop() {
            active = false
            val lastCamera = camera
            val provider = cameraProvider
            val ours = listOfNotNull(previewUseCase, analysisUseCase)
            // The torch is physically off whenever the camera is released (hand-over, leaving the capture view, finish);
            // turned off while still attached, and skipped when another scan already took the camera over (GH #276).
            if (provider != null && ours.any(provider::isBound)) {
                lastCamera?.takeIf { it.cameraInfo.hasFlashUnit() }?.cameraControl?.enableTorch(false)
                // Story 10.22: no exposure or white-balance lock outlives the corner view.
                if (locks.get().held) lastCamera?.let { applyLocks(it, ExposureLocks.NONE) }
            }
            // A white balance lock dropped here is stale: the next metering meters and locks it again.
            moveLocks(post = false, change = ExposureLocks::cleared)
            // Released with the camera: the next corner view waits for the torch to come back on before metering.
            appliedTorch = false
            startJob?.cancel()
            analysisUseCase?.clearAnalyzer()
            lastCamera?.cameraInfo?.cameraState?.removeObserver(cameraStateObserver)
            // Only this source's own use cases: the provider is process-wide, and a closed scan's late stop must never
            // release the next scan's camera (GH #276).
            if (provider != null) releaseOwnUseCases(ours, provider::isBound) { provider.unbind(*it.toTypedArray()) }
            previewUseCase = null
            camera = null
            cameraStarted = false
            // Frame numbers restart with the next camera session.
            settle.reset()
            meteringQuads = emptyList()
        }

        override fun meter() = meterOnGrid(includeAwb = true)

        override fun meter(includeAwb: Boolean) = meterOnGrid(includeAwb = includeAwb)

        override fun snapshot() = previewView?.bitmap?.asImageBitmap()

        override fun hasFlashUnit(): Boolean? = camera?.cameraInfo?.hasFlashUnit()

        override fun torch(on: Boolean) {
            // Story 10.22: a white balance locked under the other torch state is stale; the next metering redoes it.
            if (on != torchWanted) moveLocks(post = false, change = ExposureLocks::onTorchChanged)
            torchWanted = on
            applyTorch(force = true)
        }

        override fun suspendTorch(suspended: Boolean) {
            torchSuspended = suspended
            applyTorch(force = true)
        }

        /** Sets the torch to what is wanted; on frames ([force] false) only when it drifted (e.g. after a camera reopen). */
        private fun applyTorch(force: Boolean) {
            val bound = camera ?: return
            val hasFlash = bound.cameraInfo.hasFlashUnit()
            val lit = hasFlash && torchWanted && !torchSuspended && corner
            if (flashReported != hasFlash || force) {
                flashReported = hasFlash
                ScanDiagnostics.onTorch(hasFlash, lit)
            }
            if (!hasFlash) {
                appliedTorch = false
                return
            }
            val now = SystemClock.elapsedRealtime()
            val current = bound.cameraInfo.torchState.value == TorchState.ON
            if (!force && (current == lit || now - torchAppliedAt < TORCH_RETRY_MS)) {
                if (current == lit) appliedTorch = lit
                return
            }
            torchAppliedAt = now
            // Applied only once the future succeeds: a failed or cancelled change leaves the torch unapplied, and the
            // domain's settle cap covers a torch that never lit.
            val future = bound.cameraControl.enableTorch(lit)
            future.addListener(
                { if (runCatching { future.get() }.isSuccess) appliedTorch = lit },
                ContextCompat.getMainExecutor(context),
            )
        }

        override fun torchApplied(): Boolean? = appliedTorch

        override fun meterCube(includeAwb: Boolean) {
            val generation = settle.request(SystemClock.elapsedRealtime())
            settleReported = false
            // Story 10.22: a re-meter releases the exposure lock first, or the metering could not move the exposure. A
            // stale white balance is metered again even at hold 2.
            var awb = includeAwb
            moveLocks(post = false) {
                awb = ExposureLocks.metersAwb(it, includeAwb)
                ExposureLocks.onMeter(it, includeAwb)
            }
            meteredAwb = awb
            ContextCompat.getMainExecutor(context).execute {
                applyLatestLocks()
                meterOn(awb, generation, cube = true)
            }
        }

        override fun settled(): Boolean? {
            val now = SystemClock.elapsedRealtime()
            val outcome = settle.outcome(now) ?: return null
            if (outcome != Settle.SETTLING && !settleReported) {
                settleReported = true
                // Story 10.22: lock what the metering settled on (at the cap: whatever exposure the camera has).
                val lock = lockExposure()
                ScanDiagnostics.onMeteringSettled(
                    settle.elapsed(now) ?: 0L,
                    timeout = outcome == Settle.TIMEOUT,
                    lock = lock,
                    futureMs = settle.futureMs(),
                )
            }
            return outcome != Settle.SETTLING
        }

        override fun releaseExposure() {
            moveLocks(change = ExposureLocks::onCaptured)
        }

        /** Locks the exposure (and the white balance at hold 1) where the camera can; returns the diagnostics word. */
        private fun lockExposure(): String {
            val bound = camera ?: return LOCK_UNSUPPORTED
            val (ae, awb) = lockSupport(bound)
            val metered = meteredAwb
            // Never once the corner view is off: the lock would outlive it.
            val wanted = moveLocks { if (corner) ExposureLocks.onSettled(it, metered, aeSupported = ae, awbSupported = awb) else it }
            return ExposureLocks.label(wanted)
        }

        /**
         * One atomic transition of the wanted locks, from any thread. Logs an exposure lock that ends with what the
         * camera reported while it was wanted, and with [post] applies the latest state on the main thread.
         */
        private fun moveLocks(
            post: Boolean = true,
            change: (ExposureLocks) -> ExposureLocks,
        ): ExposureLocks {
            val before = locks.getAndUpdate(change)
            val after = change(before)
            val now = SystemClock.elapsedRealtime()
            if (before.ae && !after.ae) {
                ScanDiagnostics.onExposureLockReleased(confirmed = lockSeen, heldMs = lockRequestedAt?.let { now - it })
                lockRequestedAt = null
            } else if (!before.ae && after.ae) {
                lockSeen = false
                lockRequestedAt = now
            }
            if (post && (before.ae != after.ae || before.awb != after.awb)) {
                ContextCompat.getMainExecutor(context).execute { applyLatestLocks() }
            }
            return after
        }

        /** Main thread: sets the camera to the locks wanted now; with the corner view off that is none, whatever was asked. */
        private fun applyLatestLocks() {
            val bound = camera ?: return
            applyLocks(bound, if (corner) locks.get() else moveLocks(post = false, change = ExposureLocks::cleared))
        }

        /** Whether the bound camera can lock AE and AWB; (false, false) when it cannot say. */
        @OptIn(ExperimentalCamera2Interop::class)
        private fun lockSupport(bound: Camera): Pair<Boolean, Boolean> =
            runCatching {
                val info = Camera2CameraInfo.from(bound.cameraInfo)
                (info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true) to
                    (info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true)
            }.getOrDefault(false to false)

        /** Sets the capture-request lock options to [wanted]; never throws (a camera that refuses keeps its auto modes). */
        @OptIn(ExperimentalCamera2Interop::class)
        private fun applyLocks(
            bound: Camera,
            wanted: ExposureLocks,
        ) {
            runCatching {
                val control = Camera2CameraControl.from(bound.cameraControl)
                if (!wanted.held) {
                    control.clearCaptureRequestOptions()
                } else {
                    control.setCaptureRequestOptions(
                        CaptureRequestOptions
                            .Builder()
                            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, wanted.ae)
                            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, wanted.awb)
                            .build(),
                    )
                }
            }
        }

        /** Obtains the provider off the main thread, then binds on main once the preview has a viewport. */
        private fun startCamera() {
            val view = previewView ?: return
            if (cameraStarted || !cameraPhase()) return
            cameraStarted = true
            startJob =
                owner.lifecycleScope.launch {
                    val provider =
                        try {
                            withContext(Dispatchers.Default) {
                                configureBackCameraOnly()
                                ProcessCameraProvider.getInstance(context.applicationContext).get()
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (ignored: Exception) {
                            if (cameraPhase()) unavailable()
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
                        // Story 10.22: CameraX's own debug logging filled the device log buffer during a scan session.
                        .setMinimumLoggingLevel(Log.ERROR)
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

            fun bind(): Boolean {
                if (!cameraPhase() || previewView !== view) return true
                val viewPort = view.viewPort ?: return false
                // Sized once the viewport is known: a pane longer than 4:3 crops more of the frame (GH #158).
                val analysis = analysis(analysisTarget(view.width, view.height))
                val useCases =
                    UseCaseGroup
                        .Builder()
                        .setViewPort(viewPort)
                        .addUseCase(preview)
                        .addUseCase(analysis)
                        .build()
                try {
                    camera?.cameraInfo?.cameraState?.removeObserver(cameraStateObserver)
                    provider.unbindAll()
                    previewUseCase = preview
                    // A new camera session numbers its capture results from scratch.
                    settle.reset()
                    camera =
                        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, useCases).also { bound ->
                            // Bound but a critical error (disabled, fatal, Do Not Disturb, …): the camera-unavailable
                            // card, never remembered. An in-use error is not shown; CameraX retries the open.
                            bound.cameraInfo.cameraState.observe(owner, cameraStateObserver)
                        }
                } catch (ignored: IllegalArgumentException) {
                    unavailable()
                } catch (ignored: IllegalStateException) {
                    unavailable()
                } catch (ignored: UnsupportedOperationException) {
                    unavailable()
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

        private fun analysis(target: Size): ImageAnalysis {
            analysisUseCase?.clearAnalyzer()
            val selector =
                ResolutionSelector
                    .Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
            val builder =
                ImageAnalysis
                    .Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(selector)
            observeAeState(builder)
            return builder
                .build()
                .also { analysis ->
                    analysisUseCase = analysis
                    analysis.setAnalyzer(analyzerExecutor) { image ->
                        try {
                            read(image)
                        } finally {
                            image.close()
                        }
                    }
                }
        }

        @Volatile private var corner = false

        override fun cornerMode(enabled: Boolean) {
            corner = enabled
            // Story 10.22: leaving the corner view releases every lock; the Guided grid meters as before.
            if (!enabled) moveLocks(change = ExposureLocks::cleared)
        }

        /**
         * Reads the AE state, exposure time and sensitivity from every capture result: the settle signal's exposure half
         * (Stories 10.19, 10.22).
         */
        @OptIn(ExperimentalCamera2Interop::class)
        private fun observeAeState(builder: ImageAnalysis.Builder) {
            Camera2Interop.Extender(builder).setSessionCaptureCallback(
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                        // What the camera says, for the lock-release line: the wanted state alone proves nothing.
                        if (lockRequestedAt != null && ae == CaptureResult.CONTROL_AE_STATE_LOCKED) lockSeen = true
                        settle.onCaptureResult(
                            result.frameNumber,
                            ae,
                            result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                            result.get(CaptureResult.SENSOR_SENSITIVITY),
                            SystemClock.elapsedRealtime(),
                        )
                    }
                },
            )
        }

        @VisibleForTesting
        internal fun read(image: ImageProxy) {
            val rotation = image.imageInfo.rotationDegrees
            val crop = image.cropRect
            val (width, height) = viewSize
            val grid = ViewfinderGrid.inFrame(width.toDouble(), height.toDouble(), crop.left, crop.top, crop.right, crop.bottom, rotation)
            meteringGrid = MeteringGrid(image.width, image.height, grid)
            if (corner) {
                val detector = cornerDetector.orElse(CornerLocator)
                // Story 10.23: the unwarped faces are kept for the frame only when a captured-face observer is bound.
                val reading =
                    CornerReader.read(
                        with(FrameReader) { image.asYuvFrame() },
                        CornerGuide(grid, rotation),
                        detector,
                        keepCrops = capturedFaceObserver.isPresent,
                    )
                meteringQuads = if (reading.detected) reading.frameQuads else emptyList()
                applyTorch(force = false)
                val viewQuads =
                    reading.frameQuads.map { quad ->
                        quad.map { p ->
                            val (vx, vy) =
                                ViewfinderGrid.frameToViewFraction(
                                    p.x,
                                    p.y,
                                    width.toDouble(),
                                    height.toDouble(),
                                    crop.left,
                                    crop.top,
                                    crop.right,
                                    crop.bottom,
                                    rotation,
                                )
                            CornerPoint(vx, vy)
                        }
                    }
                deliver(ScanFrame(EMPTY_STATS, rotation, corner = reading.copy(normalizedQuads = viewQuads)))
                return
            }
            val reading = FrameReader.locate(image, grid, rotation)
            val lattice = reading.lattice
            ScanDiagnostics.onFrame(reading.stats, SystemClock.elapsedRealtime(), lattice.scale, lattice.dx, lattice.dy, lattice.score)
            deliver(ScanFrame(reading.stats, rotation) { FrameReader.centerRing(image, reading.samplingSquare(grid)) })
        }

        /**
         * AF/AE/AWB on the analysed grid (the drawn grid's region of the analysis frame), not the whole frame, so a dark
         * background cannot wash out the stickers. The point's size is a share of the frame's longer side, so the metered
         * region stays inside the grid. The lock cancels after [METERING_LOCK_SECONDS] so continuous AF resumes when the
         * cube moves nearer or farther (PO 2026-11-16).
         */
        private fun meterOnGrid(includeAwb: Boolean = true) = meterOn(includeAwb, generation = null, cube = false)

        /**
         * Main thread. With [cube], meters on the last detected outline when the camera supports that action, else on the
         * grid centre (logged); [generation] ties the future's completion to the settle signal of [meterCube].
         */
        private fun meterOn(
            includeAwb: Boolean,
            generation: Int?,
            cube: Boolean,
        ) {
            fun skip() {
                // Nothing to meter: the settle signal does not wait for it.
                if (generation != null) settle.onFutureDone(generation, SystemClock.elapsedRealtime())
            }
            // The corner view may have given way to the Guided grid since the analyzer asked: never meter that on the cube.
            if (cube && !corner) return skip()
            val grid = meteringGrid ?: return skip()
            val analysis = analysisUseCase ?: return skip()
            val bound = camera ?: return skip()
            val centre = centreMeteringRegion(grid.square, grid.width, grid.height)
            val outline = if (cube) cubeMeteringRegion(meteringQuads, grid.width, grid.height) else null
            val factory = SurfaceOrientedMeteringPointFactory(grid.width.toFloat(), grid.height.toFloat(), analysis)

            fun action(region: MeteringRegion): FocusMeteringAction? =
                try {
                    meteringAction(factory, region, includeAwb)
                } catch (ignored: IllegalStateException) {
                    // The analysis use case is no longer bound: nothing to meter.
                    null
                }
            val (region, supported) =
                chooseMetering(outline, centre) { r ->
                    action(r)?.let(bound.cameraInfo::isFocusMeteringSupported) ==
                        true
                }
            if (cube) ScanDiagnostics.onMetering(supported = supported, onOutline = region === outline)
            val chosen = action(region) ?: return skip()
            val future = bound.cameraControl.startFocusAndMetering(chosen)
            if (generation != null) {
                future.addListener(
                    { settle.onFutureDone(generation, SystemClock.elapsedRealtime()) },
                    ContextCompat.getMainExecutor(context),
                )
            }
        }

        companion object {
            const val METERING_LOCK_SECONDS = 3L

            /** The longest the settle signal waits for the metering future and a stable exposure (shared with the domain). */
            const val SETTLE_CAP_MS = dev.cubentia.domain.recognition.CornerSettle.CAP_MS

            /**
             * Story 10.22: exposure time × ISO counts as one level while its largest value is within this share of its
             * smallest. Still burst 1 moves about 5% inside a level and 14% between its two levels; every value from 0.04
             * to 0.10 reports "not settled" throughout that burst's swing (docs/evidence/10-22/README.md).
             */
            const val SETTLE_TOLERANCE = 0.06

            /**
             * Story 10.22: the exposure level must hold this long, after the metering future, to count as settled. On still
             * burst 1 a 400 ms window settles inside the swing; 500 ms and 750 ms do not.
             */
            const val SETTLE_WINDOW_MS = 600L

            /** [ScanDiagnostics.onMeteringSettled]'s lock word when the camera cannot lock its exposure. */
            const val LOCK_UNSUPPORTED = "unsupported"

            /** The torch is re-applied at most this often when it drifted from what is wanted. */
            const val TORCH_RETRY_MS = 1000L

            /**
             * The metering region over the detected cube outline [quads] (analysis-frame pixels): the centre of the
             * outline's bounding box, sized by its longer side as a share of the frame's longer side; null when no
             * outline was detected or the frame size is unknown.
             */
            internal fun cubeMeteringRegion(
                quads: List<List<CornerPoint>>,
                width: Int,
                height: Int,
            ): MeteringRegion? {
                val points = quads.flatten()
                if (points.isEmpty() || width <= 0 || height <= 0) return null
                val left = points.minOf { it.x }.coerceIn(0.0, width.toDouble())
                val right = points.maxOf { it.x }.coerceIn(0.0, width.toDouble())
                val top = points.minOf { it.y }.coerceIn(0.0, height.toDouble())
                val bottom = points.maxOf { it.y }.coerceIn(0.0, height.toDouble())
                if (right <= left || bottom <= top) return null
                val size = (maxOf(right - left, bottom - top) / maxOf(width, height)).coerceIn(0.0, 1.0)
                return MeteringRegion((left + right) / 2, (top + bottom) / 2, size)
            }

            /** The grid-centre metering region (today's point): the drawn grid's square on a [width]×[height] frame. */
            internal fun centreMeteringRegion(
                square: GridSquare,
                width: Int,
                height: Int,
            ): MeteringRegion =
                MeteringRegion(
                    square.left + square.side / 2,
                    square.top + square.side / 2,
                    square.side / maxOf(width, height),
                )

            /**
             * The one metering action Quick Scan (and the debug recorder's corner mode, so its fixtures meter the same way)
             * starts on [region]: [meteringFlags] for [includeAwb], cancelled after [METERING_LOCK_SECONDS].
             */
            internal fun meteringAction(
                factory: androidx.camera.core.MeteringPointFactory,
                region: MeteringRegion,
                includeAwb: Boolean,
            ): FocusMeteringAction =
                FocusMeteringAction
                    .Builder(
                        factory.createPoint(region.x.toFloat(), region.y.toFloat(), region.size.toFloat().coerceIn(0f, 1f)),
                        meteringFlags(includeAwb),
                    ).setAutoCancelDuration(METERING_LOCK_SECONDS, TimeUnit.SECONDS)
                    .build()

            /** The outline when the camera supports metering it, else the grid [centre]; second: whether it was supported. */
            internal fun chooseMetering(
                outline: MeteringRegion?,
                centre: MeteringRegion,
                supported: (MeteringRegion) -> Boolean,
            ): Pair<MeteringRegion, Boolean> =
                when {
                    outline == null -> centre to supported(centre)
                    supported(outline) -> outline to true
                    else -> centre to false
                }

            /**
             * The settle signal (Story 10.22): settled once the metering future completed and exposure time × ISO has
             * stayed within [SETTLE_TOLERANCE] for [SETTLE_WINDOW_MS] since ([stableMs]; null while no exposure level is
             * known); a timeout once [SETTLE_CAP_MS] passed without that. The AE state alone never settles it.
             */
            internal fun settleOutcome(
                futureDone: Boolean,
                stableMs: Long?,
                elapsedMs: Long,
            ): Settle =
                when {
                    futureDone && stableMs != null && stableMs >= SETTLE_WINDOW_MS -> Settle.SETTLED
                    elapsedMs >= SETTLE_CAP_MS -> Settle.TIMEOUT
                    else -> Settle.SETTLING
                }

            /**
             * AF + AE always; AWB only when [includeAwb]. Quick Scan Hold 2 skips it so white balance is not re-metered on
             * a no-white Y/O/B grid; since Story 10.22 the white balance locked at Hold 1 is carried through Hold 2
             * ([ExposureLocks]) where the camera can lock it.
             */
            internal fun meteringFlags(includeAwb: Boolean): Int =
                if (includeAwb) {
                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
                } else {
                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                }
        }
    }

/**
 * Whether [state] means the camera cannot be used (GH #158): only a CRITICAL error (disabled, fatal, Do Not Disturb,
 * stream configuration, removed). A RECOVERABLE one (in use, max cameras in use) keeps the viewfinder; CameraX retries
 * the open when the other client releases the camera.
 */
internal fun cameraUnavailable(state: CameraState): Boolean = state.error?.type == CameraState.ErrorType.CRITICAL

/**
 * The ImageAnalysis target for a [viewWidth]×[viewHeight] viewport (GH #158): 640×480 (CameraX's default) when its long
 * to short ratio is at most 4:3 or the size is unknown, else 1280×960, so a narrow pane's crop still gives the 12×12
 * lattice cells of a useful size (a 3.15:1 pane: ~406 px short side instead of ~203 px).
 */
internal fun analysisTarget(
    viewWidth: Int,
    viewHeight: Int,
): Size {
    val long = maxOf(viewWidth, viewHeight)
    val short = minOf(viewWidth, viewHeight)
    // long / short > 4 / 3, in integers.
    return if (short > 0 && long * 3 > short * 4) LARGE_ANALYSIS else DEFAULT_ANALYSIS
}

private val DEFAULT_ANALYSIS = Size(640, 480)
private val LARGE_ANALYSIS = Size(1280, 960)

/** A metering point on the analysis frame: centre ([x], [y]) in pixels, [size] a share of the frame's longer side. */
internal data class MeteringRegion(
    val x: Double,
    val y: Double,
    val size: Double,
)

internal enum class Settle { SETTLING, SETTLED, TIMEOUT }

/**
 * The exposure (AE) and white-balance (AWB) locks the corner view holds (Story 10.22). AE is locked from the moment a
 * metering settles until the capture or the next metering; AWB is locked with hold 1's metering and carried through
 * hold 2, whose metering leaves white balance alone. A camera that cannot lock keeps its auto modes. [awbStale]: the
 * white balance last locked no longer fits (the torch changed, or a camera stop dropped the lock), so the next metering
 * meters and locks it again whatever the hold.
 */
internal data class ExposureLocks(
    val ae: Boolean,
    val awb: Boolean,
    val awbStale: Boolean = false,
) {
    /** True while a lock is wanted on the camera. */
    val held: Boolean get() = ae || awb

    companion object {
        val NONE = ExposureLocks(ae = false, awb = false)

        /** Whether a metering request meters white balance: when asked ([includeAwb]) or when the last one is stale. */
        fun metersAwb(
            current: ExposureLocks,
            includeAwb: Boolean,
        ) = includeAwb || current.awbStale

        /** A metering request: exposure must move again, and white balance too when it is metered ([metersAwb]). */
        fun onMeter(
            current: ExposureLocks,
            includeAwb: Boolean,
        ) = current.copy(ae = false, awb = current.awb && !metersAwb(current, includeAwb))

        /** The metering settled: lock what the camera can; a metering that included white balance locks that too. */
        fun onSettled(
            current: ExposureLocks,
            includeAwb: Boolean,
            aeSupported: Boolean,
            awbSupported: Boolean,
        ) = ExposureLocks(ae = aeSupported, awb = current.awb || (includeAwb && awbSupported), awbStale = current.awbStale && !includeAwb)

        /** A corner was captured, or the cube was lost: the exposure is free again, the white balance stays. */
        fun onCaptured(current: ExposureLocks) = current.copy(ae = false)

        /** The torch changed: a white balance locked under the other torch state is stale. */
        fun onTorchChanged(current: ExposureLocks) = current.copy(awbStale = true)

        /** The corner view ended or the camera stopped: no lock is left, and a white balance that was locked is stale. */
        fun cleared(current: ExposureLocks) = ExposureLocks(ae = false, awb = false, awbStale = current.awb || current.awbStale)

        /** The word the settle diagnostics line carries: `on`, `on+awb` or `unsupported` (no exposure lock). */
        fun label(locks: ExposureLocks): String =
            when {
                !locks.ae -> CameraXFrameSource.LOCK_UNSUPPORTED
                locks.awb -> "on+awb"
                else -> "on"
            }
    }
}

/**
 * The settle signal's state for the latest metering request (Stories 10.19, 10.22), safe across the analyzer, main and
 * capture callback threads. [request] starts a new one: its future is not done and no exposure level is known again, and
 * only capture results with a frame number after the newest one seen at the request count, so a stale result from before
 * the request never settles it. Once the future completed, the results' exposure time × ISO is followed: the level
 * restarts whenever a result leaves [CameraXFrameSource.SETTLE_TOLERANCE] of the running level's range, reports no
 * exposure, or reports AE still searching; [outcome] is settled once one level has held for
 * [CameraXFrameSource.SETTLE_WINDOW_MS]. A converged AE state alone never settles it (still burst 1 kept moving while
 * reporting converged).
 */
internal class SettleTracker {
    private var generation = 0
    private var startedAt: Long? = null
    private var futureDone = false
    private var futureAt: Long? = null
    private var lastFrameNumber = Long.MIN_VALUE
    private var frameFloor = Long.MIN_VALUE

    /** The running exposure level: when it began, its last result, and its smallest and largest exposure time × ISO. */
    private var levelSince: Long? = null
    private var levelAt = 0L
    private var levelMin = 0.0
    private var levelMax = 0.0

    /** A metering request at [nowMs]; returns its generation, for [onFutureDone]. */
    @Synchronized
    fun request(nowMs: Long): Int {
        // The generation moves first, so a late onFutureDone of the previous request cannot mark this one done.
        val next = generation + 1
        generation = next
        startedAt = nowMs
        futureDone = false
        futureAt = null
        levelSince = null
        frameFloor = lastFrameNumber
        return next
    }

    /**
     * A new camera session (bind) or a released camera (stop): frame numbers restart, so the frame floor and the newest
     * frame number seen are forgotten, and any pending request is dropped (its future can no longer mark anything done).
     */
    @Synchronized
    fun reset() {
        generation += 1
        startedAt = null
        futureDone = false
        futureAt = null
        levelSince = null
        lastFrameNumber = Long.MIN_VALUE
        frameFloor = Long.MIN_VALUE
    }

    /** One capture result at [nowMs]: its AE state, exposure time (ns) and sensitivity (ISO), null where unreported. */
    @Synchronized
    fun onCaptureResult(
        frameNumber: Long,
        ae: Int?,
        exposureNs: Long?,
        iso: Int?,
        nowMs: Long,
    ) {
        lastFrameNumber = maxOf(lastFrameNumber, frameNumber)
        // The window runs after the metering future only, on results newer than the request.
        if (frameNumber <= frameFloor || !futureDone) return
        val level = if (exposureNs != null && iso != null && exposureNs > 0 && iso > 0) exposureNs.toDouble() * iso else null
        if (level == null || ae == CaptureResult.CONTROL_AE_STATE_SEARCHING) {
            levelSince = null
            return
        }
        val low = minOf(levelMin, level)
        val high = maxOf(levelMax, level)
        if (levelSince == null || (high - low) / low > CameraXFrameSource.SETTLE_TOLERANCE) {
            levelSince = nowMs
            levelMin = level
            levelMax = level
        } else {
            levelMin = low
            levelMax = high
        }
        levelAt = nowMs
    }

    /** The metering future of request [generation] completed at [nowMs] (or there was nothing to meter). */
    @Synchronized
    fun onFutureDone(
        generation: Int,
        nowMs: Long? = null,
    ) {
        if (generation != this.generation) return
        futureDone = true
        futureAt = nowMs
    }

    /** Milliseconds from the latest request to its metering future's completion; null while unknown. */
    @Synchronized
    fun futureMs(): Long? = futureAt?.let { done -> startedAt?.let { done - it } }

    /** Milliseconds since the latest request; null before any. */
    @Synchronized
    fun elapsed(nowMs: Long): Long? = startedAt?.let { nowMs - it }

    /** How long the running exposure level has held, up to its last result; null while none is known. */
    @Synchronized
    fun stableMs(): Long? = levelSince?.let { levelAt - it }

    /** The latest request's outcome at [nowMs]; null before any request. */
    @Synchronized
    fun outcome(nowMs: Long): Settle? = elapsed(nowMs)?.let { CameraXFrameSource.settleOutcome(futureDone, stableMs(), it) }
}

private class MeteringGrid(
    val width: Int,
    val height: Int,
    val square: GridSquare,
)

/**
 * Unbinds those of [ours] still bound and reports whether any were (GH #276). Use cases another scan took over were already
 * unbound by its bind, so a late stop releases nothing and leaves the newer camera alone.
 */
internal fun <T> releaseOwnUseCases(
    ours: List<T>,
    isBound: (T) -> Boolean,
    unbind: (List<T>) -> Unit,
): Boolean {
    val still = ours.filter(isBound)
    if (still.isNotEmpty()) unbind(still)
    return still.isNotEmpty()
}
