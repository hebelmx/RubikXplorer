package dev.rubikxplorer.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/** Maps DESIGN.md chrome and status tokens onto the M3 [ColorScheme] slots. */
fun colorSchemeOf(
    tokens: RubikTokens,
    dark: Boolean,
): ColorScheme {
    val scheme = if (dark) darkColorScheme() else lightColorScheme()
    return scheme.copy(
        background = tokens.surfaceBase,
        onBackground = tokens.inkPrimary,
        surface = tokens.surfaceRaised,
        onSurface = tokens.inkPrimary,
        surfaceContainerHighest = tokens.surfaceStage,
        onSurfaceVariant = tokens.inkSecondary,
        outline = tokens.hairline,
        outlineVariant = tokens.hairline,
        primary = tokens.accent,
        onPrimary = tokens.onAccent,
        error = tokens.statusInvalid,
    )
}

/** The RubikXplorer Material3 theme; light and dark follow the system. */
@Composable
fun RubikXplorerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val tokens = if (darkTheme) DarkTokens else LightTokens
    CompositionLocalProvider(
        LocalRubikTokens provides tokens,
        LocalRubikTextStyles provides RubikExtraTextStyles,
    ) {
        MaterialTheme(
            colorScheme = colorSchemeOf(tokens, darkTheme),
            typography = RubikTypography,
            shapes = RubikShapes,
            content = content,
        )
    }
}

/** Accessors for the tokens M3 has no slot for. */
object RubikTheme {
    val tokens: RubikTokens
        @Composable @ReadOnlyComposable
        get() = LocalRubikTokens.current

    val textStyles: RubikTextStyles
        @Composable @ReadOnlyComposable
        get() = LocalRubikTextStyles.current
}
