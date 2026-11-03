package dev.rubikxplorer.solver

import dev.rubikxplorer.application.port.CubeSolver
import dev.rubikxplorer.application.port.SolveResult
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Cubies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Original IDA* implementation from the mathematical two-phase description at
 * https://kociemba.org/math/twophase.htm; no third-party solver source used.
 * Phase one is bounded by 12 face turns, phase two by 18 (30 total). Prepared tables are injected;
 * production table delivery belongs to Story 3.8. Each solve owns its mutable search workspace.
 */
class TwoPhaseCubeSolver internal constructor(
    private val tables: PruningTables,
    private val nanoTime: () -> Long,
    private val onSearchNode: () -> Unit,
) : CubeSolver {
    constructor(tables: PruningTables) : this(tables, System::nanoTime, {})

    override suspend fun solve(cubies: Cubies): SolveResult =
        withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            context.ensureActive()
            if (!legal(cubies)) return@withContext SolveResult.Invalid
            if (cubies == Cubies.SOLVED) return@withContext SolveResult.Solved(emptyList())
            // Move tables may need reconstructing after a cache load. Preparation is outside deadline.
            val transitions = Coordinate.entries.map { MoveTables.transitions(it) { context.ensureActive() } }
            val start = nanoTime()
            val search =
                Search(cubies, tables, transitions) {
                    onSearchNode()
                    context.ensureActive()
                    nanoTime() - start >= 3_000_000_000L
                }
            val result = search.run()
            context.ensureActive()
            if (nanoTime() - start >= 3_000_000_000L) SolveResult.TimedOut else result
        }
}

private fun legal(c: Cubies): Boolean {
    fun parity(p: List<Int>): Int = p.indices.sumOf { i -> (i + 1 until p.size).count { p[i] > p[it] } } % 2
    return c.cornerOrientation.sum() % 3 == 0 && c.edgeOrientation.sum() % 2 == 0 &&
        parity(c.cornerPermutation) == parity(c.edgePermutation)
}

private class Search(
    private val input: Cubies,
    private val tables: PruningTables,
    private val transitions: List<IntArray>,
    private val expired: () -> Boolean,
) {
    private val path = IntArray(30)
    private var length = 0
    private var nodes = 0
    private var timedOut = false
    private val transforms = MoveTables.phase1Moves.map { CubeState.SOLVED.apply(it).cubies }
    private val phase2Columns = MoveTables.phase2Moves.map { MoveTables.phase1Moves.indexOf(it) }

    fun run(): SolveResult {
        val twist = Coordinate.TWIST.encode(input)
        val flip = Coordinate.FLIP.encode(input)
        val slice = Coordinate.SLICE_POSITION.encode(input)
        for (depth in tables.phase1Distance(twist, flip, slice)..12) {
            if (phase1(twist, flip, slice, depth, 0, -1)) {
                return SolveResult.Solved((0 until length).map { MoveTables.phase1Moves[path[it]] })
            }
            if (timedOut) return SolveResult.TimedOut
        }
        return SolveResult.Failed
    }

    private fun stop(): Boolean {
        if (nodes++ % 1024 == 0 && expired()) timedOut = true
        return timedOut
    }

    // Same-face turns combine; opposite faces commute, so keep only one order.
    private fun allowed(
        face: Int,
        previous: Int,
    ): Boolean = face != previous && !(previous >= 0 && face == (previous + 3) % 6 && face < previous)

    private fun phase1(
        t: Int,
        f: Int,
        s: Int,
        remaining: Int,
        level: Int,
        previous: Int,
    ): Boolean {
        if (stop() || tables.phase1Distance(t, f, s) > remaining) return false
        if (remaining == 0) {
            var endpoint = Representative.from(input)
            for (i in 0 until level) endpoint = endpoint.apply(transforms[path[i]])
            val c = Coordinate.CORNER_PERMUTATION.encode(endpoint)
            val e = Coordinate.EDGE_PERMUTATION.encode(endpoint)
            val p = Coordinate.SLICE_PERMUTATION.encode(endpoint)
            for (depth in tables.phase2Distance(c, e, p)..18) {
                if (phase2(c, e, p, depth, level, -1)) return true
                if (timedOut) return false
            }
            return false
        }
        for (move in 0 until 18) {
            val face = move / 3
            if (!allowed(face, previous)) continue
            path[level] = move
            if (phase1(
                    transitions[0][t * 18 + move],
                    transitions[1][f * 18 + move],
                    transitions[2][s * 18 + move],
                    remaining - 1,
                    level + 1,
                    face,
                )
            ) {
                return true
            }
        }
        return false
    }

    private fun phase2(
        c: Int,
        e: Int,
        p: Int,
        remaining: Int,
        level: Int,
        previous: Int,
    ): Boolean {
        if (stop() || tables.phase2Distance(c, e, p) > remaining) return false
        if (remaining == 0) {
            length = level
            return true
        }
        for (move in 0 until 10) {
            val column = phase2Columns[move]
            val face = column / 3
            if (!allowed(face, previous)) continue
            path[level] = column
            if (phase2(
                    transitions[3][c * 10 + move],
                    transitions[4][e * 10 + move],
                    transitions[5][p * 10 + move],
                    remaining - 1,
                    level + 1,
                    face,
                )
            ) {
                return true
            }
        }
        return false
    }
}
