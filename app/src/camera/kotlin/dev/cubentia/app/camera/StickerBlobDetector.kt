package dev.cubentia.app.camera

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bottom-up sticker-first cube corner detector (Story 10.18).
 *
 * Segments uniform sticker-sized blobs via fast grid-stride region growing, filters by sticker
 * geometry (area, aspect ratio, compactness, rejecting large background regions), and fits a rigid
 * 3D perspective cube pose whose 27 sticker centers align 1-to-1 with the detected blobs while
 * penalizing extra sticker blobs just outside the 3×3 faces (preventing 2×2 sub-grid locks).
 */
internal object StickerBlobDetector {
    internal data class Blob(
        val cx: Double,
        val cy: Double,
        val width: Double,
        val height: Double,
    ) {
        val size: Double get() = (width + height) * 0.5
    }

    fun detect(
        frame: YuvFrame,
        guide: CornerGuide,
    ): CornerLocator.Location? {
        if (guide.radius < 18) return null
        val blobs = extractBlobs(frame, guide.radius)
        if (blobs.size < 8) return null

        val frameCenter = guide.vertices().last()
        val rotation = guide.rotationDegrees

        // Compute the median blob bounding-box size to estimate cube circumradius (~2.3x stickerless to ~3.4x stickered).
        val sortedSizes = blobs.map { it.size }.sorted()
        val medianSize = sortedSizes[sortedSizes.size / 2]
        val estRadius = (medianSize * 2.85).coerceIn(guide.radius * 0.45, guide.radius * 1.35)

        var bestPts: List<CornerPoint>? = null
        var bestScore = Double.NEGATIVE_INFINITY
        var bestParams = DoubleArray(6) // cx, cy, scale, pitch, roll, persp

        // Coarse search over cube centers seeded by blob cluster + framing zone.
        val cxMin = (frame.left + frame.width * 0.25).coerceAtMost(frameCenter.x - guide.radius * 0.25)
        val cxMax = (frame.right - frame.width * 0.25).coerceAtLeast(frameCenter.x + guide.radius * 0.25)
        val cyMin = (frame.top + frame.height * 0.20).coerceAtMost(frameCenter.y - guide.radius * 0.35)
        val cyMax = (frame.bottom - frame.height * 0.20).coerceAtLeast(frameCenter.y + guide.radius * 0.35)
        val stepXY = (medianSize * 0.85).coerceAtLeast(8.0)

        fun evalPose(
            cx: Double,
            cy: Double,
            radius: Double,
            pitch: Double,
            yaw: Double,
            roll: Double,
            persp: Double,
            minFaces: Int,
        ): Pair<Double, List<CornerPoint>> {
            val pts = CornerLocator.projectCube(CornerPoint(cx, cy), radius, rotation, pitch, yaw, roll, persp)
            val quads = guide.quads(pts)
            val s = scorePoseAgainstBlobs(quads, blobs, medianSize, minFaces)
            return s to pts
        }

        var y = cyMin
        while (y <= cyMax) {
            var x = cxMin
            while (x <= cxMax) {
                for (scale in listOf(0.75, 0.90, 1.05, 1.25)) {
                    for (roll in listOf(-12.0, 0.0, 12.0)) {
                        val (s, pts) = evalPose(x, y, estRadius * scale, 34.0, 0.0, roll, 0.16, minFaces = 2)
                        if (s > bestScore) {
                            bestScore = s
                            bestPts = pts
                            bestParams = doubleArrayOf(x, y, estRadius * scale, 34.0, roll, 0.16)
                        }
                    }
                }
                x += stepXY
            }
            y += stepXY
        }

        if (bestPts == null || bestScore < 6.0) return null

        // Local pose refinement in three fast passes (keeps strict 3D cube geometry, ~3.3x faster than a 6D product grid).
        var bx = bestParams[0]
        var by = bestParams[1]
        var br = bestParams[2]
        var bPitch = bestParams[3]
        var bRoll = bestParams[4]
        var bPersp = bestParams[5]
        val fineStep = (medianSize * 0.25).coerceAtLeast(2.5)
        // Pass 1: refine translation, scale, coarse pitch, and roll.
        for (dx in -2..2) {
            for (dy in -2..2) {
                for (scaleDelta in listOf(0.92, 1.0, 1.08)) {
                    for (pitch in listOf(24.0, 35.0)) {
                        for (rollDelta in listOf(-5.0, 0.0, 5.0)) {
                            val cx = bx + dx * fineStep
                            val cy = by + dy * fineStep
                            val r = br * scaleDelta
                            val roll = bRoll + rollDelta
                            val (s, pts) = evalPose(cx, cy, r, pitch, 0.0, roll, bPersp, minFaces = 3)
                            if (s > bestScore) {
                                bestScore = s
                                bestPts = pts
                                bestParams = doubleArrayOf(cx, cy, r, pitch, roll, bPersp)
                            }
                        }
                    }
                }
            }
        }
        bx = bestParams[0]
        by = bestParams[1]
        br = bestParams[2]
        bPitch = bestParams[3]
        bRoll = bestParams[4]
        // Pass 2: refine pitch, perspective, scale, and small translation adjustment.
        for (pitchDelta in listOf(-4.0, 0.0, 4.0)) {
            for (persp in listOf(0.0, 0.16, 0.30)) {
                for (scaleDelta in listOf(0.95, 1.0, 1.05)) {
                    for (dx in -1..1) {
                        for (dy in -1..1) {
                            val cx = bx + dx * fineStep
                            val cy = by + dy * fineStep
                            val r = br * scaleDelta
                            val pitch = (bPitch + pitchDelta).coerceIn(18.0, 46.0)
                            val (s, pts) = evalPose(cx, cy, r, pitch, 0.0, bRoll, persp, minFaces = 3)
                            if (s > bestScore) {
                                bestScore = s
                                bestPts = pts
                                bestParams = doubleArrayOf(cx, cy, r, pitch, bRoll, persp)
                            }
                        }
                    }
                }
            }
        }
        bx = bestParams[0]
        by = bestParams[1]
        br = bestParams[2]
        bPitch = bestParams[3]
        bRoll = bestParams[4]
        bPersp = bestParams[5]
        // Pass 3: refine yaw, roll, and fine center alignment.
        for (yaw in listOf(-10.0, -5.0, 0.0, 5.0, 10.0)) {
            for (rollDelta in listOf(-3.0, 0.0, 3.0)) {
                for (dx in -1..1) {
                    for (dy in -1..1) {
                        val (s, pts) =
                            evalPose(
                                bx + dx * (fineStep * 0.6),
                                by + dy * (fineStep * 0.6),
                                br,
                                bPitch,
                                yaw,
                                bRoll + rollDelta,
                                bPersp,
                                minFaces = 3,
                            )
                        if (s > bestScore) {
                            bestScore = s
                            bestPts = pts
                        }
                    }
                }
            }
        }

        val finalPts = bestPts ?: return null
        if (bestScore < 15.0) return null
        return CornerLocator.Location(guide.quads(finalPts), bestScore)
    }

