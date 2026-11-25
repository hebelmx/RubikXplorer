package dev.cubentia.app.camera

import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.Coaching
import dev.cubentia.domain.recognition.FrameQuality
import dev.cubentia.domain.recognition.FrameStats
import dev.cubentia.domain.recognition.Framing
import dev.cubentia.domain.recognition.GuidedScanController
import dev.cubentia.domain.recognition.GuidedScanEffect
import dev.cubentia.domain.recognition.Lab
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.Random
import javax.imageio.ImageIO
import kotlin.math.sqrt

/**
 * Story 10.14 (PO 2026-11-16, device session B2): the cube face's lattice is located inside the drawn grid — a face
 * about 70–100 % of the grid's size, offset up to about half a cell — and a face clearly present outside that tolerance
 * coaches the Learner's move. Synthetic faces (stickered, gapped stickerless and gapless stickerless) and the recorded
 * session A2 fixture frames, shrunk and shifted onto a dark background in-test.
 */
class FaceLocatorTest {
    /** A [width]×[height] frame with planar chroma from full-range BT.601 of [rgb], plus ±[noise] luma noise. */
    private fun frame(
        width: Int,
        height: Int,
        noise: Int = 2,
        rgb: (Int, Int) -> Triple<Int, Int, Int>,
    ): YuvFrame {
        val random = Random(17)
        val y = ByteBuffer.allocate(width * height)
        val u = ByteBuffer.allocate(width * height / 4)
        val v = ByteBuffer.allocate(width * height / 4)
        for (row in 0 until height) {
            for (column in 0 until width) {
                val (yy, uu, vv) = yuv(rgb(column, row))
                val n = if (noise > 0) random.nextInt(2 * noise + 1) - noise else 0
                y.put(row * width + column, (yy + n).coerceIn(0, 255).toByte())
                if (row % 2 == 0 && column % 2 == 0) {
                    u.put(row / 2 * (width / 2) + column / 2, uu.toByte())
                    v.put(row / 2 * (width / 2) + column / 2, vv.toByte())
                }
            }
        }
        return YuvFrame(YuvPlane(y, width, 1), YuvPlane(u, width / 2, 1), YuvPlane(v, width / 2, 1), 0, 0, width, height)
    }

    private fun yuv(rgb: Triple<Int, Int, Int>): Triple<Int, Int, Int> {
        val (r, g, b) = rgb
        return Triple(
            (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255),
            (128 - 0.168736 * r - 0.331264 * g + 0.5 * b).toInt().coerceIn(0, 255),
            (128 + 0.5 * r - 0.418688 * g - 0.081312 * b).toInt().coerceIn(0, 255),
        )
    }

    private val width = 320
    private val height = 240
    private val grid = ViewfinderGrid.inFrame(0.0, 0.0, 0, 0, width, height, 0)
    private val cell = grid.side / 3

    /**
     * A face [scale] times the grid's size about its center, shifted [dx], [dy] grid cells, on [background]: nine
     * pieces of [colors] whose colored part is [sticker] of a piece ([sticker] 1: gapless), the rest [body].
     */
    private fun face(
        colors: List<Triple<Int, Int, Int>> = scrambled,
        scale: Double = 1.0,
        dx: Double = 0.0,
        dy: Double = 0.0,
        sticker: Double = 0.85,
        body: Triple<Int, Int, Int> = BODY,
        background: Triple<Int, Int, Int> = BACKGROUND,
    ): YuvFrame {
        val side = grid.side * scale
        val left = grid.left + (grid.side - side) / 2 + dx * cell
        val top = grid.top + (grid.side - side) / 2 + dy * cell
        val piece = side / 3
        val border = (1 - sticker) / 2
        return frame(width, height) { x, y ->
            val fx = (x + 0.5 - left) / piece
            val fy = (y + 0.5 - top) / piece
            when {
                fx < 0 || fy < 0 || fx >= 3 || fy >= 3 -> background
                fx % 1 < border || fx % 1 > 1 - border || fy % 1 < border || fy % 1 > 1 - border -> body
                else -> colors[fy.toInt() * 3 + fx.toInt()]
            }
        }
    }

