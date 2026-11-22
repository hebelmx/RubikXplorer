package dev.cubentia.app.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import android.util.Size
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.camera.camera2.Camera2Config
import androidx.camera.camera2.interop.Camera2Interop
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
     * at once, so [settled] reads false for the very next frame.
     */
    fun meterCube(includeAwb: Boolean) = meter(includeAwb)

    /** Whether the last metering change has settled (null: this source reports no settle signal). */
    fun settled(): Boolean? = null

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
    ) : ScanFrameSource {
        @VisibleForTesting
        internal constructor() : this(Optional.empty())

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
            }
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
            ContextCompat.getMainExecutor(context).execute { meterOn(includeAwb, generation, cube = true) }
        }

        override fun settled(): Boolean? {
            val now = SystemClock.elapsedRealtime()
            val outcome = settle.outcome(now) ?: return null
            if (outcome != Settle.SETTLING && !settleReported) {
                settleReported = true
                ScanDiagnostics.onMeteringSettled(settle.elapsed(now) ?: 0L, timeout = outcome == Settle.TIMEOUT)
            }
            return outcome != Settle.SETTLING
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
        }

        /** Reads CONTROL_AE_STATE from every capture result: the settle signal's exposure half (Story 10.19). */
        @OptIn(ExperimentalCamera2Interop::class)
        private fun observeAeState(builder: ImageAnalysis.Builder) {
            Camera2Interop.Extender(builder).setSessionCaptureCallback(
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        settle.onCaptureResult(result.frameNumber, result.get(CaptureResult.CONTROL_AE_STATE))
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
                val reading = CornerReader.read(with(FrameReader) { image.asYuvFrame() }, CornerGuide(grid, rotation), detector)
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
                if (generation != null) settle.onFutureDone(generation)
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
                future.addListener({ settle.onFutureDone(generation) }, ContextCompat.getMainExecutor(context))
            }
        }

        companion object {
            const val METERING_LOCK_SECONDS = 3L

            /** The longest the settle signal waits for the metering future and AE convergence (shared with the domain). */
            const val SETTLE_CAP_MS = dev.cubentia.domain.recognition.CornerSettle.CAP_MS

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
             * The settle signal: settled once the metering future completed and the AE state reported after the request is
             * CONVERGED, LOCKED or FLASH_REQUIRED; a timeout once [SETTLE_CAP_MS] passed without that (an AE state still
             * unknown then included).
             */
            internal fun settleOutcome(
                futureDone: Boolean,
                aeState: Int?,
                elapsedMs: Long,
            ): Settle =
                when {
                    futureDone && aeState != null && aeState in SETTLED_AE_STATES -> Settle.SETTLED
                    elapsedMs >= SETTLE_CAP_MS -> Settle.TIMEOUT
                    else -> Settle.SETTLING
                }

            private val SETTLED_AE_STATES =
                setOf(
                    CaptureResult.CONTROL_AE_STATE_CONVERGED,
                    CaptureResult.CONTROL_AE_STATE_LOCKED,
                    CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED,
                )

            /**
             * AF + AE always; AWB only when [includeAwb]. Quick Scan Hold 2 skips it so white balance is not re-metered on
             * a no-white Y/O/B grid; this is no AWB lock (AWB stays continuous auto once Hold 1's action cancels).
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
 * The settle signal's state for the latest metering request (Story 10.19), safe across the analyzer, main and capture
 * callback threads. [request] starts a new one: its future is not done and the AE state is unknown again, and only
 * capture results with a frame number after the newest one seen at the request count, so a stale AE state from before
 * the request never settles it.
 */
internal class SettleTracker {
    @Volatile private var generation = 0

    @Volatile private var startedAt: Long? = null

    @Volatile private var futureDone = false

    @Volatile private var aeState: Int? = null

    @Volatile private var lastFrameNumber = Long.MIN_VALUE

    @Volatile private var frameFloor = Long.MIN_VALUE

    /** A metering request at [nowMs]; returns its generation, for [onFutureDone]. */
    fun request(nowMs: Long): Int {
        // The generation moves first, so a late onFutureDone of the previous request cannot mark this one done.
        val next = generation + 1
        generation = next
        startedAt = nowMs
        futureDone = false
        aeState = null
        frameFloor = lastFrameNumber
        return next
    }

    /**
     * A new camera session (bind) or a released camera (stop): frame numbers restart, so the frame floor and the newest
     * frame number seen are forgotten, and any pending request is dropped (its future can no longer mark anything done).
     */
    fun reset() {
        generation += 1
        startedAt = null
        futureDone = false
        aeState = null
        lastFrameNumber = Long.MIN_VALUE
        frameFloor = Long.MIN_VALUE
    }

    fun onCaptureResult(
        frameNumber: Long,
        ae: Int?,
    ) {
        lastFrameNumber = maxOf(lastFrameNumber, frameNumber)
        if (frameNumber > frameFloor) aeState = ae
    }

    /** The metering future of request [generation] completed (or there was nothing to meter). */
    fun onFutureDone(generation: Int) {
        if (generation == this.generation) futureDone = true
    }

    /** Milliseconds since the latest request; null before any. */
    fun elapsed(nowMs: Long): Long? = startedAt?.let { nowMs - it }

    /** The latest request's outcome at [nowMs]; null before any request. */
    fun outcome(nowMs: Long): Settle? = elapsed(nowMs)?.let { CameraXFrameSource.settleOutcome(futureDone, aeState, it) }
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
