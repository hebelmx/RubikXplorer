package dev.cubentia.app.camera

import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.FrameQuality
import dev.cubentia.domain.recognition.Lab
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.math.sqrt

/** Story 10.2: frame stats from synthetic YUV planes. */
class FrameReaderTest {
    private val width = 120
    private val height = 90

    private fun frame(
        u: Int = 128,
        v: Int = 128,
        luma: (Int, Int) -> Int,
    ): YuvFrame {
        val y = ByteBuffer.allocate(width * height)
        for (row in 0 until height) for (column in 0 until width) y.put(row * width + column, luma(column, row).toByte())

        fun chroma(value: Int) =
            YuvPlane(
                ByteBuffer.allocate(width * height / 4).also { b -> repeat(b.capacity()) { b.put(it, value.toByte()) } },
                width / 2,
                1,
            )
        return YuvFrame(YuvPlane(y, width, 1), chroma(u), chroma(v), 0, 0, width, height)
    }

    @Test
    fun `a flat gray frame reads nine plain gray cells, no glare and no sharpness`() {
        val stats = FrameReader.read(frame { _, _ -> 128 })
        assertEquals(9, stats.cells.size)
        assertTrue(stats.cells.all { it != null && it.plain })
        val gray = stats.cells.first()!!.value
        assertEquals(53.6, gray.l, 0.5)
        assertEquals(0.0, gray.a, 0.5)
        assertEquals(128.0, stats.meanLuma, 1e-9)
        assertEquals(0.0, stats.glareFraction, 1e-9)
        assertEquals(0.0, stats.sharpness, 1e-9)
        assertEquals(CaptureQuality.BLURRED, FrameQuality.assess(stats))
    }

    @Test
    fun `a sharp checkerboard is sharp, a saturated frame is glare and a dark one low light`() {
        val sharp = FrameReader.read(frame { x, _ -> if (x / 3 % 2 == 0) 60 else 200 })
        assertTrue(sharp.sharpness > FrameQuality.MIN_SHARPNESS, "sharpness ${sharp.sharpness}")
        assertEquals(1.0, FrameReader.read(frame { _, _ -> 255 }).glareFraction, 1e-9)
        assertEquals(CaptureQuality.LOW_LIGHT, FrameQuality.assess(FrameReader.read(frame { _, _ -> 10 })))
    }

    @Test
    fun `a red chroma frame reads red cells`() {
        val red = FrameReader.read(frame(u = 90, v = 200) { _, _ -> 100 }).cells[4]!!.value
        assertTrue(red.a > 40, "a* ${red.a}")
    }

    @Test
    fun `an empty crop reads no cells`() {
        val empty = frame { _, _ -> 128 }.let { YuvFrame(it.y, it.u, it.v, 10, 10, 10, 10) }
        assertEquals(CaptureQuality.LOW_LIGHT, FrameQuality.assess(FrameReader.read(empty)))
        assertTrue(FrameReader.read(empty).cells.all { it == null })
    }

    /**
     * A frame laid out like a real camera buffer: Y rows padded to [yRowStride], chroma at [chromaPixelStride] (2 =
     * interleaved, U and V sharing one buffer one byte apart) with rows padded to [chromaRowStride], and the visible
     * crop at ([left], [top]) of size [cropWidth]×[cropHeight] inside a larger [bufferWidth]×[bufferHeight] image.
     * [chroma] gives the (U, V) pair at a full-resolution pixel.
     */
    private fun laidOut(
        bufferWidth: Int,
        bufferHeight: Int,
        yRowStride: Int,
        chromaPixelStride: Int,
        chromaRowStride: Int,
        left: Int,
        top: Int,
        cropWidth: Int,
        cropHeight: Int,
        luma: Int = 120,
        chroma: (Int, Int) -> Pair<Int, Int>,
    ): YuvFrame {
        val y = ByteBuffer.allocate(yRowStride * bufferHeight)
        repeat(y.capacity()) { y.put(it, GARBAGE.toByte()) }
        for (row in 0 until bufferHeight) for (column in 0 until bufferWidth) y.put(row * yRowStride + column, luma.toByte())
        val chromaHeight = bufferHeight / 2
        val chromaWidth = bufferWidth / 2
        val u: YuvPlane
        val v: YuvPlane
        if (chromaPixelStride == 2) {
            val shared = ByteBuffer.allocate(chromaRowStride * chromaHeight)
            repeat(shared.capacity()) { shared.put(it, GARBAGE.toByte()) }
            for (row in 0 until chromaHeight) {
                for (column in 0 until chromaWidth) {
                    val (cu, cv) = chroma(column * 2, row * 2)
                    shared.put(row * chromaRowStride + column * 2, cu.toByte())
                    shared.put(row * chromaRowStride + column * 2 + 1, cv.toByte())
                }
            }
            u = YuvPlane(shared, chromaRowStride, 2)
            v = YuvPlane(shared.duplicate().apply { position(1) }.slice(), chromaRowStride, 2)
        } else {
            val ub = ByteBuffer.allocate(chromaRowStride * chromaHeight)
            val vb = ByteBuffer.allocate(chromaRowStride * chromaHeight)
            repeat(ub.capacity()) {
                ub.put(it, GARBAGE.toByte())
                vb.put(it, GARBAGE.toByte())
            }
            for (row in 0 until chromaHeight) {
                for (column in 0 until chromaWidth) {
                    val (cu, cv) = chroma(column * 2, row * 2)
                    ub.put(row * chromaRowStride + column, cu.toByte())
                    vb.put(row * chromaRowStride + column, cv.toByte())
                }
            }
            u = YuvPlane(ub, chromaRowStride, 1)
            v = YuvPlane(vb, chromaRowStride, 1)
        }
        return YuvFrame(YuvPlane(y, yRowStride, 1), u, v, left, top, left + cropWidth, top + cropHeight)
    }

