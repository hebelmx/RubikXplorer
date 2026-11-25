package dev.cubentia.domain.recognition.continuous

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.recognition.Lab
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Shading-invariant CIELAB conversion, WHITE-referenced hue distance, exact 8-per-color
 * min-cost balanced assignment, and 3×3 grid rotation helpers (`mapper.py`).
 *
 * Kept separate from `StickerClassifier.deltaE` (CIE76 stays untouched for Guided Scan).
 */
object ContinuousColorModel {
    private const val SHADING_TARGET_NORM = 210.0
    private const val INF = 1e18

    /** Source cell of each cell after one counter-clockwise quarter turn of a row-major 3×3. */
    private val CCW_INDEX = intArrayOf(2, 5, 8, 1, 4, 7, 0, 3, 6)

    /** Source cell of each cell after one clockwise quarter turn of a row-major 3×3. */
    private val CW_INDEX = intArrayOf(6, 3, 0, 7, 4, 1, 8, 5, 2)

    /**
     * Reference shading-invariant CIELAB prototypes used ONLY for global 24-rotation center
     * matching (`mapper.py` `REF_CENTER_LAB`).
     */
    val REF_CENTER_LAB: Map<Color, Lab> =
        mapOf(
            Color.WHITE to shadingInvariantLab(RgbColor(235.0, 235.0, 235.0)),
            Color.YELLOW to shadingInvariantLab(RgbColor(235.0, 215.0, 35.0)),
            Color.GREEN to shadingInvariantLab(RgbColor(40.0, 175.0, 75.0)),
            Color.BLUE to shadingInvariantLab(RgbColor(30.0, 95.0, 210.0)),
            Color.RED to shadingInvariantLab(RgbColor(208.0, 42.0, 46.0)),
            Color.ORANGE to shadingInvariantLab(RgbColor(240.0, 122.0, 34.0)),
        )

    /** Convert sRGB in `[0, 255]` to D65 CIELAB (`mapper.py` `rgb_to_lab_vec`). */
    fun rgbToLab(rgb: RgbColor): Lab {
        val r = linearize(rgb.r.coerceIn(0.0, 255.0) / 255.0)
        val g = linearize(rgb.g.coerceIn(0.0, 255.0) / 255.0)
        val b = linearize(rgb.b.coerceIn(0.0, 255.0) / 255.0)
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
        val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883
        val fx = labF(x)
        val fy = labF(y)
        val fz = labF(z)
        return Lab(
            l = (116.0 * fy - 16.0).coerceIn(0.0, 100.0),
            a = 500.0 * (fx - fy),
            b = 200.0 * (fy - fz),
        )
    }

    /**
     * Cancel scalar Lambertian shading (`s * RGB`) by L2-normalizing RGB to target norm `210.0`
     * before CIELAB conversion (`mapper.py` `shading_invariant_lab`).
     */
    fun shadingInvariantLab(rgb: RgbColor): Lab {
        val r = rgb.r.coerceIn(1.0, 255.0)
        val g = rgb.g.coerceIn(1.0, 255.0)
        val b = rgb.b.coerceIn(1.0, 255.0)
        val norm = sqrt(r * r + g * g + b * b)
        val scale = SHADING_TARGET_NORM / max(norm, 1.0)
        return rgbToLab(
            RgbColor(
                r = (r * scale).coerceIn(0.0, 255.0),
                g = (g * scale).coerceIn(0.0, 255.0),
                b = (b * scale).coerceIn(0.0, 255.0),
            ),
        )
    }

    /** Euclidean distance between two Lab triplets. */
    fun labDistance(
        left: Lab,
        right: Lab,
    ): Double {
        val dl = left.l - right.l
        val da = left.a - right.a
        val db = left.b - right.b
        return sqrt(dl * dl + da * da + db * db)
    }

    /**
     * CIEDE2000-style hue-weighted distance relative to the cube's own WHITE center `(whiteA, whiteB)`
     * (`mapper.py` `white_referenced_hue_dist`).
     */
    fun whiteReferencedHueDistance(
        lab: Lab,
        proto: Lab,
        whiteA: Double,
        whiteB: Double,
        isWhiteProto: Boolean,
    ): Double {
        val dL = lab.l - proto.l
        if (isWhiteProto) {
            val da = lab.a - proto.a
            val db = lab.b - proto.b
            return sqrt(0.35 * dL * dL + da * da + db * db)
        }
        val uxx = lab.a - whiteA
        val uxy = lab.b - whiteB
        val upx = proto.a - whiteA
        val upy = proto.b - whiteB
        val cx = hypot(uxx, uxy)
        val cp = hypot(upx, upy)
        if (cx < 6.0 || cp < 6.0) {
            val da = lab.a - proto.a
            val db = lab.b - proto.b
            return sqrt(0.35 * dL * dL + da * da + db * db)
        }
        val cosT = ((uxx * upx + uxy * upy) / (cx * cp)).coerceIn(-1.0, 1.0)
        val sinHalf = sqrt(max(0.0, 0.5 * (1.0 - cosT)))
        val dHue = 2.0 * sqrt(cx * cp) * sinHalf
        val dRad = abs(cx - cp)
        return sqrt(0.25 * dL * dL + (1.80 * dHue) * (1.80 * dHue) + (0.22 * dRad) * (0.22 * dRad))
    }