    /** The located reading and the effects of feeding its stats for 5 s at 10 fps. */
    private fun effectsOf(stats: FrameStats): List<GuidedScanEffect> {
        val controller = GuidedScanController()
        return (0L..5000L step 100).flatMap { controller.onFrame(stats, 0, it) }
    }

    private fun assertCaptures(
        stats: FrameStats,
        name: String,
    ) {
        assertEquals(CaptureQuality.ACCEPTABLE, FrameQuality.assess(stats), "$name ${FrameQuality.inspect(stats)}")
        assertTrue(effectsOf(stats).any { it is GuidedScanEffect.Capture }, name)
    }

    private fun assertNeverCaptures(
        stats: FrameStats,
        name: String,
    ) {
        assertTrue(FrameQuality.assess(stats) != CaptureQuality.ACCEPTABLE, "$name ${FrameQuality.inspect(stats)}")
        assertTrue(effectsOf(stats).none { it is GuidedScanEffect.Capture }, name)
    }

    private fun assertColors(
        stats: FrameStats,
        colors: List<Triple<Int, Int, Int>>,
        name: String,
    ) {
        val expected = colors.map { (r, g, b) -> FrameReader.rgbToLab(r, g, b) }
        stats.cells.forEachIndexed { index, reading ->
            assertTrue(
                reading != null && deltaE(reading.value, expected[index]) < COLOR_DELTA_E,
                "$name cell $index $reading vs ${expected[index]}",
            )
        }
    }

    @Test
    fun `a face that fills the grid exactly reads the fixed grid unchanged`() {
        listOf(face(), face(sticker = 1.0), face(colors = List(9) { PALE_YELLOW }, sticker = 1.0)).forEachIndexed { index, frame ->
            val reading = FrameReader.locate(frame)
            assertTrue(reading.lattice.exact, "frame $index lattice ${reading.lattice}")
            assertEquals(FrameReader.readAt(frame, grid), reading.stats, "frame $index")
            assertCaptures(reading.stats, "frame $index")
        }
    }

    @Test
    fun `a stickered face 80 to 90 percent of the grid and up to half a cell off is located and reads its stickers`() {
        listOf(
            Triple(0.85, 0.5, 0.0), // the device session B2 screenshot: ≈ 85 %, half a cell right
            Triple(0.8, -0.3, 0.4),
            Triple(0.9, 0.4, -0.5),
            Triple(0.7, 0.0, 0.3),
        ).forEach { (scale, dx, dy) ->
            val name = "scale $scale offset ($dx, $dy)"
            val frame = face(scale = scale, dx = dx, dy = dy)
            assertTrue(FrameQuality.assess(FrameReader.readAt(frame, grid)) != CaptureQuality.ACCEPTABLE, "$name fixed grid")
            val reading = FrameReader.locate(frame)
            assertTrue(reading.stats.seams >= FrameQuality.MIN_SEAMS, "$name seams ${reading.stats.seams} at ${reading.lattice}")
            assertCaptures(reading.stats, name)
            assertColors(reading.stats, scrambled, name)
        }
    }

    @Test
    fun `an offset black-body face retains its sticker colors on black and light backgrounds`() {
        for ((scale, dx, dy) in listOf(Triple(0.75, 0.3, -0.4), Triple(0.8, 0.4, -0.3), Triple(0.85, 0.5, 0.5))) {
            for (background in listOf(BODY, LIGHT_BACKGROUND)) {
                val name = "scale $scale offset ($dx, $dy) on $background"
                val frame = face(scale = scale, dx = dx, dy = dy, background = background)
                assertTrue(FrameQuality.assess(FrameReader.readAt(frame, grid)) != CaptureQuality.ACCEPTABLE, name)
                val reading = FrameReader.locate(frame, grid)
                assertCaptures(reading.stats, name)
                assertColors(reading.stats, scrambled, name)
            }
        }
    }

