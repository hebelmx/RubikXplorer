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
import dev.cubentia.app.scan.ScanFlowContract
import dev.cubentia.app.scan.ScanLauncher
import dev.cubentia.app.scan.ScanOutcome
import javax.inject.Inject

/**
 * Debug-only launcher for device session B (Story 10.2): starts the Guided Scan through [ScanFlowContract] and shows a
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
                        rememberLauncherForActivityResult(ScanFlowContract(scanLauncher)) { summary = summarize(it) }
                    Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { scan.launch(Unit) }) { Text("Start guided scan") }
                        Text(summary)
                    }
                }
            }
        }
    }
}

/** Operator-facing summary of the typed outcome. */
internal fun summarize(outcome: ScanOutcome): String =
    when (outcome) {
        ScanOutcome.Cancelled -> "Cancelled."
        ScanOutcome.FellBackToEditor -> "Fell back to the editor."
        is ScanOutcome.Accepted -> "Accepted."
    }
