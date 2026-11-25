package dev.cubentia.domain.recognition.continuous

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure-JVM 2D projective geometry primitives matching `lattice_fit.py` and `mapper.py`
 * without any OpenCV or native dependency.
 */
object ProjectiveGeometry {
    private const val SINGULAR_EPS = 1e-12
    private const val JACOBI_EPS = 1e-15
    private const val JACOBI_MAX_SWEEPS = 60
    private const val LM_ITERS = 8

    /** Distance between two 2D points. */
    fun distance(
        a: Point2D,
        b: Point2D,
    ): Double = hypot(a.x - b.x, a.y - b.y)

    /** Signed twice-area of a polygon in image coords (Y down): positive means clockwise. */
    fun signedArea2(points: List<Point2D>): Double {
        val n = points.size
        if (n < 3) return 0.0
        var sum = 0.0
        for (i in 0 until n) {
            val a = points[i]
            val b = points[(i + 1) % n]
            sum += a.x * b.y - b.x * a.y
        }
        return sum
    }

    /** Ensure clockwise corner winding in image coordinates (Y down). */
    fun ensureClockwise(corners: List<Point2D>): List<Point2D> = if (signedArea2(corners) < 0.0) corners.reversed() else corners

    /** Ensure clockwise corner winding on a [Quad2D]. */
    fun ensureClockwise(quad: Quad2D): Quad2D = Quad2D(ensureClockwise(quad.corners))

    /** Unsigned area of a polygon. */
    fun polygonArea(points: List<Point2D>): Double = 0.5 * abs(signedArea2(points))

    /** Unsigned area of a [Quad2D]. */
    fun quadArea(quad: Quad2D): Double = polygonArea(quad.corners)

    /** True iff [quad] is strictly convex with finite coordinates. */
    fun isConvexQuad(quad: Quad2D): Boolean {
        if (!quad.isFinite()) return false
        val c = quad.corners
        var pos = 0
        var neg = 0
        for (k in 0 until 4) {
            val a = c[k]
            val b = c[(k + 1) % 4]
            val d = c[(k + 2) % 4]
            val cross = (b.x - a.x) * (d.y - b.y) - (b.y - a.y) * (d.x - b.x)
            if (cross > 0.0) {
                pos++
            } else if (cross < 0.0) {
                neg++
            } else {
                return false
            }
        }
        return pos == 4 || neg == 4
    }

    /**
     * Reject degenerate, self-intersecting, or wildly skewed face quads (`lattice_fit.py`).
     */
    fun isValidFaceQuad(
        quad: Quad2D,
        dMed: Double,
    ): Boolean {
        if (!dMed.isFinite() || dMed <= 0.0 || !isConvexQuad(quad)) return false
        val c = quad.corners
        val ex = DoubleArray(4)
        val ey = DoubleArray(4)
        val lens = DoubleArray(4)
        var minLen = Double.POSITIVE_INFINITY
        var maxLen = 0.0
        for (k in 0 until 4) {
            val next = c[(k + 1) % 4]
            val dx = next.x - c[k].x
            val dy = next.y - c[k].y
            val len = hypot(dx, dy)
            ex[k] = dx
            ey[k] = dy
            lens[k] = len
            if (len < minLen) minLen = len
            if (len > maxLen) maxLen = len
        }
        if (minLen < 1.15 * dMed || maxLen > 5.4 * dMed) return false
        if (maxLen / max(1e-6, minLen) > 3.2) return false
        if (max(lens[0], lens[2]) / max(1e-6, min(lens[0], lens[2])) > 1.55) return false
        if (max(lens[1], lens[3]) / max(1e-6, min(lens[1], lens[3])) > 1.55) return false
        for (k in 0 until 4) {
            val prev = (k + 3) % 4
            val lPrev = max(1e-6, lens[prev])
            val lCur = max(1e-6, lens[k])
            val ux = -ex[prev] / lPrev
            val uy = -ey[prev] / lPrev
            val vx = ex[k] / lCur
            val vy = ey[k] / lCur
            if (abs(ux * vy - uy * vx) < 0.32) return false
        }
        return true
    }

    /**
     * Intersection area of two convex quads via Sutherland-Hodgman polygon clipping.
     */
    fun convexIntersectionArea(
        q1: Quad2D,
        q2: Quad2D,
    ): Double {
        if (!q1.isFinite() || !q2.isFinite()) return 0.0
        var subject = ensureClockwise(q1.corners)
        val clip = ensureClockwise(q2.corners)
        for (i in 0 until 4) {
            if (subject.isEmpty()) return 0.0
            val a = clip[i]
            val b = clip[(i + 1) % 4]
            subject = clipAgainstEdge(subject, a, b)
        }
        return polygonArea(subject)
    }

