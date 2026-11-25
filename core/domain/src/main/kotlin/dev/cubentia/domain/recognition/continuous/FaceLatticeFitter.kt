package dev.cubentia.domain.recognition.continuous

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure-JVM port of `pc_spike/lattice_fit.py`: converts unordered 2D sticker-center detections
 * into up to `maxFaces` non-overlapping clockwise projective 3×3 face quads.
 */
object FaceLatticeFitter {
    private val GRID_UV: List<Point2D> =
        List(9) { gi -> Point2D((gi % 3).toDouble(), (gi / 3).toDouble()) }

    private val OUTER_UV: List<Point2D> =
        listOf(
            Point2D(-0.5, -0.5),
            Point2D(2.5, -0.5),
            Point2D(2.5, 2.5),
            Point2D(-0.5, 2.5),
        )

    private const val FULL_SPAN_MASK = 7

    /**
     * Extract up to [maxFaces] non-overlapping projective 3×3 face quads from unordered 2D sticker
     * [centers]. Returns an empty list on degenerate inputs (non-finite, `< minInliers`, or scale
     * `< 6.0` px).
     */
    fun fit(
        centers: List<Point2D>,
        maxFaces: Int = 3,
        minInliers: Int = 6,
    ): List<FittedFace> {
        if (maxFaces <= 0 || minInliers < 4 || centers.size < minInliers) return emptyList()
        if (centers.any { !it.isFinite() }) return emptyList()

        val n = centers.size
        val nn1 = DoubleArray(n)
        for (i in 0 until n) {
            var best = Double.POSITIVE_INFINITY
            val pi = centers[i]
            for (j in 0 until n) {
                if (i == j) continue
                val d = hypot(pi.x - centers[j].x, pi.y - centers[j].y)
                if (d < best) best = d
            }
            nn1[i] = best
        }
        val sortedNn1 = nn1.sortedArray()
        val dMed = percentile(sortedNn1, 50.0)
        val p75 = percentile(sortedNn1, 75.0)
        val p25 = percentile(sortedNn1, 25.0)
        if (dMed < 6.0 && p75 < 6.0) return emptyList()

        val scales = mutableListOf(dMed)
        if (n > 12 && p75 > 1.6 * max(1.0, p25)) {
            for (s in listOf(p75, p25)) {
                if (s >= 6.0 && scales.all { prev -> abs(s - prev) / max(prev, 1.0) > 0.25 }) {
                    scales.add(s)
                }
            }
        }

        var bestFaces: List<FittedFace> = emptyList()
        var bestCount = -1
        var bestArea = -1.0

        for (scD in scales) {
            if (scD < 6.0) continue
            var avail = (0 until n).toList()
            val faces = mutableListOf<FittedFace>()
            val existingQuads = mutableListOf<Quad2D>()

            for (faceIter in 0 until maxFaces) {
                val fitted = fitOneFace(centers, avail, scD, existingQuads, minInliers) ?: break
                faces.add(fitted)
                existingQuads.add(fitted.quad)
                val claimed = fitted.inlierIndices.toHashSet()
                avail = avail.filter { it !in claimed }
                if (avail.size < minInliers) break
            }

            val totalArea = faces.sumOf { ProjectiveGeometry.quadArea(it.quad) }
            if (faces.isNotEmpty() && (faces.size > bestCount || (faces.size == bestCount && totalArea > bestArea))) {
                bestCount = faces.size
                bestArea = totalArea
                bestFaces = faces
            }
            if (scales.size == 1 || (faces.size == maxFaces && scD == dMed)) break
        }
        return bestFaces
    }

    private data class Candidate(
        val primaryScore: Int,
        val inlierCount: Int,
        val meanErr: Double,
        val quad: Quad2D,
        val inliers: List<Int>,
        val gridCenters: List<Point2D>,
    ) {
        fun beats(other: Candidate?): Boolean {
            if (other == null) return true
            if (primaryScore != other.primaryScore) return primaryScore > other.primaryScore
            if (inlierCount != other.inlierCount) return inlierCount > other.inlierCount
            return meanErr < other.meanErr
        }
    }

