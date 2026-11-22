package dev.cubentia.app.camera.recorder

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.TruthSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.hypot

class RecorderRulesTest {
    private val sticker = Lab(50.0, 0.0, 0.0)

    /** WHITE and YELLOW at the given distances from [sticker]; the other four far away. */
    private fun calibration(
        white: Double,
        yellow: Double,
    ) = CalibrationCenters(
        mapOf(
            Color.WHITE to Lab(50.0 + white, 0.0, 0.0),
            Color.YELLOW to Lab(50.0 - yellow, 0.0, 0.0),
            Color.RED to Lab(50.0, 80.0, 0.0),
            Color.ORANGE to Lab(50.0, 0.0, 80.0),
            Color.GREEN to Lab(50.0, -80.0, 0.0),
            Color.BLUE to Lab(50.0, 0.0, -80.0),
        ),
    )

    @Test fun `clear win is prefilled and a close call stays blank`() {
        assertEquals(listOf(Color.WHITE), prefill(listOf(sticker), calibration(white = 5.0, yellow = 30.0)))
        assertEquals(listOf<Color?>(null), prefill(listOf(sticker), calibration(white = 20.0, yellow = 28.0)))
        assertEquals(listOf(Color.WHITE), prefill(listOf(sticker), calibration(white = 10.0, yellow = 20.0)))
    }

    @Test fun `setting a color enters it and advances to the next blank, wrapping`() {
        val entry =
            TruthEntry(
                colors = listOf(Color.RED, Color.RED) + List(7) { null },
                sources = List(2) { TruthSource.ENTERED } + List(7) { null },
                selected = 2,
            ).set(Color.BLUE)
        assertEquals(Color.BLUE, entry.colors[2])
        assertEquals(TruthSource.ENTERED, entry.sources[2])
        assertEquals(3, entry.selected)

        val wrap = TruthEntry(colors = listOf<Color?>(null) + List(8) { Color.RED }, selected = 8).set(Color.RED)
        assertEquals(0, wrap.selected)
        val done = wrap.set(Color.WHITE)
        assertNull(done.selected)
        assertTrue(done.complete)
        assertEquals(done, done.set(Color.GREEN))
    }

    @Test fun `prefill refreshes until the first entry, then freezes, and an equal entry still counts as entered`() {
        val first = TruthEntry(selected = null).withPrefill(listOf(null) + List(8) { Color.WHITE })
        assertEquals(0, first.selected)
        assertEquals(listOf(null) + List(8) { TruthSource.PREFILLED }, first.sources)
        val refreshed = first.select(5).withPrefill(List(9) { Color.YELLOW })
        assertEquals(5, refreshed.selected)
        assertEquals(List(9) { Color.YELLOW }, refreshed.colors)

        val entered = refreshed.set(Color.YELLOW)
        assertEquals(TruthSource.ENTERED, entered.sources[5])
        assertNull(entered.selected)
        assertEquals(entered, entered.withPrefill(List(9) { Color.RED }))
        assertFalse(TruthEntry().complete)
    }

    @Test fun `bad white is implausible, good white passes, logo ring is not uniform`() {
        val bad = Lab(76.0, 20.0, 71.0)
        assertTrue(swatchWarnings(Color.WHITE, bad, List(12) { bad }, emptyMap()).any { it is SwatchWarning.Implausible })
        val good = Lab(88.0, -6.0, -16.0)
        assertEquals(emptyList<SwatchWarning>(), swatchWarnings(Color.WHITE, good, List(12) { good }, emptyMap()))
        val dark = Lab(55.0, 0.0, 0.0)
        assertTrue(swatchWarnings(Color.WHITE, dark, List(12) { dark }, emptyMap()).any { it is SwatchWarning.Implausible })

        val logoRing = List(6) { Lab(88.0, 0.0, 0.0) } + List(6) { Lab(40.0, 0.0, 0.0) }
        val warnings = swatchWarnings(Color.WHITE, mean(logoRing), logoRing, emptyMap())
        assertTrue(SwatchWarning.NotUniform in warnings, warnings.toString())
        warnings.forEach { assertTrue(it.describe().isNotBlank()) }
    }

    @Test fun `gray color and a center too close to another saved center warn`() {
        val gray = Lab(50.0, 10.0, 10.0)
        assertTrue(swatchWarnings(Color.RED, gray, List(12) { gray }, emptyMap()).any { it is SwatchWarning.Implausible })
        val orange = Lab(60.0, 45.0, 55.0)
        val red = Lab(58.0, 48.0, 50.0)
        val warnings = swatchWarnings(Color.ORANGE, orange, List(12) { orange }, mapOf(Color.RED to red, Color.ORANGE to orange))
        assertEquals(listOf(Color.RED), warnings.filterIsInstance<SwatchWarning.TooClose>().map { it.other })
    }