    private fun clipAgainstEdge(
        poly: List<Point2D>,
        a: Point2D,
        b: Point2D,
    ): List<Point2D> {
        val out = ArrayList<Point2D>(poly.size + 1)
        val abx = b.x - a.x
        val aby = b.y - a.y
        var s = poly.last()
        var sInside = abx * (s.y - a.y) - aby * (s.x - a.x) >= 0.0
        for (e in poly) {
            val eInside = abx * (e.y - a.y) - aby * (e.x - a.x) >= 0.0
            if (eInside) {
                if (!sInside) {
                    intersectSegmentLine(s, e, a, b)?.let(out::add)
                }
                out.add(e)
            } else if (sInside) {
                intersectSegmentLine(s, e, a, b)?.let(out::add)
            }
            s = e
            sInside = eInside
        }
        return out
    }

    private fun intersectSegmentLine(
        s: Point2D,
        e: Point2D,
        a: Point2D,
        b: Point2D,
    ): Point2D? {
        val dx1 = e.x - s.x
        val dy1 = e.y - s.y
        val dx2 = b.x - a.x
        val dy2 = b.y - a.y
        val denom = dx1 * dy2 - dy1 * dx2
        if (abs(denom) < SINGULAR_EPS) return null
        val t = ((a.x - s.x) * dy2 - (a.y - s.y) * dx2) / denom
        return Point2D(s.x + t * dx1, s.y + t * dy1)
    }

    /** True if [quad] overlaps any quad in [existing] by more than [maxOverlapFrac] of the smaller area. */
    fun overlapsExisting(
        quad: Quad2D,
        existing: List<Quad2D>,
        maxOverlapFrac: Double = 0.08,
    ): Boolean {
        if (existing.isEmpty()) return false
        val a1 = quadArea(quad)
        for (eq in existing) {
            val inter = convexIntersectionArea(quad, eq)
            if (inter > maxOverlapFrac * min(a1, quadArea(eq))) return true
        }
        return false
    }

    /**
     * Intrinsics-free viewing-angle / foreshortening quality in `[0, 1]` and mean edge length (`mapper.py`).
     */
    fun quadQuality(quad: Quad2D): Pair<Double, Double> {
        if (!quad.isFinite()) return 0.0 to 0.0
        val c = quad.corners
        val ex = DoubleArray(4)
        val ey = DoubleArray(4)
        val lens = DoubleArray(4)
        var minLen = Double.POSITIVE_INFINITY
        var sumLen = 0.0
        for (k in 0 until 4) {
            val next = c[(k + 1) % 4]
            val dx = next.x - c[k].x
            val dy = next.y - c[k].y
            val len = hypot(dx, dy)
            ex[k] = dx
            ey[k] = dy
            lens[k] = len
            if (len < minLen) minLen = len
            sumLen += len
        }
        val meanLen = sumLen / 4.0
        if (minLen < 18.0) return 0.0 to meanLen
        val hLen = 0.5 * (lens[0] + lens[2])
        val vLen = 0.5 * (lens[1] + lens[3])
        val aspect = min(hLen, vLen) / max(hLen, vLen)
        var minSin = Double.POSITIVE_INFINITY
        for (k in 0 until 4) {
            val prev = (k + 3) % 4
            val ux = -ex[prev] / max(1e-6, lens[prev])
            val uy = -ey[prev] / max(1e-6, lens[prev])
            val vx = ex[k] / max(1e-6, lens[k])
            val vy = ey[k] / max(1e-6, lens[k])
            val cross = abs(ux * vy - uy * vx)
            if (cross < minSin) minSin = cross
        }
        val raw = aspect * minSin
        val qAngle = ((raw - 0.22) / 0.62).coerceIn(0.05, 1.0)
        return qAngle to meanLen
    }

    /**
     * If two clockwise quads share a 2D cube edge (within `0.14 * min(lenA, lenB)`), return `(edgeA, edgeB)`.
     */
    fun sharedEdge(
        quadA: Quad2D,
        quadB: Quad2D,
    ): Pair<Int, Int>? {
        val lenA = quadQuality(quadA).second
        val lenB = quadQuality(quadB).second
        val tol = 0.14 * min(lenA, lenB)
        var best: Pair<Int, Int>? = null
        var bestErr = tol
        val ca = quadA.corners
        val cb = quadB.corners
        for (ea in 0 until 4) {
            val a0 = ca[ea]
            val a1 = ca[(ea + 1) % 4]
            for (eb in 0 until 4) {
                val b0 = cb[eb]
                val b1 = cb[(eb + 1) % 4]
                val err = 0.5 * (distance(a0, b1) + distance(a1, b0))
                if (err < bestErr) {
                    bestErr = err
                    best = ea to eb
                }
            }
        }
        return best
    }

