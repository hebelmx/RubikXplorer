package dev.cubentia.app.camera

import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.FrameQuality
import dev.cubentia.domain.recognition.FrameStats
import dev.cubentia.domain.recognition.GuidedScanController
import dev.cubentia.domain.recognition.GuidedScanEffect
import dev.cubentia.domain.recognition.Lab
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.util.Random
import kotlin.math.abs
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
    fun `a sharp checkerboard is sharp, a saturated neutral frame is white not glare and a dark one low light`() {
        val sharp = FrameReader.read(frame { x, _ -> if (x / 3 % 2 == 0) 60 else 200 })
        assertTrue(sharp.sharpness > FrameQuality.MIN_SHARPNESS, "sharpness ${sharp.sharpness}")
        // Clipped and neutral everywhere reads as nine plain white cells (a clipped white face), not a highlight.
        val saturated = FrameReader.read(frame { _, _ -> 255 })
        assertEquals(0.0, saturated.glareFraction, 1e-9)
        assertTrue(saturated.cells.all { it != null && it.plain })
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

    /** A [imageWidth]×[imageHeight] frame with planar chroma; [chroma] gives (U, V) at a full-resolution pixel. */
    private fun image(
        luma: (Int, Int) -> Int,
        chroma: (Int, Int) -> Pair<Int, Int> = { _, _ -> 128 to 128 },
        imageWidth: Int = this.imageWidth,
        imageHeight: Int = this.imageHeight,
    ): YuvFrame {
        val y = ByteBuffer.allocate(imageWidth * imageHeight)
        for (row in 0 until imageHeight) {
            for (column in 0 until imageWidth) y.put(row * imageWidth + column, luma(column, row).coerceIn(0, 255).toByte())
        }
        val u = ByteBuffer.allocate(imageWidth * imageHeight / 4)
        val v = ByteBuffer.allocate(imageWidth * imageHeight / 4)
        for (row in 0 until imageHeight / 2) {
            for (column in 0 until imageWidth / 2) {
                val (cu, cv) = chroma(column * 2, row * 2)
                u.put(row * imageWidth / 2 + column, cu.toByte())
                v.put(row * imageWidth / 2 + column, cv.toByte())
            }
        }
        return YuvFrame(
            YuvPlane(y, imageWidth, 1),
            YuvPlane(u, imageWidth / 2, 1),
            YuvPlane(v, imageWidth / 2, 1),
            0,
            0,
            imageWidth,
            imageHeight,
        )
    }

    private val imageWidth = 320
    private val imageHeight = 240
    private val gridSide = minOf(imageWidth, imageHeight) * FrameReader.GRID_FRACTION
    private val gridLeft = (imageWidth - gridSide) / 2
    private val gridTop = (imageHeight - gridSide) / 2
    private val cellSide = gridSide / 3

    /** Sticker (Y, U, V) per cell: a scrambled face, green center. */
    private val stickers =
        listOf(
            Triple(200, 128, 128), // white
            Triple(80, 90, 200), // red
            Triple(110, 70, 100), // green
            Triple(180, 30, 150), // yellow
            Triple(110, 70, 100), // green (center)
            Triple(60, 200, 110), // blue
            Triple(130, 70, 190), // orange
            Triple(200, 128, 128), // white
            Triple(80, 90, 200), // red
        )

    /**
     * A cube face [dx], [dy] pixels off the grid: nine stickers with a [gap]-pixel black plastic gap around each, on a
     * dark background, plus a little sensor noise.
     */
    private fun cubeFace(
        dx: Double = 0.0,
        dy: Double = 0.0,
        gap: Int = 6,
        colors: List<Triple<Int, Int, Int>> = stickers,
        gapLuma: Int = PLASTIC,
        paint: (Int, Int) -> Triple<Int, Int, Int>? = { _, _ -> null },
        highlight: (Int, Int) -> Boolean = { _, _ -> false },
    ): YuvFrame {
        val noise = Random(7)

        fun sticker(
            x: Int,
            y: Int,
        ): Triple<Int, Int, Int>? {
            val fx = x - gridLeft - dx
            val fy = y - gridTop - dy
            if (fx < 0 || fy < 0 || fx >= gridSide || fy >= gridSide) return null
            val inX = fx % cellSide
            val inY = fy % cellSide
            if (inX < gap / 2.0 || inX >= cellSide - gap / 2.0 || inY < gap / 2.0 || inY >= cellSide - gap / 2.0) return null
            return paint(x, y) ?: colors[(fy / cellSide).toInt() * 3 + (fx / cellSide).toInt()]
        }
        return image(
            luma = { x, y -> if (highlight(x, y)) 255 else (sticker(x, y)?.first ?: gapLuma) + noise.nextInt(7) - 3 },
            chroma = { x, y -> sticker(x, y)?.takeUnless { highlight(x, y) }?.let { it.second to it.third } ?: (128 to 128) },
        )
    }

    /** Uniform gray floor or wall with sensor noise strong enough to read sharp. */
    private fun noisyGray(): YuvFrame {
        val noise = Random(11)
        return image(luma = { _, _ -> 128 + noise.nextInt(61) - 30 })
    }

    /** Terrazzo: light gray with scattered chips of other grays, and a dark grout line along the first column line. */
    private fun terrazzo(): YuvFrame {
        val random = Random(3)
        val chips = List(400) { Triple(random.nextInt(imageWidth), random.nextInt(imageHeight), 110 + random.nextInt(130)) }
        val grout = (gridLeft + cellSide).toInt()
        return image(luma = { x, y ->
            val chip = chips.firstOrNull { (cx, cy) -> abs(cx - x) + abs(cy - y) <= 4 }
            when {
                abs(x - grout) <= 1 -> 70
                chip != null -> chip.third
                else -> 175
            }
        })
    }

    /**
     * Every effect of feeding [stats] for 5 s at 10 fps: auto-capture only, since the manual shutter skips the cube-face
     * check (PO 2026-11-16).
     */
    private fun effectsOf(stats: FrameStats): List<GuidedScanEffect> {
        val controller = GuidedScanController()
        return (0L..5000L step 100).flatMap { controller.onFrame(stats, 0, it) }
    }

    /** Piece (R, G, B) per cell of the device-check face, sampled from the screenshot: green center, white on top. */
    private val deviceFace =
        listOf(
            Triple(31, 194, 41),
            Triple(207, 208, 199),
            Triple(3, 98, 190),
            Triple(202, 203, 193),
            Triple(11, 196, 32),
            Triple(218, 7, 23),
            Triple(245, 80, 5),
            Triple(1, 85, 185),
            Triple(6, 88, 187),
        )

    /**
     * The device-check frame (`.git/bmad-orchestrate/device-10-2/galaxy-stickerless-aligned-no-capture.png`, S24 FE,
     * 2026-11-16) as the analyzer sees it, synthesized because the screenshot's grid overlay hides the seams: a
     * stickerless face in a 480×400 crop (cell ≈ 107 px), its nine piece colors sampled from the screenshot, [scale]
     * times the grid about its center (the cube is a little larger than the drawn grid), shifted by [dy] cells (the
     * lower seam sits ~8 % of a cell below its grid line in the screenshot) and tilted [degrees]. Pieces are parted by
     * thin dark gaps [seam] cells (≈ 1.6 px) wide and have rounded corners (radius [corner] cells) showing the dark core; the luma
     * is box-blurred over ±[blur] px (1: the lens; more: motion), plus light, denoised sensor noise.
     */
    private fun stickerlessFace(
        colors: List<Triple<Int, Int, Int>> = deviceFace,
        scale: Double = 1.06,
        dy: Double = 0.08,
        degrees: Double = 2.0,
        seam: Double = 0.015,
        corner: Double = 0.18,
        blur: Int = 1,
    ): YuvFrame {
        val width = 480
        val height = 400
        val side = minOf(width, height) * FrameReader.GRID_FRACTION
        val cell = side / 3
        val centerX = width / 2.0
        val centerY = height / 2.0 + dy * cell
        val cos = kotlin.math.cos(Math.toRadians(degrees))
        val sin = kotlin.math.sin(Math.toRadians(degrees))

        /** The piece color at a pixel, or null for a seam, a rounded corner or outside the face. */
        fun piece(
            x: Int,
            y: Int,
        ): Triple<Int, Int, Int>? {
            val px = x + 0.5 - centerX
            val py = y + 0.5 - centerY
            val fx = (cos * px + sin * py) / (scale * cell) + 1.5
            val fy = (-sin * px + cos * py) / (scale * cell) + 1.5
            if (fx < 0 || fy < 0 || fx >= 3 || fy >= 3) return null
            val inX = fx % 1
            val inY = fy % 1
            if (inX < seam / 2 || inX > 1 - seam / 2 || inY < seam / 2 || inY > 1 - seam / 2) return null
            val cx = inX.coerceIn(corner, 1 - corner)
            val cy = inY.coerceIn(corner, 1 - corner)
            if ((inX - cx) * (inX - cx) + (inY - cy) * (inY - cy) > corner * corner) return null
            return colors[fy.toInt() * 3 + fx.toInt()]
        }

        fun yuv(rgb: Triple<Int, Int, Int>): Triple<Int, Int, Int> {
            val (r, g, b) = rgb
            return Triple(
                (0.299 * r + 0.587 * g + 0.114 * b).toInt(),
                (128 - 0.168736 * r - 0.331264 * g + 0.5 * b).toInt().coerceIn(0, 255),
                (128 + 0.5 * r - 0.418688 * g - 0.081312 * b).toInt().coerceIn(0, 255),
            )
        }
        val sharp = Array(height) { y -> IntArray(width) { x -> piece(x, y)?.let { yuv(it).first } ?: CORE } }
        val noise = Random(5)
        return image(
            luma = { x, y ->
                var sum = 0
                for (j in -blur..blur) for (i in -blur..blur) sum += sharp[(y + j).coerceIn(0, height - 1)][(x + i).coerceIn(0, width - 1)]
                sum / ((2 * blur + 1) * (2 * blur + 1)) + noise.nextInt(3) - 1
            },
            chroma = { x, y -> piece(x, y)?.let { yuv(it).second to yuv(it).third } ?: (128 to 128) },
            imageWidth = width,
            imageHeight = height,
        )
    }

    @Test
    fun `the stickerless device-check face, larger than the grid and a little off it, auto-captures`() {
        val stats = FrameReader.read(stickerlessFace())
        assertTrue(stats.seams >= FrameQuality.MIN_SEAMS, "seams ${stats.seams}")
        assertEquals(CaptureQuality.ACCEPTABLE, FrameQuality.assess(stats))
        assertTrue(effectsOf(stats).any { it is GuidedScanEffect.Capture })
    }

    @Test
    fun `a solved stickerless face auto-captures`() {
        val stats = FrameReader.read(stickerlessFace(colors = List(9) { deviceFace[4] }))
        assertTrue(stats.seams >= FrameQuality.MIN_SEAMS, "seams ${stats.seams}")
        assertEquals(CaptureQuality.ACCEPTABLE, FrameQuality.assess(stats))
        assertTrue(effectsOf(stats).any { it is GuidedScanEffect.Capture })
    }

    @Test
    fun `a sharp stickerless face of flat cells is not blurred, a motion-blurred one is`() {
        val sharp = FrameReader.read(stickerlessFace())
        assertTrue(sharp.sharpness >= FrameQuality.MIN_SHARPNESS, "sharpness ${sharp.sharpness}")
        assertEquals(CaptureQuality.ACCEPTABLE, FrameQuality.assess(sharp))
        val moving = FrameReader.read(stickerlessFace(blur = 6))
        assertEquals(CaptureQuality.BLURRED, FrameQuality.assess(moving), "sharpness ${moving.sharpness}")
        assertTrue(effectsOf(moving).none { it is GuidedScanEffect.Capture })
    }

    private val clippedWhite = Triple(255, 128, 128)
    private val white = Triple(200, 128, 128)

    @Test
    fun `a stickered face with three or more clipped white stickers is not glare and captures`() {
        listOf(3, 5).forEach { whites ->
            val colors = stickers.mapIndexed { index, color -> if (index != 4 && index < whites + 1) clippedWhite else color }
            val stats = FrameReader.read(cubeFace(colors = colors))
            assertTrue(stats.glareFraction < FrameQuality.MAX_GLARE_FRACTION, "$whites whites glare ${stats.glareFraction}")
            assertEquals(CaptureQuality.ACCEPTABLE, FrameQuality.assess(stats), "$whites whites")
            assertTrue(effectsOf(stats).any { it is GuidedScanEffect.Capture }, "$whites whites")
        }
    }

    @Test
    fun `a specular highlight on a colored sticker is still glare`() {
        val centerX = gridLeft + 1.5 * cellSide
        val centerY = gridTop + 1.5 * cellSide
        val radius = 0.2 * cellSide
        val colors = stickers.mapIndexed { index, color -> if (index == 0) clippedWhite else color }
        val stats =
            FrameReader.read(
                cubeFace(colors = colors) { x, y -> (x - centerX) * (x - centerX) + (y - centerY) * (y - centerY) <= radius * radius },
            )
        assertEquals(CaptureQuality.GLARE, FrameQuality.assess(stats), "glare ${stats.glareFraction}")
        assertTrue(effectsOf(stats).none { it is GuidedScanEffect.Capture })
    }

    /**
     * The Lenovo device-check face (`.git/bmad-orchestrate/device-10-2/r2-lenovo-face2-false-glare.png`, 2026-11-16): a
     * stickered black-body cube sitting off-center left of the grid, so the left column's cells hold part of a clipped
     * white sticker next to wide black body — near-white is never glare (PO 2026-11-16).
     */
    @Test
    fun `an off-center stickered face with clipped white stickers beside black body is not glare and captures`() {
        val colors = stickers.mapIndexed { index, color -> if (index == 0 || index == 6) clippedWhite else color }
        listOf(-0.15, 0.15).forEach { offset ->
            val stats = FrameReader.read(cubeFace(dx = offset * cellSide, gap = 12, colors = colors))
            assertTrue(stats.glareFraction < FrameQuality.MAX_GLARE_FRACTION, "offset $offset glare ${stats.glareFraction}")
            assertEquals(CaptureQuality.ACCEPTABLE, FrameQuality.assess(stats), "offset $offset")
            assertTrue(effectsOf(stats).any { it is GuidedScanEffect.Capture }, "offset $offset")
        }
    }

    @Test
    fun `a small specular spot on a red or a blue sticker is glare`() {
        listOf(1, 5).forEach { index ->
            val centerX = gridLeft + (index % 3 + 0.5) * cellSide
            val centerY = gridTop + (index / 3 + 0.5) * cellSide
            val radius = 0.15 * cellSide
            val stats =
                FrameReader.read(
                    cubeFace { x, y -> (x - centerX) * (x - centerX) + (y - centerY) * (y - centerY) <= radius * radius },
                )
            assertEquals(CaptureQuality.GLARE, FrameQuality.assess(stats), "cell $index glare ${stats.glareFraction}")
            assertTrue(effectsOf(stats).none { it is GuidedScanEffect.Capture }, "cell $index")
        }
    }

    @Test
    fun `seams offset from the grid lines by up to 15 percent of a cell still capture`() {
        listOf(-0.15, 0.15).forEach { offset ->
            val stats = FrameReader.read(stickerlessFace(scale = 1.0, dy = offset))
            assertTrue(stats.seams >= FrameQuality.MIN_SEAMS, "offset $offset seams ${stats.seams}")
            assertTrue(effectsOf(stats).any { it is GuidedScanEffect.Capture }, "offset $offset")
        }
    }

    @Test
    fun `a cube face in the grid shows all twelve seams and captures`() {
        val stats = FrameReader.read(cubeFace())
        assertEquals(12, stats.seams)
        assertEquals(CaptureQuality.ACCEPTABLE, FrameQuality.assess(stats))
        assertTrue(effectsOf(stats).any { it is GuidedScanEffect.Capture })
    }

    @Test
    fun `a solved face and a face a little off the grid still show their seams`() {
        assertEquals(12, FrameReader.read(cubeFace(colors = List(9) { stickers[4] })).seams)
        val off = FrameReader.read(cubeFace(dx = cellSide * 0.15, dy = -cellSide * 0.15))
        assertTrue(off.seams >= FrameQuality.MIN_SEAMS, "seams ${off.seams}")
        assertEquals(CaptureQuality.ACCEPTABLE, FrameQuality.assess(off))
    }

    @Test
    fun `a cube half a cell off the grid is misaligned`() {
        val stats = FrameReader.read(cubeFace(dx = cellSide * 0.5, dy = cellSide * 0.5))
        assertTrue(stats.seams < FrameQuality.MIN_SEAMS, "seams ${stats.seams}")
        assertTrue(effectsOf(stats).none { it is GuidedScanEffect.Capture })
    }

    @Test
    fun `background with no cube in the grid never captures`() {
        mapOf("noisy uniform gray" to noisyGray(), "terrazzo floor" to terrazzo()).forEach { (name, frame) ->
            val stats = FrameReader.read(frame)
            // Bright, sharp and nine plain cells: what used to pass as aligned.
            assertTrue(stats.sharpness >= FrameQuality.MIN_SHARPNESS, "$name sharpness ${stats.sharpness}")
            assertTrue(stats.cells.all { it?.plain == true }, "$name cells ${stats.cells}")
            assertTrue(stats.seams < FrameQuality.MIN_SEAMS, "$name seams ${stats.seams}")
            assertEquals(CaptureQuality.MISALIGNED, FrameQuality.assess(stats), name)
            assertTrue(effectsOf(stats).none { it is GuidedScanEffect.Capture }, name)
        }
    }

    /** A cyan logo (Y, U, V) on the center sticker, a square covering [share] of the center cell's sampled ROI. */
    private fun centerLogo(share: Double): (Int, Int) -> Triple<Int, Int, Int>? {
        val half = cellSide * FrameReader.CELL_ROI_FRACTION * sqrt(share) / 2
        val centerX = gridLeft + 1.5 * cellSide
        val centerY = gridTop + 1.5 * cellSide
        return { x, y -> if (abs(x + 0.5 - centerX) <= half && abs(y + 0.5 - centerY) <= half) CYAN_LOGO else null }
    }

    @Test
    fun `a white center carrying a logo auto-captures and reads as white (PO 2026-11-16)`() {
        listOf(white to "white", clippedWhite to "clipped white").forEach { (base, name) ->
            val colors = stickers.mapIndexed { index, color -> if (index == 4) base else color }
            val plainCenter = FrameReader.read(cubeFace(colors = colors)).cells[4]!!.value
            listOf(0.25, 0.45).forEach { share ->
                val label = "$name center, logo $share"
                val stats = FrameReader.read(cubeFace(colors = colors, paint = centerLogo(share)))
                assertEquals(CaptureQuality.ACCEPTABLE, FrameQuality.assess(stats), "$label ${stats.cells[4]} glare ${stats.glareFraction}")
                assertTrue(effectsOf(stats).any { it is GuidedScanEffect.Capture }, label)
                val center = stats.cells[4]!!.value
                assertTrue(deltaE(center, plainCenter) < 3.0, "$label reads $center, plain $plainCenter")
            }
        }
    }

    @Test
    fun `a dull gray low-contrast pattern with faint seams never auto-captures`() {
        val grays = List(9) { Triple(if (it % 2 == 0) 140 else 128, 128 + it % 3 - 1, 128 - it % 3 + 1) }
        val stats = FrameReader.read(cubeFace(colors = grays, gapLuma = 105))
        assertTrue(stats.seams >= FrameQuality.MIN_SEAMS, "seams ${stats.seams}")
        assertEquals(CaptureQuality.ACCEPTABLE, FrameQuality.assessShutter(stats))
        assertEquals(CaptureQuality.MISALIGNED, FrameQuality.assess(stats))
        assertTrue(effectsOf(stats).none { it is GuidedScanEffect.Capture })
    }

    @Test
    fun `a solved white face and a solved yellow face still auto-capture`() {
        mapOf("white" to white, "clipped white" to clippedWhite, "yellow" to stickers[3]).forEach { (name, color) ->
            val stats = FrameReader.read(cubeFace(colors = List(9) { color }))
            assertEquals(CaptureQuality.ACCEPTABLE, FrameQuality.assess(stats), name)
            assertTrue(effectsOf(stats).any { it is GuidedScanEffect.Capture }, name)
        }
    }

    /** Sticker (R, G, B) per cell of the Lenovo stickered face, sampled from the screenshot (one pale orange added). */
    private val lenovoFace =
        listOf(
            Triple(245, 235, 100), // yellow
            Triple(230, 232, 228), // white
            Triple(0, 170, 235), // blue
            Triple(0, 175, 240), // blue
            Triple(10, 215, 20), // green (center)
            Triple(245, 235, 100), // yellow
            Triple(210, 40, 35), // red
            Triple(230, 232, 228), // white
            Triple(245, 190, 90), // orange, read pale
        )

    /**
     * The Lenovo device-check face (`.git/bmad-orchestrate/device-10-2/r4-lenovo-face1-aligned-not-detected.png`, TB128XU
     * portrait two-pane, 2026-11-16) as the analyzer sees it: the tall narrow pane's viewport crop of a 640×480 frame
     * (640×204, cell ≈ 54 px), a stickered black-body cube whose stickers are [sticker] of a cell with rounded corners
     * (radius [corner] cells), the face shifted [dx], [dy] cells off the grid and tilted [degrees], on the stand's light
     * mesh, lens-blurred over ±1 px with light sensor noise.
     */
    private fun stickeredFace(
        colors: List<Triple<Int, Int, Int>> = lenovoFace,
        sticker: Double = 0.8,
        corner: Double = 0.12,
        dx: Double = -0.1,
        dy: Double = -0.05,
        degrees: Double = 1.0,
    ): YuvFrame {
        val width = 640
        val height = 204
        val cell = minOf(width, height) * FrameReader.GRID_FRACTION / 3
        val centerX = width / 2.0 + dx * cell
        val centerY = height / 2.0 + dy * cell
        val cos = kotlin.math.cos(Math.toRadians(degrees))
        val sin = kotlin.math.sin(Math.toRadians(degrees))
        val border = (1 - sticker) / 2

        /** The pixel's sticker color, BODY for the black body, null off the cube. */
        fun color(
            x: Int,
            y: Int,
        ): Triple<Int, Int, Int>? {
            val px = x + 0.5 - centerX
            val py = y + 0.5 - centerY
            val fx = (cos * px + sin * py) / cell + 1.5
            val fy = (-sin * px + cos * py) / cell + 1.5
            if (fx < -border || fy < -border || fx >= 3 + border || fy >= 3 + border) return null
            if (fx < 0 || fy < 0 || fx >= 3 || fy >= 3) return BODY
            val inX = fx % 1
            val inY = fy % 1
            if (inX < border || inX > 1 - border || inY < border || inY > 1 - border) return BODY
            val cx = inX.coerceIn(border + corner, 1 - border - corner)
            val cy = inY.coerceIn(border + corner, 1 - border - corner)
            if ((inX - cx) * (inX - cx) + (inY - cy) * (inY - cy) > corner * corner) return BODY
            return colors[fy.toInt() * 3 + fx.toInt()]
        }
        val sharp = Array(height) { y -> IntArray(width) { x -> yuv(color(x, y) ?: MESH).first } }
        val noise = Random(13)
        return image(
            luma = { x, y ->
                var sum = 0
                for (j in -1..1) for (i in -1..1) sum += sharp[(y + j).coerceIn(0, height - 1)][(x + i).coerceIn(0, width - 1)]
                sum / 9 + noise.nextInt(5) - 2
            },
            chroma = { x, y -> yuv(color(x, y) ?: MESH).let { it.second to it.third } },
            imageWidth = width,
            imageHeight = height,
        )
    }

    /** Full-range BT.601 (Y, U, V) of an (R, G, B). */
    private fun yuv(rgb: Triple<Int, Int, Int>): Triple<Int, Int, Int> {
        val (r, g, b) = rgb
        return Triple(
            (0.299 * r + 0.587 * g + 0.114 * b).toInt(),
            (128 - 0.168736 * r - 0.331264 * g + 0.5 * b).toInt().coerceIn(0, 255),
            (128 + 0.5 * r - 0.418688 * g - 0.081312 * b).toInt().coerceIn(0, 255),
        )
    }

    @Test
    fun `the Lenovo stickered black-body face, a little off the grid, captures and reads nine sticker colors`() {
        listOf(-0.1 to -0.05, 0.1 to 0.05, -0.1 to 0.1, 0.2 to 0.2, -0.2 to 0.15).forEach { (dx, dy) ->
            val name = "offset ($dx, $dy)"
            val stats = FrameReader.read(stickeredFace(dx = dx, dy = dy))
            assertTrue(stats.seams >= FrameQuality.MIN_SEAMS, "$name seams ${stats.seams}")
            assertEquals(CaptureQuality.ACCEPTABLE, FrameQuality.assess(stats), name)
            assertTrue(effectsOf(stats).any { it is GuidedScanEffect.Capture }, name)
            val expected = lenovoFace.map { (r, g, b) -> FrameReader.rgbToLab(r, g, b) }
            stats.cells.forEachIndexed { index, cell ->
                assertTrue(cell != null && cell.plain, "$name cell $index $cell")
                assertTrue(cell!!.value.l > NEAR_BLACK_L, "$name cell $index near-black ${cell.value}")
                assertTrue(deltaE(cell.value, expected[index]) < STICKER_DELTA_E, "$name cell $index ${cell.value} vs ${expected[index]}")
            }
        }
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

        /** The black plastic between stickers. */
        const val PLASTIC = 20

        /** The dark core showing in a stickerless cube's gaps and rounded corners (Y). */
        const val CORE = 35

        /** A cyan center logo (Y, U, V), as on the GAN cube's white center. */
        val CYAN_LOGO = Triple(140, 175, 60)

        /** A stickered cube's black body, and the stand's light mesh behind it (R, G, B). */
        val BODY = Triple(22, 22, 24)
        val MESH = Triple(175, 170, 168)

        /** A reading at or below this CIELAB L* is near-black: no sticker reads that dark. */
        const val NEAR_BLACK_L = 35.0

        /** A cell reads its sticker when within this CIE76 ΔE of the sticker's color (YUV rounding and blur). */
        const val STICKER_DELTA_E = 10.0
    }
}
