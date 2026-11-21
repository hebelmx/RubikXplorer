package dev.cubentia.app.camera

import android.content.Context
import android.os.SystemClock
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
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ExperimentalCameraProviderConfiguration
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.ui.graphics.asImageBitmap
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

        private var analysisUseCase: ImageAnalysis? = null

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
            startJob?.cancel()
            analysisUseCase?.clearAnalyzer()
            camera?.cameraInfo?.cameraState?.removeObserver(cameraStateObserver)
            cameraProvider?.unbindAll()
            camera = null
            cameraStarted = false
        }

        override fun meter() = meterOnGrid(includeAwb = true)

        override fun meter(includeAwb: Boolean) = meterOnGrid(includeAwb = includeAwb)

        override fun snapshot() = previewView?.bitmap?.asImageBitmap()

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
            return ImageAnalysis
                .Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(selector)
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
        private fun meterOnGrid(includeAwb: Boolean = true) {
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
                    .Builder(point, meteringFlags(includeAwb))
                    .setAutoCancelDuration(METERING_LOCK_SECONDS, TimeUnit.SECONDS)
                    .build()
            camera?.cameraControl?.startFocusAndMetering(action)
        }

        companion object {
            const val METERING_LOCK_SECONDS = 3L

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

private class MeteringGrid(
    val width: Int,
    val height: Int,
    val square: GridSquare,
)
