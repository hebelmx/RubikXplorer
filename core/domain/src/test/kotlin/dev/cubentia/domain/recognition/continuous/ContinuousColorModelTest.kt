package dev.cubentia.domain.recognition.continuous

import dev.cubentia.domain.recognition.Lab
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class ContinuousColorModelTest {
    @Test
    fun `shading invariant Lab cancels scalar Lambertian shading`() {
        val bright = RgbColor(200.0, 80.0, 40.0)
        val shaded = RgbColor(100.0, 40.0, 20.0)
        val labBright = ContinuousColorModel.shadingInvariantLab(bright)
        val labShaded = ContinuousColorModel.shadingInvariantLab(shaded)
        assertTrue(ContinuousColorModel.labDistance(labBright, labShaded) < 1e-6)
    }

    @Test
    fun `white referenced hue distance separates real webcam Red and Orange under glare and shadow`() {
        val whiteLab = ContinuousColorModel.shadingInvariantLab(RgbColor(191.0, 145.0, 160.0))
        val orangeProto = ContinuousColorModel.shadingInvariantLab(RgbColor(213.0, 108.0, 74.0))
        val redProto = ContinuousColorModel.shadingInvariantLab(RgbColor(123.0, 27.0, 41.0))

        val orangeSamples =
            listOf(
                RgbColor(152.0, 54.0, 42.0),
                RgbColor(209.0, 100.0, 60.0),
                RgbColor(114.0, 42.0, 30.0),
                RgbColor(95.0, 24.0, 18.0),
            )
        val redSamples =
            listOf(
                RgbColor(123.0, 27.0, 41.0),
                RgbColor(157.0, 79.0, 89.0),
                RgbColor(157.0, 46.0, 60.0),
                RgbColor(84.0, 22.0, 28.0),
            )

        for (rgb in orangeSamples) {
            val lab = ContinuousColorModel.shadingInvariantLab(rgb)
            val dOrange = ContinuousColorModel.whiteReferencedHueDistance(lab, orangeProto, whiteLab.a, whiteLab.b, false)
            val dRed = ContinuousColorModel.whiteReferencedHueDistance(lab, redProto, whiteLab.a, whiteLab.b, false)
            assertTrue(dOrange < dRed, "Orange $rgb misclassified: dOrange=$dOrange, dRed=$dRed")
        }
        for (rgb in redSamples) {
            val lab = ContinuousColorModel.shadingInvariantLab(rgb)
            val dRed = ContinuousColorModel.whiteReferencedHueDistance(lab, redProto, whiteLab.a, whiteLab.b, false)
            val dOrange = ContinuousColorModel.whiteReferencedHueDistance(lab, orangeProto, whiteLab.a, whiteLab.b, false)
            assertTrue(dRed < dOrange, "Red $rgb misclassified: dRed=$dRed, dOrange=$dOrange")
        }

        // White prototype & low-chroma fallback
        val whiteDist = ContinuousColorModel.whiteReferencedHueDistance(whiteLab, whiteLab, whiteLab.a, whiteLab.b, true)
        assertEquals(0.0, whiteDist, 1e-9)
        val nearWhite = Lab(whiteLab.l - 5.0, whiteLab.a + 1.0, whiteLab.b + 1.0)
        val lowChromaDist = ContinuousColorModel.whiteReferencedHueDistance(nearWhite, orangeProto, whiteLab.a, whiteLab.b, false)
        assertTrue(lowChromaDist > 10.0)
    }

    @Test
    fun `balanced 8 per color solver produces exact counts of 8 and optimal pairwise swaps`() {
        val rng = Random(123L)
        repeat(10) {
            val cost =
                Array(48) {
                    DoubleArray(6) {
                        val v = 1.0 + rng.nextDouble() * 49.0
                        v * v
                    }
                }
            val assign = ContinuousColorModel.solveBalanced8PerColor(cost, cap = 8)
            val counts = IntArray(6)
            for (c in assign) counts[c]++
            for (c in 0 until 6) {
                assertEquals(8, counts[c], "Color $c count was ${counts[c]}")
            }
            // Verify optimality against any pairwise swap
            for (i in 0 until 48) {
                for (j in i + 1 until 48) {
                    val ci = assign[i]
                    val cj = assign[j]
                    if (ci == cj) continue
                    val before = cost[i][ci] + cost[j][cj]
                    val after = cost[i][cj] + cost[j][ci]
                    assertTrue(before <= after + 1e-7, "Found improving swap ($i, $j): $before > $after")
                }
            }
        }
    }

    @Test
    fun `grid and quad rotations are consistent and keep center invariant`() {
        val cells = (0 until 9).toList()
        val ccw1 = ContinuousColorModel.rotateGridCcw(cells, 1)
        assertEquals(listOf(2, 5, 8, 1, 4, 7, 0, 3, 6), ccw1)
        assertEquals(4, ccw1[4])
        assertEquals(cells, ContinuousColorModel.rotateGridCw(ccw1, 1))
        assertEquals(cells, ContinuousColorModel.rotateGridCcw(cells, 4))
        assertEquals(cells, ContinuousColorModel.rotateGridCw(cells, 4))

        val q = Quad2D(listOf(Point2D(0.0, 0.0), Point2D(10.0, 0.0), Point2D(10.0, 10.0), Point2D(0.0, 10.0)))
        val rolled = ContinuousColorModel.rollQuad(q, 1)
        assertEquals(q.corners[1], rolled.corners[0])
        assertEquals(q, ContinuousColorModel.rollQuad(rolled, 3))

        // Degenerate balanced solver inputs
        assertEquals(0, ContinuousColorModel.solveBalanced8PerColor(emptyArray()).size)
        assertEquals(0, ContinuousColorModel.solveBalanced8PerColor(arrayOf(doubleArrayOf())).size)
        assertThrows(IllegalArgumentException::class.java) {
            ContinuousColorModel.solveBalanced8PerColor(arrayOf(doubleArrayOf(1.0, 2.0), doubleArrayOf(3.0)))
        }
    }
}
