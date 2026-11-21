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
import kotlin.math.pow
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

    @Test
    fun `off-center perspective-foreshortened cube is detected and read without isometric guide alignment`() {
        val cube =
            CubeState.SOLVED.apply(
                listOf(FaceTurn(Face.R, Turn.CLOCKWISE), FaceTurn(Face.U, Turn.HALF), FaceTurn(Face.F, Turn.COUNTERCLOCKWISE)),
            )
        val centers = CalibrationCenters(palette.mapValues { (_, rgb) -> readColor(rgb) })
        val guide = CornerGuide(grid(240, 320, 0), 0)
        val center = guide.vertices().last() + CornerPoint(14.0, -16.0)
        // Perspective-foreshortened projection (low pitch, strong perspective) shifted off-center
        val radius = guide.radius * 0.74
        val yaw = 45.0 * kotlin.math.PI / 180.0
        val pitch = 24.0 * kotlin.math.PI / 180.0
        val roll = -10.0 * kotlin.math.PI / 180.0
        val unitScale = radius / 1.4142135623730951
        val pts =
            listOf(
                Triple(-1.0, 1.0, -1.0),
                Triple(-1.0, 1.0, 1.0),
                Triple(-1.0, -1.0, 1.0),
                Triple(1.0, -1.0, 1.0),
                Triple(1.0, -1.0, -1.0),
                Triple(1.0, 1.0, -1.0),
                Triple(1.0, 1.0, 1.0),
            ).map { (cx, cy, cz) ->
                val x1 = cx * kotlin.math.cos(yaw) - cz * kotlin.math.sin(yaw)
                val z1 = cx * kotlin.math.sin(yaw) + cz * kotlin.math.cos(yaw)
                val y2 = cy * kotlin.math.cos(pitch) - z1 * kotlin.math.sin(pitch)
                val z2 = cy * kotlin.math.sin(pitch) + z1 * kotlin.math.cos(pitch)
                val depth = 1.0 - z2 * 0.30 / 1.732
                val px = x1 / depth
                val py = -y2 / depth
                val rx = px * kotlin.math.cos(roll) - py * kotlin.math.sin(roll)
                val ry = px * kotlin.math.sin(roll) + py * kotlin.math.cos(roll)
                center + CornerPoint(rx * unitScale, ry * unitScale)
            }
        val quads = guide.quads(pts)
        val expected = QuickScanPlan.holds[0].map { step -> expectedFace(cube, step.face) }
        val image =
            render(240, 320, quads) { face, x, y ->
                val column = (x * 3).toInt()
                val row = (y * 3).toInt()
                if (listOf(x, y).any { abs(it * 3 - (it * 3).roundToInt()) < 0.06 }) {
                    BODY
                } else {
                    palette.getValue(expected[face][row * 3 + column])
                }
            }
        val corner = CornerReader.read(image.toCornerFrame(noise = 3), guide)
        assertTrue(corner.detected, "Expected off-center perspective cube to be detected: $corner")
        assertEquals(3, corner.normalizedQuads.size)
        corner.faces.forEachIndexed { face, stats ->
            assertEquals(expected[face], stats?.cells?.map { it?.value?.let { lab -> classify(lab, centers).color } })
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

    @Test
    fun `yawed perspective corner is detected accurately without collapsing to a sub-grid`() {
        val cube =
            CubeState.SOLVED.apply(
                listOf(FaceTurn(Face.R, Turn.CLOCKWISE), FaceTurn(Face.U, Turn.HALF), FaceTurn(Face.F, Turn.COUNTERCLOCKWISE)),
            )
        val centers = CalibrationCenters(palette.mapValues { (_, rgb) -> readColor(rgb) })
        val guide = CornerGuide(grid(240, 320, 0), 0)
        val center = guide.vertices().last() + CornerPoint(-8.0, 10.0)
        val pts = CornerLocator.projectCube(center, guide.radius * 0.80, 0, 29.0, 9.0, -5.0, 0.20)
        val quads = guide.quads(pts)
        val expected = QuickScanPlan.holds[0].map { step -> expectedFace(cube, step.face) }
        val image =
            render(240, 320, quads) { face, x, y ->
                val column = (x * 3).toInt()
                val row = (y * 3).toInt()
                if (listOf(x, y).any { abs(it * 3 - (it * 3).roundToInt()) < 0.06 }) {
                    BODY
                } else {
                    palette.getValue(expected[face][row * 3 + column])
                }
            }
        val frame = image.toCornerFrame(noise = 2)
        val blobs = StickerBlobDetector.extractBlobs(frame, guide.radius)
        val blob = StickerBlobDetector.detect(frame, guide)
        assertTrue(
            blob != null,
            "Expected StickerBlobDetector to detect yawed perspective corner (blobs=${blobs.size}, sizes=${blobs.map {
                it.size.roundToInt()
            }})",
        )
        val corner = CornerReader.read(frame, guide)
        assertTrue(corner.detected, "Expected yawed perspective corner to be detected: $corner")
        corner.faces.forEachIndexed { face, stats ->
            assertEquals(expected[face], stats?.cells?.map { it?.value?.let { lab -> classify(lab, centers).color } })
        }
    }

    @Test
    fun `overlay smoother dampens small jitter, snaps on large moves, and clears on empty`() {
        val smoother = CornerOverlaySmoother(alpha = 0.5, snapThreshold = 0.1)
        val q1 = listOf(listOf(CornerPoint(0.2, 0.2), CornerPoint(0.4, 0.2), CornerPoint(0.4, 0.4), CornerPoint(0.2, 0.4)))
        assertEquals(q1, smoother.update(q1))
        val jittered = listOf(listOf(CornerPoint(0.22, 0.2), CornerPoint(0.4, 0.2), CornerPoint(0.4, 0.4), CornerPoint(0.2, 0.4)))
        val smoothed = smoother.update(jittered)
        assertEquals(0.21, smoothed[0][0].x, 1e-6)
        val jumped = listOf(listOf(CornerPoint(0.5, 0.5), CornerPoint(0.7, 0.5), CornerPoint(0.7, 0.7), CornerPoint(0.5, 0.7)))
        assertEquals(jumped, smoother.update(jumped))
        assertTrue(smoother.update(emptyList()).isEmpty())
    }

    @Test
    fun `CornerReader unwarps and validates stickers from an injected CornerDetector seam`() {
        val cube =
            CubeState.SOLVED.apply(
                listOf(FaceTurn(Face.R, Turn.CLOCKWISE), FaceTurn(Face.U, Turn.HALF), FaceTurn(Face.F, Turn.COUNTERCLOCKWISE)),
            )
        val centers = CalibrationCenters(palette.mapValues { (_, rgb) -> readColor(rgb) })
        val guide = CornerGuide(grid(240, 320, 0), 0)
        val pts = CornerLocator.projectCube(guide.vertices().last(), guide.radius * 0.80, 0, 29.0, 9.0, -5.0, 0.20)
        assertTrue(CornerLocator.isValidCubeGeometry(pts, guide))
        val quads = guide.quads(pts)
        val expected = QuickScanPlan.holds[0].map { step -> expectedFace(cube, step.face) }
        val image =
            render(240, 320, quads) { face, x, y ->
                val column = (x * 3).toInt()
                val row = (y * 3).toInt()
                if (listOf(x, y).any { abs(it * 3 - (it * 3).roundToInt()) < 0.06 }) {
                    BODY
                } else {
                    palette.getValue(expected[face][row * 3 + column])
                }
            }
        val injected = CornerDetector { _, _ -> CornerLocator.Location(quads, 1.0) }
        val corner = CornerReader.read(image.toCornerFrame(), guide, detector = injected)
        assertTrue(corner.detected)
        corner.faces.forEachIndexed { face, stats ->
            assertEquals(expected[face], stats?.cells?.map { it?.value?.let { lab -> classify(lab, centers).color } })
        }

        // A detector miss (null) must never fall back to CornerLocator heuristics.
        val missing = CornerReader.read(image.toCornerFrame(), guide, detector = CornerDetector { _, _ -> null })
        assertFalse(missing.detected)
    }

    @Test
    fun `synthetic shading over real Lenovo and S24 FE palettes keeps Red and Orange apart and never confidently wrong`() {
        // Regression guard only: it is not a repro of the Lenovo unc=9 R/L fallback (lenovo-quickscan-2026-11-20-6cd18ab1.log
        // logs no side-face colours to replay) and it passes with the old 0.60 ROI too. On-device R/L is device gate D2.
        // Story 10.19 (PO 2026-11-21 B): with no saved calibration Quick Scan's agreement gate may send a face to the Guided
        // grid (a safe fallback; e.g. stlb's R over a Blue sticker), so the guard is now: Red and Orange centers stay
        // apart, and no R or L sticker the gated read keeps CONFIDENT is wrong.
        val groups =
            listOf(
                "lenovo-tb128xu_stka_studio-a",
                "lenovo-tb128xu_stkb_studio-a",
                "lenovo-tb128xu_stla_studio-a",
                "lenovo-tb128xu_stlb_studio-a",
                "sm-s721u1_stka_studio-a",
                "sm-s721u1_stkb_studio-a",
                "sm-s721u1_stla_studio-a",
                "sm-s721u1_stlb_studio-a",
            )
        val cube =
            CubeState.SOLVED.apply(
                listOf(FaceTurn(Face.R, Turn.CLOCKWISE), FaceTurn(Face.U, Turn.HALF), FaceTurn(Face.F, Turn.COUNTERCLOCKWISE)),
            )
        val guide = CornerGuide(grid(320, 240, 90), 90)
        val pts = CornerLocator.projectCube(guide.vertices().last(), guide.radius * 0.84, 90, 30.0, 6.0, -4.0, 0.22)
        val quads = guide.quads(pts)
        val exactDetector = CornerDetector { _, _ -> CornerLocator.Location(quads, 0.96) }

        groups.forEach { groupName ->
            val uRecord = LiveFixtureJson.decode(File("../Fixtures/live/$groupName/U.json").readText())
            val realPalette = uRecord.calibration.centers.mapValues { (_, lab) -> labToRgbInt(lab) }
            // lightCheck off: this guard is about Red/Orange; without a saved calibration the colour-dependent light check
            // (Story 10.19, PO decision E) may hold this scrambled cube, which SideLightTest and the replay cover.
            val controller =
                dev.cubentia.domain.recognition
                    .QuickScanController(lightCheck = false)
            var now = 1_000L
            val samples = mutableListOf<dev.cubentia.domain.recognition.FaceSample>()
            var red: Lab? = null
            var orange: Lab? = null
            QuickScanPlan.holds.forEachIndexed { holdIdx, hold ->
                val expected = hold.map { step -> expectedFace(cube, step.face) }
                // Assumed (not measured) directional 3D shading: 0.92/0.88 in sRGB on the oblique side faces, plus a
                // dark body border at cell edges.
                val shades = listOf(1.0, 0.92, 0.88)
                val image =
                    render(320, 240, quads) { face, x, y ->
                        val column = (x * 3).toInt()
                        val row = (y * 3).toInt()
                        val border = listOf(x, y).any { abs(it * 3 - (it * 3).roundToInt()) < 0.14 }
                        if (border) {
                            BODY
                        } else {
                            scaleRgb(realPalette.getValue(expected[face][row * 3 + column]), shades[face])
                        }
                    }
                val reading = CornerReader.read(image.toCornerFrame(noise = 2), guide, detector = exactDetector)
                assertTrue(reading.detected, "$groupName hold ${holdIdx + 1}")
                hold.forEachIndexed { index, step ->
                    val center =
                        reading.faces[index]
                            ?.cells
                            ?.get(CENTER_CELL)
                            ?.value
                    if (step.face == Face.R) red = center
                    if (step.face == Face.L) orange = center
                    val cells = reading.faces[index]?.cells?.filterNotNull()
                    if (cells != null && cells.size == 9) {
                        samples +=
                            dev.cubentia.domain.recognition.FaceSample(
                                face = step.face,
                                orientationDegrees = 0,
                                stickers = cells.map { it.value },
                                quality =
                                    dev.cubentia.domain.recognition.FrameQuality
                                        .assessShutter(reading.faces[index]!!),
                                centerColor = step.centerColor,
                                upNeighborColor = step.upNeighborColor,
                                centerPlain = cells[CENTER_CELL].plain,
                            )
                    }
                }
                controller.onFrame(reading.detected, reading.faces, now)
                now += 1_000L
                if (holdIdx == 1) {
                    // Feed another frame so Hold 2 clears prior hold tracking before shutter.
                    controller.onFrame(reading.detected, reading.faces, now)
                    now += 1_000L
                }
                val effects = controller.onShutter()
                assertTrue(
                    effects.any { it is dev.cubentia.domain.recognition.QuickScanEffect.CornerCaptured },
                    "$groupName hold ${holdIdx + 1}",
                )
            }
            listOf(Face.R, Face.L).forEach { face ->
                val sample = samples.single { it.face == face }
                val truth = expectedFace(cube, face)
                val readings =
                    dev.cubentia.domain.recognition.QuickScanClassification
                        .read(sample, samples, null)
                readings.forEachIndexed { i, reading ->
                    val confident = reading?.confidence == dev.cubentia.domain.recognition.StickerConfidence.CONFIDENT
                    assertFalse(
                        confident && reading?.color != truth[i],
                        "$groupName $face sticker $i confidently $reading, truth ${truth[i]}",
                    )
                }
                // Even a face sent to the grid (a safe fallback) never reads a Red sticker as Orange or back.
                val redOrange = setOf(Color.RED, Color.ORANGE)
                val swapped =
                    readings.withIndex().filter { (i, r) ->
                        r?.color in redOrange && truth[i] in redOrange && r?.color != truth[i]
                    }
                assertTrue(swapped.isEmpty(), "$groupName $face Red/Orange swapped at ${swapped.map { it.index }}")
            }
            val separation = deltaE(checkNotNull(red) { "$groupName: no R center" }, checkNotNull(orange) { "$groupName: no L center" })
            assertTrue(separation >= 10.0, "$groupName shaded Red/Orange centers only ${"%.1f".format(separation)} ΔE apart")
        }
    }

    @Test
    fun `the 3D corner ROI skips a cell's bevel band that the 2D ROI reads`() {
        // 600 px face, 200 px cells: a desaturated bevel band 0.200–0.275 of a cell in from every cell edge. The 2D ROI
        // (0.6) puts its two outer lattice rings (56% of samples) on the band; the 3D corner ROI starts inside it.
        val side = 600
        val cell = side / 3
        val sticker = 0xd53133
        val bevel = 0x8a6a68
        val plainImage = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
        val bevelImage = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until side) {
            for (x in 0 until side) {
                fun edge(p: Int) = minOf(p % cell + 0.5, cell - (p % cell + 0.5)) / cell
                val d = minOf(edge(x), edge(y))
                plainImage.setRGB(x, y, sticker)
                bevelImage.setRGB(x, y, if (d >= 0.2 && d < 0.275) bevel else sticker)
            }
        }
        val square = GridSquare(0.0, 0.0, side.toDouble())
        val truth = FrameReader.readAt(plainImage.toCornerFrame(), square, CornerReader.CORNER_CELL_ROI_FRACTION).cells
        val corner = FrameReader.readAt(bevelImage.toCornerFrame(), square, CornerReader.CORNER_CELL_ROI_FRACTION).cells
        val flat = FrameReader.readAt(bevelImage.toCornerFrame(), square, FrameReader.CELL_ROI_FRACTION).cells
        corner.forEachIndexed { index, reading ->
            val expected = checkNotNull(truth[index]?.value)
            assertTrue(reading != null && reading.plain && deltaE(reading.value, expected) < 3.0, "corner ROI cell $index: $reading")
        }
        assertTrue(
            flat.all { it == null || !it.plain || deltaE(it.value, checkNotNull(truth[0]?.value)) >= 3.0 },
            "the 2D ROI was expected to read the bevel band: $flat",
        )
    }

    private fun deltaE(
        first: Lab,
        second: Lab,
    ): Double = sqrt((first.l - second.l).pow(2) + (first.a - second.a).pow(2) + (first.b - second.b).pow(2))

    private fun labToRgbInt(lab: Lab): Int {
        val color = labColor(lab)
        val r = (color.red * 255f).roundToInt().coerceIn(0, 255)
        val g = (color.green * 255f).roundToInt().coerceIn(0, 255)
        val b = (color.blue * 255f).roundToInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    private fun scaleRgb(
        rgb: Int,
        factor: Double,
    ): Int {
        val r = ((rgb shr 16 and 255) * factor).roundToInt().coerceIn(0, 255)
        val g = ((rgb shr 8 and 255) * factor).roundToInt().coerceIn(0, 255)
        val b = ((rgb and 255) * factor).roundToInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
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
        private const val CENTER_CELL = 4
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
