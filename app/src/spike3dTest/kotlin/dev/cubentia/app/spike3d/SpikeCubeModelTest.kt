package dev.cubentia.app.spike3d

import dev.cubentia.app.render.PresentationItem
import dev.cubentia.app.spike3d.SpikeTurnDriver.Companion.percentile
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Turn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.random.Random

/** OQ-3 spike: `renderedFacelets()` is derivable from the 27-cubie geometry alone. */
class SpikeCubeModelTest {
    @Test
    fun `solved model reads back the solved facelets`() {
        assertEquals(CubeState.SOLVED.toFacelets(), SpikeCubeModel().facelets())
    }

    @ParameterizedTest
    @MethodSource("quarterTurns")
    fun `a committed quarter turn reads back the engine's facelets`(move: FaceTurn) {
        val model = SpikeCubeModel()
        model.beginTurn(move)
        model.setProgress(1f)
        model.commit()
        assertEquals(CubeState.SOLVED.apply(move).toFacelets(), model.facelets())
    }

    @Test
    fun `mid-animation readback is the pre-turn facelets`() {
        val model = SpikeCubeModel()
        model.beginTurn(FaceTurn(Face.R, Turn.CLOCKWISE))
        model.setProgress(0.5f)
        assertTrue(model.isTurning)
        assertEquals(CubeState.SOLVED.toFacelets(), model.facelets())
        model.commit()
        assertFalse(model.isTurning)
        assertEquals(CubeState.SOLVED.apply(FaceTurn(Face.R, Turn.CLOCKWISE)).toFacelets(), model.facelets())
    }

    @Test
    fun `mid-animation matrix turns the layer by half the angle`() {
        val model = SpikeCubeModel()
        model.beginTurn(FaceTurn(Face.U, Turn.CLOCKWISE))
        model.setProgress(0.5f)
        val urf = model.cubies.indexOfFirst { it.homeX == 1 && it.homeY == 1 && it.homeZ == 1 }
        val m = FloatArray(16)
        model.modelMatrix(urf, m)
        // U clockwise takes URF towards UFL; half way is -45° about +y: (1, 1, 1) -> (0, 1, √2).
        assertEquals(0.0, m[12].toDouble(), 1e-5)
        assertEquals(1.0, m[13].toDouble(), 1e-5)
        assertEquals(Math.sqrt(2.0), m[14].toDouble(), 1e-5)
    }

    @Test
    fun `random sequence of 25 face turns reads back the engine's facelets`() {
        val random = Random(SEED)
        val moves = List(25) { FaceTurn(Face.entries.random(random), Turn.entries.random(random)) }
        val model = SpikeCubeModel()
        for (move in moves) {
            model.beginTurn(move)
            model.setProgress(0.37f)
            model.commit()
        }
        assertEquals(CubeState.SOLVED.apply(moves).toFacelets(), model.facelets())
    }

    @Test
    fun `driver drains the queue over 250 ms per turn and fast-forwards a burst`() {
        val stats = mutableListOf<TurnStats>()
        val driver = SpikeTurnDriver(onTurnFinished = { stats += it })
        var state = CubeState.SOLVED
        val moves: List<Move> = List(4) { FaceTurn(Face.R, if (it % 2 == 0) Turn.CLOCKWISE else Turn.COUNTERCLOCKWISE) }
        driver.submit(
            moves.mapIndexed { i, move ->
                state = state.apply(move)
                PresentationItem.Animate(move, i + 1L, state.toFacelets())
            },
        )
        var now = 0L
        while (driver.isBusy()) {
            driver.onFrame(now)
            now += FRAME_NANOS
        }
        assertEquals(state.toFacelets(), driver.facelets())
        assertEquals(listOf(true, false, false, false), stats.map { it.fastForward })
        assertTrue(stats.all { it.readbackMatchesTarget })
        assertEquals(SpikeTurnDriver.TURN_MILLIS / 4.0, stats[0].elapsedMillis, FRAME_NANOS / 1e6)
        assertEquals(SpikeTurnDriver.TURN_MILLIS.toDouble(), stats[1].elapsedMillis, FRAME_NANOS / 1e6)
    }

    @Test
    fun `a snap resets the model to its target`() {
        val target = CubeState.SOLVED.apply(FaceTurn(Face.F, Turn.HALF), FaceTurn(Face.L, Turn.CLOCKWISE)).toFacelets()
        val driver = SpikeTurnDriver()
        driver.submit(listOf(PresentationItem.Snap(1, target)))
        driver.onFrame(0)
        assertEquals(target, driver.facelets())
    }

    @Test
    fun `percentile of a single interval is that interval`() {
        assertEquals(5.0, listOf(5.0).percentile(0.9))
    }

    @Test
    fun `percentile uses the nearest rank`() {
        val intervals = (1..10).map { it.toDouble() }
        assertEquals(9.0, intervals.percentile(0.9))
        assertEquals(5.0, intervals.percentile(0.5))
        assertEquals(10.0, intervals.percentile(0.99))
    }

    @Test
    fun `percentile of no intervals is zero`() {
        assertEquals(0.0, emptyList<Double>().percentile(0.9))
    }

    companion object {
        private const val SEED = 20261030
        private const val FRAME_NANOS = 16_666_667L

        @JvmStatic
        fun quarterTurns(): List<FaceTurn> =
            Face.entries.flatMap { face -> listOf(FaceTurn(face, Turn.CLOCKWISE), FaceTurn(face, Turn.COUNTERCLOCKWISE)) }
    }
}
