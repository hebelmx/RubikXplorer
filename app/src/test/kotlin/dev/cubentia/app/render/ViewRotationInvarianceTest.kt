package dev.cubentia.app.render

import dev.cubentia.app.ui.freecube.FreeCubeViewModel
import dev.cubentia.domain.cube.Axis
import dev.cubentia.domain.cube.CubeRotation
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Slice
import dev.cubentia.domain.cube.SliceTurn
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.input.MoveIntent
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class ViewRotationInvarianceTest {
    @Test
    fun `camera sequences never mutate history or rendered geometry`() {
        val random = Random(503)
        repeat(200) {
            val vm = FreeCubeViewModel()
            repeat(random.nextInt(1, 40)) {
                vm.onMoveIntent(
                    MoveIntent(
                        when (random.nextInt(3)) {
                            0 -> FaceTurn(Face.entries.random(random), Turn.entries.random(random))
                            1 -> CubeRotation(Axis.entries.random(random), Turn.entries.random(random))
                            else -> SliceTurn(Slice.entries.random(random), Turn.entries.random(random))
                        },
                    ),
                )
            }
            val history = vm.history
            val drawn = vm.renderer.renderedFacelets()
            repeat(100) {
                when (random.nextInt(3)) {
                    0 -> vm.renderer.rotateView(random.nextDouble(-720.0, 720.0), random.nextDouble(-180.0, 180.0))
                    1 -> vm.renderer.dragView(random.nextDouble(-1000.0, 1000.0), random.nextDouble(-1000.0, 1000.0), 400.0)
                    else -> vm.renderer.resetView()
                }
                assertEquals(history, vm.history)
                assertEquals(drawn, vm.renderer.renderedFacelets())
            }
        }
    }
}