    @Test fun `ring has twelve points at thirty percent of the cell side`() {
        val ring = ringOffsets()
        assertEquals(12, ring.size)
        ring.forEach { (x, y) -> assertEquals(0.30, hypot(x, y), 1e-9) }
    }

    @Test fun `frames scale to a 1024 long edge and small frames are kept`() {
        assertEquals(1024 to 576, scaledSize(1920, 1080))
        assertEquals(576 to 1024, scaledSize(1080, 1920))
        assertEquals(640 to 480, scaledSize(640, 480))
    }

    @Test fun `realignment re-meters once after 600 ms steady and not again while unchanged`() {
        val trigger = RemeterTrigger()
        val before = List(9) { Lab(50.0, 0.0, 0.0) }
        val after = List(9) { Lab(80.0, 0.0, 0.0) }
        trigger.settle(before, 0)
        var time = 0L
        val fires = mutableListOf<Long>()

        fun feed(
            grid: List<Lab>,
            until: Long,
        ) {
            while (time < until) {
                time += 100
                if (trigger.onFrame(grid, time)) fires += time
            }
        }
        feed(before, 2000)
        assertEquals(emptyList<Long>(), fires)
        feed(after, 2500)
        assertEquals(emptyList<Long>(), fires, "not yet steady for 600 ms")
        feed(after, 6000)
        assertEquals(listOf(2700L), fires)
    }

    @Test fun `a change inside the settle window becomes the reference and does not re-meter`() {
        val trigger = RemeterTrigger()
        trigger.settle(List(9) { Lab(50.0, 0.0, 0.0) }, 0)
        val shifted = List(9) { Lab(80.0, 0.0, 0.0) }
        var fired = 0
        var time = 0L
        while (time < 3000) {
            time += 100
            if (trigger.onFrame(shifted, time)) fired++
        }
        assertEquals(0, fired)
    }

    @Test fun `confirmed truth keeps per-sticker sources and is null until complete`() {
        val prefilled = List(9) { if (it == 4) null else Color.RED }
        val partial = TruthEntry(selected = null).withPrefill(prefilled)
        assertEquals(null, partial.confirmed())
        val (colors, sources) = partial.set(Color.WHITE).confirmed()!!
        assertEquals(List(9) { if (it == 4) Color.WHITE else Color.RED }, colors)
        assertEquals(List(9) { if (it == 4) TruthSource.ENTERED else TruthSource.PREFILLED }, sources)
    }

    @Test fun `unsteady frames restart the steady window`() {
        val trigger = RemeterTrigger()
        trigger.settle(List(9) { Lab(50.0, 0.0, 0.0) }, 0)
        var fired = 0
        var time = 2000L
        repeat(20) { index ->
            time += 100
            if (trigger.onFrame(List(9) { Lab(if (index % 2 == 0) 80.0 else 95.0, 0.0, 0.0) }, time)) fired++
        }
        assertEquals(0, fired)
    }

    @Test fun `suggest again refreshes prefilled cells and keeps entered ones`() {
        val entry =
            TruthEntry(
                colors =
                    List(9) {
                        if (it == 0) {
                            Color.RED
                        } else if (it == 4) {
                            Color.GREEN
                        } else {
                            Color.WHITE
                        }
                    },
                sources = List(9) { if (it == 0 || it == 4) TruthSource.ENTERED else TruthSource.PREFILLED },
                selected = null,
            )
        val next = entry.suggestAgain(List(9) { Color.BLUE })
        assertEquals(
            List(9) {
                if (it == 0) {
                    Color.RED
                } else if (it == 4) {
                    Color.GREEN
                } else {
                    Color.BLUE
                }
            },
            next.colors,
        )
        assertEquals(List(9) { if (it == 0 || it == 4) TruthSource.ENTERED else TruthSource.PREFILLED }, next.sources)
        assertNull(next.selected)
    }

    @Test fun `suggest again blanks a cell whose prefill is blank and moves selection to the first blank`() {
        val entry = TruthEntry(selected = null).withPrefill(List(9) { Color.WHITE }).select(1)
        val next = entry.suggestAgain(List(9) { if (it == 6) null else Color.WHITE })
        assertNull(next.colors[6])
        assertNull(next.sources[6])
        assertEquals(6, next.selected)

        val kept = next.select(6).suggestAgain(List(9) { if (it == 2 || it == 6) null else Color.WHITE })
        assertEquals(6, kept.selected, "a selection on a still-blank cell is kept")
    }

