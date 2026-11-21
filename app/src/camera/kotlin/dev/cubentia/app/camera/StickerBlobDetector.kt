package dev.cubentia.app.camera

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

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

        // Compute the median blob size to estimate the cube's rough scale (~6.5 sticker widths across a 3×3 corner).
        val sortedSizes = blobs.map { it.size }.sorted()
        val medianSize = sortedSizes[sortedSizes.size / 2]
        val estRadius = (medianSize * 3.8).coerceIn(guide.radius * 0.45, guide.radius * 1.45)

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
            val pts = projectCube(CornerPoint(cx, cy), radius, rotation, pitch, yaw, roll, persp)
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

        // Local pose refinement (keeps strict 3D cube geometry — no independent vertex warping).
        val bx = bestParams[0]
        val by = bestParams[1]
        val br = bestParams[2]
        val bRoll = bestParams[4]
        val fineStep = (medianSize * 0.25).coerceAtLeast(2.5)
        for (dx in -2..2) {
            for (dy in -2..2) {
                for (scaleDelta in listOf(0.92, 1.0, 1.08)) {
                    for (pitch in listOf(22.0, 28.0, 35.0, 44.0)) {
                        for (persp in listOf(0.0, 0.16, 0.30)) {
                            for (rollDelta in listOf(-5.0, 0.0, 5.0)) {
                                val (s, pts) =
                                    evalPose(
                                        bx + dx * fineStep,
                                        by + dy * fineStep,
                                        br * scaleDelta,
                                        pitch,
                                        0.0,
                                        bRoll + rollDelta,
                                        persp,
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

                    val neighbors = intArrayOf(cur - 1, cur + 1, cur - gw, cur + gw)
                    for (nIdx in neighbors) {
                        val nx = nIdx % gw
                        val ny = nIdx / gw
                        if (nx < 0 || nx >= gw || ny < 0 || ny >= gh) continue
                        if (visited[nIdx]) continue
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
                                break
                            }
                            visited[nIdx] = true
                            queue[tail++] = nIdx
                        }
                    }
                    if (aborted) break
                }

                if (aborted || count < 4) continue
                val bw = (maxX - minX + 1) * stride.toDouble()
                val bh = (maxY - minY + 1) * stride.toDouble()
                if (bw < minSide || bh < minSide || bw > maxSide || bh > maxSide) continue
                val aspect = bw / bh
                if (aspect < 0.42 || aspect > 2.40) continue
                val fillRatio = (count * stride * stride) / (bw * bh)
                if (fillRatio < 0.45) continue

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

    private fun projectCube(
        center: CornerPoint,
        radius: Double,
        frameRotation: Int,
        pitchDeg: Double,
        yawDeg: Double,
        rollDeg: Double,
        persp: Double,
    ): List<CornerPoint> {
        val yaw = (45.0 + yawDeg) * PI / 180.0
        val pitch = pitchDeg * PI / 180.0
        val roll = (-rollDeg - frameRotation) * PI / 180.0
        val cosY = cos(yaw)
        val sinY = sin(yaw)
        val cosP = cos(pitch)
        val sinP = sin(pitch)
        val cosR = cos(roll)
        val sinR = sin(roll)
        val unitScale = radius / 1.632993161855452
        val corners3D =
            listOf(
                Triple(-1.0, 1.0, -1.0), // T
                Triple(-1.0, 1.0, 1.0), // UL
                Triple(-1.0, -1.0, 1.0), // LL
                Triple(1.0, -1.0, 1.0), // B
                Triple(1.0, -1.0, -1.0), // LR
                Triple(1.0, 1.0, -1.0), // UR
                Triple(1.0, 1.0, 1.0), // C
            )
        return corners3D.map { (cx, cy, cz) ->
            val x1 = cx * cosY - cz * sinY
            val z1 = cx * sinY + cz * cosY
            val y2 = cy * cosP - z1 * sinP
            val z2 = cy * sinP + z1 * cosP
            val depth = (1.0 - z2 * persp / 1.732).coerceAtLeast(0.35)
            val px = x1 / depth
            val py = -y2 / depth
            val rx = px * cosR - py * sinR
            val ry = px * sinR + py * cosR
            center + CornerPoint(rx * unitScale, ry * unitScale)
        }
    }
}
