package dev.cubentia.app.camera

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
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
    val continuous: ContinuousFrame? = null,
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

    fun continuousMode(
        engine: ContinuousScanEngine?,
        analyzing: () -> Boolean,
    ) = Unit

    fun cornerMode(enabled: Boolean) = Unit

    /** Story 10.21: the calibration phase is shown; the torch may be lit in it as in the continuous Quick Scan. */
    fun calibrationMode(enabled: Boolean) = Unit

    fun stop()

    fun meter()

    fun snapshot(): androidx.compose.ui.graphics.ImageBitmap? = null

    /**
     * The torch state last applied physically (Story 10.19): set when the camera's `enableTorch` future completed, so a
     * torch change re-meters only on the new exposure; null before any was applied.
     */
    fun torchApplied(): Boolean? = null

    /** Whether the bound camera has a flash unit; null before a camera is bound. */
    fun hasFlashUnit(): Boolean? = null

    /** The torch the continuous Quick Scan or calibration wants (Stories 10.21, 10.32); applied only on a camera with a flash unit. */
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

        @Volatile private var continuous: ContinuousScanEngine? = null

        @Volatile private var analyzing: () -> Boolean = { false }

        override fun continuousMode(
            engine: ContinuousScanEngine?,
            analyzing: () -> Boolean,
        ) {
            this.analyzing = analyzing
            continuous = engine
        }

        private var cameraStarted = false

        /** The preview view's size in pixels, read on the analyzer thread to place the grid; 0 until laid out. */
        @Volatile private var viewSize = 0 to 0

        /** The last analysed grid, as an AF/AE/AWB point on the analysis frame; null before the first frame. */
        @Volatile private var meteringGrid: MeteringGrid? = null

        @get:VisibleForTesting
        internal val meteringSquare: GridSquare? get() = meteringGrid?.square

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
            // Released with the camera.
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
        }

        override fun meter() = meterOnGrid()

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
            val lit = torchLit(hasFlash, torchWanted, torchSuspended, corner, calibrating, continuous != null)
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
            // Applied only once the future succeeds: a failed or cancelled change leaves the torch unapplied.
            val future = bound.cameraControl.enableTorch(lit)
            future.addListener(
                { if (runCatching { future.get() }.isSuccess) appliedTorch = lit },
                ContextCompat.getMainExecutor(context),
            )
        }

        override fun torchApplied(): Boolean? = appliedTorch

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

        /** Story 10.21: the calibration phase is shown, where the torch may be lit. */
        @Volatile private var calibrating = false

        override fun calibrationMode(enabled: Boolean) {
            calibrating = enabled
        }

        override fun cornerMode(enabled: Boolean) {
            corner = enabled
        }

        @VisibleForTesting
        internal fun read(image: ImageProxy) {
            val rotation = image.imageInfo.rotationDegrees
            val crop = image.cropRect
            val (width, height) = viewSize
            continuous?.let { engine ->
                applyTorch(force = false)
                if (analyzing()) {
                    // GH #406: the timed analyzer interval spans conversion, processing, frameDone and delivery.
                    engine.analyzerStart()
                    // Story 10.34: the analyzer time of the frame and its geometry go to a recording tap as primitives.
                    val start = System.nanoTime()
                    val result = engine.processFrame(with(FrameReader) { image.asYuvFrame() }, rotation, width, height)
                    val analyzerNs = System.nanoTime() - start
                    engine.frameDone(
                        image.imageInfo.timestamp,
                        analyzerNs,
                        rotation,
                        image.width,
                        image.height,
                        crop.left,
                        crop.top,
                        crop.right,
                        crop.bottom,
                    )
                    deliver(ScanFrame(EMPTY_STATS, rotation, continuous = result))
                    engine.analyzerEnd()
                }
                return
            }
            val grid = ViewfinderGrid.inFrame(width.toDouble(), height.toDouble(), crop.left, crop.top, crop.right, crop.bottom, rotation)
            if (corner) {
                meteringGrid = MeteringGrid(image.width, image.height, grid)
                val detector = cornerDetector.orElse(CornerLocator)
                val reading =
                    CornerReader.read(
                        with(FrameReader) { image.asYuvFrame() },
                        CornerGuide(grid, rotation),
                        detector,
                    )
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
            // Story 10.21: the calibration phase keeps the torch as wanted (re-applied when it drifted after a reopen).
            if (calibrating) applyTorch(force = false)
            val reading = FrameReader.locate(image, grid, rotation)
            val samplingSquare = reading.samplingSquare(grid)
            meteringGrid = MeteringGrid(image.width, image.height, samplingSquare)
            val lattice = reading.lattice
            ScanDiagnostics.onFrame(reading.stats, SystemClock.elapsedRealtime(), lattice.scale, lattice.dx, lattice.dy, lattice.score)
            deliver(ScanFrame(reading.stats, rotation) { FrameReader.centerRing(image, samplingSquare) })
        }

        /**
         * AF/AE/AWB on the accepted Guided face lattice, falling back to the drawn grid when the frame is rejected.
         * Corner mode uses the drawn grid for centre metering. The point's size is a share of the frame's longer side, so the metered
         * region stays inside the grid. The lock cancels after [METERING_LOCK_SECONDS] so continuous AF resumes when the
         * cube moves nearer or farther (PO 2026-11-16).
         */
        private fun meterOnGrid() {
            val grid = meteringGrid ?: return
            val analysis = analysisUseCase ?: return
            val bound = camera ?: return
            val centre = centreMeteringRegion(grid.square, grid.width, grid.height)
            val factory = SurfaceOrientedMeteringPointFactory(grid.width.toFloat(), grid.height.toFloat(), analysis)
            val action =
                try {
                    meteringAction(factory, centre)
                } catch (ignored: IllegalStateException) {
                    // The analysis use case is no longer bound: nothing to meter.
                    return
                }
            bound.cameraControl.startFocusAndMetering(action)
        }

        companion object {
            const val METERING_LOCK_SECONDS = 3L

            /**
             * Whether the torch is physically lit: only on a camera with a flash unit, when wanted and not suspended (the
             * app in the background), and only in a view that may light it: Case capture's corner view, the calibration
             * phase or the continuous Quick Scan (Story 10.32).
             */
            internal fun torchLit(
                hasFlash: Boolean,
                wanted: Boolean,
                suspended: Boolean,
                corner: Boolean,
                calibrating: Boolean,
                continuous: Boolean,
            ): Boolean = hasFlash && wanted && !suspended && (corner || calibrating || continuous)

            /** The torch is re-applied at most this often when it drifted from what is wanted. */
            const val TORCH_RETRY_MS = 1000L

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

            /** The one metering action Quick Scan starts on [region]: [meteringFlags], cancelled after [METERING_LOCK_SECONDS]. */
            internal fun meteringAction(
                factory: androidx.camera.core.MeteringPointFactory,
                region: MeteringRegion,
            ): FocusMeteringAction =
                FocusMeteringAction
                    .Builder(
                        factory.createPoint(region.x.toFloat(), region.y.toFloat(), region.size.toFloat().coerceIn(0f, 1f)),
                        meteringFlags(),
                    ).setAutoCancelDuration(METERING_LOCK_SECONDS, TimeUnit.SECONDS)
                    .build()

            /** Every metering request meters AF, AE and AWB. */
            internal fun meteringFlags(): Int = FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
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