    /**
     * True if clockwise [quad] shares a 2D cube edge (within `0.16 * min(meanL, eMeanL)`) with any quad in [existing].
     */
    fun sharesEdgeWithAny(
        quad: Quad2D,
        existing: List<Quad2D>,
    ): Boolean {
        if (existing.isEmpty()) return false
        val ca = quad.corners
        val meanL = meanEdgeLength(ca)
        for (eq in existing) {
            val cb = eq.corners
            val tol = 0.16 * min(meanL, meanEdgeLength(cb))
            for (ea in 0 until 4) {
                val a0 = ca[ea]
                val a1 = ca[(ea + 1) % 4]
                for (eb in 0 until 4) {
                    val b0 = cb[eb]
                    val b1 = cb[(eb + 1) % 4]
                    if (0.5 * (distance(a0, b1) + distance(a1, b0)) < tol) return true
                }
            }
        }
        return false
    }

    private fun meanEdgeLength(corners: List<Point2D>): Double {
        var sum = 0.0
        for (k in 0 until 4) {
            sum += distance(corners[k], corners[(k + 1) % 4])
        }
        return sum / 4.0
    }

    /**
     * Pure-Kotlin Hartley-normalized DLT homography solver with cyclic Jacobi eigendecomposition
     * and Levenberg-Marquardt reprojection refinement. Returns a 9-element row-major 3×3 matrix `H`
     * normalized so `H[8] = 1.0`, or `null` if degenerate.
     */
    fun findHomography(
        src: List<Point2D>,
        dst: List<Point2D>,
    ): DoubleArray? {
        val n = src.size
        if (n < 4 || dst.size != n) return null
        if (src.any { !it.isFinite() } || dst.any { !it.isFinite() }) return null

        val normSrc = normalizePoints(src) ?: return null
        val normDst = normalizePoints(dst) ?: return null

        // Build symmetric 9x9 normal matrix M = A^T A
        val m = Array(9) { DoubleArray(9) }
        val row1 = DoubleArray(9)
        val row2 = DoubleArray(9)
        for (i in 0 until n) {
            val x = normSrc.points[i].x
            val y = normSrc.points[i].y
            val u = normDst.points[i].x
            val v = normDst.points[i].y

            row1[0] = -x
            row1[1] = -y
            row1[2] = -1.0
            row1[3] = 0.0
            row1[4] = 0.0
            row1[5] = 0.0
            row1[6] = u * x
            row1[7] = u * y
            row1[8] = u

            row2[0] = 0.0
            row2[1] = 0.0
            row2[2] = 0.0
            row2[3] = -x
            row2[4] = -y
            row2[5] = -1.0
            row2[6] = v * x
            row2[7] = v * y
            row2[8] = v

            for (r in 0 until 9) {
                val r1 = row1[r]
                val r2 = row2[r]
                for (c in r until 9) {
                    val value = r1 * row1[c] + r2 * row2[c]
                    m[r][c] += value
                    if (r != c) m[c][r] += value
                }
            }
        }

        val hNorm = smallestEigenvector(m) ?: return null
        val denorm = multiply3x3(multiply3x3(normDst.inverse, hNorm), normSrc.transform)
        val normalized = normalizeHomography(denorm) ?: return null
        return if (n > 4) refineHomographyLm(normalized, src, dst) else normalized
    }

    /** Project [point] through a 3×3 row-major homography [h], or `null` if at infinity. */
    fun perspectiveTransform(
        h: DoubleArray,
        point: Point2D,
    ): Point2D? {
        val w = h[6] * point.x + h[7] * point.y + h[8]
        if (!w.isFinite() || abs(w) < SINGULAR_EPS) return null
        val x = (h[0] * point.x + h[1] * point.y + h[2]) / w
        val y = (h[3] * point.x + h[4] * point.y + h[5]) / w
        val result = Point2D(x, y)
        return result.takeIf { it.isFinite() }
    }

    private data class NormalizedPoints(
        val points: List<Point2D>,
        val transform: DoubleArray,
        val inverse: DoubleArray,
    )

