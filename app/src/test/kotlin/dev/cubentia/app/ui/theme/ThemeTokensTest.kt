package dev.cubentia.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.pow

/** AC 1: the theme carries the DESIGN.md tokens, contrast ratios, Plex families and shape radii. */
class ThemeTokensTest {
    private fun hex(color: Color) = "#%06X".format(color.toArgb() and 0xFFFFFF)

    private fun RubikTokens.hexes() =
        listOf(
            surfaceBase,
            surfaceRaised,
            surfaceStage,
            inkPrimary,
            inkSecondary,
            inkDisabled,
            hairline,
            accent,
            onAccent,
            statusVerified,
            statusLikely,
            statusUnknown,
            statusInvalid,
            cubieBody,
            stickerHighlight,
        ).map(::hex)

    @Test
    fun `light tokens equal the DESIGN md hexes`() {
        assertEquals(
            listOf(
                "#F4F5F7",
                "#FFFFFF",
                "#E8EBEF",
                "#15181C",
                "#5A6370",
                "#A3AAB3",
                "#D5DAE0",
                "#5B3FD1",
                "#FFFFFF",
                "#1E7A4C",
                "#9A5B00",
                "#5A6370",
                "#B3261E",
                "#121417",
                "#A797FF",
            ),
            LightTokens.hexes(),
        )
    }

    @Test
    fun `dark tokens equal the DESIGN md hexes`() {
        assertEquals(
            listOf(
                "#111317",
                "#1A1D22",
                "#0B0C0F",
                "#E7EAEE",
                "#9AA3AE",
                "#4D545E",
                "#2A2F36",
                "#A797FF",
                "#160F33",
                "#5FD39A",
                "#F2B45A",
                "#9AA3AE",
                "#FF8A80",
                "#121417",
                "#A797FF",
            ),
            DarkTokens.hexes(),
        )
    }

    @Test
    fun `color scheme maps the chrome tokens`() {
        listOf(false to LightTokens, true to DarkTokens).forEach { (dark, t) ->
            val scheme = colorSchemeOf(t, dark)
            assertEquals(t.surfaceBase, scheme.background)
            assertEquals(t.surfaceRaised, scheme.surface)
            assertEquals(t.surfaceStage, scheme.surfaceContainerHighest)
            assertEquals(t.inkPrimary, scheme.onSurface)
            assertEquals(t.inkSecondary, scheme.onSurfaceVariant)
            assertEquals(t.hairline, scheme.outline)
            assertEquals(t.accent, scheme.primary)
            assertEquals(t.onAccent, scheme.onPrimary)
            assertEquals(t.statusInvalid, scheme.error)
        }
    }

    @Test
    fun `text, accent, status and highlight meet their contrast bars in both themes`() {
        listOf("light" to LightTokens, "dark" to DarkTokens).forEach { (name, t) ->
            t.surfaces.forEach { surface ->
                assertAtLeast(4.5, t.inkPrimary, surface, "$name ink-primary on ${hex(surface)}")
                assertAtLeast(4.5, t.inkSecondary, surface, "$name ink-secondary on ${hex(surface)}")
                t.statuses.forEach { assertAtLeast(3.0, it, surface, "$name status ${hex(it)} on ${hex(surface)}") }
            }
            listOf(t.surfaceBase, t.surfaceRaised).forEach { surface ->
                t.statuses.forEach { status ->
                    assertAtLeast(4.5, status, surface, "$name status text ${hex(status)} on ${hex(surface)}")
                }
            }
            assertAtLeast(4.5, t.onAccent, t.accent, "$name on-accent on accent")
            assertAtLeast(7.0, t.stickerHighlight, t.cubieBody, "$name sticker-highlight on cubie-body")
        }
    }

    @Test
    fun `typography uses Plex Sans, with notation and data in Plex Mono`() {
        val t = RubikTypography
        listOf(t.headlineMedium, t.titleLarge, t.bodyLarge, t.labelLarge, t.bodySmall).forEach {
            assertEquals(PlexSans, it.fontFamily)
        }
        assertEquals(FontWeight.SemiBold, t.headlineMedium.fontWeight)
        assertEquals(FontWeight.SemiBold, t.titleLarge.fontWeight)
        assertEquals(FontWeight.Normal, t.bodyLarge.fontWeight)
        assertEquals(FontWeight.Medium, t.labelLarge.fontWeight)
        assertEquals(FontWeight.Normal, t.bodySmall.fontWeight)

        val notation = RubikExtraTextStyles.notation
        assertEquals(PlexMono, notation.fontFamily)
        assertEquals(FontWeight.Medium, notation.fontWeight)
        assertTrue(notation.fontSize > t.labelLarge.fontSize, "notation sits a step above the key label text")

        val data = RubikExtraTextStyles.data
        assertEquals(PlexMono, data.fontFamily)
        assertEquals(FontWeight.Normal, data.fontWeight)
        assertEquals("tnum", data.fontFeatureSettings)
    }

    @Test
    fun `shape radii are 2, 4, 8 and 12dp and spacing follows the scale`() {
        val density = Density(1f)
        val size = Size(100f, 100f)
        val radii =
            listOf(RubikShapes.extraSmall, RubikShapes.small, RubikShapes.medium, RubikShapes.large)
                .map { (it as RoundedCornerShape).topStart.toPx(size, density) }
        assertEquals(listOf(2f, 4f, 8f, 12f), radii)
        assertEquals(
            listOf(4f, 8f, 12f, 16f, 24f, 32f, 48f, 48f),
            with(Spacing) { listOf(s1, s2, s3, s4, s5, s6, s7, touchMin) }.map { it.value },
        )
    }

    private fun luminance(color: Color): Double {
        val argb = color.toArgb()

        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun assertAtLeast(
        min: Double,
        a: Color,
        b: Color,
        what: String,
    ) {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        val ratio = (hi + 0.05) / (lo + 0.05)
        assertTrue(ratio >= min, "$what: %.2f < $min".format(ratio))
    }
}