    @Test fun `a face frozen by entries on the wrong face recovers with suggest again, withPrefill stays frozen`() {
        val stale = TruthEntry(selected = null).withPrefill(List(9) { Color.WHITE })
        val frozen =
            stale
                .select(0)
                .set(Color.RED)
                .select(1)
                .set(Color.RED)
        val current = List(9) { Color.YELLOW }
        assertEquals(frozen, frozen.withPrefill(current))

        val recovered = frozen.suggestAgain(current)
        assertEquals(listOf(Color.RED, Color.RED) + List(7) { Color.YELLOW }, recovered.colors)
        assertEquals(List(2) { TruthSource.ENTERED } + List(7) { TruthSource.PREFILLED }, recovered.sources)
    }

    @Test fun `chips name the center color and the mismatch reads face first`() {
        assertEquals("B · Green", faceChipLabel(Face.B))
        assertEquals("F · Blue", faceChipLabel(Face.F))
        assertEquals("Face B needs the Green center — this one is Blue", centerMismatch(Face.B, Color.BLUE))
    }

    @Test fun `saved summary counts entered stickers and names an override`() {
        val sources = List(9) { if (it == 3) TruthSource.ENTERED else TruthSource.PREFILLED }
        assertEquals("Saved L · 1 entered by you", savedSummary(Face.L, sources, null))
        assertEquals("Saved L · 1 entered by you · override MISALIGNED", savedSummary(Face.L, sources, CaptureQuality.MISALIGNED))
        assertEquals("Saved U · 0 entered by you", savedSummary(Face.U, List(9) { TruthSource.PREFILLED }, null))
    }

    @Test fun `capture locks name hold and the center mismatch and are empty when ready`() {
        val green = List<Color?>(9) { Color.GREEN }
        val ready = TruthEntry(colors = green, sources = List(9) { TruthSource.ENTERED }, selected = null)
        assertEquals(emptyList<String>(), captureLocks(false, false, true, 6, ready, Face.B))
        assertEquals(listOf("hold"), captureLocks(false, true, true, 6, ready, Face.B))
        assertEquals(
            listOf("Face F needs the Blue center — this one is Green"),
            captureLocks(false, false, true, 6, ready, Face.F),
        )
        val partial = TruthEntry(colors = List(9) { if (it < 4) Color.GREEN else null }, selected = null)
        assertEquals(listOf("saving…", "4/9 stickers set"), captureLocks(true, false, true, 6, partial, Face.B))
    }

    @Test fun `next face is the first uncaptured face, or the current one when all six are captured`() {
        assertEquals(Face.R, nextFace(listOf(Face.U, Face.F), Face.U))
        assertEquals(Face.L, nextFace(Face.entries, Face.L))
    }

    // Story 10.19 corner-mode rules.

    @Test fun `the seven keypoints come from the detector quads in model order and map onto the crop`() {
        val t =
            dev.cubentia.app.camera
                .CornerPoint(100.0, 40.0)
        val ul =
            dev.cubentia.app.camera
                .CornerPoint(50.0, 70.0)
        val ll =
            dev.cubentia.app.camera
                .CornerPoint(55.0, 130.0)
        val b =
            dev.cubentia.app.camera
                .CornerPoint(100.0, 160.0)
        val lr =
            dev.cubentia.app.camera
                .CornerPoint(145.0, 130.0)
        val ur =
            dev.cubentia.app.camera
                .CornerPoint(150.0, 70.0)
        val c =
            dev.cubentia.app.camera
                .CornerPoint(100.0, 100.0)
        val quads = listOf(listOf(t, ur, c, ul), listOf(ul, c, b, ll), listOf(c, ur, lr, b))
        assertEquals(listOf(t, ul, ll, b, lr, ur, c), keypointsOf(quads))
        assertNull(keypointsOf(quads.take(2)))
        val crop = ModelCrop(100.0, 100.0, 80.0, 0)
        val points = checkNotNull(detectorKeypoints(quads, crop))
        assertEquals(listOf(0.5, 0.5), points[6])
        assertEquals(0.5, points[0][0], 1e-9)
        assertEquals(0.125, points[0][1], 1e-9)
        // Every rotation round-trips between the frame and the crop.
        listOf(0, 90, 180, 270).forEach { rotation ->
            val rotated = ModelCrop(100.0, 100.0, 80.0, rotation)
            val back = rotated.toCrop(rotated.toFrame(0.2, 0.7))
            assertEquals(0.2, back[0], 1e-9, "$rotation")
            assertEquals(0.7, back[1], 1e-9, "$rotation")
        }
        // The recorder's crop mapping equals the model transform's at every rotation.
        listOf(0, 90, 180, 270).forEach { rotation ->
            val rotated = ModelCrop(100.0, 100.0, 80.0, rotation)
            listOf(0.2 to 0.7, 0.0 to 0.0, 1.0 to 0.35).forEach { (u, v) ->
                val mine = rotated.toFrame(u, v)
                val model =
                    dev.cubentia.app.camera.corner.CornerModelTransform
                        .cropToFrame(u, v, 100.0, 100.0, 80.0, rotation)
                assertEquals(model.x, mine.x, 1e-9, "toFrame x at $rotation")
                assertEquals(model.y, mine.y, 1e-9, "toFrame y at $rotation")
            }
            listOf(
                dev.cubentia.app.camera
                    .CornerPoint(37.0, 151.0),
                dev.cubentia.app.camera
                    .CornerPoint(180.0, 20.0),
            ).forEach { point ->
                val mine = rotated.toCrop(point)
                val model =
                    dev.cubentia.app.camera.corner.CornerModelTransform
                        .frameToCrop(point.x, point.y, 100.0, 100.0, 80.0, rotation)
                assertEquals(model.x, mine[0], 1e-9, "toCrop u at $rotation")
                assertEquals(model.y, mine[1], 1e-9, "toCrop v at $rotation")
            }
        }
        // At 90 degrees the crop's top (v = 0) lies toward the frame's -x... as CornerModelTransform maps it.
        val at90 = ModelCrop(100.0, 100.0, 80.0, 90).toFrame(0.5, 0.0)
        assertEquals(20.0, at90.x, 1e-9)
        assertEquals(100.0, at90.y, 1e-9)
    }

