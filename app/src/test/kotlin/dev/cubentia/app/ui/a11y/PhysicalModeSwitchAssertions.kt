package dev.cubentia.app.ui.a11y

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.unit.dp
import dev.cubentia.app.ui.theme.Spacing
import org.junit.Assert.assertTrue

internal fun SemanticsNodeInteraction.assertUncheckedPhysicalModeContrast() {
    val pixels = captureToImage().toPixelMap()
    val density = fetchSemanticsNode().layoutInfo.density
    with(density) {
        // The switch is at the trailing edge of the row's padding. The centerline runs through its outline and thumb.
        val switchRight = pixels.width - Spacing.s2.roundToPx()
        val centerY = pixels.height / 2
        val surface = pixels[switchRight + 4.dp.roundToPx(), centerY]
        val outline = pixels[switchRight - 1.dp.roundToPx(), centerY]
        val track = pixels[switchRight - 12.dp.roundToPx(), centerY]
        val thumb = pixels[switchRight - 36.dp.roundToPx(), centerY]
        for ((part, foreground) in listOf("outline" to outline, "thumb" to thumb)) {
            for ((backdrop, background) in listOf("track" to track, "surface" to surface)) {
                val ratio = contrast(foreground, background)
                assertTrue("Unchecked Physical mode switch $part has ${"%.2f".format(ratio)}:1 contrast against $backdrop", ratio >= 3)
            }
        }
    }
}

private fun contrast(
    first: Color,
    second: Color,
): Float {
    val firstLuminance = first.luminance()
    val secondLuminance = second.luminance()
    return (maxOf(firstLuminance, secondLuminance) + 0.05f) / (minOf(firstLuminance, secondLuminance) + 0.05f)
}
