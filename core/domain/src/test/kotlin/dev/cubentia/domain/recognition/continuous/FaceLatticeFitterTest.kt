package dev.cubentia.domain.recognition.continuous

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot

class FaceLatticeFitterTest {
    @Serializable
    private data class CornerParityFixture(
        val schemaVersion: Int,
        val modelSha256: String,
        val threshold: Double,
        val maxPeaks: Int,
        val stills: List<CornerStill>,
    )

    @Serializable
    private data class CornerStill(
        val group: String,
        val still: String,
        val split: String,
        val width: Int,
        val height: Int,
        val peaks: List<List<Double>>,
        val expectedFaces: Int,
        val expectedSharedEdges: Int,
    )

    @Test
    fun `corner peaks parity fixture recovers 90 of 90 faces and 90 of 90 shared edges`() {
        val raw =
            requireNotNull(javaClass.classLoader.getResource("fixtures/continuous/corner-peaks-parity.json")) {
                "Missing corner-peaks-parity.json test resource"
            }.readText()
        val fixture = Json.decodeFromString<CornerParityFixture>(raw)

        assertEquals("d18fb2c2fe2bb975cf646877f811718c16775ce6bcde907f12547d143174be7c", fixture.modelSha256)
        assertEquals(0.45, fixture.threshold, 1e-9)
        assertEquals(30, fixture.stills.size)
        assertEquals(14, fixture.stills.count { it.split == "tuning" })
        assertEquals(16, fixture.stills.count { it.split == "heldout" })

        var totalFaces = 0
        var totalSharedEdges = 0

        for (still in fixture.stills) {
            val centers = still.peaks.map { Point2D(it[0], it[1]) }
            val nn1 =
                DoubleArray(centers.size) { i ->
                    var best = Double.POSITIVE_INFINITY
                    for (j in centers.indices) {
                        if (i == j) continue
                        val d = ProjectiveGeometry.distance(centers[i], centers[j])
                        if (d < best) best = d
                    }
                    best
                }.sortedArray()
            val maxScale = maxOf(FaceLatticeFitter.percentile(nn1, 50.0), FaceLatticeFitter.percentile(nn1, 75.0))
            val fitted = FaceLatticeFitter.fit(centers, maxFaces = 3, minInliers = 6)
            assertEquals(
                still.expectedFaces,
                fitted.size,
                "Expected ${still.expectedFaces} faces on ${still.group}/${still.still}, got ${fitted.size}",
            )
            totalFaces += fitted.size

            // Verify every fitted quad is CW, convex, with opposite-edge ratio <= 1.55 and mean_err <= 0.12 * d
            for (face in fitted) {
                assertTrue(ProjectiveGeometry.signedArea2(face.quad.corners) > 0.0, "Quad must be clockwise")
                assertTrue(ProjectiveGeometry.isConvexQuad(face.quad), "Quad must be convex")
                val c = face.quad.corners
                val lens = DoubleArray(4) { k -> ProjectiveGeometry.distance(c[k], c[(k + 1) % 4]) }
                assertTrue(maxOf(lens[0], lens[2]) / minOf(lens[0], lens[2]) <= 1.55)
                assertTrue(maxOf(lens[1], lens[3]) / minOf(lens[1], lens[3]) <= 1.55)
                assertEquals(9, face.gridCenters.size)
                assertTrue(face.inlierIndices.size >= 6)
                assertTrue(
                    face.meanError.isFinite() && face.meanError >= 0.0 && face.meanError <= 0.12 * maxScale + 1e-6,
                    "meanError=${face.meanError} exceeded 0.12 * $maxScale on ${still.group}/${still.still}",
                )
            }

            var shared = 0
            for (i in fitted.indices) {
                for (j in i + 1 until fitted.size) {
                    if (ProjectiveGeometry.sharedEdge(fitted[i].quad, fitted[j].quad) != null) {
                        shared++
                    }
                }
            }
            assertEquals(
                still.expectedSharedEdges,
                shared,
                "Expected ${still.expectedSharedEdges} shared edges on ${still.group}/${still.still}, got $shared",
            )
            totalSharedEdges += shared
        }

        assertEquals(90, totalFaces)
        assertEquals(90, totalSharedEdges)
    }

