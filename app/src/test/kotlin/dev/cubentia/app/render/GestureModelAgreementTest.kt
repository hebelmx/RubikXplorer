package dev.cubentia.app.render

import dev.cubentia.app.render.gl.CubieModel
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Slice
import dev.cubentia.domain.cube.SliceTurn
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.input.GestureInterpreter
import dev.cubentia.domain.input.MoveIntent
import dev.cubentia.domain.input.TouchPath
import dev.cubentia.domain.input.Vector3
import dev.cubentia.domain.input.ViewPose
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureModelAgreementTest {
    private val axes = listOf(Vector3(1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0), Vector3(0.0, 0.0, 1.0))
    private val eyes = listOf(Vector3(4.2, 4.6, 6.4), Vector3(-4.2, -4.6, 6.4), Vector3(-4.2, 4.6, -6.4), Vector3(4.2, -4.6, -6.4))

    private fun path(
        pose: ViewPose,
        start: Vector3,
        delta: Vector3,
    ) = TouchPath(listOf(pose.project(start), pose.project(start + delta)))

    @Test
    fun `every visible sticker moves along its drag under the independent cubie model`() {
        eyes.forEach { eye ->
            val pose = ViewPose(800, 600, eye)
            val model = CubieModel()
            model.cubies.forEachIndexed { index, cubie ->
                cubie.stickers.forEach { sticker ->
                    val normal = Vector3(sticker.nx.toDouble(), sticker.ny.toDouble(), sticker.nz.toDouble())
                    if (normal.dot(eye) > 1.5) {
                        val local = normal * 0.5
                        val start = Vector3(cubie.homeX.toDouble(), cubie.homeY.toDouble(), cubie.homeZ.toDouble()) + local
                        axes.filter { it.dot(normal) == 0.0 }.flatMap { listOf(it, it * -1.0) }.forEach { direction ->
                            val intent = GestureInterpreter.interpret(path(pose, start, direction * 0.6), pose)
                            assertNotNull("$eye $start $direction", intent)
                            model.begin(intent!!.move)
                            model.setProgress(0.05f)
                            val matrix = FloatArray(16)
                            model.modelMatrix(index, matrix, 0)
                            val after =
                                Vector3(
                                    matrix[0] * local.x + matrix[4] * local.y + matrix[8] * local.z + matrix[12],
                                    matrix[1] * local.x + matrix[5] * local.y + matrix[9] * local.z + matrix[13],
                                    matrix[2] * local.x + matrix[6] * local.y + matrix[10] * local.z + matrix[14],
                                )
                            assertTrue("${intent.move}: $start $direction", (after - start).dot(direction) > 0)
                            model.reset(CubeState.SOLVED.toFacelets())
                        }
                    }
                }
            }
        }
    }
}
