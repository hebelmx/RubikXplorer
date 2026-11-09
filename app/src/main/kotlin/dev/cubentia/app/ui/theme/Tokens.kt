package dev.cubentia.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The DESIGN.md color tokens for one theme. Chrome and status tokens differ between light and dark;
 * cube tokens ([cubieBody], [stickerHighlight]) are identical in both, because the physical cube does
 * not change with the theme.
 */
@Immutable
data class RubikTokens(
    val surfaceBase: Color,
    val surfaceRaised: Color,
    val surfaceStage: Color,
    val inkPrimary: Color,
    val inkSecondary: Color,
    val inkDisabled: Color,
    val hairline: Color,
    val accent: Color,
    val onAccent: Color,
    val statusVerified: Color,
    val statusLikely: Color,
    val statusUnknown: Color,
    val statusInvalid: Color,
    val cubieBody: Color = CubeTokens.cubieBody,
    val stickerHighlight: Color = CubeTokens.stickerHighlight,
) {
    /** Every surface a text or status token can sit on. */
    val surfaces: List<Color> get() = listOf(surfaceBase, surfaceRaised, surfaceStage)

    /** Every status token. */
    val statuses: List<Color> get() = listOf(statusVerified, statusLikely, statusUnknown, statusInvalid)
}

/** Cube tokens shared by both themes. */
object CubeTokens {
    val cubieBody = Color(0xFF121417)
    val stickerHighlight = Color(0xFFA797FF)

    /** `sticker-dimmed-overlay` (#00000099), drawn over a sticker to mute it; the sticker is never recolored. */
    val stickerDimmedOverlay = Color(0x99000000)
}

val LightTokens =
    RubikTokens(
        surfaceBase = Color(0xFFF4F5F7),
        surfaceRaised = Color(0xFFFFFFFF),
        surfaceStage = Color(0xFFE8EBEF),
        inkPrimary = Color(0xFF15181C),
        inkSecondary = Color(0xFF5A6370),
        inkDisabled = Color(0xFFA3AAB3),
        hairline = Color(0xFFD5DAE0),
        accent = Color(0xFF5B3FD1),
        onAccent = Color(0xFFFFFFFF),
        statusVerified = Color(0xFF1E7A4C),
        statusLikely = Color(0xFF9A5B00),
        statusUnknown = Color(0xFF5A6370),
        statusInvalid = Color(0xFFB3261E),
    )

val DarkTokens =
    RubikTokens(
        surfaceBase = Color(0xFF111317),
        surfaceRaised = Color(0xFF1A1D22),
        surfaceStage = Color(0xFF0B0C0F),
        inkPrimary = Color(0xFFE7EAEE),
        inkSecondary = Color(0xFF9AA3AE),
        inkDisabled = Color(0xFF4D545E),
        hairline = Color(0xFF2A2F36),
        accent = Color(0xFFA797FF),
        onAccent = Color(0xFF160F33),
        statusVerified = Color(0xFF5FD39A),
        statusLikely = Color(0xFFF2B45A),
        statusUnknown = Color(0xFF9AA3AE),
        statusInvalid = Color(0xFFFF8A80),
    )

/** The full token set of the current theme, including those M3's `ColorScheme` has no slot for. */
val LocalRubikTokens = staticCompositionLocalOf { LightTokens }