    @Test
    fun `unordered centers with bimodal clutter and 2 dropped stickers recover 3 non-overlapping shared-edge quads`() {
        val cfg = SyntheticCubeStream.buildScenarios()[0]
        val frames = SyntheticCubeStream.generateSequence(cfg)
        val fr = frames[25]
        assertEquals(3, fr.visibleFaces.size)

        val rng = Random(42L)
        val unit = listOf(Point2D(0.0, 0.0), Point2D(1.0, 0.0), Point2D(1.0, 1.0), Point2D(0.0, 1.0))
        val gridUv = List(9) { idx -> Point2D((idx % 3) / 3.0 + 1.0 / 6.0, (idx / 3) / 3.0 + 1.0 / 6.0) }
        val centers = mutableListOf<Point2D>()
        for (q in fr.visibleFaces.values) {
            val h = requireNotNull(ProjectiveGeometry.findHomography(unit, q.corners))
            for (uv in gridUv) {
                val p = requireNotNull(ProjectiveGeometry.perspectiveTransform(h, uv))
                centers.add(Point2D(p.x + rng.nextGaussian() * 1.0, p.y + rng.nextGaussian() * 1.0))
            }
        }
        // Drop 2 stickers and add distant tightly-spaced bimodal clutter points
        centers.shuffle(rng)
        val kept = centers.dropLast(2).toMutableList()
        for (k in 0 until 12) {
            kept.add(Point2D(20.0 + (k % 4) * 9.0, 20.0 + (k / 4) * 9.0))
        }

        val fitted = FaceLatticeFitter.fit(kept, maxFaces = 3, minInliers = 6)
        assertEquals(3, fitted.size)
        for (i in fitted.indices) {
            for (j in i + 1 until fitted.size) {
                val inter = ProjectiveGeometry.convexIntersectionArea(fitted[i].quad, fitted[j].quad)
                val minArea = minOf(ProjectiveGeometry.quadArea(fitted[i].quad), ProjectiveGeometry.quadArea(fitted[j].quad))
                assertTrue(inter <= 0.08 * minArea)
            }
        }
        val shared =
            (0 until 3).sumOf { i ->
                (i + 1 until 3).count { j -> ProjectiveGeometry.sharedEdge(fitted[i].quad, fitted[j].quad) != null }
            }
        assertTrue(shared >= 2, "Expected >=2 shared edges among 3 faces, got $shared")
    }

    @Test
    fun `rejects partial 2-row 6-inlier grids and degenerate inputs`() {
        // 2 rows x 3 cols = 6 points: must be rejected because rowsMask != 7
        val twoRows =
            (0 until 2).flatMap { r -> (0 until 3).map { c -> Point2D(100.0 + c * 40.0, 100.0 + r * 40.0) } }
        assertTrue(FaceLatticeFitter.fit(twoRows, maxFaces = 3, minInliers = 6).isEmpty())

        // 3 rows x 2 cols = 6 points: must be rejected because colsMask != 7
        val twoCols =
            (0 until 3).flatMap { r -> (0 until 2).map { c -> Point2D(100.0 + c * 40.0, 100.0 + r * 40.0) } }
        assertTrue(FaceLatticeFitter.fit(twoCols, maxFaces = 3, minInliers = 6).isEmpty())

        // Too few points
        assertTrue(FaceLatticeFitter.fit(twoRows.take(5)).isEmpty())

        // Non-finite points
        val withNan = twoRows + Point2D(Double.NaN, 120.0)
        assertTrue(FaceLatticeFitter.fit(withNan).isEmpty())

        // Scale d_med < 6.0
        val tiny = (0 until 3).flatMap { r -> (0 until 3).map { c -> Point2D(10.0 + c * 3.0, 10.0 + r * 3.0) } }
        assertTrue(FaceLatticeFitter.fit(tiny).isEmpty())
    }