    private fun normalizePoints(points: List<Point2D>): NormalizedPoints? {
        val n = points.size
        var mx = 0.0
        var my = 0.0
        for (p in points) {
            mx += p.x
            my += p.y
        }
        mx /= n
        my /= n
        var meanDist = 0.0
        for (p in points) {
            meanDist += hypot(p.x - mx, p.y - my)
        }
        meanDist /= n
        if (!meanDist.isFinite() || meanDist < 1e-9) return null
        val scale = sqrt(2.0) / meanDist
        val invScale = 1.0 / scale
        val normPts = points.map { Point2D((it.x - mx) * scale, (it.y - my) * scale) }
        val t =
            doubleArrayOf(
                scale,
                0.0,
                -scale * mx,
                0.0,
                scale,
                -scale * my,
                0.0,
                0.0,
                1.0,
            )
        val tInv =
            doubleArrayOf(
                invScale,
                0.0,
                mx,
                0.0,
                invScale,
                my,
                0.0,
                0.0,
                1.0,
            )
        return NormalizedPoints(normPts, t, tInv)
    }

    private fun smallestEigenvector(sym: Array<DoubleArray>): DoubleArray? {
        val n = 9
        val a = Array(n) { r -> sym[r].copyOf() }
        val v = Array(n) { r -> DoubleArray(n) { c -> if (r == c) 1.0 else 0.0 } }

        for (sweep in 0 until JACOBI_MAX_SWEEPS) {
            var maxOff = 0.0
            for (p in 0 until n) {
                for (q in p + 1 until n) {
                    val apq = a[p][q]
                    val absApq = abs(apq)
                    if (absApq > maxOff) maxOff = absApq
                    if (absApq <= JACOBI_EPS) continue

                    val app = a[p][p]
                    val aqq = a[q][q]
                    val tau = (aqq - app) / (2.0 * apq)
                    val t =
                        if (tau >= 0.0) {
                            1.0 / (tau + sqrt(1.0 + tau * tau))
                        } else {
                            -1.0 / (-tau + sqrt(1.0 + tau * tau))
                        }
                    val c = 1.0 / sqrt(1.0 + t * t)
                    val s = t * c

                    a[p][p] = app - t * apq
                    a[q][q] = aqq + t * apq
                    a[p][q] = 0.0
                    a[q][p] = 0.0

                    for (k in 0 until n) {
                        if (k == p || k == q) continue
                        val akp = a[k][p]
                        val akq = a[k][q]
                        val newAkp = c * akp - s * akq
                        val newAkq = s * akp + c * akq
                        a[k][p] = newAkp
                        a[p][k] = newAkp
                        a[k][q] = newAkq
                        a[q][k] = newAkq
                    }

                    for (k in 0 until n) {
                        val vkp = v[k][p]
                        val vkq = v[k][q]
                        v[k][p] = c * vkp - s * vkq
                        v[k][q] = s * vkp + c * vkq
                    }
                }
            }
            if (maxOff <= 1e-14) break
        }

        var minIdx = 0
        var minVal = a[0][0]
        for (i in 1 until n) {
            if (a[i][i] < minVal) {
                minVal = a[i][i]
                minIdx = i
            }
        }
        val vec = DoubleArray(n) { r -> v[r][minIdx] }
        return if (vec.all { it.isFinite() }) vec else null
    }

