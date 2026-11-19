package dev.cubentia.domain.input

import dev.cubentia.domain.cube.Face
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToInt
import kotlin.math.sin

class FaceLabelsTest {
    @Test
    fun `example orbit places F below-left R right and U above outside the hull`() {
        val pose = ViewPose(1000, 1000, ViewOrbit(35.0, 32.0).eye())
        val labels = FaceLabels.place(pose, labelWidth = 20.0, labelHeight = 24.0, gap = 8.0)
        assertEquals(setOf(Face.F, Face.R, Face.U), labels.map { it.face }.toSet())
        val byFace = labels.associateBy { it.face }
        val fCentre = pose.project(Vector3(0.0, 0.0, 1.5))
        val rCentre = pose.project(Vector3(1.5, 0.0, 0.0))
        val uCentre = pose.project(Vector3(0.0, 1.5, 0.0))
        val f = byFace.getValue(Face.F)
        val r = byFace.getValue(Face.R)
        val u = byFace.getValue(Face.U)
        assertTrue(f.left + 10.0 < fCentre.x && f.top + 12.0 > fCentre.y, "f=$f fCentre=$fCentre")
        assertTrue(r.left + 10.0 > rCentre.x, "r=$r rCentre=$rCentre")
        assertTrue(u.top + 12.0 < uCentre.y, "u=$u uCentre=$uCentre")
        // And at yaw 30° / pitch 25° (per spec task), every face exceeding MIN_FACING_DEGREES gets a letter.
        val labels30 = FaceLabels.place(ViewPose(1000, 1000, ViewOrbit(30.0, 25.0).eye()), 20.0, 24.0, 8.0)
        assertEquals(listOf(Face.F), labels30.map { it.face })
    }

    @Test
    fun `head-on face uses fallback direction and edge-on faces are omitted`() {
        // Looking straight at F (yaw 0, pitch 0): F projects to the origin, U/D/R/L are edge-on.
        val pose = ViewPose(600, 600, ViewOrbit(0.0, 0.0).eye())
        val labels = FaceLabels.place(pose, labelWidth = 20.0, labelHeight = 24.0, gap = 8.0)
        assertEquals(listOf(Face.F), labels.map { it.face })
        val f = labels.single()
        assertTrue(f.top > 300.0, "Fallback direction places F below the cube")
    }

    @Test
    fun `sweep across orbits densities and stage sizes never overlaps stickers hull or other labels`() {
        val minSin = sin(Math.toRadians(FaceLabels.MIN_FACING_DEGREES))
        val baseStagesDp =
            listOf(
                96 to 96,
                280 to 280,
                360 to 420,
                420 to 360,
                640 to 640,
            )
        val densities = listOf(1.0, 2.0, 3.5)

        for (density in densities) {
            // A ~1.25-char notation glyph (16sp titleMedium): ~15dp wide × 20dp tall, 6dp gap.
            val labelW = 15.0 * density
            val labelH = 20.0 * density
            val gap = 6.0 * density
            for ((wDp, hDp) in baseStagesDp) {
                val stageW = (wDp * density).roundToInt()
                val stageH = (hDp * density).roundToInt()
                for (yaw in -180..165 step 15) {
                    for (pitch in -80..80 step 20) {
                        val orbit = ViewOrbit(yaw.toDouble(), pitch.toDouble())
                        val pose = ViewPose(stageW, stageH, orbit.eye())
                        val labels = FaceLabels.place(pose, labelW, labelH, gap)
                        val hull = FaceLabels.silhouette(pose)
                        val stickers = FaceLabels.stickerQuads(pose)

                        for (label in labels) {
                            // Inside stage bounds.
                            assertTrue(label.left >= -1e-9 && label.left + labelW <= stageW + 1e-9)
                            assertTrue(label.top >= -1e-9 && label.top + labelH <= stageH + 1e-9)

                            // Facing angle strictly above MIN_FACING_DEGREES.
                            val normal = normalOf(label.face)
                            val facingSin = normal.dot((pose.eye - normal * 1.5).normalized())
                            assertTrue(facingSin > minSin, "Face ${label.face} at $orbit has facingSin=$facingSin <= $minSin")

                            // Disjoint from silhouette polygon and every visible sticker quad.
                            assertFalse(
                                FaceLabels.boxIntersectsPolygon(
                                    label.left,
                                    label.top,
                                    label.left + labelW,
                                    label.top + labelH,
                                    hull,
                                ),
                                "Label ${label.face} overlaps silhouette at $orbit on ${stageW}x$stageH",
                            )
                            for (quad in stickers) {
                                assertFalse(
                                    FaceLabels.boxIntersectsPolygon(
                                        label.left,
                                        label.top,
                                        label.left + labelW,
                                        label.top + labelH,
                                        quad,
                                    ),
                                    "Label ${label.face} overlaps sticker quad at $orbit on ${stageW}x$stageH",
                                )
                            }
                        }

                        // No two placed label boxes overlap.
                        for (i in labels.indices) {
                            for (j in i + 1 until labels.size) {
                                val a = labels[i]
                                val b = labels[j]
                                val overlaps =
                                    a.left < b.left + labelW &&
                                        a.left + labelW > b.left &&
                                        a.top < b.top + labelH &&
                                        a.top + labelH > b.top
                                assertFalse(overlaps, "Labels ${a.face} and ${b.face} overlap at $orbit on ${stageW}x$stageH")
                            }
                        }

                        // Non-vacuous on >= 600px square stage: every face above MIN_FACING_DEGREES gets a label.
                        if (stageW >= 600 && stageH >= 600 && stageW == stageH) {
                            val expectedFaces =
                                Face.entries
                                    .filter { face ->
                                        val n = normalOf(face)
                                        n.dot((pose.eye - n * 1.5).normalized()) > minSin
                                    }.toSet()
                            assertEquals(expectedFaces, labels.map { it.face }.toSet(), "Missing faces at $orbit on ${stageW}x$stageH")
                        }
                    }
                }
            }
        }
    }

    private fun normalOf(face: Face): Vector3 =
        when (face) {
            Face.R -> Vector3(1.0, 0.0, 0.0)
            Face.L -> Vector3(-1.0, 0.0, 0.0)
            Face.U -> Vector3(0.0, 1.0, 0.0)
            Face.D -> Vector3(0.0, -1.0, 0.0)
            Face.F -> Vector3(0.0, 0.0, 1.0)
            Face.B -> Vector3(0.0, 0.0, -1.0)
        }
}