    @Test
    fun `rank spends its limited reads on clean candidates before higher scoring dark ones`() {
        // Only the lower right sticker is black. Shrinking toward the upper left avoids it but loses its edge contrast.
        val white = Triple(180, 180, 180)
        val frame = face(colors = List(8) { white } + listOf(DARK), sticker = 1.0, background = white)
        val map = FaceLocator.LumaMap(frame, grid)
        val dark = Triple(1.0, 0.0, 0.0)
        val clean = listOf(Triple(0.7, -0.5, -0.5), Triple(0.7, -0.4, -0.5))
        val candidates = listOf(dark) + clean

        fun scoreOf(candidate: Triple<Double, Double, Double>): Double {
            val (scale, dx, dy) = candidate
            return FaceLocator.score(map, scale, dx, dy)
        }
        assertTrue(clean.all { scoreOf(dark) > scoreOf(it) })
        val expected = clean.sortedByDescending(::scoreOf) + dark
        for (count in 1..candidates.size) {
            val ranked = FaceLocator.rank(map, candidates, count)
            assertEquals(expected.take(count), ranked.map { Triple(it.scale, it.dx, it.dy) })
        }
    }

    @Test
    fun `all dark candidates retain score order and ties retain input order`() {
        val map = FaceLocator.LumaMap(frame(width, height, noise = 0) { _, _ -> DARK }, grid)
        val candidates = listOf(Triple(0.7, 0.0, 0.0), Triple(1.0, -0.5, 0.0), Triple(1.0, 0.5, 0.0), Triple(1.0, 0.0, 0.0))
        val ranked = FaceLocator.rank(map, candidates, count = 3)
        assertEquals(listOf(candidates[3], candidates[1], candidates[2]), ranked.map { Triple(it.scale, it.dx, it.dy) })
    }

    @Test
    fun `a dark drawn grid can contain an acceptable well-lit face`() {
        val colors = List(9) { Triple(150, 20, 80) }
        val frame = face(colors = colors, scale = 0.75, dx = 0.4, dy = -0.5, background = BODY)
        assertEquals(CaptureQuality.LOW_LIGHT, FrameQuality.assess(FrameReader.readAt(frame, grid)))
        val reading = FrameReader.locate(frame, grid)
        assertCaptures(reading.stats, "dark drawn grid")
        assertColors(reading.stats, colors, "located face")
    }

    @Test
    fun `a well-lit face just outside the tolerance on a dark background coaches the move, not low light`() {
        val frame = face(colors = List(9) { Triple(150, 20, 80) }, scale = 0.8, dx = 1.0, background = BODY)
        assertEquals(CaptureQuality.LOW_LIGHT, FrameQuality.assess(FrameReader.readAt(frame, grid)))
        val reading = FrameReader.locate(frame, grid)
        val quality = FrameQuality.assess(reading.stats)
        assertEquals(CaptureQuality.LOW_LIGHT, quality)
        assertEquals(Framing.LEFT, reading.stats.framing, "outside ${reading.outside}")
        assertEquals(Coaching.MOVE_LEFT, GuidedScanController.coachingFor(quality, reading.stats.framing))
        val controller = GuidedScanController()
        controller.onFrame(reading.stats, 0, 0)
        assertEquals(Coaching.MOVE_LEFT, controller.coaching)
    }

    @Test
    fun `rank does not treat a dim but saturated sticker as a dark cell`() {
        // A dark blue in dim light: as dark as the black body, but far more chromatic.
        val blue = Triple(10, 30, 110)
        val lab = FrameReader.rgbToLab(blue.first, blue.second, blue.third)
        assertTrue(lab.l <= FrameQuality.DARK_CELL_MAX_L && sqrt(lab.a * lab.a + lab.b * lab.b) > FrameQuality.DARK_CELL_MAX_CHROMA)
        val white = Triple(180, 180, 180)
        val frame = face(colors = List(8) { white } + listOf(blue), sticker = 1.0, background = white)
        val map = FaceLocator.LumaMap(frame, grid)
        val full = Triple(1.0, 0.0, 0.0)
        val clean = listOf(Triple(0.7, -0.5, -0.5), Triple(0.7, -0.4, -0.5))
        assertTrue(clean.all { (scale, dx, dy) -> FaceLocator.score(map, 1.0, 0.0, 0.0) > FaceLocator.score(map, scale, dx, dy) })
        val ranked = FaceLocator.rank(map, listOf(full) + clean, count = 1)
        assertEquals(listOf(full), ranked.map { Triple(it.scale, it.dx, it.dy) })
        assertTrue(!ranked.single().promoted)
    }