    private fun multiply3x3(
        a: DoubleArray,
        b: DoubleArray,
    ): DoubleArray {
        val out = DoubleArray(9)
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                out[r * 3 + c] =
                    a[r * 3 + 0] * b[0 * 3 + c] +
                    a[r * 3 + 1] * b[1 * 3 + c] +
                    a[r * 3 + 2] * b[2 * 3 + c]
            }
        }
        return out
    }

    private fun normalizeHomography(h: DoubleArray): DoubleArray? {
        val h22 = h[8]
        if (!h22.isFinite() || abs(h22) < SINGULAR_EPS) return null
        val out = DoubleArray(9) { h[it] / h22 }
        if (out.any { !it.isFinite() }) return null
        val det =
            out[0] * (out[4] * out[8] - out[5] * out[7]) -
                out[1] * (out[3] * out[8] - out[5] * out[6]) +
                out[2] * (out[3] * out[7] - out[4] * out[6])
        if (!det.isFinite() || abs(det) < SINGULAR_EPS) return null
        return out
    }

    /** Levenberg-Marquardt refinement of the 8-parameter homography minimizing geometric reprojection error. */
    private fun refineHomographyLm(
        initial: DoubleArray,
        src: List<Point2D>,
        dst: List<Point2D>,
    ): DoubleArray {
        var h = initial.copyOf()
        var err = reprojectionErrorSq(h, src, dst)
        var lambda = 1e-3
        val jtj = Array(8) { DoubleArray(8) }
        val jtr = DoubleArray(8)
        val jrX = DoubleArray(8)
        val jrY = DoubleArray(8)

        for (iter in 0 until LM_ITERS) {
            for (r in 0 until 8) {
                jtr[r] = 0.0
                for (c in 0 until 8) jtj[r][c] = 0.0
            }
            var validJac = true
            for (i in src.indices) {
                val x = src[i].x
                val y = src[i].y
                val w = h[6] * x + h[7] * y + 1.0
                if (abs(w) < SINGULAR_EPS) {
                    validJac = false
                    break
                }
                val invW = 1.0 / w
                val px = (h[0] * x + h[1] * y + h[2]) * invW
                val py = (h[3] * x + h[4] * y + h[5]) * invW
                val rx = px - dst[i].x
                val ry = py - dst[i].y

                jrX[0] = x * invW
                jrX[1] = y * invW
                jrX[2] = invW
                jrX[3] = 0.0
                jrX[4] = 0.0
                jrX[5] = 0.0
                jrX[6] = -px * x * invW
                jrX[7] = -px * y * invW

                jrY[0] = 0.0
                jrY[1] = 0.0
                jrY[2] = 0.0
                jrY[3] = x * invW
                jrY[4] = y * invW
                jrY[5] = invW
                jrY[6] = -py * x * invW
                jrY[7] = -py * y * invW

                for (r in 0 until 8) {
                    jtr[r] += jrX[r] * rx + jrY[r] * ry
                    for (c in r until 8) {
                        val v = jrX[r] * jrX[c] + jrY[r] * jrY[c]
                        jtj[r][c] += v
                        if (r != c) jtj[c][r] += v
                    }
                }
            }
            if (!validJac) break
            val damped = Array(8) { r -> jtj[r].copyOf() }
            for (r in 0 until 8) {
                damped[r][r] += lambda * (damped[r][r] + 1.0)
            }
            val negRhs = DoubleArray(8) { -jtr[it] }
            val step = solveLinearSystem8(damped, negRhs) ?: break
            val cand = h.copyOf()
            for (k in 0 until 8) cand[k] += step[k]
            val candErr = reprojectionErrorSq(cand, src, dst)
            if (candErr < err && normalizeHomography(cand) != null) {
                val improvement = err - candErr
                h = cand
                err = candErr
                lambda = max(1e-7, lambda * 0.3)
                if (improvement < 1e-12) break
            } else {
                lambda = min(1e6, lambda * 5.0)
            }
        }
        return normalizeHomography(h) ?: initial
    }

    private fun reprojectionErrorSq(
        h: DoubleArray,
        src: List<Point2D>,
        dst: List<Point2D>,
    ): Double {
        var sum = 0.0
        for (i in src.indices) {
            val p = perspectiveTransform(h, src[i]) ?: return Double.POSITIVE_INFINITY
            val dx = p.x - dst[i].x
            val dy = p.y - dst[i].y
            sum += dx * dx + dy * dy
        }
        return sum
    }

    private fun solveLinearSystem8(
        matrix: Array<DoubleArray>,
        rhs: DoubleArray,
    ): DoubleArray? {
        val n = 8
        val a = Array(n) { r -> matrix[r].copyOf() }
        val b = rhs.copyOf()
        for (col in 0 until n) {
            var pivotRow = col
            var pivotVal = abs(a[col][col])
            for (r in col + 1 until n) {
                val v = abs(a[r][col])
                if (v > pivotVal) {
                    pivotVal = v
                    pivotRow = r
                }
            }
            if (pivotVal < SINGULAR_EPS) return null
            if (pivotRow != col) {
                val tmpRow = a[col]
                a[col] = a[pivotRow]
                a[pivotRow] = tmpRow
                val tmpB = b[col]
                b[col] = b[pivotRow]
                b[pivotRow] = tmpB
            }
            val diag = a[col][col]
            for (r in col + 1 until n) {
                val factor = a[r][col] / diag
                if (factor == 0.0) continue
                for (c in col until n) {
                    a[r][c] -= factor * a[col][c]
                }
                b[r] -= factor * b[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var sum = b[r]
            for (c in r + 1 until n) sum -= a[r][c] * x[c]
            val diag = a[r][r]
            if (abs(diag) < SINGULAR_EPS) return null
            x[r] = sum / diag
        }
        return if (x.all { it.isFinite() }) x else null
    }
}
