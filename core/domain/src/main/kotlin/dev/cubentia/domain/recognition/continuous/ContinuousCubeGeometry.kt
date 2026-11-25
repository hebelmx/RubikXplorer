package dev.cubentia.domain.recognition.continuous

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import kotlin.math.abs
import kotlin.math.roundToInt

/** Kind of physical invariant violation detected on a partial or full 54-sticker net. */
enum class LocalInvariantKind {
    COLOR_COUNT_EXCEEDED,
    IMPOSSIBLE_EDGE,
    DUPLICATE_EDGE,
    IMPOSSIBLE_CORNER_PAIR,
    INVALID_CORNER_CHIRALITY,
    DUPLICATE_CORNER,
}

/** One immediate physical invariant violation with the offending slots. */
data class LocalInvariantViolation(
    val kind: LocalInvariantKind,
    val slots: List<FaceSlot>,
)

/**
 * Pure-JVM cube geometry, 24 SO(3) fragment-slot rotations, shared-edge face adjacency,
 * and immediate local cubie invariants (`cube_model.py`).
 */
object ContinuousCubeGeometry {
    internal data class Vec3(
        val x: Double,
        val y: Double,
        val z: Double,
    ) {
        operator fun plus(o: Vec3): Vec3 = Vec3(x + o.x, y + o.y, z + o.z)

        operator fun minus(o: Vec3): Vec3 = Vec3(x - o.x, y - o.y, z - o.z)

        operator fun times(s: Double): Vec3 = Vec3(x * s, y * s, z * s)

        fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z

        fun cross(o: Vec3): Vec3 = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

        fun closeTo(o: Vec3): Boolean = abs(x - o.x) < 1e-6 && abs(y - o.y) < 1e-6 && abs(z - o.z) < 1e-6
    }

    internal data class FaceBasis(
        val normal: Vec3,
        val right: Vec3,
        val down: Vec3,
    )

