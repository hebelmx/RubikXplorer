package dev.cubentia.app.camera

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.LiveFixtureJson
import dev.cubentia.domain.recognition.QuickScanPlan
import dev.cubentia.domain.recognition.StickerClassification
import dev.cubentia.domain.recognition.StickerClassifier
import dev.cubentia.domain.recognition.StickerConfidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.util.Random
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

class CornerLocatorTest {
    @Test
    fun `projected solved and scrambled corners read all 27 stickers in both camera orientations`() {
        val scramble =
            CubeState.SOLVED.apply(
                listOf(FaceTurn(Face.R, Turn.CLOCKWISE), FaceTurn(Face.U, Turn.HALF), FaceTurn(Face.F, Turn.COUNTERCLOCKWISE)),
            )
        val centers = CalibrationCenters(palette.mapValues { (_, rgb) -> readColor(rgb) })
        listOf(CubeState.SOLVED, scramble).forEach { cube ->
            listOf(0, 90, 270).forEach { rotation ->
                val width = if (rotation == 0) 240 else 320
                val height = if (rotation == 0) 320 else 240
                val guide = CornerGuide(grid(width, height, rotation), rotation)
                listOf(0.7 to -10.0, 0.84 to 6.0, 1.0 to 10.0).forEach { (scale, roll) ->
                    QuickScanPlan.holds.forEachIndexed { index, hold ->
                        val expected = hold.map { step -> expectedFace(cube, step.face) }
                        val quads = guide.quads(project(guide.vertices(scale, roll), guide.vertices().last(), guide.radius))
                        val image =
                            render(width, height, quads) { face, x, y ->
                                val column = (x * 3).toInt()
                                val row = (y * 3).toInt()
                                if (listOf(x, y).any { abs(it * 3 - (it * 3).roundToInt()) < 0.06 }) {
                                    BODY
                                } else {
                                    palette.getValue(expected[face][row * 3 + column])
                                }
                            }
                        val corner = CornerReader.read(image.toCornerFrame(noise = 3), guide)
                        val name = "hold ${index + 1}, scale $scale, roll $roll, rotation $rotation, solved ${cube == CubeState.SOLVED}"
                        assertTrue(corner.detected, "$name: $corner")
                        corner.faces.forEachIndexed { face, stats ->
                            assertEquals(expected[face], stats?.cells?.map { it?.value?.let { lab -> classify(lab, centers).color } }, name)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `orthographic cube projection reads the Quick Scan corner frame at each camera rotation`() {
        val cube =
            CubeState.SOLVED.apply(
                listOf(FaceTurn(Face.R, Turn.CLOCKWISE), FaceTurn(Face.U, Turn.HALF), FaceTurn(Face.F, Turn.COUNTERCLOCKWISE)),
            )
        val centers = CalibrationCenters(palette.mapValues { (_, rgb) -> readColor(rgb) })
        val colors = cube.toFacelets().colors
        for (rotation in listOf(0, 90, 180, 270)) {
            val width = if (rotation % 180 == 0) 240 else 320
            val height = if (rotation % 180 == 0) 320 else 240
            val square = grid(width, height, rotation)
            val center = CornerPoint(square.left + square.side / 2, square.top + square.side / 2)
            val scale = square.side * 0.22
            val guide = CornerGuide(square, rotation)
            QuickScanPlan.holds.forEachIndexed { index, hold ->
                val sign = if (index == 0) 1 else -1
                val visible = if (sign == 1) listOf(Face.U, Face.F, Face.R) else listOf(Face.D, Face.L, Face.B)

                // Eye is (sign, sign, sign) and camera up is (0, sign, 0).
                // Project onto the resulting right and down unit vectors, then rotate into frame pixels.
                fun project(point: Triple<Int, Int, Int>): CornerPoint {
                    val (x, y, z) = point
                    val right = (x - z) / sqrt(2.0)
                    val down = sign * (x - 2 * y + z) / sqrt(6.0)
                    val pixel =
                        when (rotation) {
                            90 -> CornerPoint(down, -right)
                            180 -> CornerPoint(-right, -down)
                            270 -> CornerPoint(-down, right)
                            else -> CornerPoint(right, down)
                        }
                    return center + pixel * scale
                }

                val quads =
                    visible.map { face ->
                        listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1).map { (column, row) ->
                            // Cube axes point toward R, U and F. Each face uses its own URFDLB row and column directions.
                            project(
                                when (face) {
                                    Face.U -> Triple(column, 1, row)
                                    Face.R -> Triple(1, -row, -column)
                                    Face.F -> Triple(column, -row, 1)
                                    Face.D -> Triple(column, -1, -row)
                                    Face.L -> Triple(-1, -row, column)
                                    Face.B -> Triple(-column, -row, -1)
                                },
                            )
                        }
                    }
                val image =
                    render(width, height, quads) { face, x, y ->
                        val column = (x * 3).toInt()
                        val row = (y * 3).toInt()
                        if (listOf(x, y).any { abs(it * 3 - (it * 3).roundToInt()) < 0.06 }) {
                            BODY
                        } else {
                            palette.getValue(colors[visible[face].ordinal * 9 + row * 3 + column])
                        }
                    }
                val corner = CornerReader.read(image.toCornerFrame(), guide)
                val scenario = "hold ${index + 1}, camera rotation $rotation"
                assertTrue(corner.detected, scenario)
                val measured = corner.faces.map { stats -> stats?.cells?.map { it?.value?.let { lab -> classify(lab, centers).color } } }
                assertEquals(hold.map { expectedFace(cube, it.face) }, measured, scenario)
            }
        }
    }

    private fun expectedFace(
        cube: CubeState,
        face: Face,
    ): List<Color> {
        val colors =
            cube
                .toFacelets()
                .colors
                .drop(face.ordinal * 9)
                .take(9)
        return when (face) {
            Face.D -> listOf(2, 5, 8, 1, 4, 7, 0, 3, 6).map(colors::get)
            Face.L, Face.B -> colors.reversed()
            else -> colors
        }
    }

    @Test
    fun `warped recorder crops preserve confidently correct stickers for each recorded cube and light`() {
        val groups = File("../Fixtures/live").listFiles { dir -> dir.isDirectory && dir.name.endsWith("-a") }.orEmpty().sorted()
        assertTrue(groups.size >= 8, "Expected at least eight cube and device fixture groups")
        val guide = CornerGuide(grid(240, 320, 0))
        var compared = 0
        groups.forEach { folder ->
            QuickScanPlan.holds.forEach { hold ->
                val records = hold.map { step -> LiveFixtureJson.decode(File(folder, "${step.face}.json").readText()) }
                val images = hold.map { step -> ImageIO.read(File(folder, "${step.face}.jpg")) }
                val grids = images.map { grid(it.width / 2 * 2, it.height / 2 * 2, 0) }
                val quads = guide.quads(project(guide.vertices(0.88, -5.0), guide.vertices().last(), guide.radius))
                val warped =
                    render(240, 320, quads) { face, x, y ->
                        val square = grids[face]
                        images[face].getRGB((square.left + x * square.side).toInt(), (square.top + y * square.side).toInt())
                    }
                val corner = CornerReader.read(warped.toCornerFrame(), guide)
                assertTrue(corner.detected, "${folder.name}: ${hold.map { it.face }}")
                records.forEachIndexed { face, record ->
                    val flat = FrameReader.readAt(images[face].toCornerFrame(), grids[face])
                    flat.cells.forEachIndexed { index, cell ->
                        val truth = record.labeledSample.truth[index]
                        val before = classify(checkNotNull(cell).value, record.calibration)
                        val after = classify(checkNotNull(corner.faces[face]?.cells?.get(index)).value, record.calibration)
                        if (before.confidence == StickerConfidence.CONFIDENT && before.color == truth) {
                            compared++
                            assertFalse(
                                after.confidence == StickerConfidence.CONFIDENT && after.color != truth,
                                "${folder.name} ${hold[face].face} sticker $index: $truth → $after",
                            )
                        }
                    }
                }
            }
        }
        assertTrue(compared > 200, "$compared confident baseline stickers")
    }

    @Test
    fun `empty background and a single frontal cube face are rejected`() {
        val guide = CornerGuide(grid(240, 320, 0))
        for (flat in listOf(false, true)) {
            val scene = BufferedImage(240, 320, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until scene.height) {
                for (x in 0 until scene.width) {
                    val pixel =
                        if (flat && x in 30..209 && y in 70..249) {
                            if ((x - 30) % 60 in 4..55 && (y - 70) % 60 in 4..55) {
                                palette.values.elementAt(((x - 30) / 60 + 3 * ((y - 70) / 60)) % palette.size)
                            } else {
                                BODY
                            }
                        } else {
                            BACKGROUND
                        }
                    scene.setRGB(x, y, pixel)
                }
            }
            assertFalse(CornerReader.read(scene.toCornerFrame(noise = 2), guide).detected, "flat face=$flat")
        }
    }

    private fun readColor(rgb: Int): Lab {
        val image =
            BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB).apply {
                for (y in 0 until height) for (x in 0 until width) setRGB(x, y, rgb)
            }
        return checkNotNull(FrameReader.readAt(image.toCornerFrame(), GridSquare(0.0, 0.0, 96.0)).cells[4]).value
    }

    private fun project(
        points: List<CornerPoint>,
        center: CornerPoint,
        radius: Double,
    ): List<CornerPoint> =
        points.map {
            val (x, y) = it - center
            val perspective = 1 + (0.04 * x - 0.035 * y) / radius
            center + CornerPoint((x + 0.025 * y) / perspective, (y + 0.025 * x) / perspective)
        }

    private fun render(
        width: Int,
        height: Int,
        quads: List<List<CornerPoint>>,
        pixel: (Int, Double, Double) -> Int,
    ): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, BACKGROUND)
        quads.forEachIndexed { face, quad ->
            val mapping = checkNotNull(Homography.from(quad))
            // Rasterize from the source at subpixel spacing so each target pixel has a sample.
            for (row in 0 until 480) {
                for (column in 0 until 480) {
                    val u = (column + 0.5) / 480
                    val v = (row + 0.5) / 480
                    val p = mapping.apply(u, v)
                    val x = p.x.toInt()
                    val y = p.y.toInt()
                    if (x in 0 until width && y in 0 until height) image.setRGB(x, y, pixel(face, u, v))
                }
            }
        }
        return image
    }

    companion object {
        private const val BACKGROUND = 0x080b10
        private const val BODY = 0x26282a
        private val palette =
            mapOf(
                Color.WHITE to 0xf6f4f0,
                Color.YELLOW to 0xeada19,
                Color.RED to 0xd53133,
                Color.ORANGE to 0xed872c,
                Color.GREEN to 0x46b64c,
                Color.BLUE to 0x3176db,
            )
    }
}

internal fun grid(
    width: Int,
    height: Int,
    rotation: Int,
): GridSquare = ViewfinderGrid.inFrame(0.0, 0.0, 0, 0, width, height, rotation)

internal fun classify(
    lab: Lab,
    calibration: CalibrationCenters,
) = (StickerClassifier.classify(lab, calibration) as StickerClassification.Classified).reading

internal fun BufferedImage.toCornerFrame(noise: Int = 0): YuvFrame {
    val w = width / 2 * 2
    val h = height / 2 * 2
    val y = ByteBuffer.allocate(w * h)
    val u = ByteBuffer.allocate(w * h / 4)
    val v = ByteBuffer.allocate(w * h / 4)
    val random = Random(17)
    for (row in 0 until h) {
        for (column in 0 until w) {
            val rgb = getRGB(column, row)
            val r = rgb shr 16 and 255
            val g = rgb shr 8 and 255
            val b = rgb and 255
            val n = random.nextInt(2 * noise + 1) - noise
            y.put(row * w + column, (0.299 * r + 0.587 * g + 0.114 * b + n).roundToInt().coerceIn(0, 255).toByte())
            if (row % 2 == 0 && column % 2 == 0) {
                val index = row / 2 * (w / 2) + column / 2
                u.put(index, (128 - 0.168736 * r - 0.331264 * g + 0.5 * b).roundToInt().coerceIn(0, 255).toByte())
                v.put(index, (128 + 0.5 * r - 0.418688 * g - 0.081312 * b).roundToInt().coerceIn(0, 255).toByte())
            }
        }
    }
    return YuvFrame(YuvPlane(y, w, 1), YuvPlane(u, w / 2, 1), YuvPlane(v, w / 2, 1), 0, 0, w, h)
}