    @Test
    fun `projective DLT homography matches ground-truth perspective transform to sub-micropixel accuracy`() {
        val trueH =
            doubleArrayOf(
                42.0,
                -4.5,
                180.0,
                3.2,
                39.0,
                120.0,
                0.0018,
                -0.0012,
                1.0,
            )
        val src = List(9) { i -> Point2D((i % 3).toDouble(), (i / 3).toDouble()) }
        val dst = src.map { p -> requireNotNull(ProjectiveGeometry.perspectiveTransform(trueH, p)) }

        val est4 =
            requireNotNull(
                ProjectiveGeometry.findHomography(
                    listOf(src[0], src[2], src[8], src[6]),
                    listOf(dst[0], dst[2], dst[8], dst[6]),
                ),
            )
        val est9 = requireNotNull(ProjectiveGeometry.findHomography(src, dst))

        for (i in src.indices) {
            val p4 = requireNotNull(ProjectiveGeometry.perspectiveTransform(est4, src[i]))
            val p9 = requireNotNull(ProjectiveGeometry.perspectiveTransform(est9, src[i]))
            assertTrue(hypot(p4.x - dst[i].x, p4.y - dst[i].y) < 1e-6)
            assertTrue(hypot(p9.x - dst[i].x, p9.y - dst[i].y) < 1e-6)
        }

        // Convex intersection area tests
        val sq1 = Quad2D(listOf(Point2D(0.0, 0.0), Point2D(10.0, 0.0), Point2D(10.0, 10.0), Point2D(0.0, 10.0)))
        val sq2 = Quad2D(listOf(Point2D(5.0, 0.0), Point2D(15.0, 0.0), Point2D(15.0, 10.0), Point2D(5.0, 10.0)))
        val sq3 = Quad2D(listOf(Point2D(20.0, 0.0), Point2D(30.0, 0.0), Point2D(30.0, 10.0), Point2D(20.0, 10.0)))
        assertEquals(50.0, ProjectiveGeometry.convexIntersectionArea(sq1, sq2), 1e-6)
        assertEquals(0.0, ProjectiveGeometry.convexIntersectionArea(sq1, sq3), 1e-6)
        assertEquals(100.0, ProjectiveGeometry.convexIntersectionArea(sq1, sq1), 1e-6)
        assertTrue(abs(ProjectiveGeometry.quadArea(sq1) - 100.0) < 1e-6)
        assertFalse(ProjectiveGeometry.isValidFaceQuad(sq1, dMed = 40.0))

        // Degenerate zero-area point quad must have 0.0 intersection area
        val ptQuad = Quad2D(List(4) { Point2D(5.0, 5.0) })
        assertEquals(0.0, ProjectiveGeometry.convexIntersectionArea(sq1, ptQuad), 1e-9)
        assertEquals(0.0, ProjectiveGeometry.convexIntersectionArea(ptQuad, sq1), 1e-9)

        // Collinear / rank-deficient correspondences must return null homography
        val collinear4 = listOf(Point2D(0.0, 0.0), Point2D(1.0, 0.0), Point2D(2.0, 0.0), Point2D(3.0, 0.0))
        val target4 = listOf(Point2D(0.0, 0.0), Point2D(10.0, 0.0), Point2D(10.0, 10.0), Point2D(0.0, 10.0))
        assertTrue(ProjectiveGeometry.findHomography(collinear4, target4) == null)
        val threeCollinear = listOf(Point2D(0.0, 0.0), Point2D(1.0, 0.0), Point2D(2.0, 0.0), Point2D(1.0, 1.0))
        assertTrue(ProjectiveGeometry.findHomography(threeCollinear, target4) == null)
    }

    @Test
    fun `bimodal multi-scale fallback recovers cube faces when tightly-spaced clutter outnumbers cube centers`() {
        // 18 cube points (2 adjacent 3x3 faces at 40 px spacing) + 24 clutter points at 9 px spacing
        // -> dMed lands on 9 px clutter spacing, while p75 holds the 40 px cube spacing!
        val centers = mutableListOf<Point2D>()
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                centers.add(Point2D(300.0 + c * 40.0, 200.0 + r * 40.0))
                centers.add(Point2D(420.0 + c * 40.0, 200.0 + r * 40.0))
            }
        }
        // Add 24 tightly-spaced clutter points scattered in small pairs/clusters (not a 3x3 face)
        for (k in 0 until 12) {
            val bx = 20.0 + (k % 4) * 55.0
            val by = 20.0 + (k / 4) * 55.0
            centers.add(Point2D(bx, by))
            centers.add(Point2D(bx + 9.0, by + 1.0))
        }
        val fitted = FaceLatticeFitter.fit(centers, maxFaces = 2, minInliers = 6)
        assertEquals(2, fitted.size, "Multi-scale p75 fallback must recover both 40 px cube faces")
        assertTrue(fitted.all { ProjectiveGeometry.quadArea(it.quad) > 8000.0 })
    }
}
