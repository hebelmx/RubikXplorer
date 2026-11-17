package dev.cubentia.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.cubentia.app.content.LessonCatalog
import dev.cubentia.app.preferences.PreferenceStore
import dev.cubentia.app.preferences.PreferredTheme
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.ui.freecube.FreeCubeViewModel
import dev.cubentia.app.ui.shell.AppShell
import dev.cubentia.app.ui.theme.isDarkTheme
import dev.cubentia.application.port.FeatureFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** The feature-flag port (AD-18); the shell gates Teaching Modes on it. */
    @Inject
    lateinit var featureFlags: FeatureFlags

    /** The one Learning Session owner; injected so Hilt wires it (and its Clock) from the first build. */
    @Inject
    lateinit var sessionHost: SessionHost

    /** The shipped Lesson content, loaded once for the app process. */
    @Inject
    lateinit var lessonCatalog: LessonCatalog

    @Inject lateinit var preferenceStore: PreferenceStore

    @Inject lateinit var scanLauncher: java.util.Optional<dev.cubentia.app.scan.ScanLauncher>

    @Inject lateinit var resetCalibration: dev.cubentia.application.usecase.ResetCalibration

    private val solveFlow: dev.cubentia.app.ui.solve.SolveFlowViewModel by viewModels()

    private val freeCube: FreeCubeViewModel by viewModels()

    /** True from a *Scan my cube* tap until its denial check opens the Editor or the scan's result returns. */
    private var scanInFlight = false

    override fun onStop() {
        super.onStop()
        sessionHost.retryPendingEnd()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val onScan: (() -> Unit)? =
            if (featureFlags.isEnabled(dev.cubentia.application.port.FeatureFlag.CAMERA_SCAN) && scanLauncher.isPresent) {
                val launcher = scanLauncher.get()
                val result =
                    registerForActivityResult(
                        dev.cubentia.app.scan
                            .ScanFlowContract(launcher),
                    ) {
                        scanInFlight = false
                        solveFlow.onScanOutcome(it)
                    }
                val action: () -> Unit = action@{
                    // A second tap while the denial check runs or the scan is open starts nothing.
                    if (scanInFlight) return@action
                    scanInFlight = true
                    lifecycleScope.launch {
                        if (withContext(Dispatchers.IO) { launcher.cameraDenied(this@MainActivity) }) {
                            scanInFlight = false
                            solveFlow.scanDenied()
                        } else {
                            try {
                                result.launch(Unit)
                            } catch (failed: RuntimeException) {
                                scanInFlight = false
                                throw failed
                            }
                        }
                    }
                }
                action
            } else {
                null
            }
        setContent {
            val prefs by preferenceStore.preferences.collectAsState()
            val dark = isDarkTheme(prefs.theme, isSystemInDarkTheme())
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            PreferredTheme(preferenceStore) {
                AppShell(
                    preferenceStore = preferenceStore,
                    host = sessionHost,
                    solveFlow = solveFlow,
                    catalog = lessonCatalog,
                    freeCube = freeCube,
                    showDebugToggles = DebugOptions.SHOW_DEBUG_TOGGLES,
                    flags = featureFlags,
                    onScan = onScan,
                    showWelcome = true,
                    calibration =
                        if (featureFlags.isEnabled(dev.cubentia.application.port.FeatureFlag.CAMERA_SCAN) &&
                            scanLauncher.isPresent
                        ) {
                            dev.cubentia.app.ui.settings.CalibrationSettings(
                                onCalibrate = { startActivity(scanLauncher.get().calibrationIntent(this)) },
                                onReset = { resetCalibration() == dev.cubentia.application.port.CalibrationWrite.Saved },
                            )
                        } else {
                            null
                        },
                )
            }
        }
    }
}