    private fun scorePoseAgainstBlobs(
        quads: List<List<CornerPoint>>,
        blobs: List<Blob>,
        medianSize: Double,
        minFaces: Int,
    ): Double {
        val hitRadius = medianSize * 0.52
        val hitRadiusSq = hitRadius * hitRadius
        val used = BooleanArray(blobs.size)
        var hits = 0.0
        var facesWithHits = 0
        var outsidePenalty = 0.0

        val outsideEdgesPerFace =
            listOf(
                // q0 [T, UR, C, UL]: outer edges are y=-0.18 and x=-0.18
                listOf(-0.18 to 0.17, -0.18 to 0.50, -0.18 to 0.83, 0.17 to -0.18, 0.50 to -0.18, 0.83 to -0.18),
                // q1 [UL, C, B, LL]: outer edges are x=-0.18 and y=1.18
                listOf(-0.18 to 0.17, -0.18 to 0.50, -0.18 to 0.83, 0.17 to 1.18, 0.50 to 1.18, 0.83 to 1.18),
                // q2 [C, UR, LR, B]: outer edges are x=1.18 and y=1.18
                listOf(1.18 to 0.17, 1.18 to 0.50, 1.18 to 0.83, 0.17 to 1.18, 0.50 to 1.18, 0.83 to 1.18),
            )
        val homographies =
            quads.map { quad ->
                val h = Homography.from(quad) ?: return Double.NEGATIVE_INFINITY
                var faceHits = 0
                for (row in 0..2) {
                    for (col in 0..2) {
                        val expected = h.apply((col + 0.5) / 3.0, (row + 0.5) / 3.0)
                        var bestIdx = -1
                        var bestDistSq = hitRadiusSq
                        for (i in blobs.indices) {
                            if (used[i]) continue
                            val b = blobs[i]
                            val dx = b.cx - expected.x
                            val dy = b.cy - expected.y
                            val dSq = dx * dx + dy * dy
                            if (dSq < bestDistSq) {
                                bestDistSq = dSq
                                bestIdx = i
                            }
                        }
                        if (bestIdx >= 0) {
                            used[bestIdx] = true
                            faceHits++
                            hits += 1.0 - 0.4 * (bestDistSq / hitRadiusSq)
                        }
                    }
                }
                if (faceHits >= 5) facesWithHits++
                h
            }

        if (facesWithHits < minFaces) return Double.NEGATIVE_INFINITY

        // Check only the 2 outer edges of each face AFTER all 3 faces have claimed their stickers.
        homographies.forEachIndexed { faceIdx, h ->
            for ((ox, oy) in outsideEdgesPerFace[faceIdx]) {
                val p = h.apply(ox, oy)
                for (i in blobs.indices) {
                    if (used[i]) continue
                    val b = blobs[i]
                    val dx = b.cx - p.x
                    val dy = b.cy - p.y
                    if (dx * dx + dy * dy < hitRadiusSq * 0.7) {
                        outsidePenalty += 1.5
                        break
                    }
                }
            }
        }

        return hits + facesWithHits * 1.5 - outsidePenalty
    }

