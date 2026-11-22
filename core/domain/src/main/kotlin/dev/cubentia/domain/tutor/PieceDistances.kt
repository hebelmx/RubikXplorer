package dev.cubentia.domain.tutor

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Cubies
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceletTables
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.predicate.Piece
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-cubie move tables and exact small-group distances for [RecoveryPlanner.planPieces].
 *
 * A tracked piece is a cubie ID: corners `0..7` and edges `8..19`, in [Cubies] slot order. Its
 * position is `slot * 3 + twist` for a corner and `slot * 2 + flip` for an edge, so each has
 * [POSITIONS] positions and is home at `slot * 3` (or `* 2`) with its own index as slot. A Move is
 * the home face's ordinal times 3 plus the [Turn] ordinal ([MOVES] of them).
 *
 * The distance of a group of one, two or three pieces is the fewest face turns that bring every
 * piece of the group home and untwisted (unflipped), found by breadth-first search over all
 * [MOVES] face turns from the solved group. Tables are built on first use and cached process-wide.
 */
internal object PieceDistances {
    val MOVES: Int = 18
    val POSITIONS: Int = 24
    const val CORNER_PIECES: Int = Cubies.CORNERS
    const val PIECES: Int = Cubies.CORNERS + Cubies.EDGES

    private val TURNS = 3
    private val UNSEEN: Byte = -1

    private val CORNER_MOVES: IntArray = moveTable(corner = true)
    private val EDGE_MOVES: IntArray = moveTable(corner = false)

    private val tables = ConcurrentHashMap<Int, ByteArray>()

    /** The Move index of turning the home [face] by [turn]. */
    fun moveIndex(
        face: Face,
        turn: Turn,
    ): Int = face.ordinal * TURNS + turn.ordinal

    /** The position of [piece] after Move [move] from [position]. */
    fun next(
        piece: Int,
        position: Int,
        move: Int,
    ): Int = if (piece < CORNER_PIECES) CORNER_MOVES[position * MOVES + move] else EDGE_MOVES[position * MOVES + move]

    /** The position at which [piece] is home and untwisted (unflipped). */
    fun home(piece: Int): Int = if (piece < CORNER_PIECES) piece * Cubies.TWISTS else (piece - CORNER_PIECES) * Cubies.FLIPS

    /** True when [piece] is a corner. */
    fun isCorner(piece: Int): Boolean = piece < CORNER_PIECES

    /** Where [piece] sits in [cubies]. */
    fun positionOf(
        cubies: Cubies,
        piece: Int,
    ): Int =
        if (piece < CORNER_PIECES) {
            val slot = cubies.cornerPermutation.indexOf(piece)
            slot * Cubies.TWISTS + cubies.cornerOrientation[slot]
        } else {
            val slot = cubies.edgePermutation.indexOf(piece - CORNER_PIECES)
            slot * Cubies.FLIPS + cubies.edgeOrientation[slot]
        }

    /** The cubie ID of the [Piece] with these colors, or null when no cubie has them. */
    fun idOf(piece: Piece): Int? {
        val corner = FaceletTables.CORNER_FACES.indexOfFirst { faces -> faces.map(Color::home).toSet() == piece.colors }
        if (corner >= 0) return corner
        val edge = FaceletTables.EDGE_FACES.indexOfFirst { faces -> faces.map(Color::home).toSet() == piece.colors }
        return if (edge >= 0) CORNER_PIECES + edge else null
    }

    /**
     * The distance table of [pieces] (1 to 3 distinct cubie IDs, in this order): the entry at
     * `sum(position[j] * POSITIONS^(k - 1 - j))` is the group's distance from home.
     */
    fun table(pieces: IntArray): ByteArray {
        require(pieces.size in 1..MAX_GROUP) { "a distance group has 1..$MAX_GROUP pieces, was ${pieces.size}" }
        val key = pieces.fold(pieces.size) { acc, piece -> acc * PIECES + piece }
        return tables.computeIfAbsent(key) { build(pieces) }
    }

    /** The largest group with a distance table. */
    val MAX_GROUP: Int = 3

    private fun build(pieces: IntArray): ByteArray {
        var size = 1
        repeat(pieces.size) { size *= POSITIONS }
        val distance = ByteArray(size) { UNSEEN }
        val queue = IntArray(size)
        var head = 0
        var tail = 0
        val start = pieces.fold(0) { acc, piece -> acc * POSITIONS + home(piece) }
        distance[start] = 0
        queue[tail++] = start
        val positions = IntArray(pieces.size)
        while (head < tail) {
            val current = queue[head++]
            var rest = current
            for (j in pieces.indices.reversed()) {
                positions[j] = rest % POSITIONS
                rest /= POSITIONS
            }
            val reached = (distance[current] + 1).toByte()
            for (move in 0 until MOVES) {
                var index = 0
                for (j in pieces.indices) index = index * POSITIONS + next(pieces[j], positions[j], move)
                if (distance[index] == UNSEEN) {
                    distance[index] = reached
                    queue[tail++] = index
                }
            }
        }
        return distance
    }

