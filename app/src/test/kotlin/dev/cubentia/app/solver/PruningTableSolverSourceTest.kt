package dev.cubentia.app.solver

import dev.cubentia.application.session.Effect
import dev.cubentia.application.usecase.SolveCube
import dev.cubentia.application.usecase.SolveCubeOutcome
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Turn
import dev.cubentia.solver.PruningTableProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The production [SolverSource] turns real pruning tables into a solver whose Solution solves an entered cube. */
class PruningTableSolverSourceTest {
    @Test
    fun loadedTablesSolveAScrambledCube(
        @TempDir directory: File,
    ) = runBlocking {
        val source = PruningTableSolverSource(PruningTableProvider(File(directory, "solver/pruning-v1.bin")))
        assertFalse(source.isReady)
        val loaded = source.load()
        assertTrue(loaded is SolverLoad.Ready)
        assertTrue(source.isReady)
        assertSame((loaded as SolverLoad.Ready).solver, (source.load() as SolverLoad.Ready).solver)

        val cube =
            CubeState.SOLVED.apply(
                FaceTurn(Face.R, Turn.CLOCKWISE),
                FaceTurn(Face.U, Turn.COUNTERCLOCKWISE),
                FaceTurn(Face.F, Turn.HALF),
                FaceTurn(Face.L, Turn.CLOCKWISE),
                FaceTurn(Face.B, Turn.COUNTERCLOCKWISE),
                FaceTurn(Face.D, Turn.CLOCKWISE),
            )
        val outcome = SolveCube(loaded.solver)(Effect.SolveCube(1, 1, cube)).outcome
        assertTrue(outcome is SolveCubeOutcome.Solved)
        assertTrue(cube.apply((outcome as SolveCubeOutcome.Solved).moves).isSolved)
    }
}
