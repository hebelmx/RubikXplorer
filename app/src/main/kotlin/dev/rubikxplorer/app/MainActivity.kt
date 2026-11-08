package dev.rubikxplorer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import dev.rubikxplorer.app.content.LessonCatalog
import dev.rubikxplorer.app.preferences.PreferenceStore
import dev.rubikxplorer.app.preferences.PreferredTheme
import dev.rubikxplorer.app.session.SessionHost
import dev.rubikxplorer.app.ui.freecube.FreeCubeViewModel
import dev.rubikxplorer.app.ui.shell.AppShell
import dev.rubikxplorer.application.port.FeatureFlags
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

    private val freeCube: FreeCubeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val progressEvents by sessionHost.progressEvents.collectAsState()
            val attemptRecords by sessionHost.attemptRecords.collectAsState()
            PreferredTheme(preferenceStore) {
                AppShell(
                    preferenceStore = preferenceStore,
                    host = sessionHost,
                    catalog = lessonCatalog,
                    freeCube = freeCube,
                    showDebugToggles = DebugOptions.SHOW_DEBUG_TOGGLES,
                    flags = featureFlags,
                    showWelcome = progressEvents.isEmpty() && attemptRecords.isEmpty(),
                )
            }
        }
    }
}