    // Slot s with twist o goes to slot i where the turn's permutation[i] == s, gaining its twist at i.
    private fun moveTable(corner: Boolean): IntArray {
        val table = IntArray(POSITIONS * MOVES)
        for (face in Face.entries) {
            for (turn in Turn.entries) {
                val move = moveIndex(face, turn)
                val turned = Cubies.SOLVED.turn(face, turn)
                val permutation = if (corner) turned.cornerPermutation else turned.edgePermutation
                val orientation = if (corner) turned.cornerOrientation else turned.edgeOrientation
                val kinds = if (corner) Cubies.TWISTS else Cubies.FLIPS
                for (position in 0 until POSITIONS) {
                    val slot = position / kinds
                    val twist = position % kinds
                    val target = permutation.indexOf(slot)
                    table[position * MOVES + move] = target * kinds + (twist + orientation[target]) % kinds
                }
            }
        }
        return table
    }
}

/**
 * The admissible lower bound of [RecoveryPlanner.planPieces] for the goal [pieces] (cubie IDs, in
 * ascending order): the larger of the count bound (`ceil(n / 4)` misplaced corners or edges) and the
 * exact distances of each piece alone, each pair, and each triple holding a piece [missing] at the
 * start. Each of those is at most the distance of the whole goal, so their maximum is too.
 */
internal class PieceBound(
    private val pieces: IntArray,
    missing: BooleanArray,
) {
    private val homes = IntArray(pieces.size) { PieceDistances.home(pieces[it]) }
    private val singles: Array<ByteArray> = Array(pieces.size) { PieceDistances.table(intArrayOf(pieces[it])) }
    private val pairs: IntArray
    private val pairTables: Array<ByteArray>
    private val triples: IntArray
    private val tripleTables: Array<ByteArray>

    init {
        val pairIndices = mutableListOf<Int>()
        val pairList = mutableListOf<ByteArray>()
        val tripleIndices = mutableListOf<Int>()
        val tripleList = mutableListOf<ByteArray>()
        for (a in pieces.indices) {
            for (b in a + 1 until pieces.size) {
                pairIndices += listOf(a, b)
                pairList += PieceDistances.table(intArrayOf(pieces[a], pieces[b]))
                for (c in b + 1 until pieces.size) {
                    if (missing[a] || missing[b] || missing[c]) {
                        tripleIndices += listOf(a, b, c)
                        tripleList += PieceDistances.table(intArrayOf(pieces[a], pieces[b], pieces[c]))
                    }
                }
            }
        }
        pairs = pairIndices.toIntArray()
        pairTables = pairList.toTypedArray()
        triples = tripleIndices.toIntArray()
        tripleTables = tripleList.toTypedArray()
    }

    /**
     * The bound at [positions] (one per goal piece, in [pieces] order). It returns as soon as the
     * bound exceeds [budget], so a larger result is only known to exceed it.
     */
    fun of(
        positions: IntArray,
        budget: Int,
    ): Int {
        var corners = 0
        var edges = 0
        for (i in pieces.indices) {
            if (positions[i] != homes[i]) {
                if (PieceDistances.isCorner(pieces[i])) {
                    corners++
                } else {
                    edges++
                }
            }
        }
        var h = maxOf(perTurn(corners), perTurn(edges))
        if (h == 0) return 0
        val n = PieceDistances.POSITIONS
        for (t in tripleTables.indices) {
            val base = t * GROUP_OF_THREE
            val index = (positions[triples[base]] * n + positions[triples[base + 1]]) * n + positions[triples[base + 2]]
            h = maxOf(h, tripleTables[t][index].toInt())
            if (h > budget) return h
        }
        for (p in pairTables.indices) {
            val base = p * GROUP_OF_TWO
            h = maxOf(h, pairTables[p][positions[pairs[base]] * n + positions[pairs[base + 1]]].toInt())
            if (h > budget) return h
        }
        for (i in pieces.indices) h = maxOf(h, singles[i][positions[i]].toInt())
        return h
    }

    private fun perTurn(n: Int): Int = (n + Cubies.PER_FACE_TURN - 1) / Cubies.PER_FACE_TURN

    private companion object {
        val GROUP_OF_TWO = 2
        val GROUP_OF_THREE = 3
    }
}
