package dev.cubentia.solver

import dev.cubentia.domain.cube.CubeState

/**
 * Clean-room breadth-first projections from the published mathematical description:
 * https://kociemba.org/math/pruning.htm and https://kociemba.org/math/twophase.htm.
 * Exact projected distances: taking the maximum of each phase's pair is admissible.
 * No third-party solver implementation is used.
 */
class PruningTables internal constructor(
    internal val distances: List<ByteArray>,
    val generationNanos: Long,
) {
    val byteSize: Int get() = distances.sumOf { it.size }

    fun phase1Distance(
        twist: Int,
        flip: Int,
        slicePosition: Int,
    ): Int = maxOf(distance(0, twist, slicePosition), distance(1, flip, slicePosition))

    fun phase2Distance(
        corners: Int,
        edges: Int,
        slicePermutation: Int,
    ): Int = maxOf(distance(2, corners, slicePermutation), distance(3, edges, slicePermutation))

    internal fun distance(
        table: Int,
        first: Int,
        second: Int,
    ): Int {
        val pair = pairs[table]
        require(first in 0 until pair.first.size && second in 0 until pair.second.size)
        return distances[table][first * pair.second.size + second].toInt()
    }

    companion object {
        internal val pairs =
            listOf(
                Coordinate.TWIST to Coordinate.SLICE_POSITION,
                Coordinate.FLIP to Coordinate.SLICE_POSITION,
                Coordinate.CORNER_PERMUTATION to Coordinate.SLICE_PERMUTATION,
                Coordinate.EDGE_PERMUTATION to Coordinate.SLICE_PERMUTATION,
            )
        val expectedByteSize: Int = pairs.sumOf { (first, second) -> first.size * second.size }

        /** CPU-only, on demand. The shell must run this on its CPU dispatcher. */
        fun generate(checkCancellation: () -> Unit = {}): PruningTables {
            val start = System.nanoTime()
            val tables = pairs.map { (first, second) -> build(first, second, checkCancellation) }
            return PruningTables(tables, System.nanoTime() - start)
        }

        private fun build(
            first: Coordinate,
            second: Coordinate,
            check: () -> Unit,
        ): ByteArray {
            check()
            val a = MoveTables.transitions(first, check)
            check()
            val b = MoveTables.transitions(second, check)
            val moves = MoveTables.moves(first).size
            val distances = ByteArray(first.size * second.size) { -1 }
            val queue = IntArray(distances.size)
            val solved = CubeState.SOLVED.cubies
            val root = first.encode(solved) * second.size + second.encode(solved)
            distances[root] = 0
            queue[0] = root
            var head = 0
            var tail = 1
            while (head < tail) {
                if (head % 4096 == 0) check()
                val state = queue[head++]
                val nextDistance = (distances[state] + 1).toByte()
                val x = state / second.size * moves
                val y = state % second.size * moves
                for (move in 0 until moves) {
                    val next = a[x + move] * second.size + b[y + move]
                    if (distances[next] == (-1).toByte()) {
                        distances[next] = nextDistance
                        queue[tail++] = next
                    }
                }
            }
            check(tail == distances.size) { "Incomplete pruning projection" }
            return distances
        }
    }
}