    /**
     * Exact global min-cost assignment of `nCells` cells to `k` colors with at most/exactly [cap]
     * cells per color (`cap = 8` for 48 non-center stickers across 6 colors), using successive
     * shortest augmenting paths (Bellman-Ford on the `k`-node residual graph).
     */
    fun solveBalanced8PerColor(
        costMatrix: Array<DoubleArray>,
        cap: Int = 8,
    ): IntArray {
        val nCells = costMatrix.size
        if (nCells == 0) return IntArray(0)
        val k = costMatrix[0].size
        if (k == 0) return IntArray(0)
        require(costMatrix.all { it.size == k }) { "All costMatrix rows must have length $k" }
        require(cap > 0) { "cap must be positive, got $cap" }
        require(cap.toLong() * k >= nCells) { "Infeasible cap=$cap for $nCells cells across $k colors" }
        val assign = IntArray(nCells)
        val counts = IntArray(k)

        for (i in 0 until nCells) {
            var bestC = 0
            var bestV = costMatrix[i][0]
            for (c in 1 until k) {
                if (costMatrix[i][c] < bestV) {
                    bestV = costMatrix[i][c]
                    bestC = c
                }
            }
            assign[i] = bestC
            counts[bestC]++
        }

        val edgeW = Array(k) { DoubleArray(k) }
        val bestCell = Array(k) { IntArray(k) }
        val dist = DoubleArray(k)
        val parent = IntArray(k)

        for (step in 0 until nCells) {
            var hasOver = false
            var hasUnder = false
            for (c in 0 until k) {
                if (counts[c] > cap) hasOver = true
                if (counts[c] < cap) hasUnder = true
            }
            if (!hasOver || !hasUnder) break

            for (u in 0 until k) {
                for (v in 0 until k) {
                    edgeW[u][v] = INF
                    bestCell[u][v] = -1
                }
            }
            for (i in 0 until nCells) {
                val u = assign[i]
                val base = costMatrix[i][u]
                for (v in 0 until k) {
                    if (u == v) continue
                    val delta = costMatrix[i][v] - base
                    if (delta < edgeW[u][v]) {
                        edgeW[u][v] = delta
                        bestCell[u][v] = i
                    }
                }
            }

            dist.fill(INF)
            parent.fill(-1)
            for (u in 0 until k) {
                if (counts[u] > cap) dist[u] = 0.0
            }
            for (bfStep in 0 until k - 1) {
                var updated = false
                for (u in 0 until k) {
                    if (dist[u] >= INF * 0.5) continue
                    for (v in 0 until k) {
                        val w = edgeW[u][v]
                        if (w < INF * 0.5 && dist[u] + w < dist[v] - 1e-12) {
                            dist[v] = dist[u] + w
                            parent[v] = u
                            updated = true
                        }
                    }
                }
                if (!updated) break
            }

            var tgt = -1
            var bestDist = INF
            for (v in 0 until k) {
                if (counts[v] < cap && dist[v] < bestDist) {
                    bestDist = dist[v]
                    tgt = v
                }
            }
            if (tgt < 0 || bestDist >= INF * 0.5) break

            val pathU = IntArray(k)
            val pathV = IntArray(k)
            val seen = BooleanArray(k)
            var pathLen = 0
            var cur = tgt
            while (parent[cur] != -1 && pathLen < k && !seen[cur]) {
                seen[cur] = true
                pathU[pathLen] = parent[cur]
                pathV[pathLen] = cur
                pathLen++
                cur = parent[cur]
            }
            if (parent[cur] != -1 || counts[cur] <= cap) break
            for (p in 0 until pathLen) {
                val u = pathU[p]
                val v = pathV[p]
                val cellI = bestCell[u][v]
                assign[cellI] = v
                counts[u]--
                counts[v]++
            }
        }
        return assign
    }

    /**
     * Roll [quad] corners left by [shiftLeft] (equivalent to `np.roll(corners, -shiftLeft, axis=0)`).
     */
    fun rollQuad(
        quad: Quad2D,
        shiftLeft: Int,
    ): Quad2D {
        val s = Math.floorMod(shiftLeft, 4)
        if (s == 0) return quad
        val c = quad.corners
        return Quad2D(List(4) { i -> c[(i + s) % 4] })
    }

    /** Rotate a 3×3 row-major list counter-clockwise by [ccwTurns] quarter turns (matches `rollQuad(..., ccwTurns)`). */
    fun <T> rotateGridCcw(
        cells: List<T>,
        ccwTurns: Int,
    ): List<T> {
        require(cells.size == 9) { "Expected 9 cells, got ${cells.size}" }
        val turns = Math.floorMod(ccwTurns, 4)
        var cur = cells
        repeat(turns) {
            val prev = cur
            cur = List(9) { idx -> prev[CCW_INDEX[idx]] }
        }
        return cur
    }

    /** Rotate a 3×3 row-major list clockwise by [cwTurns] quarter turns. */
    fun <T> rotateGridCw(
        cells: List<T>,
        cwTurns: Int,
    ): List<T> {
        require(cells.size == 9) { "Expected 9 cells, got ${cells.size}" }
        val turns = Math.floorMod(cwTurns, 4)
        var cur = cells
        repeat(turns) {
            val prev = cur
            cur = List(9) { idx -> prev[CW_INDEX[idx]] }
        }
        return cur
    }

    private fun linearize(c: Double): Double = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun labF(v: Double): Double = if (v > 0.0088564517) cbrt(max(v, 0.0)) else 7.787037 * v + 16.0 / 116.0
}