    @Test
    fun `a promoted inside fit that outscores every accepted outside fit is read without framing`() {
        val frame = face(colors = paleScrambled, scale = 1.0, dx = -0.5, dy = -0.7, background = BODY)
        val map = FaceLocator.LumaMap(frame, grid)

        fun accepted(lattice: Lattice) = FrameQuality.assess(FrameReader.readAt(frame, lattice.square(grid))) == CaptureQuality.ACCEPTABLE
        val inside = FaceLocator.rank(map, FaceLocator.tolerated).first(::accepted)
        assertTrue(inside.promoted, "$inside")
        // Outside fits read as a face too, but none outscores the inside fit: they must not take precedence.
        val outside = FaceLocator.rank(map, FaceLocator.wide).filter(::accepted)
        assertTrue(outside.isNotEmpty() && outside.all { it.score <= inside.score }, "$outside vs $inside")
        val reading = FrameReader.locate(frame, grid)
        assertEquals(inside, reading.lattice)
        assertNull(reading.outside)
        assertNull(reading.stats.framing)
        assertCaptures(reading.stats, "promoted inside fit")
    }

    @Test
    fun `a gapless stickerless face, scrambled or solved, captures in and off the grid`() {
        mapOf("scrambled" to paleScrambled, "solved yellow" to List(9) { PALE_YELLOW }, "solved orange" to List(9) { PALE_ORANGE })
            .forEach { (kind, colors) ->
                listOf(Triple(1.0, 0.0, 0.0), Triple(0.85, 0.4, -0.3), Triple(0.75, -0.2, 0.5)).forEach { (scale, dx, dy) ->
                    listOf(BACKGROUND, LIGHT_BACKGROUND).forEach { background ->
                        val name = "$kind scale $scale offset ($dx, $dy) on $background"
                        val frame = face(colors = colors, scale = scale, dx = dx, dy = dy, sticker = 1.0, background = background)
                        val stats = FrameReader.read(frame)
                        assertEquals(FrameQuality.FACE_EDGES, stats.faceEdges, name)
                        assertCaptures(stats, name)
                        assertColors(stats, colors, name)
                    }
                }
            }
    }

    @Test
    fun `a plain colored wall and a gray scene are never aligned`() {
        val noise = Random(3)
        val scenes =
            mapOf(
                "yellow wall" to frame(width, height, noise = 6) { _, _ -> PALE_YELLOW },
                "orange wall" to frame(width, height, noise = 6) { _, _ -> PALE_ORANGE },
                "white wall" to frame(width, height, noise = 6) { _, _ -> Triple(225, 225, 222) },
                "gray scene" to
                    frame(width, height, noise = 0) { _, _ -> (110 + noise.nextInt(60)).let { Triple(it, it, it) } },
                "gray squares" to face(colors = List(9) { Triple(140 + it % 2 * 12, 140 + it % 2 * 12, 140) }, sticker = 1.0),
            )
        scenes.forEach { (name, frame) ->
            val stats = FrameReader.read(frame)
            assertNeverCaptures(stats, name)
            assertNull(stats.framing, name)
        }
    }

