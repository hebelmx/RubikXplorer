package dev.rubikxplorer.solver.benchmark

import dev.rubikxplorer.application.port.CubeSolver
import dev.rubikxplorer.application.port.SolveResult
import dev.rubikxplorer.domain.cube.CubeState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SolverWorkloadTest {
    private val failing = CubeSolver { SolveResult.Failed }

    @Test
    fun `corpus is reproducible with solved uniform legal states and deep Scrambles`() {
        val cases = 20
        val first = SolverWorkload(cases, 7, failing).corpus()
        assertEquals(121, first.size)
        assertEquals(CubeState.SOLVED, first.first())
        assertEquals(first, SolverWorkload(20, 7, failing).corpus())
        val uniform = first.drop(1).take(cases)
        assertEquals(cases, uniform.distinct().size)
        assertEquals(uniform, SolverWorkload(cases, 7, failing).corpus().drop(1).take(cases))
        assertNotEquals(uniform, SolverWorkload(cases, 8, failing).corpus().drop(1).take(cases))
    }

    @Test
    fun `failed and invented solutions fail independent verification`() {
        assertFalse(SolverWorkload(1, 7, failing).attempt(CubeState.SOLVED))
        val workload = SolverWorkload(1, 7, CubeSolver { SolveResult.Solved(emptyList()) })
        assertFalse(workload.attempt(workload.corpus()[1]))
        assertThrows<IllegalArgumentException> { SolverWorkload(0, 7, failing) }
    }
}
