package dev.cubentia.app.spikeScan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.ClassificationResult
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.NearestCenterClassifier
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

/** Disposable, debug-only CameraX screen. Frames stay in memory and are never written or exported. */
class GuidedScanActivity : ComponentActivity() {
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val latestFrame = AtomicReference<FrameMeasurements?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { GuidedScanScreen(::startCamera, latestFrame) } } }
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
                        provider.bindToLifecycle(this@GuidedScanActivity, CameraSelector.DEFAULT_BACK_CAMERA, useCases)
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
private fun GuidedScanScreen(
    startCamera: (PreviewView) -> Unit,
    latestFrame: AtomicReference<FrameMeasurements?>,
) {
    val context = LocalContext.current
    val initialPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var hasPermission by remember { mutableStateOf(initialPermission) }
    var selectedColor by remember { mutableStateOf(Color.WHITE) }
    var selectedFace by remember { mutableStateOf(Face.U) }
    val centers = remember { mutableMapOf<Color, Lab>() }
    val truthLabels = remember { mutableStateListOf<Color?>(null, null, null, null, null, null, null, null, null) }
    var qualityOverride by remember { mutableStateOf<CaptureQuality?>(null) }
    var resultText by remember { mutableStateOf("Allow camera, calibrate all six centers, then capture each face.") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> hasPermission = granted }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Guided 6-face scan prototype", style = MaterialTheme.typography.titleLarge)
        Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
            if (hasPermission) {
                AndroidView(
                    factory = { view -> PreviewView(view).also(startCamera) },
                    modifier = Modifier.fillMaxSize(),
                )
                FixedGrid(Modifier.fillMaxSize())
            } else {
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            }
        }
        Text("Calibrate center: $selectedColor")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Color.entries.forEach { color ->
                OutlinedButton(onClick = { selectedColor = color }) { Text(color.name.take(1)) }
            }
        }
        Button(onClick = {
            val frame = latestFrame.get()
            if (frame == null) {
                resultText = "Waiting for a camera frame."
            } else if (frame.quality != CaptureQuality.ACCEPTABLE || qualityOverride != null) {
                resultText = qualityOverride?.let { "$it marked; recapture this face." } ?: frame.detail ?: "Recapture required."
            } else {
                centers[selectedColor] = frame.lab[FaceSample.CENTER_INDEX]
                resultText = "Calibrated $selectedColor (${centers.size}/6 centers)."
            }
        }) { Text("Set center") }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Face.entries.forEach { face ->
                OutlinedButton(onClick = {
                    selectedFace = face
                    truthLabels.indices.forEach { truthLabels[it] = null }
                }) { Text(face.name) }
            }
        }
        Button(onClick = {
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
                when (val classified = NearestCenterClassifier.predict(sample, calibration)) {
                    is ClassificationResult.Unavailable -> {
                        resultText = classified.reason
                    }

                    is ClassificationResult.Classified -> {
                        val measured =
                            if (truthLabels.all { it != null }) {
                                NearestCenterClassifier.classify(sample, calibration, truthLabels.filterNotNull())
                            } else {
                                null
                            }
                        val accuracy =
                            when (measured) {
                                is ClassificationResult.Classified -> {
                                    val percent = "%.1f".format(measured.value.accuracy * 100)
                                    "Accuracy: ${measured.value.correct}/${measured.value.total} stickers ($percent%)."
                                }

                                is ClassificationResult.Unavailable -> {
                                    "Accuracy unavailable: ${measured.reason}."
                                }

                                null -> {
                                    "Accuracy unavailable; label all nine truth cells first."
                                }
                            }
                        resultText = Json.encodeToString(sample) + "\nPredicted: ${classified.value.colors}\n$accuracy"
                    }
                }
            }
        }) { Text("Capture face") }
        Text("Ground truth: tap each sticker label to cycle through colors.", style = MaterialTheme.typography.bodySmall)
        repeat(3) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) { column ->
                    val index = row * 3 + column
                    OutlinedButton(
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
                    ) { Text("${index + 1}: ${truthLabels[index]?.name?.take(1) ?: "?"}") }
                }
            }
        }
        Text("Capture quality override (inspect blur/alignment/completeness)", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                null to "Clear",
                CaptureQuality.BLURRED to "Blurred",
                CaptureQuality.MISALIGNED to "Misaligned",
                CaptureQuality.INCOMPLETE to "Incomplete",
            ).forEach { (quality, label) ->
                OutlinedButton(onClick = { qualityOverride = quality }) { Text(label) }
            }
        }
        Text(resultText, style = MaterialTheme.typography.bodySmall)
    }
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
