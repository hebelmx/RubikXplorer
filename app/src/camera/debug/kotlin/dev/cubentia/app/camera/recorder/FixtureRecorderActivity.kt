package dev.cubentia.app.camera.recorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.ClassificationResult
import dev.cubentia.domain.recognition.DeviceInfo
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.LiveFixturePaths
import dev.cubentia.domain.recognition.NearestCenterClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.pow
import androidx.compose.ui.graphics.Color as UiColor

private data class FrameMeasurements(
    val lab: List<Lab>,
    val orientation: Int,
    val quality: CaptureQuality,
    val detail: String?,
)

/** Disposable, debug-only CameraX screen. Only CIELAB measurements and operator metadata are exported; frames stay in memory. */
class FixtureRecorderActivity : ComponentActivity() {
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val latestFrame = AtomicReference<FrameMeasurements?>(null)
    private var camera: Camera? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { FixtureRecorderScreen(::startCamera, latestFrame, ::changeExposure) } } }
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

    /** Steps exposure compensation by [delta] (0 re-meters on the grid); returns a status line. */
    private fun changeExposure(delta: Int): String {
        val current = camera ?: return "Camera not ready."
        if (delta == 0) {
            meterOnGrid()
            return "Re-metered on the grid."
        }
        val state = current.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return "Exposure compensation is not supported on this camera."
        val range = state.exposureCompensationRange
        val next = (state.exposureCompensationIndex + delta).coerceIn(range.lower, range.upper)
        current.cameraControl.setExposureCompensationIndex(next)
        return "Exposure %+.1f EV".format(next * state.exposureCompensationStep.toFloat())
    }

    override fun onDestroy() {
        super.onDestroy()
        analyzerExecutor.shutdown()
    }

    private fun startCamera(view: PreviewView) {
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
                    latestFrame.set(measure(image))
                } finally {
                    image.close()
                }
            }
            view.viewTreeObserver.addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        val viewPort = view.viewPort ?: return
                        view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        val useCases =
                            UseCaseGroup
                                .Builder()
                                .setViewPort(viewPort)
                                .addUseCase(preview)
                                .addUseCase(analysis)
                                .build()
                        provider.unbindAll()
                        camera = provider.bindToLifecycle(this@FixtureRecorderActivity, CameraSelector.DEFAULT_BACK_CAMERA, useCases)
                        meterOnGrid()
                    }
                },
            )
        }, ContextCompat.getMainExecutor(this))
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
        val samples = mutableListOf<Lab>()
        val gridSide = minOf(crop.width(), crop.height()) * 0.8
        val gridLeft = crop.left + (crop.width() - gridSide) / 2.0
        val gridTop = crop.top + (crop.height() - gridSide) / 2.0
        for (row in 0..2) {
            for (column in 0..2) {
                val x = (gridLeft + (column + 0.5) * gridSide / 3).toInt().coerceIn(crop.left, crop.right - 1)
                val y = (gridTop + (row + 0.5) * gridSide / 3).toInt().coerceIn(crop.top, crop.bottom - 1)
                val yy = byte(yPlane, x, y).toDouble()
                val uu = byte(uPlane, x / 2, y / 2).toDouble() - 128.0
                val vv = byte(vPlane, x / 2, y / 2).toDouble() - 128.0
                val red = (yy + 1.402 * vv).toInt().coerceIn(0, 255)
                val green = (yy - 0.344136 * uu - 0.714136 * vv).toInt().coerceIn(0, 255)
                val blue = (yy + 1.772 * uu).toInt().coerceIn(0, 255)
                samples += rgbToLab(red, green, blue)
            }
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
        return FrameMeasurements(samples, image.imageInfo.rotationDegrees, quality, detail)
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
}

