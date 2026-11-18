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
import dev.cubentia.app.scan.CaseCaptureContract
import dev.cubentia.app.scan.CaseCaptureInput
import dev.cubentia.app.scan.QuickCheckCapture
import dev.cubentia.app.scan.QuickCheckOutcome
import dev.cubentia.app.scan.ScanLauncher
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.ui.freecube.FreeCubeViewModel
import dev.cubentia.app.ui.recognize.RecognizeFlowViewModel
import dev.cubentia.app.ui.shell.AppShell
import dev.cubentia.app.ui.theme.isDarkTheme
import dev.cubentia.application.port.CalibrationLoad
import dev.cubentia.application.port.CalibrationRepository
import dev.cubentia.application.port.CalibrationWrite
import dev.cubentia.application.port.FeatureFlag
import dev.cubentia.application.port.FeatureFlags
import dev.cubentia.application.session.Effect
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.application.session.SyncState
import dev.cubentia.domain.recognition.PhysicalObservation
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

    @Inject lateinit var scanLauncher: java.util.Optional<ScanLauncher>

    @Inject lateinit var resetCalibration: dev.cubentia.application.usecase.ResetCalibration

    private val solveFlow: dev.cubentia.app.ui.solve.SolveFlowViewModel by viewModels()

    private val placementFlow: dev.cubentia.app.ui.placement.PlacementFlowViewModel by viewModels()

    private val recognizeFlow: RecognizeFlowViewModel by viewModels()

    private val freeCube: FreeCubeViewModel by viewModels()

    /** True from a *Scan my cube* tap until its denial check opens the Editor or the scan's result returns. */
    private var scanInFlight = false
    private var quickInFlight: Effect.QuickCheck? = null

    @Inject lateinit var calibrationRepository: CalibrationRepository

    override fun onSaveInstanceState(outState: Bundle) {
        quickInFlight?.let { effect ->
            outState.putLong("quickCheck:id", effect.effectId)
            outState.putLong("quickCheck:revision", effect.revision)
            outState.putStringArray("quickCheck:faces", effect.faces.map { it.name }.toTypedArray())
        }
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        super.onStop()
        sessionHost.retryPendingEnd()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.containsKey("quickCheck:id") == true) {
            quickInFlight =
                Effect.QuickCheck(
                    savedInstanceState.getLong("quickCheck:id"),
                    savedInstanceState.getLong("quickCheck:revision"),
                    savedInstanceState
                        .getStringArray("quickCheck:faces")
                        .orEmpty()
                        .map(dev.cubentia.domain.cube.Face::valueOf)
                        .toSet(),
                    true,
                )
        }

        fun launchScan(
            launcher: ScanLauncher,
            onDenied: () -> Unit,
            start: () -> Unit,
        ) {
            // A second tap while the denial check runs or the scan is open starts nothing.
            if (scanInFlight) return
            scanInFlight = true
            lifecycleScope.launch {
                if (withContext(Dispatchers.IO) { launcher.cameraDenied(this@MainActivity) }) {
                    scanInFlight = false
                    onDenied()
                } else {
                    try {
                        start()
                    } catch (failed: RuntimeException) {
                        scanInFlight = false
                        throw failed
                    }
                }
            }
        }

        fun guidedScanAction(
            onDenied: () -> Unit,
            quick: Boolean = false,
            onOutcome: (dev.cubentia.app.scan.ScanOutcome) -> Unit,
        ): (() -> Unit)? =
            if (featureFlags.isEnabled(if (quick) FeatureFlag.QUICK_SCAN else FeatureFlag.CAMERA_SCAN) && scanLauncher.isPresent) {
                val launcher = scanLauncher.get()
                val result =
                    registerForActivityResult(
                        dev.cubentia.app.scan
                            .ScanFlowContract(launcher, quick),
                    ) {
                        scanInFlight = false
                        onOutcome(it)
                    }
                val action: () -> Unit = { launchScan(launcher, onDenied) { result.launch(Unit) } }
                action
            } else {
                null
            }
        val onScan = guidedScanAction({ solveFlow.scanDenied() }) { solveFlow.onScanOutcome(it) }
        val onQuickScan = guidedScanAction({ solveFlow.scanDenied() }, quick = true) { solveFlow.onScanOutcome(it) }
        val onPlacementScan =
            if (featureFlags.isEnabled(FeatureFlag.PLACEMENT)) {
                guidedScanAction({ placementFlow.scanDenied() }) { placementFlow.onScanOutcome(it) }
                    ?.let { launch ->
                        {
                            placementFlow.scanningAgain()
                            launch()
                        }
                    }
            } else {
                null
            }
        val onRecognize: ((Int) -> Unit)? =
            if (featureFlags.isEnabled(FeatureFlag.CASE_RECOGNITION) && scanLauncher.isPresent) {
                val launcher = scanLauncher.get()
                val result =
                    registerForActivityResult(CaseCaptureContract(launcher)) {
                        scanInFlight = false
                        recognizeFlow.onCapture(it)
                    }
                val action: (Int) -> Unit = { corners ->
                    launchScan(launcher, recognizeFlow::scanDenied) { result.launch(CaseCaptureInput(corners)) }
                }
                action
            } else {
                null
            }
        val quickCheck: QuickCheckCapture? =
            if (featureFlags.isEnabled(FeatureFlag.CAMERA_SCAN) && scanLauncher.isPresent) {
                val result =
                    registerForActivityResult(
                        dev.cubentia.app.scan
                            .QuickCheckContract(scanLauncher.get()),
                    ) { outcome ->
                        val issued = quickInFlight
                        quickInFlight = null
                        lifecycleScope.launch {
                            val current = sessionHost.state.value
                            if (issued == null || issued.effectId == -1L) {
                                ScanDiagnostics.discardQuickCheckCapture()
                            }
                            if (issued?.effectId == -1L) {
                                val observation = (outcome as? QuickCheckOutcome.Observed)?.observation
                                val full = observation as? PhysicalObservation.Full
                                if (full != null && issued.revision == current.revision &&
                                    current.syncState == SyncState.PHYSICAL_AWAITING_CONFIRM
                                ) {
                                    sessionHost.dispatch {
                                        SessionEvent
                                            .EditorEntered(full.state, it)
                                    }
                                }
                            } else {
                                val pending = current.pendingQuickCheck
                                if (issued != null) {
                                    // Never attribute a returning capture to a replacement request.
                                    val effect = pending?.takeIf { it.effectId == issued.effectId } ?: issued
                                    val applied =
                                        sessionHost.dispatch { at ->
                                            when (outcome) {
                                                is QuickCheckOutcome.Observed -> {
                                                    SessionEvent.QuickCheckObserved(
                                                        effect.effectId,
                                                        effect.revision,
                                                        outcome.observation,
                                                        at,
                                                    )
                                                }

                                                QuickCheckOutcome.Cancelled -> {
                                                    SessionEvent
                                                        .QuickCheckCancelled(effect.effectId, at)
                                                }
                                            }
                                        }
                                    // debug only (no-op in release): observed vs expected and the Certainty (PO 2026-11-17).
                                    if (outcome == QuickCheckOutcome.Cancelled) {
                                        ScanDiagnostics.onQuickCheckRun(current.expected, null, null, null)
                                    }
                                    (outcome as? QuickCheckOutcome.Observed)?.let {
                                        val check = applied.state.quickCheck
                                        ScanDiagnostics.onQuickCheck(current.expected, it.observation, check?.certainty)
                                        if (check != null && check !== current.quickCheck &&
                                            pending?.effectId == effect.effectId && effect.revision == current.revision
                                        ) {
                                            ScanDiagnostics.onQuickCheckRun(
                                                current.expected,
                                                it.observation,
                                                check.certainty,
                                                check.matchesExpected,
                                            )
                                        } else {
                                            ScanDiagnostics.discardQuickCheckCapture()
                                        }
                                    }
                                }
                            }
                        }
                    }
                QuickCheckCapture { effect ->
                    if (quickInFlight == null) {
                        quickInFlight = effect
                        lifecycleScope.launch {
                            val saved =
                                withContext(
                                    Dispatchers.IO,
                                ) { (calibrationRepository.load() as? CalibrationLoad.Loaded)?.calibration }
                            try {
                                result.launch(
                                    dev.cubentia.app.scan
                                        .QuickCheckInput(effect.faces, effect.faces.size == 6, saved != null),
                                )
                            } catch (failed: RuntimeException) {
                                quickInFlight = null
                                throw failed
                            }
                        }
                    }
                }
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
                    placementFlow = placementFlow,
                    recognizeFlow = recognizeFlow,
                    onRecognize = onRecognize,
                    onPlacementScan = onPlacementScan,
                    catalog = lessonCatalog,
                    freeCube = freeCube,
                    showDebugToggles = DebugOptions.SHOW_DEBUG_TOGGLES,
                    flags = featureFlags,
                    onScan = onScan,
                    onQuickScan = onQuickScan,
                    quickCheck = quickCheck,
                    showWelcome = true,
                    calibration =
                        if (featureFlags.isEnabled(FeatureFlag.CAMERA_SCAN) &&
                            scanLauncher.isPresent
                        ) {
                            dev.cubentia.app.ui.settings.CalibrationSettings(
                                onCalibrate = { startActivity(scanLauncher.get().calibrationIntent(this)) },
                                onReset = { resetCalibration() == CalibrationWrite.Saved },
                            )
                        } else {
                            null
                        },
                )
            }
        }
    }
}