    internal val FACE_BASIS: Map<Face, FaceBasis> =
        mapOf(
            Face.U to FaceBasis(Vec3(0.0, 1.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0)),
            Face.R to FaceBasis(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 0.0, -1.0), Vec3(0.0, -1.0, 0.0)),
            Face.F to FaceBasis(Vec3(0.0, 0.0, 1.0), Vec3(1.0, 0.0, 0.0), Vec3(0.0, -1.0, 0.0)),
            Face.D to FaceBasis(Vec3(0.0, -1.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 0.0, -1.0)),
            Face.L to FaceBasis(Vec3(-1.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0), Vec3(0.0, -1.0, 0.0)),
            Face.B to FaceBasis(Vec3(0.0, 0.0, -1.0), Vec3(-1.0, 0.0, 0.0), Vec3(0.0, -1.0, 0.0)),
        )

    /** Opposite color on a standard Western cube. */
    val OPPOSITE_COLOR: Map<Color, Color> =
        mapOf(
            Color.WHITE to Color.YELLOW,
            Color.YELLOW to Color.WHITE,
            Color.RED to Color.ORANGE,
            Color.ORANGE to Color.RED,
            Color.GREEN to Color.BLUE,
            Color.BLUE to Color.GREEN,
        )

    /** Number of proper whole-cube rotations in SO(3). */
    const val SO3_COUNT = 24

    internal class IntMat3(
        val m: IntArray,
    ) {
        fun transform(v: Vec3): Vec3 =
            Vec3(
                m[0] * v.x + m[1] * v.y + m[2] * v.z,
                m[3] * v.x + m[4] * v.y + m[5] * v.z,
                m[6] * v.x + m[7] * v.y + m[8] * v.z,
            )
    }

    internal val SO3_MATRICES: List<IntMat3> = buildSo3Matrices()

    /**
     * For each `(face, edgeIdx)` (`0=top, 1=right, 2=bottom, 3=left` on a CW quad), the adjacent
     * `(neighborFace, neighborEdgeIdx)` on a rigid 3D cube.
     */
    val FACE_ADJACENCY: Map<Pair<Face, Int>, Pair<Face, Int>> = buildAdjacency()

    /** The 12 edge cubies as pairs of [FaceSlot]. */
    val EDGE_CUBIES: List<Pair<FaceSlot, FaceSlot>>

    /** The 8 corner cubies as clockwise triples of [FaceSlot] when viewed from outside. */
    val CORNER_CUBIES: List<Triple<FaceSlot, FaceSlot, FaceSlot>>

    private val VALID_CORNER_CYCLES: Set<Triple<Color, Color, Color>>

    // Precomputed rotation lookup tables: [rotIdx][face.ordinal] and [rotIdx][face.ordinal * 9 + cellIdx].
    private val ROTATED_FACE: Array<Array<Face>>
    private val ROTATED_SLOT: Array<Array<FaceSlot>>

    init {
        val (edges, corners) = buildCubieTables()
        EDGE_CUBIES = edges
        CORNER_CUBIES = corners
        VALID_CORNER_CYCLES =
            corners
                .map { (a, b, c) ->
                    canonicalCornerCycle(Color.home(a.face), Color.home(b.face), Color.home(c.face))
                }.toSet()

        ROTATED_FACE =
            Array(SO3_COUNT) { r ->
                val mat = SO3_MATRICES[r]
                Array(Face.entries.size) { fOrd ->
                    val face = Face.entries[fOrd]
                    val n = mat.transform(FACE_BASIS.getValue(face).normal)
                    Face.entries.maxBy { tgt -> FACE_BASIS.getValue(tgt).normal.dot(n) }
                }
            }
        ROTATED_SLOT =
            Array(SO3_COUNT) { r ->
                val mat = SO3_MATRICES[r]
                Array(Face.entries.size * 9) { flat ->
                    val face = Face.entries[flat / 9]
                    val cellIdx = flat % 9
                    val tgtFace = ROTATED_FACE[r][face.ordinal]
                    val p = mat.transform(stickerCenter3d(face, cellIdx))
                    val basis = FACE_BASIS.getValue(tgtFace)
                    val col = (p.dot(basis.right) * 1.5).roundToInt() + 1
                    val row = (p.dot(basis.down) * 1.5).roundToInt() + 1
                    FaceSlot(tgtFace, row * 3 + col)
                }
            }
    }

    /** Map [face] under SO(3) rotation index [rotIdx] (`0..23`). */
    fun rotateFace(
        face: Face,
        rotIdx: Int,
    ): Face {
        require(rotIdx in 0 until SO3_COUNT) { "rotIdx out of bounds: $rotIdx" }
        return ROTATED_FACE[rotIdx][face.ordinal]
    }

    /** Map `(face, cellIdx)` under SO(3) rotation index [rotIdx] (`0..23`). */
    fun rotateSlot(
        face: Face,
        cellIdx: Int,
        rotIdx: Int,
    ): FaceSlot {
        require(rotIdx in 0 until SO3_COUNT) { "rotIdx out of bounds: $rotIdx" }
        require(cellIdx in 0 until 9) { "cellIdx out of bounds: $cellIdx" }
        return ROTATED_SLOT[rotIdx][face.ordinal * 9 + cellIdx]
    }

    /** 3D center of sticker `(face, cellIdx)` on `[-1, 1]^3`. */
    internal fun stickerCenter3d(
        face: Face,
        cellIdx: Int,
    ): Vec3 {
        val row = cellIdx / 3
        val col = cellIdx % 3
        val basis = FACE_BASIS.getValue(face)
        val u = (col - 1) * (2.0 / 3.0)
        val v = (row - 1) * (2.0 / 3.0)
        return basis.normal + basis.right * u + basis.down * v
    }

    /** 3D corners `[TL, TR, BR, BL]` of [face] on `[-1, 1]^3`. */
    internal fun faceQuad3d(face: Face): List<Vec3> {
        val b = FACE_BASIS.getValue(face)
        return listOf(
            b.normal - b.right - b.down,
            b.normal + b.right - b.down,
            b.normal + b.right + b.down,
            b.normal - b.right + b.down,
        )
    }

    /** True if a 3×3 face matches itself under any non-identity 90/180/270-deg rotation (or has blanks). */
    fun isFaceRotationallySymmetric(cells: List<Color?>): Boolean {
        if (cells.size != 9 || cells.any { it == null }) return true
        for (k in 1..3) {
            if (ContinuousColorModel.rotateGridCw(cells, k) == cells) return true
        }
        return false
    }

    /**
     * Check immediate physical invariants on a partial or full 54-sticker net (`cube_model.py`).
     *
     * Checks:
     * 1. Per-color count `<= 9`.
     * 2. Edge cubies: no same or opposite color pair; no duplicate edge cubie.
     * 3. Corner cubies: no same or opposite pair (even when 2 of 3 observed); valid CW chirality
     *    when all 3 observed; no duplicate corner cubie.
     */
    fun checkLocalInvariants(partialNet: Map<Face, List<Color?>>): List<LocalInvariantViolation> {
        val violations = mutableListOf<LocalInvariantViolation>()

        // 1. Per-color count <= 9
        val byColor = Color.entries.associateWith { mutableListOf<FaceSlot>() }
        for (f in Face.entries) {
            val cells = partialNet[f] ?: continue
            for (i in cells.indices) {
                val col = cells[i]
                if (col != null) byColor.getValue(col).add(FaceSlot(f, i))
            }
        }
        for (col in Color.entries) {
            val slots = byColor.getValue(col)
            if (slots.size > 9) {
                violations.add(LocalInvariantViolation(LocalInvariantKind.COLOR_COUNT_EXCEEDED, slots.toList()))
            }
        }

        // 2. Edge cubies
        val seenEdges = HashMap<Int, Pair<FaceSlot, FaceSlot>>()
        for ((a, b) in EDGE_CUBIES) {
            val ca = partialNet[a.face]?.getOrNull(a.cellIndex) ?: continue
            val cb = partialNet[b.face]?.getOrNull(b.cellIndex) ?: continue
            if (ca == cb || OPPOSITE_COLOR.getValue(ca) == cb) {
                violations.add(LocalInvariantViolation(LocalInvariantKind.IMPOSSIBLE_EDGE, listOf(a, b)))
                continue
            }
            val key = unorderedColorPairKey(ca, cb)
            val prev = seenEdges[key]
            if (prev != null) {
                violations.add(
                    LocalInvariantViolation(
                        LocalInvariantKind.DUPLICATE_EDGE,
                        listOf(prev.first, prev.second, a, b),
                    ),
                )
            } else {
                seenEdges[key] = a to b
            }
        }

        // 3. Corner cubies
        val seenCorners = HashMap<Triple<Color, Color, Color>, Triple<FaceSlot, FaceSlot, FaceSlot>>()
        for (triple in CORNER_CUBIES) {
            val (a, b, c) = triple
            val obs = ArrayList<Pair<FaceSlot, Color>>(3)
            partialNet[a.face]?.getOrNull(a.cellIndex)?.let { obs.add(a to it) }
            partialNet[b.face]?.getOrNull(b.cellIndex)?.let { obs.add(b to it) }
            partialNet[c.face]?.getOrNull(c.cellIndex)?.let { obs.add(c to it) }
            if (obs.size < 2) continue

            var pairBad = false
            for (i in obs.indices) {
                for (j in i + 1 until obs.size) {
                    val (s1, c1) = obs[i]
                    val (s2, c2) = obs[j]
                    if (c1 == c2 || OPPOSITE_COLOR.getValue(c1) == c2) {
                        violations.add(
                            LocalInvariantViolation(LocalInvariantKind.IMPOSSIBLE_CORNER_PAIR, listOf(s1, s2)),
                        )
                        pairBad = true
                    }
                }
            }
            if (pairBad || obs.size < 3) continue

            val cyc = canonicalCornerCycle(obs[0].second, obs[1].second, obs[2].second)
            if (cyc !in VALID_CORNER_CYCLES) {
                violations.add(LocalInvariantViolation(LocalInvariantKind.INVALID_CORNER_CHIRALITY, listOf(a, b, c)))
            } else {
                val prev = seenCorners[cyc]
                if (prev != null) {
                    violations.add(
                        LocalInvariantViolation(
                            LocalInvariantKind.DUPLICATE_CORNER,
                            listOf(prev.first, prev.second, prev.third, a, b, c),
                        ),
                    )
                } else {
                    seenCorners[cyc] = triple
                }
            }
        }
        return violations
    }

    private fun unorderedColorPairKey(
        a: Color,
        b: Color,
    ): Int {
        val lo = minOf(a.ordinal, b.ordinal)
        val hi = maxOf(a.ordinal, b.ordinal)
        return lo * 8 + hi
    }

    private fun canonicalCornerCycle(
        c0: Color,
        c1: Color,
        c2: Color,
    ): Triple<Color, Color, Color> {
        val r0 = Triple(c0, c1, c2)
        val r1 = Triple(c1, c2, c0)
        val r2 = Triple(c2, c0, c1)
        var best = r0
        if (compareTriples(r1, best) < 0) best = r1
        if (compareTriples(r2, best) < 0) best = r2
        return best
    }

    private fun compareTriples(
        a: Triple<Color, Color, Color>,
        b: Triple<Color, Color, Color>,
    ): Int = compareValuesBy(a, b, { it.first.ordinal }, { it.second.ordinal }, { it.third.ordinal })

    private fun cubieCoord3d(
        face: Face,
        cellIdx: Int,
    ): Triple<Int, Int, Int> {
        val row = cellIdx / 3
        val col = cellIdx % 3
        val b = FACE_BASIS.getValue(face)
        val p = b.normal + b.right * (col - 1).toDouble() + b.down * (row - 1).toDouble()
        return Triple(p.x.roundToInt(), p.y.roundToInt(), p.z.roundToInt())
    }

    private fun buildCubieTables(): Pair<List<Pair<FaceSlot, FaceSlot>>, List<Triple<FaceSlot, FaceSlot, FaceSlot>>> {
        val byCubie = LinkedHashMap<Triple<Int, Int, Int>, MutableList<FaceSlot>>()
        for (f in Face.entries) {
            for (i in 0 until 9) {
                val coord = cubieCoord3d(f, i)
                byCubie.getOrPut(coord) { mutableListOf() }.add(FaceSlot(f, i))
            }
        }
        val edges = mutableListOf<Pair<FaceSlot, FaceSlot>>()
        val corners = mutableListOf<Triple<FaceSlot, FaceSlot, FaceSlot>>()
        val sortedCoords = byCubie.keys.sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
        for (coord in sortedCoords) {
            val slots = byCubie.getValue(coord)
            val nz = listOf(coord.first, coord.second, coord.third).count { it != 0 }
            if (nz == 2) {
                edges.add(slots[0] to slots[1])
            } else if (nz == 3) {
                val outward = Vec3(coord.first.toDouble(), coord.second.toDouble(), coord.third.toDouble())
                val s0 = slots[0]
                val s1 = slots[1]
                val s2 = slots[2]
                val n0 = FACE_BASIS.getValue(s0.face).normal
                val n1 = FACE_BASIS.getValue(s1.face).normal
                if (n0.cross(n1).dot(outward) > 0.0) {
                    corners.add(Triple(s0, s2, s1))
                } else {
                    corners.add(Triple(s0, s1, s2))
                }
            }
        }
        return edges to corners
    }

    private fun buildAdjacency(): Map<Pair<Face, Int>, Pair<Face, Int>> {
        val quads = Face.entries.associateWith { faceQuad3d(it) }
        val adj = LinkedHashMap<Pair<Face, Int>, Pair<Face, Int>>()
        for (f1 in Face.entries) {
            val q1 = quads.getValue(f1)
            for (e1 in 0 until 4) {
                val a1 = q1[e1]
                val b1 = q1[(e1 + 1) % 4]
                for (f2 in Face.entries) {
                    if (f2 == f1) continue
                    val q2 = quads.getValue(f2)
                    for (e2 in 0 until 4) {
                        val a2 = q2[e2]
                        val b2 = q2[(e2 + 1) % 4]
                        if (a1.closeTo(b2) && b1.closeTo(a2)) {
                            adj[f1 to e1] = f2 to e2
                        }
                    }
                }
            }
        }
        return adj
    }

    private fun buildSo3Matrices(): List<IntMat3> {
        val axes =
            listOf(
                intArrayOf(1, 0, 0),
                intArrayOf(-1, 0, 0),
                intArrayOf(0, 1, 0),
                intArrayOf(0, -1, 0),
                intArrayOf(0, 0, 1),
                intArrayOf(0, 0, -1),
            )
        val rots = ArrayList<IntMat3>(SO3_COUNT)
        for (x in axes) {
            for (y in axes) {
                if (x[0] * y[0] + x[1] * y[1] + x[2] * y[2] != 0) continue
                val z =
                    intArrayOf(
                        x[1] * y[2] - x[2] * y[1],
                        x[2] * y[0] - x[0] * y[2],
                        x[0] * y[1] - x[1] * y[0],
                    )
                // Columns are x, y, z
                rots.add(
                    IntMat3(
                        intArrayOf(
                            x[0],
                            y[0],
                            z[0],
                            x[1],
                            y[1],
                            z[1],
                            x[2],
                            y[2],
                            z[2],
                        ),
                    ),
                )
            }
        }
        return rots
    }
}
