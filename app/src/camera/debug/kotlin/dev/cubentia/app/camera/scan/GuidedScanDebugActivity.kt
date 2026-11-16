package dev.cubentia.app.camera.scan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import dev.cubentia.app.scan.GuidedScanCapture
import dev.cubentia.app.scan.GuidedScanContract
import dev.cubentia.app.scan.ScanLauncher
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.ScanAssembler
import dev.cubentia.domain.recognition.ScanResult
import dev.cubentia.domain.recognition.SelfCalibration
import dev.cubentia.domain.recognition.StickerConfidence
import javax.inject.Inject

/**
 * Debug-only launcher for device session B (Story 10.2): starts the Guided Scan through [GuidedScanContract] and shows a
 * plain, operator-facing summary of the result. Not Learner-facing; no release build contains it.
 */
@AndroidEntryPoint
class GuidedScanDebugActivity : ComponentActivity() {
    @Inject
    lateinit var scanLauncher: ScanLauncher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    var summary by remember { mutableStateOf("No scan yet.") }
                    val scan =
                        rememberLauncherForActivityResult(GuidedScanContract(scanLauncher)) { summary = summarize(it) }
                    Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { scan.launch(Unit) }) { Text("Start guided scan") }
                        Text(summary)
                    }
                }
            }
        }
    }
}

/** Operator summary: self-calibration, then Full/Partial with the uncertain count and the gaps. */
internal fun summarize(capture: GuidedScanCapture): String =
    when (capture) {
        GuidedScanCapture.Cancelled -> "Cancelled."
        GuidedScanCapture.FellBackToEditor -> "Fell back to the editor."
        is GuidedScanCapture.Captured -> summarize(capture.samples)
    }

internal fun summarize(samples: List<FaceSample>): String =
    when (val calibration = SelfCalibration.from(samples)) {
        is SelfCalibration.NeedsCalibration -> {
            "${samples.size} samples; needs calibration for ${calibration.colors.joinToString { it.name }}."
        }

        is SelfCalibration.Calibrated -> {
            val guarded = if (calibration.guarded.isEmpty()) "" else " (guarded: ${calibration.guarded.joinToString { it.name }})"
            when (val result = ScanAssembler.assemble(samples, calibration.calibration)) {
                is ScanResult.Full -> {
                    "Full$guarded: ${result.confidence.count { it == StickerConfidence.UNCERTAIN }} uncertain of 54."
                }

                is ScanResult.Partial -> {
                    val uncertain = result.confidence.values.sumOf { list -> list.count { it == StickerConfidence.UNCERTAIN } }
                    "Partial$guarded: $uncertain uncertain; gaps ${result.gaps.entries.joinToString { "${it.key}=${it.value}" }}."
                }
            }
        }
    }
