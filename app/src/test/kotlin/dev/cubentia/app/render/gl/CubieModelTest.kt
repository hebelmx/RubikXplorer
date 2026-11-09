package dev.cubentia.app.render.gl

import dev.cubentia.app.session.allMoves
import dev.cubentia.domain.cube.Axis
import dev.cubentia.domain.cube.CubeRotation
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Slice
import dev.cubentia.domain.cube.SliceTurn
import dev.cubentia.domain.cube.WideTurn
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class CubieModelTest {
    @Test fun solvedAndEveryMoveFromScrambledState() {
        assertEquals(CubeState.SOLVED.toFacelets(), CubieModel().facelets())
        val state = CubeState.SOLVED.apply(allMoves.take(17))
        allMoves.forEach { move ->
            val model = CubieModel(state.toFacelets())
            model.begin(move)
            model.setProgress(0.5f)
            assertEquals(state.toFacelets(), model.facelets())
            model.commit()
            assertEquals(move.toString(), state.apply(move).toFacelets(), model.facelets())
        }
    }

    @Test fun randomMovesAndResets() {
        val random = Random(20261030)
        var state = CubeState.SOLVED
        val model = CubieModel()
        repeat(200) {
            val move = allMoves.random(random)
            state = state.apply(move)
            if (random.nextInt(8) == 0) {
                model.reset(state.toFacelets())
            } else {
                model.begin(move)
                model.commit()
            }
            assertEquals("step $it", state.toFacelets(), model.facelets())
        }
    }

    /**
     * The matrices GL draws: unchanged at progress 0, only the expected layer moves mid-turn, and at
     * progress 1 they equal the committed geometry's matrices, for every Move of every type.
     */
    @Test fun modelMatricesAnimateExactlyTheTurningLayer() {
        val state = CubeState.SOLVED.apply(allMoves.take(11))
        allMoves.forEach { move ->
            val model = CubieModel(state.toFacelets())
            val before = matrices(model)
            val (axis, layers) = turnOf(move)
            val expected =
                model.cubies.indices.filter { i ->
                    val p = model.cubies[i].position()
                    p[0] * axis[0] + p[1] * axis[1] + p[2] * axis[2] in layers
                }
            model.begin(move)
            model.setProgress(0f)
            assertArrayEquals("$move at 0", before, matrices(model), EPS)
            model.setProgress(0.5f)
            val mid = matrices(model)
            val moved =
                model.cubies.indices.filter { i ->
                    (0 until MAT4).any { k -> abs(mid[i * MAT4 + k] - before[i * MAT4 + k]) > EPS }
                }
            assertEquals("$move moves its layer only", expected, moved)
            model.setProgress(1f)
            val end = matrices(model)
            model.commit()
            assertArrayEquals("$move at 1 equals committed", matrices(model), end, EPS)
        }
    }

    private fun matrices(model: CubieModel) =
        FloatArray(CubieModel.CUBIE_COUNT * MAT4).also { out ->
            repeat(CubieModel.CUBIE_COUNT) {
                model.modelMatrix(
                    it,
                    out,
                    it * MAT4,
                )
            }
        }

    /** Axis and layer coordinates per Move type (spec Design Notes), in the viewer frame. */
    private fun turnOf(move: Move): Pair<IntArray, Set<Int>> =
        when (move) {
            is FaceTurn -> {
                CubieModel.axisOf(move.face) to setOf(1)
            }

            is WideTurn -> {
                CubieModel.axisOf(move.face) to setOf(1, 0)
            }

            is SliceTurn -> {
                CubieModel.axisOf(mapOf(Slice.M to Face.L, Slice.E to Face.D, Slice.S to Face.F).getValue(move.slice)) to
                    setOf(0)
            }

            is CubeRotation -> {
                CubieModel.axisOf(mapOf(Axis.X to Face.R, Axis.Y to Face.U, Axis.Z to Face.F).getValue(move.axis)) to
                    setOf(-1, 0, 1)
            }
        }

    private companion object {
        const val MAT4 = 16
        const val EPS = 1e-4f
    }
}
