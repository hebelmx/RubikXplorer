package dev.cubentia.app.camera

import android.content.Context
import android.os.SystemClock
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

    fun snapshot(): androidx.compose.ui.graphics.ImageBitmap? = null
}

internal class CameraXFrameSource
    @Inject
    constructor() : ScanFrameSource {
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
                val closed = state.type == CameraState.Type.CLOSED || state.type == CameraState.Type.PENDING_OPEN
                if (state.error != null && closed && cameraPhase()) unavailable()
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

        override fun meter() = meterOnGrid()

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
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysisUseCase = analysis
            analysis.setAnalyzer(analyzerExecutor) { image ->
                try {
                    read(image)
                } finally {
                    image.close()
                }
            }

            fun bind(): Boolean {
                if (!cameraPhase() || previewView !== view) return true
                val viewPort = view.viewPort ?: return false
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
                            // Bound but failed to open (in use, disabled, fatal error): the camera-unavailable card,
                            // never remembered.
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
                val reading = CornerReader.read(with(FrameReader) { image.asYuvFrame() }, CornerGuide(grid, rotation))
                deliver(ScanFrame(EMPTY_STATS, rotation, corner = reading))
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
            const val METERING_LOCK_SECONDS = 3L
        }
    }

private class MeteringGrid(
    val width: Int,
    val height: Int,
    val square: GridSquare,
)