@Composable
private fun FixtureRecorderScreen(
    startCamera: (PreviewView) -> Unit,
    latestFrame: AtomicReference<FrameMeasurements?>,
    onExposure: (Int) -> String,
) {
    val context = LocalContext.current
    val initialPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var hasPermission by remember { mutableStateOf(initialPermission) }
    var selectedColor by remember { mutableStateOf(Color.WHITE) }
    var selectedFace by remember { mutableStateOf(Face.U) }
    val centers = remember { mutableStateMapOf<Color, Lab>() }
    var cube by remember { mutableStateOf("") }
    var light by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val truthLabels = remember { mutableStateListOf<Color?>(null, null, null, null, null, null, null, null, null) }
    var qualityOverride by remember { mutableStateOf<CaptureQuality?>(null) }
    val capturedFaces = remember { mutableStateListOf<Face>() }
    val calibrated = centers.size == Color.entries.size
    // Faces already saved for this device, cube and light survive a restart: read them back from the export directory.
    LaunchedEffect(cube.trim(), light.trim()) {
        val saved =
            withContext(Dispatchers.IO) {
                runCatching {
                    val root = context.getExternalFilesDir(null) ?: return@runCatching emptyList()
                    val directory = File(root, "Fixtures/live/${LiveFixturePaths.directoryName(Build.MODEL, cube.trim(), light.trim())}")
                    Face.entries.filter { File(directory, LiveFixturePaths.fileName(it)).isFile }
                }.getOrDefault(emptyList())
            }
        capturedFaces.clear()
        capturedFaces.addAll(saved)
        selectedFace = Face.entries.firstOrNull { it !in saved } ?: Face.U
    }
    var resultText by remember {
        mutableStateOf("Step 1: enter cube and light. Step 2: calibrate the six centers. Step 3: capture the six faces.")
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> hasPermission = granted }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Cubentia fixture recorder", style = MaterialTheme.typography.titleLarge)
        // A new cube or light is a new capture context: earlier centers and truth labels never carry over.
        OutlinedTextField(value = cube, onValueChange = {
            // The keyboard re-sends unchanged text when it commits or closes; only a real change starts a new context.
            if (it != cube) {
                cube = it
                if (centers.isNotEmpty()) resultText = "Cube changed: centers cleared, calibrate all six again."
                centers.clear()
                truthLabels.indices.forEach { index -> truthLabels[index] = null }
            }
        }, label = { Text("Cube") })
        OutlinedTextField(value = light, onValueChange = {
            if (it != light) {
                light = it
                if (centers.isNotEmpty()) resultText = "Light changed: centers cleared, calibrate all six again."
                centers.clear()
                truthLabels.indices.forEach { index -> truthLabels[index] = null }
            }
        }, label = { Text("Light description") })
        Box(Modifier.fillMaxWidth().height(280.dp).clipToBounds(), contentAlignment = Alignment.Center) {
            if (hasPermission) {
                AndroidView(
                    factory = { view ->
                        PreviewView(view)
                            .apply {
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            }.also(startCamera)
                    },
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                )
                FixedGrid(Modifier.fillMaxSize())
            } else {
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { resultText = onExposure(-1) }) { Text("Exposure −") }
            OutlinedButton(onClick = { resultText = onExposure(1) }) { Text("Exposure +") }
            OutlinedButton(onClick = { resultText = onExposure(0) }) { Text("Re-meter") }
        }
        Text(resultText, style = MaterialTheme.typography.bodyMedium)
        val missing =
            buildList {
                if (!cube.any { it.isLetterOrDigit() && it.code < 128 }) add("cube name")
                if (!light.any { it.isLetterOrDigit() && it.code < 128 }) add("light")
                if (!calibrated) add("centers ${centers.size}/6")
                val labeled = truthLabels.count { it != null }
                if (labeled < 9) add("truth $labeled/9")
                val expected = FACE_CENTER.getValue(selectedFace)
                val centerLabel = truthLabels[FaceSample.CENTER_INDEX]
                if (centerLabel != null &&
                    centerLabel != expected
                ) {
                    add("sticker 5 is ${label(centerLabel)} but face $selectedFace is ${label(expected)}: pick the matching face")
                }
            }
        Text(
            if (missing.isEmpty()) {
                "Ready to capture face $selectedFace (${label(
                    FACE_CENTER.getValue(selectedFace),
                )} center)."
            } else {
                "Face $selectedFace. To capture, still need: ${missing.joinToString()}"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            if (calibrated) {
                "Step 2 · Centers calibrated (6/6)"
            } else {
                "Step 2 · Calibrate center ${label(
                    selectedColor,
                )} (${centers.size}/6)"
            },
            style = MaterialTheme.typography.titleSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Color.entries.forEach { color ->
                ColorButton(
                    color,
                    color.name.take(1),
                    shade =
                        if (color in centers) {
                            Shade.DONE
                        } else if (color == selectedColor) {
                            Shade.NEXT
                        } else {
                            Shade.TODO
                        },
                    selected = !calibrated && color == selectedColor,
                    enabled = !calibrated,
                ) { selectedColor = color }
            }
        }
        if (calibrated) {
            OutlinedButton(onClick = {
                centers.clear()
                selectedColor = Color.WHITE
                resultText = "Centers cleared: calibrate all six again."
            }) { Text("Recalibrate centers") }
        }
        Button(enabled = !calibrated, onClick = {
            val frame = latestFrame.get()
            if (frame == null) {
                resultText = "Waiting for a camera frame."
            } else if (frame.quality != CaptureQuality.ACCEPTABLE || qualityOverride != null) {
                resultText = qualityOverride?.let { "$it marked; recapture this face." } ?: frame.detail ?: "Recapture required."
            } else {
                centers[selectedColor] = frame.lab[FaceSample.CENTER_INDEX]
                resultText = "Calibrated ${label(selectedColor)} (${centers.size}/6 centers)."
                Color.entries.firstOrNull { it !in centers }?.let { selectedColor = it }
            }
        }) { Text("Set center") }
        Text(
            if (capturedFaces.size == Face.entries.size) {
                "Step 3 · All six faces captured (tap a face to recapture it)"
            } else {
                "Step 3 · Capture face ${selectedFace.name} · ${label(
                    FACE_CENTER.getValue(selectedFace),
                )} (${capturedFaces.size}/6 captured)"
            },
            style = MaterialTheme.typography.titleSmall,
        )
        Face.entries.chunked(3).forEach { faces ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                faces.forEach { face ->
                    val center = FACE_CENTER.getValue(face)
                    ColorButton(
                        center,
                        "${face.name} · ${label(center)}",
                        shade =
                            if (face in capturedFaces) {
                                Shade.DONE
                            } else if (face == selectedFace) {
                                Shade.NEXT
                            } else {
                                Shade.TODO
                            },
                        selected = face == selectedFace,
                        enabled = calibrated,
                        modifier = Modifier.weight(1f),
                    ) { selectedFace = face }
                }
            }
        }
        Button(
            onClick = {
                val frame = latestFrame.get()
                if (frame == null) {
                    resultText = "Waiting for a camera frame."
                } else {
                    val quality = qualityOverride ?: frame.quality
                    val qualityDetail = qualityOverride?.let { "$it marked; recapture this face." } ?: frame.detail
                    val sample =
                        FaceSample(
                            face = selectedFace,
                            orientationDegrees = frame.orientation,
                            stickers = frame.lab,
                            quality = quality,
                            qualityDetail = qualityDetail,
                        )
                    val calibration = CalibrationCenters(centers.toMap())
                    val record =
                        fixtureRecord(
                            device =
                                DeviceInfo(
                                    model = Build.MODEL,
                                    manufacturer = Build.MANUFACTURER,
                                    osRelease = Build.VERSION.RELEASE,
                                    osBuild = Build.DISPLAY,
                                    sdkInt = Build.VERSION.SDK_INT,
                                ),
                            cube = cube,
                            light = light,
                            capturedAt = Instant.now(),
                            calibration = calibration,
                            sample = sample,
                            truth = truthLabels.filterNotNull(),
                        )
                    saving = true
                    scope.launch {
                        try {
                            val path =
                                withContext(Dispatchers.IO) {
                                    val root = checkNotNull(context.getExternalFilesDir(null)) { "External files directory unavailable" }
                                    writeFixture(root, record).absolutePath
                                }
                            val classified = NearestCenterClassifier.classify(sample, calibration, record.labeledSample.truth)
                            val prediction =
                                when (classified) {
                                    is ClassificationResult.Unavailable -> {
                                        "Unavailable: ${classified.reason}"
                                    }

                                    is ClassificationResult.Classified -> {
                                        "Predicted: ${classified.value.colors}\n" +
                                            "Accuracy: ${classified.value.correct}/${classified.value.total} stickers."
                                    }
                                }
                            resultText = "Saved: $path\n$prediction"
                            if (sample.face !in capturedFaces) capturedFaces.add(sample.face)
                            truthLabels.indices.forEach { truthLabels[it] = null }
                            qualityOverride = null
                            Face.entries.firstOrNull { it !in capturedFaces }?.let { selectedFace = it }
                        } catch (failure: Exception) {
                            resultText = "Save failed: ${failure.message}"
                        } finally {
                            saving = false
                        }
                    }
                }
            },
            enabled = !saving && missing.isEmpty(),
        ) { Text("Capture face") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Ground truth: tap each sticker to cycle its color.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { truthLabels.indices.forEach { truthLabels[it] = null } }) { Text("Reset labels") }
        }
        repeat(3) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) { column ->
                    val index = row * 3 + column
                    ColorButton(
                        truthLabels[index],
                        "${index + 1}: ${truthLabels[index]?.let(::label) ?: "?"}",
                        shade = Shade.DONE,
                        selected = false,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val current = truthLabels[index]
                            truthLabels[index] =
                                if (current == null) {
                                    Color.entries.first()
                                } else {
                                    Color.entries[(Color.entries.indexOf(current) + 1) % Color.entries.size]
                                }
                        },
                    )
                }
            }
        }
        Text("Capture quality override (inspect blur/alignment/completeness)", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                null to "Clear",
                CaptureQuality.BLURRED to "Blurred",
                CaptureQuality.MISALIGNED to "Misaligned",
                CaptureQuality.INCOMPLETE to "Incomplete",
            ).forEach { (quality, label) ->
                OutlinedButton(onClick = {
                    qualityOverride = quality
                }, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

/** Recording convention: each face button is named by its center color, so the operator never tracks orientation. */
private val FACE_CENTER =
    mapOf(
        Face.U to Color.WHITE,
        Face.R to Color.ORANGE,
        Face.F to Color.BLUE,
        Face.D to Color.YELLOW,
        Face.L to Color.RED,
        Face.B to Color.GREEN,
    )

private fun swatch(color: Color): UiColor =
    when (color) {
        Color.WHITE -> UiColor(0xFFFFFFFF)
        Color.YELLOW -> UiColor(0xFFFFD500)
        Color.GREEN -> UiColor(0xFF009E60)
        Color.BLUE -> UiColor(0xFF0051BA)
        Color.RED -> UiColor(0xFFC41E3A)
        Color.ORANGE -> UiColor(0xFFFF5800)
    }

private fun label(color: Color) = color.name.lowercase().replaceFirstChar { it.uppercase() }

/** Progress shading: light = still to do, medium = next, full color = done. */
private enum class Shade(
    val strength: Float,
) {
    TODO(0.22f),
    NEXT(0.6f),
    DONE(1f),
}

@Composable
private fun ColorButton(
    color: Color?,
    text: String,
    shade: Shade,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val container = color?.let { lerp(UiColor.White, swatch(it), shade.strength) }
    val content = container?.let { if (it.luminance() > 0.4f) UiColor.Black else UiColor.White }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors =
            if (container == null || content == null) {
                ButtonDefaults.outlinedButtonColors()
            } else {
                // Keep the progress colors visible when the step is locked; only dim the label.
                ButtonDefaults.outlinedButtonColors(
                    containerColor = container,
                    contentColor = content,
                    disabledContainerColor = container,
                    disabledContentColor = content.copy(alpha = 0.6f),
                )
            },
        border = BorderStroke(if (selected) 4.dp else 1.dp, if (selected) UiColor.Black else UiColor.Gray),
    ) { Text(text, maxLines = 1) }
}

@Composable
private fun FixedGrid(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 2.dp.toPx()
        val side = minOf(size.width, size.height) * 0.8f
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        for (index in 1..2) {
            val x = left + side * index / 3
            drawLine(UiColor.White, Offset(x, top), Offset(x, top + side), stroke)
            val y = top + side * index / 3
            drawLine(UiColor.White, Offset(left, y), Offset(left + side, y), stroke)
        }
        drawRect(
            UiColor.White,
            topLeft = Offset(left, top),
            size =
                androidx.compose.ui.geometry
                    .Size(side, side),
            style =
                androidx.compose.ui.graphics.drawscope
                    .Stroke(stroke),
        )
    }
}
