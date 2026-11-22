package dev.cubentia.app.render

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.cubentia.app.ui.theme.CubeTokens
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Pixels of the drawn Move arrow (#260): cased sticker-highlight shaft and filled arrowhead, and the 60% ghost. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MoveArrowDrawScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val background = Color.White
    private val fill = CubeTokens.stickerHighlight
    private val casing = CubeTokens.cubieBody

    @Test fun straightArrowIsCasedWithAFilledHead() = assertArrow(::straight, ghost = false)

    @Test fun curvedArrowIsCasedWithAFilledHead() = assertArrow(::curved, ghost = false)

    @Test fun ghostArrowIsOneUniformSixtyPercentLayer() = assertArrow(::straight, ghost = true)

    @Test fun curvedGhostArrowIsOneUniformSixtyPercentLayer() = assertArrow(::curved, ghost = true)

    /** Thirty-three points along a horizontal line from (40, 100) to (160, 100), in dp. */
    private fun straight(dp: Float): List<Offset> = (0..32).map { i -> Offset((40f + 120f * i / 32) * dp, 100f * dp) }

    /** Thirty-three points along a quarter circle of radius 60 about (100, 100), ending at its top, in dp. */
    private fun curved(dp: Float): List<Offset> =
        (0..32).map { i ->
            val angle = PI + PI / 2 * i / 32
            Offset((100f + 60f * cos(angle).toFloat()) * dp, (100f + 60f * sin(angle).toFloat()) * dp)
        }

    private fun assertArrow(
        polyline: (Float) -> List<Offset>,
        ghost: Boolean,
    ) {
        var dp = 0f
        var points = emptyList<Offset>()
        rule.setContent {
            dp = with(LocalDensity.current) { 1.dp.toPx() }
            points = polyline(dp)
            Box(Modifier.size(200.dp).background(background).testTag("arrowStage")) {
                Canvas(Modifier.fillMaxSize()) { drawMoveArrow(points, ghost) }
            }
        }
        rule.waitForIdle()
        val pixels = rule.onNodeWithTag("arrowStage").captureToImage().toPixelMap()

        val alpha = if (ghost) Cube3DRenderer.GHOST_ARROW_ALPHA else 1f
        val seenFill = lerp(background, fill, alpha)
        val seenCasing = lerp(background, casing, alpha)

        val tip = points.last()
        val headLength = 18f * dp
        val chord = tip - Cube3DRenderer.pointBack(points, headLength)
        val direction = chord / chord.getDistance()
        val normal = Offset(-direction.y, direction.x)
        val head = Cube3DRenderer.arrowhead(tip, direction, headLength, 9f * dp)
        val baseMid = (head[1] + head[2]) / 2f

        // Inside the head, about 9dp back from the apex on its axis: fill only, not darkened by casing.
        pixels.assertNear("head interior", tip - direction * (9f * dp), seenFill, tolerance = 0.08f)
        // At the shaft/head join on the axis: fill, no dark seam.
        pixels.assertNear("join", baseMid, seenFill, tolerance = 0.08f)
        // On the shaft axis halfway along.
        val middle = points[points.size / 2]
        val along = (points[points.size / 2 + 1] - points[points.size / 2 - 1]).let { it / it.getDistance() }
        val across = Offset(-along.y, along.x)
        pixels.assertNear("shaft axis", middle, seenFill, tolerance = 0.08f)
        // About 1dp beyond the 2dp half-stroke edge on either side: casing.
        pixels.assertNear("shaft casing +", middle + across * (3f * dp), seenCasing, tolerance = 0.25f)
        pixels.assertNear("shaft casing -", middle - across * (3f * dp), seenCasing, tolerance = 0.25f)
        // About 0.75dp outside each side edge of the head, at the edge's middle: casing.
        listOf(head[1], head[2]).forEach { corner ->
            val edgeMid = (tip + corner) / 2f
            val edge = (corner - tip).let { it / it.getDistance() }
            val outward =
                Offset(-edge.y, edge.x).let {
                    if ((edgeMid + it - baseMid).getDistance() >
                        (edgeMid - baseMid).getDistance()
                    ) {
                        it
                    } else {
                        -it
                    }
                }
            pixels.assertNear("head casing", edgeMid + outward * (0.75f * dp), seenCasing, tolerance = 0.25f)
        }
        // Well outside the head and past the apex: untouched background, so nothing pokes past the tip.
        pixels.assertNear("outside head +", tip - direction * (9f * dp) + normal * (4.5f * dp + 12f * dp), background, tolerance = 0.03f)
        pixels.assertNear("outside head -", tip - direction * (9f * dp) - normal * (4.5f * dp + 12f * dp), background, tolerance = 0.03f)
        pixels.assertNear("past apex", tip + direction * (3f * dp), background, tolerance = 0.03f)
    }

    private fun PixelMap.assertNear(
        what: String,
        at: Offset,
        expected: Color,
        tolerance: Float,
    ) {
        val actual = this[at.x.roundToInt(), at.y.roundToInt()]
        val distance = abs(actual.red - expected.red) + abs(actual.green - expected.green) + abs(actual.blue - expected.blue)
        assertTrue("$what at $at: expected $expected, was $actual (distance $distance)", distance < tolerance)
    }
}
