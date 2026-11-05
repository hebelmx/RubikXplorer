package dev.rubikxplorer.app.render
import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import dev.rubikxplorer.domain.cube.Facelets
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class Net2DRendererTest {
    @Test fun speedScalesTurnTiming() {
        val renderer = Net2DRenderer()
        var speed by androidx.compose.runtime.mutableStateOf(dev.rubikxplorer.application.preference.AnimationSpeed.FAST)
        rule.mainClock.autoAdvance = false
        rule.setContent { renderer.Content(RenderStyle(animationSpeed = speed), Modifier.size(360.dp, 270.dp)) }
        rule.mainClock.advanceTimeByFrame()
        val start = dev.rubikxplorer.domain.cube.CubeState.SOLVED
        val move =
            dev.rubikxplorer.domain.cube
                .FaceTurn(dev.rubikxplorer.domain.cube.Face.R, dev.rubikxplorer.domain.cube.Turn.CLOCKWISE)
        val target = start.apply(move).toFacelets()
        rule.runOnIdle { renderer.submit(listOf(PresentationItem.Animate(move, 1, target))) }
        rule.mainClock.advanceTimeBy(48)
        assertEquals(start.toFacelets(), renderer.renderedFacelets())
        rule.mainClock.advanceTimeBy(48)
        assertEquals(target, renderer.renderedFacelets())
        rule.mainClock.advanceTimeBy(80)
        rule.runOnIdle { speed = dev.rubikxplorer.application.preference.AnimationSpeed.SLOW }
        androidx.compose.runtime.snapshots.Snapshot
            .sendApplyNotifications()
        rule.mainClock.advanceTimeBy(32)
        rule.waitForIdle()
        val back = move.inverse()
        rule.runOnIdle { renderer.submit(listOf(PresentationItem.Animate(back, 2, start.toFacelets()))) }
        rule.mainClock.advanceTimeBy(176)
        assertEquals(target, renderer.renderedFacelets())
        rule.mainClock.advanceTimeBy(80)
        assertEquals(start.toFacelets(), renderer.renderedFacelets())
        rule.mainClock.advanceTimeBy(240)
    }

    @get:Rule val rule = createComposeRule()

    private fun DpRect.centerX(): Float = (left.value + right.value) / 2

    private fun DpRect.centerY(): Float = (top.value + bottom.value) / 2

    @Test
    fun `every sticker sits in its Kociemba cross cell`() {
        rule.setContent { Net2DRenderer().Content(RenderStyle(), Modifier.size(360.dp, 270.dp)) }
        val net = rule.onNodeWithTag("net").getUnclippedBoundsInRoot()
        val cell = (net.right.value - net.left.value) / 12
        assertEquals(cell * 9, net.bottom.value - net.top.value, 0.5f)
        // Top-left (column, row) of each URFDLB face: U 3/0, R 6/3, F 3/3, D 3/6, L 0/3, B 9/3.
        val origin = listOf(3 to 0, 6 to 3, 3 to 3, 3 to 6, 0 to 3, 9 to 3)
        (0 until Facelets.SIZE).forEach { index ->
            val (faceColumn, faceRow) = origin[index / 9]
            val column = faceColumn + index % 9 % 3
            val row = faceRow + index % 9 / 3
            val bounds = rule.onNodeWithTag("sticker:$index").getUnclippedBoundsInRoot()
            assertEquals("sticker $index column", column, ((bounds.centerX() - net.left.value) / cell).toInt())
            assertEquals("sticker $index row", row, ((bounds.centerY() - net.top.value) / cell).toInt())
        }
    }
}
