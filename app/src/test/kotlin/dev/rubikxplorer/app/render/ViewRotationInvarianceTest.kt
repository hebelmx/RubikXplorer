package dev.rubikxplorer.app.render

import dev.rubikxplorer.app.ui.freecube.FreeCubeViewModel
import dev.rubikxplorer.domain.cube.Axis
import dev.rubikxplorer.domain.cube.CubeRotation
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Slice
import dev.rubikxplorer.domain.cube.SliceTurn
import dev.rubikxplorer.domain.cube.Turn
import dev.rubikxplorer.domain.input.MoveIntent
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
