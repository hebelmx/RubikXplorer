package dev.cubentia.app.camera

import dev.cubentia.app.camera.corner.CornerModelTransform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CornerModelTransformTest {
    @Test
    fun `cropToFrame and frameToCrop round-trip exactly across all four sensor rotations`() {
        val cx = 320.0
        val cy = 240.0
        val half = 165.0
        val uvSamples = listOf(0.12 to 0.18, 0.5 to 0.5, 0.87 to 0.24, 0.21 to 0.89, 0.78 to 0.72)
        for (rotation in listOf(0, 90, 180, 270, -90, 450)) {
            for ((u, v) in uvSamples) {
                val framePt = CornerModelTransform.cropToFrame(u, v, cx, cy, half, rotation)
                val back = CornerModelTransform.frameToCrop(framePt.x, framePt.y, cx, cy, half, rotation)
                assertEquals(u, back.x, 1e-6, "u rot=$rotation")
                assertEquals(v, back.y, 1e-6, "v rot=$rotation")
            }
        }
    }

    @Test
    fun `fillRgbInput extracts an upright crop regardless of 0, 90, 180, or 270 sensor rotation`() {
        // Place a bright red patch at upright top-center (u=0.5, v=0.2) and bright blue at upright right-center (u=0.8, v=0.5).
        val redMarker = 0xe82020
        val blueMarker = 0x2048e8
        val bg = 0x202020
        val buffer = ByteBuffer.allocateDirect(CornerModelTransform.INPUT_BYTES).order(ByteOrder.nativeOrder())

        for (rotation in listOf(0, 90, 180, 270)) {
            val width = if (rotation % 180 == 0) 240 else 320
            val height = if (rotation % 180 == 0) 320 else 240
            val guide = CornerGuide(grid(width, height, rotation), rotation)
            val center = guide.vertices().last()
            val half = guide.radius * CornerModelTransform.CROP_RADIUS_MULTIPLIER
            val redCenter = CornerModelTransform.cropToFrame(0.5, 0.2, center.x, center.y, half, rotation)
            val blueCenter = CornerModelTransform.cropToFrame(0.8, 0.5, center.x, center.y, half, rotation)

            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val rgb =
                        when {
                            kotlin.math.hypot(x - redCenter.x, y - redCenter.y) <= 10.0 -> redMarker
                            kotlin.math.hypot(x - blueCenter.x, y - blueCenter.y) <= 10.0 -> blueMarker
                            else -> bg
                        }
                    image.setRGB(x, y, rgb)
                }
            }
            CornerModelTransform.fillRgbInput(image.toCornerFrame(), guide, buffer)

            fun rgbAt(
                col: Int,
                row: Int,
            ): Triple<Float, Float, Float> {
                val base = (row * CornerModelTransform.INPUT_SIZE + col) * 3 * Float.SIZE_BYTES
                return Triple(buffer.getFloat(base), buffer.getFloat(base + 4), buffer.getFloat(base + 8))
            }

            // Upright top-center -> col ~ 64, row ~ 25 should be strongly red
            val (rTop, gTop, bTop) = rgbAt(64, 25)
            assertTrue(rTop > 0.70f && rTop > gTop + 0.40f && rTop > bTop + 0.40f, "rot=$rotation top=($rTop,$gTop,$bTop)")

            // Upright right-center -> col ~ 102, row ~ 64 should be strongly blue
            val (rRight, gRight, bRight) = rgbAt(102, 64)
            assertTrue(
                bRight > 0.70f && bRight > rRight + 0.40f && bRight > gRight + 0.20f,
                "rot=$rotation right=($rRight,$gRight,$bRight)",
            )
        }
    }

    @Test
    fun `decode maps valid 7 keypoints across all rotations and rejects low presence or bad geometry`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val width = if (rotation % 180 == 0) 240 else 320
            val height = if (rotation % 180 == 0) 320 else 240
            val guide = CornerGuide(grid(width, height, rotation), rotation)
            val center = guide.vertices().last()
            val half = guide.radius * CornerModelTransform.CROP_RADIUS_MULTIPLIER
            val pts = CornerLocator.projectCube(center, guide.radius * 0.82, rotation, 32.0, -8.0, 6.0, 0.22)
            val output = FloatArray(CornerModelTransform.OUTPUT_FLOATS)
            output[0] = 0.91f
            pts.forEachIndexed { idx, p ->
                val uv = CornerModelTransform.frameToCrop(p.x, p.y, center.x, center.y, half, rotation)
                output[1 + idx * 2] = uv.x.toFloat()
                output[2 + idx * 2] = uv.y.toFloat()
            }

            val located = CornerModelTransform.decode(output, guide)
            assertNotNull(located, "rot=$rotation")
            assertEquals(0.91, located!!.score, 1e-4)
            assertEquals(3, located.quads.size)
            val expectedQuads = guide.quads(pts)
            expectedQuads.zip(located.quads).forEach { (expQ, gotQ) ->
                expQ.zip(gotQ).forEach { (e, g) ->
                    assertEquals(e.x, g.x, 1e-3, "rot=$rotation")
                    assertEquals(e.y, g.y, 1e-3, "rot=$rotation")
                }
            }

            // Below presence threshold -> null
            val lowPresence = output.copyOf().also { it[0] = 0.42f }
            assertNull(CornerModelTransform.decode(lowPresence, guide))

            // Non-finite value -> null
            val nanOutput = output.copyOf().also { it[3] = Float.NaN }
            assertNull(CornerModelTransform.decode(nanOutput, guide))
        }

        // Collapsed geometry (all points identical) -> rejected by isValidCubeGeometry
        val guide = CornerGuide(grid(240, 320, 0), 0)
        val collapsed = FloatArray(CornerModelTransform.OUTPUT_FLOATS) { 0.5f }.also { it[0] = 0.99f }
        assertNull(CornerModelTransform.decode(collapsed, guide))
    }
}
