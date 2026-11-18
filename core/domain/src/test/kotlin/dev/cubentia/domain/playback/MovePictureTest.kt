package dev.cubentia.domain.playback

import dev.cubentia.domain.cube.Axis
import dev.cubentia.domain.cube.CubeRotation
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Slice
import dev.cubentia.domain.cube.SliceTurn
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.cube.WideTurn
import dev.cubentia.domain.playback.MovePicture.Direction.CCW
import dev.cubentia.domain.playback.MovePicture.Direction.CW
import dev.cubentia.domain.playback.MovePicture.Direction.DOWN
import dev.cubentia.domain.playback.MovePicture.Direction.LEFT
import dev.cubentia.domain.playback.MovePicture.Direction.RIGHT
import dev.cubentia.domain.playback.MovePicture.Direction.UP
import dev.cubentia.domain.playback.MovePicture.Shape.CURVED
import dev.cubentia.domain.playback.MovePicture.Shape.ROUND
import dev.cubentia.domain.playback.MovePicture.Shape.STRAIGHT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MovePictureTest {
    @Test fun facesAndWideTurns() {
        val directions = mapOf(Face.R to UP, Face.L to DOWN, Face.U to LEFT, Face.D to RIGHT, Face.F to CW, Face.B to CCW)
        for ((face, direction) in directions) {
            for (wide in listOf(false, true)) {
                val span =
                    when (face) {
                        Face.R, Face.D -> if (wide) 1..2 else 2..2
                        Face.L, Face.U -> if (wide) 0..1 else 0..0
                        Face.F, Face.B -> 0..2
                    }
                val shaded =
                    when (face) {
                        Face.R, Face.L -> cells(0..2, span)
                        Face.U, Face.D -> cells(span, 0..2)
                        Face.F -> cells(0..2, 0..2)
                        Face.B -> emptySet()
                    }
                val round = face == Face.F || face == Face.B
                val expected =
                    MovePicture(
                        shadedCells = shaded,
                        arrow = MovePicture.Arrow(if (round) ROUND else CURVED, direction, span, face == Face.B, round && wide),
                    )
                checkTurns(expected) { if (wide) WideTurn(face, it) else FaceTurn(face, it) }
            }
        }
    }

    @Test fun slices() {
        for (slice in Slice.entries) {
            checkTurns(
                MovePicture(
                    view = if (slice == Slice.S) MovePicture.View.TOP else MovePicture.View.FRONT,
                    shadedCells = if (slice == Slice.M) cells(0..2, 1..1) else cells(1..1, 0..2),
                    arrow = MovePicture.Arrow(STRAIGHT, if (slice == Slice.M) DOWN else RIGHT, 1..1),
                ),
            ) { SliceTurn(slice, it) }
        }
    }

    @Test fun rotations() {
        for ((axis, direction) in mapOf(Axis.X to UP, Axis.Y to LEFT, Axis.Z to CW)) {
            checkTurns(
                MovePicture(
                    shadedCells = emptySet(),
                    arrow = MovePicture.Arrow(if (axis == Axis.Z) ROUND else CURVED, direction, 0..2),
                    doubledOutline = true,
                ),
            ) { CubeRotation(axis, it) }
        }
    }

    private fun checkTurns(
        expected: MovePicture,
        move: (Turn) -> Move,
    ) {
        val plain = move(Turn.CLOCKWISE)
        assertEquals(expected, MovePicture.of(plain), plain.toString())
        val prime = MovePicture.of(move(Turn.COUNTERCLOCKWISE))
        val opposite =
            when (expected.arrow.direction) {
                UP -> DOWN
                DOWN -> UP
                LEFT -> RIGHT
                RIGHT -> LEFT
                CW -> CCW
                CCW -> CW
            }
        assertEquals(expected.copy(arrow = expected.arrow.copy(direction = opposite)), prime, plain.toString())
        assertEquals(prime, MovePicture.of(plain).reversed(), plain.toString())
        assertEquals(prime, MovePicture.of(plain.inverse()), plain.toString())
        assertEquals(expected.copy(superscriptTwo = true), MovePicture.of(move(Turn.HALF)), plain.toString())
    }

    private fun cells(
        rows: IntRange,
        cols: IntRange,
    ) = rows.flatMap { row -> cols.map { col -> MovePicture.Cell(row, col) } }.toSet()
}
