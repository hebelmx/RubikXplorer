package dev.cubentia.app.camera

import dev.cubentia.app.camera.corner.CornerModelTransform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    @Test
    fun `decode rejects presence outside 0 to 1 and accepts presence exactly 1 (#270)`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val (guide, output) = validOutput(rotation)

            val above = output.copyOf().also { it[0] = 1.2f }
            assertNull(CornerModelTransform.decode(above, guide), "rot=$rotation presence=1.2")

            // Threshold below zero so only the range check can reject a negative presence.
            val below = output.copyOf().also { it[0] = -0.1f }
            assertNull(CornerModelTransform.decode(below, guide, presenceThreshold = -1f), "rot=$rotation presence=-0.1")

            val exactlyOne = output.copyOf().also { it[0] = 1.0f }
            val located = CornerModelTransform.decode(exactlyOne, guide)
            assertNotNull(located, "rot=$rotation presence=1.0")
            assertEquals(3, located!!.quads.size)

            val exactlyZero = output.copyOf().also { it[0] = 0.0f }
            assertNotNull(CornerModelTransform.decode(exactlyZero, guide, presenceThreshold = -1f), "rot=$rotation presence=0.0")
        }
    }

    @Test
    fun `decode rejects any keypoint u or v outside 0 to 1 (#270)`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val (guide, output) = validOutput(rotation)
            for (idx in 1 until CornerModelTransform.OUTPUT_FLOATS) {
                for (bad in listOf(-0.05f, 1.05f)) {
                    val perturbed = output.copyOf().also { it[idx] = bad }
                    assertNull(CornerModelTransform.decode(perturbed, guide), "rot=$rotation index=$idx value=$bad")
                }
            }
        }
    }

    @Test
    fun `decode rejects out-of-range keypoints even when cube geometry would pass and accepts exact bounds (#270)`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            // A cube that nearly fills the crop puts outer keypoints close to the [0, 1] edges.
            val (guide, output) = validOutput(rotation, cubeRadiusScale = 1.5)
            val center = guide.vertices().last()
            val half = guide.radius * CornerModelTransform.CROP_RADIUS_MULTIPLIER
            var checked = 0
            assertNotNull(CornerModelTransform.decode(output, guide), "rot=$rotation baseline must be valid")
            for (idx in 1 until CornerModelTransform.OUTPUT_FLOATS) {
                // Only coordinates already near an edge can be pushed past it without breaking the cube shape.
                if (minOf(output[idx], 1f - output[idx]) > 0.06f) continue
                checked++
                val towardOne = output[idx] >= 0.5f
                val bound = if (towardOne) 1.0f else 0.0f
                val beyond = if (towardOne) 1.01f else -0.01f

                val atBound = output.copyOf().also { it[idx] = bound }
                assertNotNull(CornerModelTransform.decode(atBound, guide), "rot=$rotation index=$idx value=$bound")

                val outOfRange = output.copyOf().also { it[idx] = beyond }
                val mapped =
                    List(CornerModelTransform.NUM_KEYPOINTS) { k ->
                        CornerModelTransform.cropToFrame(
                            outOfRange[1 + k * 2].toDouble(),
                            outOfRange[2 + k * 2].toDouble(),
                            center.x,
                            center.y,
                            half,
                            rotation,
                        )
                    }
                assertTrue(CornerLocator.isValidCubeGeometry(mapped, guide), "rot=$rotation index=$idx geometry should pass")
                assertNull(CornerModelTransform.decode(outOfRange, guide), "rot=$rotation index=$idx value=$beyond")
            }
            assertTrue(checked >= 2, "rot=$rotation expected near-edge keypoint coordinates, got $checked")
        }
    }

    @Test
    fun `out-of-range decode output never reaches face unwarping through CornerReader (#270)`() {
        val (guide, output) = validOutput(0)
        val frame = BufferedImage(240, 320, BufferedImage.TYPE_INT_RGB).toCornerFrame()

        fun readWith(modelOutput: FloatArray): Pair<CornerFrame, CornerLocator.Location?> {
            var located: CornerLocator.Location? = null
            val detector =
                CornerDetector { _, g -> CornerModelTransform.decode(modelOutput, g).also { located = it } }
            return CornerReader.read(frame, guide, detector = detector) to located
        }

        val badPresence = output.copyOf().also { it[0] = 1.2f }
        val badUv = output.copyOf().also { it[1] = 1.05f }
        for ((name, bad) in listOf("presence=1.2" to badPresence, "T.x=1.05" to badUv)) {
            val (corner, located) = readWith(bad)
            assertNull(located, name)
            assertFalse(corner.detected, name)
            assertTrue(corner.faces.all { it == null }, name)
        }

        // Non-vacuous: the same path with the valid output hands quads to unwarping.
        val (_, validLocated) = readWith(output)
        assertNotNull(validLocated)
        assertEquals(3, validLocated!!.quads.size)
    }

    private fun validOutput(
        rotation: Int,
        cubeRadiusScale: Double = 0.82,
    ): Pair<CornerGuide, FloatArray> {
        val width = if (rotation % 180 == 0) 240 else 320
        val height = if (rotation % 180 == 0) 320 else 240
        val guide = CornerGuide(grid(width, height, rotation), rotation)
        val center = guide.vertices().last()
        val half = guide.radius * CornerModelTransform.CROP_RADIUS_MULTIPLIER
        val pts = CornerLocator.projectCube(center, guide.radius * cubeRadiusScale, rotation, 32.0, -8.0, 6.0, 0.22)
        val output = FloatArray(CornerModelTransform.OUTPUT_FLOATS)
        output[0] = 0.91f
        pts.forEachIndexed { idx, p ->
            val uv = CornerModelTransform.frameToCrop(p.x, p.y, center.x, center.y, half, rotation)
            output[1 + idx * 2] = uv.x.toFloat()
            output[2 + idx * 2] = uv.y.toFloat()
        }
        return guide to output
    }
}
