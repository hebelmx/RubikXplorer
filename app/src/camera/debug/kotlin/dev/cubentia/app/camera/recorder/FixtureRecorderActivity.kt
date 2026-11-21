package dev.cubentia.app.camera.recorder

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import dev.cubentia.app.camera.CornerDetector
import dev.cubentia.app.camera.CornerFrame
import dev.cubentia.app.camera.CornerGuide
import dev.cubentia.app.camera.CornerLocator
import dev.cubentia.app.camera.CornerPoint
import dev.cubentia.app.camera.CornerReader
import dev.cubentia.app.camera.FrameReader
import dev.cubentia.app.camera.ViewfinderGrid
import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.Lab
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
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
)

/** What the recorder screen needs from the camera; implemented by [FixtureRecorderActivity]. */
internal interface RecorderCamera {
    /** Bumped on every automatic re-meter, so the screen can say so. */
    val autoRemeters: State<Int>

    /** Corner mode (Story 10.19): the live detected outline, as view fractions; empty when nothing is detected. */
    val detectedQuads: State<List<List<CornerPoint>>>

    fun bind(view: PreviewView)

    fun cornerMode(enabled: Boolean) = Unit

    fun latest(): FrameMeasurements?

    /** Measures the next analysis frame and encodes the same image as a JPEG; null if no frame arrives in time. */
    suspend fun capture(): CapturedFrame?

    /** Re-meters AF/AE/AWB on the grid now and takes the next frame as the settled reference. */
    fun remeter()

    /**
     * Corner mode (Story 10.19): meters like Quick Scan, AF+AE on the detected outline (the grid centre when none or
     * unsupported) and AWB only when [includeAwb] (hold 1).
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

    /** The last detected outline in analysis-frame pixels, with that frame's size, for corner metering. */
    @Volatile private var lastOutline: Triple<List<List<CornerPoint>>, Int, Int>? = null

    @Volatile private var viewSize = 0 to 0

    @Volatile private var cornerMode = false

    private val recorderCamera =
        object : RecorderCamera {
            override val autoRemeters: State<Int> get() = autoRemeterCount

            override val detectedQuads: State<List<List<CornerPoint>>> get() = this@FixtureRecorderActivity.detectedQuads

            override fun bind(view: PreviewView) = startCamera(view)

            override fun cornerMode(enabled: Boolean) {
                cornerMode = enabled
            }

            override fun latest(): FrameMeasurements? = latestFrame.get()

            override suspend fun capture(): CapturedFrame? {
                val request = CompletableDeferred<CapturedFrame>()
                captureRequest.getAndSet(request)?.cancel()
                return withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { request.await() }.also { captureRequest.compareAndSet(request, null) }
            }

            override fun remeter() = this@FixtureRecorderActivity.remeter()

            override fun remeterCorner(includeAwb: Boolean) {
                meterOnCube(includeAwb)
                settleRequested.set(true)
            }

            override fun changeExposure(delta: Int): String = this@FixtureRecorderActivity.changeExposure(delta)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { FixtureRecorderScreen(recorderCamera) } } }
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

    /** Story 10.19: AF+AE (AWB when [includeAwb]) on the last detected outline, as Quick Scan meters; the grid centre otherwise. */
    private fun meterOnCube(includeAwb: Boolean) {
        val bound = camera ?: return
        val analysis = analysisUseCase
        val outline = lastOutline
        val flags =
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or (if (includeAwb) FocusMeteringAction.FLAG_AWB else 0)
        val point =
            outline?.let { (quads, width, height) ->
                val points = quads.flatten()
                if (analysis == null || points.isEmpty() || width <= 0 || height <= 0) return@let null
                val left = points.minOf { it.x }
                val right = points.maxOf { it.x }
                val top = points.minOf { it.y }
                val bottom = points.maxOf { it.y }
                runCatching {
                    SurfaceOrientedMeteringPointFactory(width.toFloat(), height.toFloat(), analysis).createPoint(
                        ((left + right) / 2).toFloat(),
                        ((top + bottom) / 2).toFloat(),
                        (maxOf(right - left, bottom - top) / maxOf(width, height)).toFloat().coerceIn(0f, 1f),
                    )
                }.getOrNull()
            }
        val onOutline = point?.let { FocusMeteringAction.Builder(it, flags).disableAutoCancel().build() }
        val action =
            onOutline?.takeIf { bound.cameraInfo.isFocusMeteringSupported(it) }
                ?: FocusMeteringAction
                    .Builder(SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f, 0.5f), flags)
                    .disableAutoCancel()
                    .build()
        bound.cameraControl.startFocusAndMetering(action)
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
                camera = provider.bindToLifecycle(this@FixtureRecorderActivity, CameraSelector.DEFAULT_BACK_CAMERA, useCases)
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

    /** Analyzer thread: measure, answer a pending capture with the same image, and decide on an automatic re-meter. */
    private fun analyze(image: ImageProxy) {
        val frame = measure(image)
        latestFrame.set(frame)
        val isCorner = cornerMode
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
        publishOutline(corner, image.width, image.height, crop, rotation)
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
                ),
            )
        }
        val now = SystemClock.elapsedRealtime()
        val grid = frame.lab.takeIf { it.size == 9 }
        if (settleRequested.getAndSet(false)) {
            remeterTrigger.settle(grid, now)
        } else if (grid != null && remeterTrigger.onFrame(grid, now)) {
            ContextCompat.getMainExecutor(this).execute {
                meterOnGrid()
                autoRemeterCount.intValue++
            }
        }
    }

    /** Keeps the outline for metering and shows it as view fractions; cleared when nothing is detected or not in corner mode. */
    private fun publishOutline(
        corner: CornerFrame?,
        width: Int,
        height: Int,
        crop: android.graphics.Rect,
        rotation: Int,
    ) {
        val quads = corner?.takeIf { it.detected }?.frameQuads.orEmpty()
        lastOutline = if (quads.isEmpty()) lastOutline.takeIf { cornerMode } else Triple(quads, width, height)
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
    }
}
