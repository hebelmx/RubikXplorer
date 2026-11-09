package dev.cubentia.domain.input

import dev.cubentia.domain.cube.CubeGeometry
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GestureInterpreterTest {
    @Test
    fun `cube start and twist matrix`() {
        val pose = ViewPose(800, 600)
        assertEquals(true, GestureInterpreter.startsOnCube(pose.project(Vector3(0.0, 0.0, 1.5)), pose))
        assertEquals(false, GestureInterpreter.startsOnCube(ScreenPoint(0.0, 0.0), pose))

        fun twist(
            degrees: Double,
            camera: ViewPose = pose,
            cx: Double = 400.0,
            cy: Double = 300.0,
        ): MoveIntent? {
            val radians = Math.toRadians(degrees)
            return GestureInterpreter.interpretTwist(
                ScreenPoint(cx - 50, cy),
                ScreenPoint(cx + 50, cy),
                ScreenPoint(cx - 50 * kotlin.math.cos(radians), cy - 50 * kotlin.math.sin(radians)),
                ScreenPoint(cx + 50 * kotlin.math.cos(radians), cy + 50 * kotlin.math.sin(radians)),
                camera,
            )
        }
        val z =
            dev.cubentia.domain.cube
                .CubeRotation(dev.cubentia.domain.cube.Axis.Z, Turn.CLOCKWISE)
        assertEquals(MoveIntent(z), twist(40.0))
        assertEquals(MoveIntent(z.inverse()), twist(-40.0))
        assertEquals(MoveIntent(z.inverse()), twist(40.0, ViewPose(800, 600, Vector3(0.0, 0.0, -9.0))))
        assertNull(twist(29.0))
        assertNull(twist(40.0, cx = 0.0, cy = 0.0))
    }

    private val axes = listOf(Vector3(1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0), Vector3(0.0, 0.0, 1.0))
    private val eyes = listOf(Vector3(4.2, 4.6, 6.4), Vector3(-4.2, -4.6, 6.4), Vector3(-4.2, 4.6, -6.4), Vector3(4.2, -4.6, -6.4))

    private fun path(
        pose: ViewPose,
        start: Vector3,
        delta: Vector3,
    ) = TouchPath(listOf(pose.project(start), pose.project(start + delta)))

    @Test
    fun `documented front face moves`() {
        val pose = ViewPose(800, 600)
        val cases =
            listOf(
                Triple(Vector3(0.0, 1.0, 1.5), Vector3(-0.6, 0.0, 0.0), FaceTurn(Face.U, Turn.CLOCKWISE)),
                Triple(Vector3(0.0, 1.0, 1.5), Vector3(0.6, 0.0, 0.0), FaceTurn(Face.U, Turn.COUNTERCLOCKWISE)),
                Triple(Vector3(1.0, 0.0, 1.5), Vector3(0.0, 0.6, 0.0), FaceTurn(Face.R, Turn.CLOCKWISE)),
                Triple(Vector3(0.0, 0.0, 1.5), Vector3(0.0, 0.6, 0.0), SliceTurn(Slice.M, Turn.COUNTERCLOCKWISE)),
                Triple(Vector3(0.0, 0.0, 1.5), Vector3(0.6, 0.0, 0.0), SliceTurn(Slice.E, Turn.CLOCKWISE)),
            )
        cases.forEach { (start, delta, expected) ->
            assertEquals(MoveIntent(expected), GestureInterpreter.interpret(path(pose, start, delta), pose))
        }
    }

    @Test
    fun `every visible row and column has the same world move across poses`() {
        val expected = mutableMapOf<Pair<Vector3, Vector3>, MoveIntent>()
        eyes.forEach { eye ->
            for ((width, height) in listOf(800 to 600, 600 to 800)) {
                val pose = ViewPose(width, height, eye)
                for (normal in axes.flatMap { listOf(it, it * -1.0) }.filter { it.dot(eye) > 1.5 }) {
                    val tangent = axes.filter { it.dot(normal) == 0.0 }
                    for (row in -1..1) {
                        for (col in -1..1) {
                            for (direction in tangent.flatMap { listOf(it, it * -1.0) }) {
                                val start = normal * 1.5 + tangent[0] * row.toDouble() + tangent[1] * col.toDouble()
                                val actual = GestureInterpreter.interpret(path(pose, start, direction * 0.6), pose)
                                assertNotNull(actual, "$eye $start $direction")
                                val key = start to direction
                                val previous = expected.putIfAbsent(key, actual!!)
                                if (previous != null) assertEquals(previous, actual)
                                assertEquals(
                                    actual.move.inverse(),
                                    GestureInterpreter.interpret(path(pose, start, direction * -0.6), pose)!!.move,
                                )
                            }
                        }
                    }
                }
            }
        }
        assertEquals(6 * 9 * 4, expected.size)
    }

    @Test
    fun `uncertain missing and degenerate paths do nothing`() {
        val pose = ViewPose(800, 600)
        val start = Vector3(0.0, 0.0, 1.5)
        for (delta in listOf(Vector3(0.34, 0.0, 0.0), Vector3(0.6, 0.4, 0.0), Vector3(0.0, 0.0, 0.0))) {
            assertNull(GestureInterpreter.interpret(path(pose, start, delta), pose))
        }
        assertNull(GestureInterpreter.interpret(TouchPath(emptyList()), pose))
        assertNull(GestureInterpreter.interpret(TouchPath(listOf(pose.project(start))), pose))
        assertNull(GestureInterpreter.interpret(TouchPath(listOf(ScreenPoint(0.0, 0.0), ScreenPoint(400.0, 300.0))), pose))
        // A direction tangent to the picked F plane, then one pointing away from it.
        val parallel = pose.project(pose.eye + Vector3(-1.0, 0.0, 0.0))
        val behind = pose.project(pose.eye + Vector3(-10.0, -10.0, 1.0))
        for (end in listOf(parallel, behind)) assertNull(GestureInterpreter.interpret(TouchPath(listOf(pose.project(start), end)), pose))
    }

    @Test
    fun `top-down and bottom-up poses recognize every U and D row and column like an oblique pose`() {
        val cases =
            listOf(
                Triple(Vector3(0.0, 8.0, 0.0), Vector3(0.0, 1.0, 0.0), Vector3(4.2, 4.6, 6.4)),
                Triple(Vector3(0.0, -8.0, 0.0), Vector3(0.0, -1.0, 0.0), Vector3(-4.2, -4.6, 6.4)),
            )
        cases.forEach { (eye, normal, obliqueEye) ->
            for ((width, height) in listOf(800 to 600, 600 to 800)) {
                val pose = ViewPose(width, height, eye)
                val oblique = ViewPose(width, height, obliqueEye)
                val centre = pose.ray(width / 2.0, height / 2.0).direction
                assert(!centre.x.isNaN() && !centre.y.isNaN() && !centre.z.isNaN()) { "$eye ray is NaN" }
                val tangent = axes.filter { it.dot(normal) == 0.0 }
                for (row in -1..1) {
                    for (col in -1..1) {
                        for (direction in tangent.flatMap { listOf(it, it * -1.0) }) {
                            val start = normal * 1.5 + tangent[0] * row.toDouble() + tangent[1] * col.toDouble()
                            val actual = GestureInterpreter.interpret(path(pose, start, direction * 0.6), pose)
                            assertNotNull(actual, "$eye $start $direction")
                            assertEquals(GestureInterpreter.interpret(path(oblique, start, direction * 0.6), oblique), actual)
                            assertEquals(
                                actual!!.move.inverse(),
                                GestureInterpreter.interpret(path(pose, start, direction * -0.6), pose)!!.move,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `grazing poses pick the rendered sticker the ray reaches, not the bounding box face`() {
        val lift = 1.0 + CubeGeometry.STICKER_LIFT
        val cases =
            listOf(
                // The F centre is visible past the R edge; the bounding box would hit R first and pick F.
                Triple(
                    Vector3(8.0, 0.0, 1.6),
                    Vector3(0.0, 0.0, lift) to Vector3(0.0, 0.6, 0.0),
                    SliceTurn(Slice.M, Turn.COUNTERCLOCKWISE),
                ),
                // The U centre is visible past the F edge; the bounding box would hit F first and pick U.
                Triple(Vector3(0.0, 1.6, 8.0), Vector3(0.0, lift, 0.0) to Vector3(0.6, 0.0, 0.0), SliceTurn(Slice.S, Turn.CLOCKWISE)),
            )
        cases.forEach { (eye, drag, expected) ->
            val pose = ViewPose(800, 600, eye)
            assertEquals(MoveIntent(expected), GestureInterpreter.interpret(path(pose, drag.first, drag.second), pose), "$eye")
        }
    }

    @Test
    fun `a touch in the gap between stickers does nothing`() {
        val pose = ViewPose(800, 600)
        val gap = Vector3(0.5, 0.0, CubeGeometry.STICKER_LIFT + 1.0)
        assertNull(GestureInterpreter.interpret(path(pose, gap, Vector3(0.0, 0.6, 0.0)), pose))
    }
}
