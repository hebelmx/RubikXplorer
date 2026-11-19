package dev.cubentia.app.render

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.domain.input.FaceLabels
import dev.cubentia.domain.input.ViewOrbit
import dev.cubentia.domain.input.ViewPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewRotatedScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val strings =
        ContentStrings(
            mapOf(
                "msg.view.rotated" to "View rotated",
                "btn.view.reset" to "Reset view",
                "msg.view.reset" to "View reset",
            ),
        )

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun compactLightHomeAndRotated() = assertHomeAndRotated(dark = false)

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun compactDarkHomeAndRotated() = assertHomeAndRotated(dark = true)

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun expandedLightHomeAndRotated() = assertHomeAndRotated(dark = false)

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun expandedDarkHomeAndRotated() = assertHomeAndRotated(dark = true)

    private fun assertHomeAndRotated(dark: Boolean) {
        var surfaceStage = Color.Unspecified
        var inkPrimary = Color.Unspecified
        var inkSecondary = Color.Unspecified
        var orbit by mutableStateOf(ViewOrbit.DEFAULT)
        var boxW = 0
        var boxH = 0
        var gapPx = 0.0

        rule.setContent {
            CubentiaTheme(darkTheme = dark) {
                surfaceStage = RubikTheme.tokens.surfaceStage
                inkPrimary = RubikTheme.tokens.inkPrimary
                inkSecondary = RubikTheme.tokens.inkSecondary
                val glyphs = rememberFaceLabelGlyphs()
                boxW = glyphs.boxWidth
                boxH = glyphs.boxHeight
                gapPx = with(LocalDensity.current) { FACE_LABEL_GAP.toPx().toDouble() }
                Box(
                    Modifier
                        .size(320.dp)
                        .background(surfaceStage)
                        .testTag("stageOverlay"),
                ) {
                    FaceLabelOverlay(orbit = orbit, modifier = Modifier.fillMaxSize(), glyphs = glyphs)
                    ViewRotatedBadge(orbit = orbit, strings = strings, onReset = {})
                }
            }
        }
        rule.waitForIdle()

        // Home: no badge node, and the whole stage capture is pure surfaceStage with no badge or letter ink.
        rule.onNodeWithTag("stage:viewRotated").assertDoesNotExist()
        val homePixels = rule.onNodeWithTag("stageOverlay").captureToImage().toPixelMap()
        assertEquals(0, homePixels.nonSurfaceCount(0 until homePixels.width, 0 until homePixels.height, surfaceStage))

        // Rotated: badge ink in the top-left and inkSecondary letter ink at each FaceLabels.place box.
        val rotatedOrbit = ViewOrbit(35.0, 32.0)
        rule.runOnIdle { orbit = rotatedOrbit }
        rule.waitForIdle()
        val badgeNode = rule.onNodeWithTag("stage:viewRotated").fetchSemanticsNode()
        val stageNode = rule.onNodeWithTag("stageOverlay").fetchSemanticsNode()
        val rotatedPixels = rule.onNodeWithTag("stageOverlay").captureToImage().toPixelMap()

        val badgeRight = (badgeNode.boundsInRoot.right - stageNode.boundsInRoot.left).roundToInt().coerceAtMost(rotatedPixels.width)
        val badgeBottom = (badgeNode.boundsInRoot.bottom - stageNode.boundsInRoot.top).roundToInt().coerceAtMost(rotatedPixels.height)
        assertTrue(
            "Expected badge ink in top-left",
            rotatedPixels.colorCount(0 until badgeRight, 0 until badgeBottom, inkPrimary) > 20,
        )

        val pose = ViewPose(rotatedPixels.width, rotatedPixels.height, rotatedOrbit.eye())
        val labels = FaceLabels.place(pose, boxW.toDouble(), boxH.toDouble(), gapPx)
        assertTrue("Expected at least 2 face labels at $rotatedOrbit", labels.size >= 2)
        for (label in labels) {
            val left = label.left.roundToInt().coerceIn(0, rotatedPixels.width - 1)
            val right = (label.left + boxW).roundToInt().coerceIn(left + 1, rotatedPixels.width)
            val top = label.top.roundToInt().coerceIn(0, rotatedPixels.height - 1)
            val bottom = (label.top + boxH).roundToInt().coerceIn(top + 1, rotatedPixels.height)
            val letterInk = rotatedPixels.colorCount(left until right, top until bottom, inkSecondary)
            assertTrue("Expected inkSecondary for ${label.face} in [$left..$right, $top..$bottom]", letterInk > 5)
        }
    }

    private fun PixelMap.nonSurfaceCount(
        xs: IntRange,
        ys: IntRange,
        surface: Color,
    ): Int = ys.sumOf { y -> xs.count { x -> !this[x, y].nearColor(surface) } }

    private fun PixelMap.colorCount(
        xs: IntRange,
        ys: IntRange,
        target: Color,
    ): Int = ys.sumOf { y -> xs.count { x -> this[x, y].nearColor(target) } }

    private fun Color.nearColor(other: Color): Boolean = abs(red - other.red) + abs(green - other.green) + abs(blue - other.blue) < 0.25f
}
