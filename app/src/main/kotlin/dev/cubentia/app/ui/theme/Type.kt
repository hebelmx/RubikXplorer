package dev.cubentia.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import dev.cubentia.app.R

/** IBM Plex Sans, bundled (NFR-5: no network fonts). */
val PlexSans =
    FontFamily(
        Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
        Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
    )

/** IBM Plex Mono, bundled; for anything you could type into a solver. */
val PlexMono =
    FontFamily(
        Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    )

private val base = Typography()

private fun TextStyle.sans(weight: FontWeight) = copy(fontFamily = PlexSans, fontWeight = weight)

/** M3 roles: display = Headline Medium, title = Title Large, body = Body Large, label = Label Large, meta = Body Small. */
val RubikTypography =
    Typography(
        displayLarge = base.displayLarge.sans(FontWeight.SemiBold),
        displayMedium = base.displayMedium.sans(FontWeight.SemiBold),
        displaySmall = base.displaySmall.sans(FontWeight.SemiBold),
        headlineLarge = base.headlineLarge.sans(FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.sans(FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.sans(FontWeight.SemiBold),
        titleLarge = base.titleLarge.sans(FontWeight.SemiBold),
        titleMedium = base.titleMedium.sans(FontWeight.SemiBold),
        titleSmall = base.titleSmall.sans(FontWeight.Medium),
        bodyLarge = base.bodyLarge.sans(FontWeight.Normal),
        bodyMedium = base.bodyMedium.sans(FontWeight.Normal),
        bodySmall = base.bodySmall.sans(FontWeight.Normal),
        labelLarge = base.labelLarge.sans(FontWeight.Medium).copy(letterSpacing = 0.02.em),
        labelMedium = base.labelMedium.sans(FontWeight.Medium).copy(letterSpacing = 0.02.em),
        labelSmall = base.labelSmall.sans(FontWeight.Medium).copy(letterSpacing = 0.02.em),
    )

/** The two Plex Mono roles M3 has no slot for. */
@Immutable
data class RubikTextStyles(
    /** Moves and Algorithms: Mono 500, one M3 step above the surrounding text (here Title Medium over Label Large). */
    val notation: TextStyle,
    /** Counters and timers: Mono 400 with tabular figures. */
    val data: TextStyle,
)

val RubikExtraTextStyles =
    RubikTextStyles(
        notation = base.titleMedium.copy(fontFamily = PlexMono, fontWeight = FontWeight.Medium),
        data = base.bodyMedium.copy(fontFamily = PlexMono, fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum"),
    )

val LocalRubikTextStyles = staticCompositionLocalOf { RubikExtraTextStyles }
