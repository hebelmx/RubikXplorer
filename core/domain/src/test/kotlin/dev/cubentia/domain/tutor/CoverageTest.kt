package dev.cubentia.domain.tutor

import dev.cubentia.domain.cube.Axis
import dev.cubentia.domain.cube.CubeRotation
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Slice
import dev.cubentia.domain.cube.SliceTurn
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.cube.WideTurn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CoverageTest {
    @Test fun `rotated face turns resolve home faces`() {
        val before = CubeState.SOLVED.apply(CubeRotation(Axis.Y, Turn.CLOCKWISE))
        Face.entries.forEach { assertEquals(setOf(before.orientation.homeFaceAt(it)), Coverage.touched(before, FaceTurn(it, Turn.HALF))) }
    }

    @Test fun `wide and slice follow their parts in each orientation`() {
        Axis.entries.forEach { axis ->
            val before = CubeState.SOLVED.apply(CubeRotation(axis, Turn.CLOCKWISE))
            Face.entries.forEach { face ->
                val m = WideTurn(face, Turn.CLOCKWISE)
                assertEquals(Coverage.touched(before, CubeState.parts(m)), Coverage.touched(before, m))
            }
            Slice.entries.forEach { slice ->
                val m = SliceTurn(slice, Turn.HALF)
                assertEquals(Coverage.touched(before, CubeState.parts(m)), Coverage.touched(before, m))
            }
        }
    }

    @Test fun `rotations touch nothing and update following frame`() {
        val rotation = CubeRotation(Axis.X, Turn.CLOCKWISE)
        val move = FaceTurn(Face.U, Turn.CLOCKWISE)
        assertEquals(emptySet<Face>(), Coverage.touched(CubeState.SOLVED, rotation))
        assertEquals(Coverage.touched(CubeState.SOLVED.apply(rotation), move), Coverage.touched(CubeState.SOLVED, listOf(rotation, move)))
    }
}