    private fun fitOneFace(
        points: List<Point2D>,
        availIdx: List<Int>,
        dMed: Double,
        existingQuads: List<Quad2D>,
        minInliers: Int,
    ): FittedFace? {
        val n = availIdx.size
        if (n < minInliers) return null
        val pts = Array(n) { points[availIdx[it]] }
        val distMat = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val d = hypot(pts[i].x - pts[j].x, pts[i].y - pts[j].y)
                distMat[i][j] = d
                distMat[j][i] = d
            }
        }

        val nbrsPerPt =
            Array(n) { i ->
                val nb = mutableListOf<Int>()
                for (j in 0 until n) {
                    if (j != i && distMat[i][j] in (0.48 * dMed)..(1.65 * dMed)) {
                        nb.add(j)
                    }
                }
                nb.sortBy { distMat[i][it] }
                if (nb.size > 5) nb.subList(0, 5).toList() else nb
            }
        val seedOrder = (0 until n).sortedBy { abs(nbrsPerPt[it].size - 4) }

        val seenMatchSets = HashSet<Set<Int>>()
        var bestCandidate: Candidate? = null
        var earlyStop = false

        val bestPi = IntArray(9)
        val bestD2 = DoubleArray(9)
        val usedP = BooleanArray(n)
        val usedF = BooleanArray(n)

        for (i in seedOrder) {
            if (earlyStop) break
            val nbrs = nbrsPerPt[i]
            if (nbrs.size < 2) continue
            val piXy = pts[i]
            for (aIdx in nbrs.indices) {
                if (earlyStop) break
                val j = nbrs[aIdx]
                val v1x = pts[j].x - piXy.x
                val v1y = pts[j].y - piXy.y
                val l1 = distMat[i][j]
                for (bIdx in nbrs.indices) {
                    if (aIdx == bIdx) continue
                    val k = nbrs[bIdx]
                    val v2x = pts[k].x - piXy.x
                    val v2y = pts[k].y - piXy.y
                    val l2 = distMat[i][k]
                    val cross = v1x * v2y - v1y * v2x
                    val sinTh = cross / max(1e-6, l1 * l2)
                    if (sinTh < 0.40) continue
                    if (max(l1, l2) / max(1e-6, min(l1, l2)) > 2.3) continue

                    val dLoc = min(dMed, max(l1, l2))
                    val tolInitSq = (0.34 * dLoc) * (0.34 * dLoc)
                    val tolFinal = 0.26 * dLoc

                    for (r0 in 0..1) {
                        for (c0 in 0..1) {
                            var closeCount = 0
                            for (gi in 0 until 9) {
                                val gc = gi % 3
                                val gr = gi / 3
                                val px = piXy.x + (gc - c0) * v1x + (gr - r0) * v2x
                                val py = piXy.y + (gc - c0) * v1y + (gr - r0) * v2y
                                var minD2 = Double.POSITIVE_INFINITY
                                var minIdx = 0
                                for (p in 0 until n) {
                                    val dx = px - pts[p].x
                                    val dy = py - pts[p].y
                                    val d2 = dx * dx + dy * dy
                                    if (d2 < minD2) {
                                        minD2 = d2
                                        minIdx = p
                                    }
                                }
                                bestPi[gi] = minIdx
                                bestD2[gi] = minD2
                                if (minD2 <= tolInitSq) closeCount++
                            }
                            if (closeCount < minInliers) continue

                            usedP.fill(false)
                            val matchGi = ArrayList<Int>(9)
                            val matchPi = ArrayList<Int>(9)
                            var rowsMask = 0
                            var colsMask = 0
                            for (gi in 0 until 9) {
                                if (bestD2[gi] <= tolInitSq) {
                                    val pi = bestPi[gi]
                                    if (!usedP[pi]) {
                                        usedP[pi] = true
                                        matchGi.add(gi)
                                        matchPi.add(pi)
                                        rowsMask = rowsMask or (1 shl (gi / 3))
                                        colsMask = colsMask or (1 shl (gi % 3))
                                    }
                                }
                            }
                            if (matchGi.size < minInliers || rowsMask != FULL_SPAN_MASK || colsMask != FULL_SPAN_MASK) {
                                continue
                            }

                            val sig = HashSet<Int>(matchGi.size * 2)
                            for (m in matchGi.indices) {
                                sig.add(matchGi[m] * n + matchPi[m])
                            }
                            if (!seenMatchSets.add(sig)) continue

                            val srcG = List(matchGi.size) { m -> GRID_UV[matchGi[m]] }
                            val dstP = List(matchPi.size) { m -> pts[matchPi[m]] }
                            val h = ProjectiveGeometry.findHomography(srcG, dstP) ?: continue

                            val projGrid = ArrayList<Point2D>(9)
                            var projOk = true
                            for (gi in 0 until 9) {
                                val pt = ProjectiveGeometry.perspectiveTransform(h, GRID_UV[gi])
                                if (pt == null) {
                                    projOk = false
                                    break
                                }
                                projGrid.add(pt)
                            }
                            if (!projOk) continue

                            val rawQuadPts = ArrayList<Point2D>(4)
                            for (kCorner in 0 until 4) {
                                val pt = ProjectiveGeometry.perspectiveTransform(h, OUTER_UV[kCorner])
                                if (pt == null) {
                                    projOk = false
                                    break
                                }
                                rawQuadPts.add(pt)
                            }
                            if (!projOk) continue

                            if (ProjectiveGeometry.signedArea2(rawQuadPts) <= 0.0) continue
                            val projQuad = Quad2D(rawQuadPts)
                            if (!ProjectiveGeometry.isValidFaceQuad(projQuad, dMed)) continue
                            if (ProjectiveGeometry.overlapsExisting(projQuad, existingQuads)) continue

                            usedF.fill(false)
                            val fOrigIndices = ArrayList<Int>(9)
                            var fRows = 0
                            var fCols = 0
                            var errSum = 0.0
                            var maxErr = 0.0
                            for (gi in 0 until 9) {
                                val pg = projGrid[gi]
                                var minD2 = Double.POSITIVE_INFINITY
                                var minIdx = 0
                                for (p in 0 until n) {
                                    val dx = pg.x - pts[p].x
                                    val dy = pg.y - pts[p].y
                                    val d2 = dx * dx + dy * dy
                                    if (d2 < minD2) {
                                        minD2 = d2
                                        minIdx = p
                                    }
                                }
                                val distV = sqrt(minD2)
                                if (distV <= tolFinal && !usedF[minIdx]) {
                                    usedF[minIdx] = true
                                    fOrigIndices.add(availIdx[minIdx])
                                    errSum += distV
                                    if (distV > maxErr) maxErr = distV
                                    fRows = fRows or (1 shl (gi / 3))
                                    fCols = fCols or (1 shl (gi % 3))
                                }
                            }
                            val fCount = fOrigIndices.size
                            if (fCount < minInliers || fRows != FULL_SPAN_MASK || fCols != FULL_SPAN_MASK) continue

                            val meanErr = errSum / fCount
                            if (meanErr > 0.12 * dLoc) continue

                            fOrigIndices.sort()
                            val edgeBonus = if (ProjectiveGeometry.sharesEdgeWithAny(projQuad, existingQuads)) 2 else 0
                            val cand =
                                Candidate(
                                    primaryScore = fCount + edgeBonus,
                                    inlierCount = fCount,
                                    meanErr = meanErr,
                                    quad = projQuad,
                                    inliers = fOrigIndices,
                                    gridCenters = projGrid,
                                )
                            if (cand.beats(bestCandidate)) {
                                bestCandidate = cand
                                if (
                                    fCount == 9 &&
                                    meanErr < 0.032 * dLoc &&
                                    maxErr < 0.065 * dLoc &&
                                    (existingQuads.isEmpty() || edgeBonus > 0)
                                ) {
                                    earlyStop = true
                                    break
                                }
                            }
                        }
                        if (earlyStop) break
                    }
                    if (earlyStop) break
                }
            }
        }

        val best = bestCandidate ?: return null
        return FittedFace(
            quad = best.quad,
            score = best.inlierCount.toDouble() / 9.0,
            inlierIndices = best.inliers,
            gridCenters = best.gridCenters,
            meanError = best.meanErr,
        )
    }

    internal fun percentile(
        sorted: DoubleArray,
        p: Double,
    ): Double {
        if (sorted.isEmpty()) return 0.0
        if (sorted.size == 1) return sorted[0]
        val pos = (p / 100.0) * (sorted.size - 1)
        val lo = floor(pos).toInt().coerceIn(0, sorted.lastIndex)
        val hi = ceil(pos).toInt().coerceIn(0, sorted.lastIndex)
        val frac = pos - lo
        return sorted[lo] * (1.0 - frac) + sorted[hi] * frac
    }
}
