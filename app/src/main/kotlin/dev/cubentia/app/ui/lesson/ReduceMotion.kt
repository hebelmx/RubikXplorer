package dev.cubentia.app.ui.lesson

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.cubentia.app.preferences.LocalReduceMotionOverride

/**
 * True when the system asks for reduced motion: animations are switched off
 * (`Settings.Global.ANIMATOR_DURATION_SCALE == 0`) or preferences enable the override.
 * The system value is read once per composition entry.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return LocalReduceMotionOverride.current ||
        remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}
