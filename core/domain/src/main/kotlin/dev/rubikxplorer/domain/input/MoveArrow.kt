package dev.rubikxplorer.domain.input

import dev.rubikxplorer.domain.cube.Axis
import dev.rubikxplorer.domain.cube.CubeRotation
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.Slice
import dev.rubikxplorer.domain.cube.SliceTurn
import dev.rubikxplorer.domain.cube.Turn
import dev.rubikxplorer.domain.cube.WideTurn
import kotlin.math.cos
import kotlin.math.sin

/** World-space teaching arc; the final point is the arrow tip. */
object MoveArrow {
    const val ARROW_RADIUS = 1.85

    fun arc(
        move: Move,
        eye: Vector3,
    ): List<Vector3> {
        val (face, turn) =
            when (move) {
                is FaceTurn -> {
                    move.face to move.turn
                }

                is WideTurn -> {
                    move.face to move.turn
                }

                is SliceTurn -> {
                    when (move.slice) {
                        Slice.M -> Face.L
                        Slice.E -> Face.D
                        Slice.S -> Face.F
                    } to move.turn
                }

                is CubeRotation -> {
                    when (move.axis) {
                        Axis.X -> Face.R
                        Axis.Y -> Face.U
                        Axis.Z -> Face.F
                    } to move.turn
                }
            }
        val axis =
            when (face) {
                Face.R -> Vector3(1.0, 0.0, 0.0)
                Face.L -> Vector3(-1.0, 0.0, 0.0)
                Face.U -> Vector3(0.0, 1.0, 0.0)
                Face.D -> Vector3(0.0, -1.0, 0.0)
                Face.F -> Vector3(0.0, 0.0, 1.0)
                Face.B -> Vector3(0.0, 0.0, -1.0)
            }
        val offset =
            when (move) {
                is FaceTurn -> 1.0
                is WideTurn -> 0.5
                else -> 0.0
            }
        val projection = eye - axis * eye.dot(axis)
        val middle =
            if (projection.length() >=
                0.1
            ) {
                projection.normalized()
            } else if (axis.y != 0.0) {
                Vector3(0.0, 0.0, 1.0)
            } else {
                Vector3(0.0, 1.0, 0.0)
            }
        val tangent = axis.cross(middle)
        val sweep =
            when (turn) {
                Turn.CLOCKWISE -> -Math.PI / 2
                Turn.COUNTERCLOCKWISE -> Math.PI / 2
                Turn.HALF -> -Math.PI
            }
        return (0..32).map { i ->
            val angle = sweep * (i / 32.0 - 0.5)
            axis * offset + (middle * cos(angle) + tangent * sin(angle)) * ARROW_RADIUS
        }
    }
}