    @Test fun `manual placement always starts at the guide hexagon, never the detector points, and stays inside the crop`() {
        val template = initialKeypoints()
        // The seed is the Y-anchor guide geometry alone, whatever the detector found: an untouched point is never its guess.
        val detector = List(7) { listOf(0.1 * it, 0.5) }
        assertTrue(template != detector)
        assertEquals(template, initialKeypoints())
        assertEquals(7, template.size)
        assertEquals(listOf(0.5, 0.5), template[6])
        assertEquals(0.5, template[0][0], 1e-9)
        assertTrue(template[0][1] < 0.5 && template[3][1] > 0.5, "T above, B below the centre")
        assertTrue(template[1][0] < 0.5 && template[5][0] > 0.5, "UL left, UR right")
        assertEquals(listOf(1.0, 0.0), movedKeypoint(listOf(0.9, 0.1), 0.5, -0.5))
        assertEquals(listOf(0.6, 0.3), movedKeypoint(listOf(0.5, 0.5), 0.1, -0.2).map { Math.round(it * 10) / 10.0 })
    }

    @Test fun `a new session never writes into a saved group`() {
        val taken = setOf("lenovo_stla_warm", "lenovo_stla_warm-2")
        assertEquals("lenovo_stla_cool", freshGroupName("lenovo_stla_cool") { it in taken })
        assertEquals("lenovo_stla_warm-3", freshGroupName("lenovo_stla_warm") { it in taken })
        assertEquals(1, nextKeypointIndex(emptyList()))
        assertEquals(4, nextKeypointIndex(listOf("0001.json", "0001.png", "0003.json", "notes.txt")))
    }

    @Test fun `the group manifest marks the 10-19 pipeline and the operator's light direction`() {
        val device =
            dev.cubentia.domain.recognition
                .DeviceInfo("TB128XU", "LENOVO", "13", "b", 33)
        val manifest = groupManifest(device, " stla ", "warm ", dev.cubentia.domain.recognition.LightDirection.FRONT)
        assertEquals("10.19", manifest.pipeline)
        assertEquals("stla", manifest.cube)
        assertEquals("warm", manifest.light)
        assertEquals(dev.cubentia.domain.recognition.LightDirection.FRONT, manifest.lightDirection)
        assertEquals(null, manifest.torch)
        val lit = groupManifest(device, "stla", "warm", dev.cubentia.domain.recognition.LightDirection.FRONT, torch = true)
        assertEquals(true, lit.torch)
    }

    @Test fun `the torch is recorded only on a camera with a flash unit`() {
        assertEquals(true, recordedTorch(hasFlash = true, torchOn = true))
        assertEquals(false, recordedTorch(hasFlash = true, torchOn = false))
        assertEquals(null, recordedTorch(hasFlash = false, torchOn = true))
        assertEquals(null, recordedTorch(hasFlash = null, torchOn = true))
    }

    @Test fun `the model-input crop uses the corner model's own radius multiplier`() {
        assertEquals(dev.cubentia.app.camera.CORNER_MODEL_CROP_RADIUS_MULTIPLIER, MODEL_CROP_RADIUS_MULTIPLIER, 0.0)
    }
}