    @Test
    fun `a face outside the tolerance coaches the Learner's move, as the preview shows it`() {
        val cases =
            listOf(
                Triple(face(scale = 0.8, dx = 1.0), 0, Framing.LEFT),
                Triple(face(scale = 0.8, dx = -1.0), 0, Framing.RIGHT),
                Triple(face(scale = 0.6, dy = 0.9), 0, Framing.UP),
                Triple(face(scale = 0.6, dy = -0.9), 0, Framing.DOWN),
                Triple(face(scale = 0.5), 0, Framing.CLOSER),
                // The preview turns the frame 90° clockwise: frame right is the Learner's down, so move up.
                Triple(face(scale = 0.8, dx = 1.0), 90, Framing.UP),
                Triple(face(scale = 0.8, dx = 1.0), 270, Framing.DOWN),
                Triple(face(scale = 0.8, dx = 1.0), 180, Framing.RIGHT),
            )
        val coaching =
            mapOf(
                Framing.LEFT to Coaching.MOVE_LEFT,
                Framing.RIGHT to Coaching.MOVE_RIGHT,
                Framing.UP to Coaching.MOVE_UP,
                Framing.DOWN to Coaching.MOVE_DOWN,
                Framing.CLOSER to Coaching.MOVE_CLOSER,
            )
        cases.forEach { (frame, rotation, expected) ->
            val name = "$expected at $rotation°"
            val reading = FrameReader.locate(frame, rotationDegrees = rotation)
            assertEquals(CaptureQuality.MISALIGNED, FrameQuality.assess(reading.stats), name)
            assertEquals(expected, reading.stats.framing, "$name outside ${reading.outside}")
            val controller = GuidedScanController()
            controller.onFrame(reading.stats, rotation, 0)
            assertEquals(coaching.getValue(expected), controller.coaching, name)
            assertTrue(effectsOf(reading.stats).none { it is GuidedScanEffect.Capture }, name)
        }
    }

    @Test
    fun `no face at all keeps the generic coaching`() {
        val controller = GuidedScanController()
        controller.onFrame(FrameReader.read(frame(width, height, noise = 6) { _, _ -> PALE_YELLOW }), 0, 0)
        assertEquals(Coaching.MISALIGNED, controller.coaching)
    }

    /**
     * The recorded session A2 frames (`Fixtures/live/<device>_<cube>_studio-a/<face>.jpg`, both reference devices, four
     * cubes): each frame whose fixed grid reads as a cube face is shrunk to 80–90 % and shifted 0.3–0.5 cell onto a dark
     * background; the located lattice reads it as a cube face again, with every sticker's color within
     * [FIXTURE_DELTA_E] of the original reading.
     */
    @Test
    fun `recorded fixture faces shrunk and shifted onto a dark background are located with their colors`() {
        val images =
            FIXTURES.listFiles { dir -> dir.isDirectory && dir.name.endsWith("-a") }.orEmpty().sorted().flatMap { dir ->
                dir.listFiles { file -> file.extension == "jpg" }.orEmpty().sorted()
            }
        assertTrue(images.size >= 40, "fixture frames ${images.size}")
        var exercised = 0
        images.forEach { file ->
            val image = ImageIO.read(file)
            val w = image.width / 2 * 2
            val h = image.height / 2 * 2

            fun pixel(
                x: Int,
                y: Int,
            ): Triple<Int, Int, Int> {
                val rgb = image.getRGB(x.coerceIn(0, image.width - 1), y.coerceIn(0, image.height - 1))
                return Triple(rgb shr 16 and 0xff, rgb shr 8 and 0xff, rgb and 0xff)
            }
            val original = frame(w, h, noise = 0, rgb = ::pixel)
            val exact = FrameReader.read(original)
            if (FrameQuality.assess(exact) != CaptureQuality.ACCEPTABLE) return@forEach
            exercised++
            val square = ViewfinderGrid.inFrame(0.0, 0.0, 0, 0, w, h, 0)
            val gridCell = square.side / 3
            listOf(Triple(0.85, 0.5, -0.3), Triple(0.8, -0.3, 0.4), Triple(0.9, 0.4, 0.4)).forEach { (scale, dx, dy) ->
                val name = "${file.parentFile.name}/${file.name} scale $scale offset ($dx, $dy)"
                val side = square.side * scale
                val left = square.left + (square.side - side) / 2 + dx * gridCell
                val top = square.top + (square.side - side) / 2 + dy * gridCell
                val shifted =
                    frame(w, h, noise = 0) { x, y ->
                        val fx = (x + 0.5 - left) / scale
                        val fy = (y + 0.5 - top) / scale
                        if (fx < 0 || fy < 0 || fx >= square.side || fy >= square.side) {
                            DARK
                        } else {
                            pixel((square.left + fx).toInt(), (square.top + fy).toInt())
                        }
                    }
                val reading = FrameReader.locate(shifted)
                assertCaptures(reading.stats, "$name at ${reading.lattice}")
                reading.stats.cells.forEachIndexed { index, cell ->
                    val before = exact.cells[index]!!.value
                    assertTrue(deltaE(cell!!.value, before) < FIXTURE_DELTA_E, "$name cell $index ${cell.value} vs $before")
                }
            }
        }
        assertTrue(exercised >= MIN_FIXTURES, "fixture faces read as cube faces: $exercised of ${images.size}")
    }

