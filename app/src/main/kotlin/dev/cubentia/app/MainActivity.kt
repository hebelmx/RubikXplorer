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
import dagger.hilt.android.AndroidEntryPoint
import dev.cubentia.app.content.LessonCatalog
import dev.cubentia.app.preferences.PreferenceStore
import dev.cubentia.app.preferences.PreferredTheme
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.ui.freecube.FreeCubeViewModel
import dev.cubentia.app.ui.shell.AppShell
import dev.cubentia.app.ui.theme.isDarkTheme
import dev.cubentia.application.port.FeatureFlags
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

    private val solveFlow: dev.cubentia.app.ui.solve.SolveFlowViewModel by viewModels()

    private val freeCube: FreeCubeViewModel by viewModels()

    override fun onStop() {
        super.onStop()
        sessionHost.retryPendingEnd()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    showWelcome = true,
                )
            }
        }
    }
}
