package dev.rubikxplorer.app.ui.lesson

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * True when the system asks for reduced motion: animations are switched off
 * (`Settings.Global.ANIMATOR_DURATION_SCALE == 0`). Read once per composition entry; there is no
 * in-app override yet.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}
