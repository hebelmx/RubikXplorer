package dev.rubikxplorer.app.preferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import dev.rubikxplorer.app.render.RenderStyle
import dev.rubikxplorer.app.ui.theme.RubikXplorerTheme
import dev.rubikxplorer.app.ui.theme.isDarkTheme

val LocalRenderStyle = compositionLocalOf { RenderStyle() }
val LocalHaptics = compositionLocalOf { true }
val LocalReduceMotionOverride = compositionLocalOf { false }
val LocalPreferenceStore = compositionLocalOf { PreferenceStore() }

/** The app theme as [store]'s theme preference chooses it: system, light or dark (UX-DR30). */
@Composable
fun PreferredTheme(
    store: PreferenceStore,
    content: @Composable () -> Unit,
) {
    val prefs by store.preferences.collectAsState()
    RubikXplorerTheme(darkTheme = isDarkTheme(prefs.theme, isSystemInDarkTheme()), content = content)
}

@Composable
fun PreferenceProvider(
    store: PreferenceStore,
    content: @Composable () -> Unit,
) {
    val prefs by store.preferences.collectAsState()
    CompositionLocalProvider(
        LocalPreferenceStore provides store,
        LocalRenderStyle provides RenderStyle(prefs.colorBlindGlyphs, prefs.animationSpeed),
        LocalHaptics provides prefs.haptics,
        LocalReduceMotionOverride provides prefs.reduceMotion,
        content = content,
    )
}