    /** The grid cell (0–8, row-major) a full-resolution pixel falls in for a crop, or -1 outside the grid. */
    private fun cellAt(
        x: Int,
        y: Int,
        left: Int,
        top: Int,
        cropWidth: Int,
        cropHeight: Int,
    ): Int {
        val side = minOf(cropWidth, cropHeight) * FrameReader.GRID_FRACTION
        val column = ((x - left - (cropWidth - side) / 2) / (side / 3)).let { if (it < 0) -1 else it.toInt() }
        val row = ((y - top - (cropHeight - side) / 2) / (side / 3)).let { if (it < 0) -1 else it.toInt() }
        return if (column in 0..2 && row in 0..2) row * 3 + column else -1
    }

    /** The CIELAB a uniform (U, V) frame reads as, for comparison. */
    private fun uniformLab(chroma: Pair<Int, Int>): Lab =
        FrameReader.read(frame(u = chroma.first, v = chroma.second) { _, _ -> 120 }).cells[4]!!.value

    private val blocks: List<Pair<Int, Int>> =
        listOf(70, 128, 190).flatMap { row -> listOf(70, 128, 190).map { column -> row to column } }

    @Test
    fun `nine distinct chroma blocks read in row-major order`() {
        val frame =
            laidOut(160, 120, 176, 2, 176, 12, 10, 136, 100) { x, y ->
                blocks.getOrElse(cellAt(x, y, 12, 10, 136, 100)) { 128 to 128 }
            }
        val cells = FrameReader.read(frame).cells
        val expected = blocks.map(::uniformLab)
        cells.forEachIndexed { index, cell ->
            val nearest = expected.indices.minBy { deltaE(cell!!.value, expected[it]) }
            assertEquals(index, nearest, "cell $index (row ${index / 3}, column ${index % 3})")
            assertTrue(deltaE(cell!!.value, expected[index]) < 1.0, "cell $index ${cell.value}")
        }
    }

    private val red = 90 to 200

    /** A frame whose center cell is [red] and every other pixel a blue-ish chroma. */
    private fun centerOnly(
        bufferWidth: Int,
        bufferHeight: Int,
        yRowStride: Int,
        chromaPixelStride: Int,
        chromaRowStride: Int,
        left: Int,
        top: Int,
        cropWidth: Int,
        cropHeight: Int,
    ): YuvFrame =
        laidOut(bufferWidth, bufferHeight, yRowStride, chromaPixelStride, chromaRowStride, left, top, cropWidth, cropHeight) { x, y ->
            if (cellAt(x, y, left, top, cropWidth, cropHeight) == 4) red else 190 to 90
        }

    private fun assertReadsRedCenter(frame: YuvFrame) {
        val center = FrameReader.read(frame).cells[4]!!.value
        assertTrue(deltaE(center, uniformLab(red)) < 1.0, "center $center")
    }

    @Test
    fun `interleaved chroma with pixelStride 2 reads the painted center`() {
        assertReadsRedCenter(centerOnly(120, 90, 120, 2, 120, 0, 0, 120, 90))
    }

    @Test
    fun `a padded rowStride wider than the image reads the painted center`() {
        assertReadsRedCenter(centerOnly(120, 90, 128, 1, 64, 0, 0, 120, 90))
    }

    @Test
    fun `a crop not at the origin reads the painted center`() {
        assertReadsRedCenter(centerOnly(160, 120, 160, 1, 80, 30, 20, 120, 90))
    }

    @Test
    fun `sRGB white and black convert to CIELAB extremes`() {
        assertEquals(100.0, FrameReader.rgbToLab(255, 255, 255).l, 0.01)
        assertEquals(0.0, FrameReader.rgbToLab(0, 0, 0).l, 0.01)
    }

    /** CIE76 ΔE (the domain's helper is internal to `:core:domain`). */
    private fun deltaE(
        first: Lab,
        second: Lab,
    ): Double {
        val dl = first.l - second.l
        val da = first.a - second.a
        val db = first.b - second.b
        return sqrt(dl * dl + da * da + db * db)
    }

    private companion object {
        /** Bytes outside the image (row padding, outside the crop) hold this, so reading them shows. */
        const val GARBAGE = 255
    }
}