    /**
     * Per-frame cost on this machine (printed, not asserted): the worst case, a frame with no face in the grid, reads
     * the fixed grid, the tolerated search and its ranked candidates, then the wide search and its ranked candidates.
     */
    @Test
    fun `per-frame cost of the worst case on a Lenovo-sized frame`() {
        // The Lenovo's portrait two-pane analysis crop (cell ≈ 53 px) and the S24 FE's (cell ≈ 113 px).
        listOf(200 to 640, 480 to 422).forEach { (w, h) ->
            val noise = Random(9)
            val scene = frame(w, h, noise = 0) { _, _ -> (110 + noise.nextInt(60)).let { Triple(it, it - 5, it + 5) } }
            repeat(WARMUP) { FrameReader.locate(scene) }
            val start = System.nanoTime()
            repeat(RUNS) { FrameReader.locate(scene) }
            val worst = (System.nanoTime() - start) / RUNS / 1e6
            val exactFace = frame(w, h, noise = 2) { _, _ -> PALE_YELLOW }
            val begin = System.nanoTime()
            repeat(RUNS) { FrameReader.readAt(exactFace, ViewfinderGrid.inFrame(0.0, 0.0, 0, 0, w, h, 0)) }
            val plain = (System.nanoTime() - begin) / RUNS / 1e6
            println("FaceLocator cost ${w}x$h: worst case %.2f ms, one fixed-grid read %.2f ms".format(worst, plain))
        }
    }

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
        val FIXTURES = File("../Fixtures/live")

        val BODY = Triple(22, 22, 24)
        val BACKGROUND = Triple(30, 30, 34)
        val LIGHT_BACKGROUND = Triple(120, 115, 110)
        val DARK = Triple(20, 20, 20)

        /** The pale stickerless cube (device session B2): the solved yellow face read C*ab ≈ 85, L* ≈ 78. */
        val PALE_YELLOW = Triple(235, 200, 60)
        val PALE_ORANGE = Triple(245, 150, 70)

        val scrambled =
            listOf(
                Triple(230, 232, 228),
                Triple(210, 40, 35),
                Triple(10, 160, 60),
                Triple(245, 220, 40),
                Triple(10, 160, 60),
                Triple(20, 90, 200),
                Triple(245, 130, 30),
                Triple(230, 232, 228),
                Triple(210, 40, 35),
            )

        /** A gapless pale face with neighbors of one color on several edges. */
        val paleScrambled =
            listOf(
                PALE_YELLOW,
                PALE_YELLOW,
                Triple(120, 200, 120),
                Triple(235, 235, 230),
                Triple(120, 200, 120),
                Triple(120, 200, 120),
                PALE_ORANGE,
                Triple(235, 110, 110),
                Triple(235, 110, 110),
            )

        const val COLOR_DELTA_E = 10.0
        const val FIXTURE_DELTA_E = 12.0
        const val MIN_FIXTURES = 20
        const val WARMUP = 5
        const val RUNS = 20
    }
}
