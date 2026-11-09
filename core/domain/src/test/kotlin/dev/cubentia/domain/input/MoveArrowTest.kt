package dev.cubentia.domain.input

import dev.cubentia.domain.cube.Axis
import dev.cubentia.domain.cube.CubeRotation
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Slice
import dev.cubentia.domain.cube.SliceTurn
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.cube.WideTurn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.sin

class MoveArrowTest {
    @Test
    fun `every turn has the correct plane radius signed sweep and eye facing midpoint`() {
        val faces =
            mapOf(
                Face.R to Vector3(1.0, 0.0, 0.0),
                Face.L to Vector3(-1.0, 0.0, 0.0),
                Face.U to Vector3(0.0, 1.0, 0.0),
                Face.D to Vector3(0.0, -1.0, 0.0),
                Face.F to Vector3(0.0, 0.0, 1.0),
                Face.B to Vector3(0.0, 0.0, -1.0),
            )
        for (turn in Turn.entries) {
            val cases =
                faces.flatMap { (face, axis) ->
                    listOf(Triple(FaceTurn(face, turn) as Move, axis, 1.0), Triple(WideTurn(face, turn) as Move, axis, 0.5))
                } +
                    listOf(
                        Triple(SliceTurn(Slice.M, turn), faces.getValue(Face.L), 0.0),
                        Triple(SliceTurn(Slice.E, turn), faces.getValue(Face.D), 0.0),
                        Triple(SliceTurn(Slice.S, turn), faces.getValue(Face.F), 0.0),
                    ) +
                    listOf(
                        Triple(CubeRotation(Axis.X, turn), faces.getValue(Face.R), 0.0),
                        Triple(CubeRotation(Axis.Y, turn), faces.getValue(Face.U), 0.0),
                        Triple(CubeRotation(Axis.Z, turn), faces.getValue(Face.F), 0.0),
                    )
            for ((move, axis, offset) in cases) {
                for (eye in listOf(ViewPose.DEFAULT_EYE, axis * 6.0, axis * 6.0 + Vector3(0.01, 0.01, 0.01))) {
                    val arc = MoveArrow.arc(move, eye)
                    assertTrue(arc.size >= 16)
                    arc.forEach { p ->
                        assertTrue(p.x.isFinite() && p.y.isFinite() && p.z.isFinite())
                        assertEquals(offset, p.dot(axis), 1e-9)
                        assertEquals(MoveArrow.ARROW_RADIUS, (p - axis * offset).length(), 1e-9)
                    }
                    val angle =
                        when (turn) {
                            Turn.CLOCKWISE -> -Math.PI / 2
                            Turn.COUNTERCLOCKWISE -> Math.PI / 2
                            Turn.HALF -> -Math.PI
                        }
                    val start = arc.first() - axis * offset
                    val rotated = start * cos(angle) + axis.cross(start) * sin(angle)
                    assertEquals(0.0, (arc.last() - axis * offset - rotated).length(), 1e-9)
                    val projected = eye - axis * eye.dot(axis)
                    val midpoint = arc[arc.size / 2] - axis * offset
                    if (projected.length() >= 0.1) {
                        assertTrue(midpoint.dot(projected) > 0.0)
                    } else {
                        assertTrue(midpoint.dot(if (axis.y != 0.0) Vector3(0.0, 0.0, 1.0) else Vector3(0.0, 1.0, 0.0)) > 0.0)
                    }
                }
            }
        }
    }
}
