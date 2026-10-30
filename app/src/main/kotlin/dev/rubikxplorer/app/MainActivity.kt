package dev.rubikxplorer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import dev.rubikxplorer.app.ui.freecube.FreeCubeScreen
import dev.rubikxplorer.app.ui.freecube.FreeCubeViewModel
import dev.rubikxplorer.app.ui.theme.RubikXplorerTheme
import dev.rubikxplorer.application.port.FeatureFlags
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Injected so the Hilt graph (port → flags adapter) is exercised from the first build. */
    @Inject
    lateinit var featureFlags: FeatureFlags

    private val freeCube: FreeCubeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RubikXplorerTheme {
                FreeCubeScreen(viewModel = freeCube, showDebugToggles = DebugOptions.SHOW_DEBUG_TOGGLES)
            }
        }
    }
}