    internal fun extractBlobs(
        frame: YuvFrame,
        guideRadius: Double,
    ): List<Blob> {
        val stride =
            when {
                maxOf(frame.width, frame.height) >= 640 -> 3
                maxOf(frame.width, frame.height) > 320 -> 2
                else -> 1
            }
        val gw = frame.width / stride
        val gh = frame.height / stride
        if (gw < 16 || gh < 16) return emptyList()

        val lumaGrid = IntArray(gw * gh)
        val uGrid = IntArray(gw * gh)
        val vGrid = IntArray(gw * gh)
        for (gy in 0 until gh) {
            val fy = (frame.top + gy * stride).coerceIn(frame.top, frame.bottom - 1)
            for (gx in 0 until gw) {
                val fx = (frame.left + gx * stride).coerceIn(frame.left, frame.right - 1)
                val idx = gy * gw + gx
                lumaGrid[idx] = frame.y.at(fx, fy)
                uGrid[idx] = frame.u.at(fx / 2, fy / 2)
                vGrid[idx] = frame.v.at(fx / 2, fy / 2)
            }
        }

        val visited = BooleanArray(gw * gh)
        val minSide = (guideRadius * 0.07).coerceAtLeast(4.0)
        val maxSide = (guideRadius * 0.60).coerceAtLeast(minSide + 4.0)
        val maxCells = ((maxSide * maxSide * 1.5) / (stride * stride)).roundToInt().coerceAtLeast(16)
        val queue = IntArray(maxCells + 8)
        val blobs = mutableListOf<Blob>()

        for (sy in 1 until gh - 1) {
            for (sx in 1 until gw - 1) {
                val sIdx = sy * gw + sx
                if (visited[sIdx]) continue
                val l0 = lumaGrid[sIdx]
                val u0 = uGrid[sIdx]
                val v0 = vGrid[sIdx]
                val sat0 = maxOf(abs(u0 - 128), abs(v0 - 128))
                // Skip dark plastic/shadows unless clearly saturated.
                if (l0 < 45 && sat0 < 22) continue

                visited[sIdx] = true
                var head = 0
                var tail = 0
                queue[tail++] = sIdx
                var sumX = 0.0
                var sumY = 0.0
                var count = 0
                var minX = sx
                var maxX = sx
                var minY = sy
                var maxY = sy
                var aborted = false

                while (head < tail) {
                    val cur = queue[head++]
                    val cx = cur % gw
                    val cy = cur / gw
                    sumX += cx
                    sumY += cy
                    count++
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy

                    val cl = lumaGrid[cur]
                    val cu = uGrid[cur]
                    val cv = vGrid[cur]

                    fun tryVisit(nIdx: Int) {
                        if (aborted || visited[nIdx]) return
                        val nl = lumaGrid[nIdx]
                        val nu = uGrid[nIdx]
                        val nv = vGrid[nIdx]
                        if (abs(nl - cl) <= 20 &&
                            abs(nu - cu) <= 12 &&
                            abs(nv - cv) <= 12 &&
                            abs(nl - l0) <= 34 &&
                            abs(nu - u0) <= 18 &&
                            abs(nv - v0) <= 18
                        ) {
                            if (tail >= maxCells) {
                                aborted = true
                                return
                            }
                            visited[nIdx] = true
                            queue[tail++] = nIdx
                        }
                    }
                    if (cx > 0) tryVisit(cur - 1)
                    if (cx + 1 < gw) tryVisit(cur + 1)
                    if (cy > 0) tryVisit(cur - gw)
                    if (cy + 1 < gh) tryVisit(cur + gw)
                    if (aborted) break
                }

                if (aborted || count < 4) continue
                val bw = (maxX - minX + 1) * stride.toDouble()
                val bh = (maxY - minY + 1) * stride.toDouble()
                if (bw < minSide || bh < minSide || bw > maxSide || bh > maxSide) continue
                val aspect = bw / bh
                // Foreshortened top-face diamonds can reach ~3.5:1 aspect and ~0.35–0.48 bounding-box fill ratio.
                if (aspect < 0.30 || aspect > 3.60) continue
                val fillRatio = (count * stride * stride) / (bw * bh)
                if (fillRatio < 0.32) continue

                blobs +=
                    Blob(
                        cx = frame.left + (sumX / count) * stride,
                        cy = frame.top + (sumY / count) * stride,
                        width = bw,
                        height = bh,
                    )
            }
        }
        return blobs
    }
}
