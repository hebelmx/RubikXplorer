package dev.cubentia.app.camera.recorder

import dev.cubentia.app.camera.CornerDetector
import dev.cubentia.app.camera.CornerGuide
import dev.cubentia.app.camera.CornerLocator
import dev.cubentia.app.camera.CornerReader
import dev.cubentia.app.camera.grid
import dev.cubentia.app.camera.toCornerFrame
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

    @Test fun `new face review selects the first unread sticker and palette entry advances`() {
        val prefilled = List(9) { if (it == 4 || it == 6) null else Color.WHITE }
        val review = TruthEntry().withPrefill(prefilled)
        assertEquals(4, review.selected)

        val edited = review.set(Color.RED)
        assertEquals(Color.RED, edited.colors[4])
        assertEquals(TruthSource.ENTERED, edited.sources[4])
        assertEquals(6, edited.selected)
        assertEquals(Color.WHITE, edited.colors[0])
        assertEquals(TruthSource.PREFILLED, edited.sources[0])
    }

    @Test fun `fully prefilled face review needs explicit selection before applying a palette color`() {
        val review = TruthEntry().withPrefill(List(9) { Color.WHITE })
        assertNull(review.selected)
        assertTrue(review.complete)
        assertEquals(review, review.set(Color.RED))

        val edited = review.select(0).set(Color.RED)
        assertEquals(Color.RED, edited.colors[0])
        assertEquals(TruthSource.ENTERED, edited.sources[0])
        assertTrue(edited.complete)
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

    @Test fun `restarting holds keeps keypoint-only positives and no-cube shots in the completed group`() {
        val base = "lenovo_stla_warm"
        val taken = setOf(base, "$base-2")
        val restarted = CornerRecordingGroup().saved(base, CornerGroupSave.HOLD).restartHolds()
        assertEquals(base, restarted.name)

        val positive = restarted.targetName(CornerGroupSave.SUPPLEMENTAL, base) { it in taken }
        assertEquals(base, positive)
        val afterPositive = restarted.saved(positive, CornerGroupSave.SUPPLEMENTAL)
        val negative = afterPositive.targetName(CornerGroupSave.SUPPLEMENTAL, base) { it in taken }
        assertEquals(base, negative)
        val afterNegative = afterPositive.saved(negative, CornerGroupSave.SUPPLEMENTAL)
        assertEquals(base, afterNegative.name)
        assertEquals("$base-3", afterNegative.targetName(CornerGroupSave.HOLD, base) { it in taken })
    }

    @Test fun `the group changes only when the next labeled hold is written`() {
        val base = "lenovo_stla_warm"
        val taken = setOf(base)
        val restarted = CornerRecordingGroup().saved(base, CornerGroupSave.HOLD).restartHolds()
        val next = restarted.targetName(CornerGroupSave.HOLD, base) { it in taken }
        assertEquals("$base-2", next)

        // Preparing a target or failing to write the hold leaves supplemental saves in the previous group.
        assertEquals(base, restarted.name)
        assertEquals(base, restarted.targetName(CornerGroupSave.SUPPLEMENTAL, base) { it in taken + next })
        assertEquals("$base-3", restarted.targetName(CornerGroupSave.HOLD, base) { it in taken + next })

        // Once the hold is written, a retry after a companion failure, hold 2 and supplemental saves all reuse the folder.
        val active = restarted.saved(next, CornerGroupSave.HOLD)
        assertEquals(next, active.name)
        assertEquals(next, active.targetName(CornerGroupSave.HOLD, base) { it in taken + next })
        assertEquals(next, active.targetName(CornerGroupSave.SUPPLEMENTAL, base) { it in taken + next })
    }

    @Test fun `a new condition clears the active group and can start with standalone keypoints`() {
        val base = "lenovo_stla_warm"
        val taken = setOf(base)
        val newCondition = CornerRecordingGroup()
        assertNull(newCondition.name)

        // A new calibration or torch state can have the same device, cube and light in its base name.
        val standalone = newCondition.targetName(CornerGroupSave.SUPPLEMENTAL, base) { it in taken }
        assertEquals("$base-2", standalone)
        val active = newCondition.saved(standalone, CornerGroupSave.SUPPLEMENTAL)
        assertEquals(standalone, active.name)
        assertEquals(standalone, active.targetName(CornerGroupSave.HOLD, base) { it in taken + standalone })
        assertEquals("lenovo_stlb_cool", newCondition.targetName(CornerGroupSave.HOLD, "lenovo_stlb_cool") { it in taken })
    }

    @Test fun `the group manifest marks the 10-22 pipeline, the operator's light direction and the recorder calibration`() {
        val device =
            dev.cubentia.domain.recognition
                .DeviceInfo("TB128XU", "LENOVO", "13", "b", 33)
        val manifest = groupManifest(device, " stla ", "warm ", dev.cubentia.domain.recognition.LightDirection.FRONT)
        assertEquals("10.22", manifest.pipeline)
        assertEquals("stla", manifest.cube)
        assertEquals("warm", manifest.light)
        assertEquals(dev.cubentia.domain.recognition.LightDirection.FRONT, manifest.lightDirection)
        assertEquals(null, manifest.torch)
        assertEquals(null, manifest.calibration)
        assertEquals(emptyList<dev.cubentia.domain.recognition.CornerHoldManifest>(), manifest.holds)
        val centers = calibration(white = 5.0, yellow = 30.0)
        val lit =
            groupManifest(device, "stla", "warm", dev.cubentia.domain.recognition.LightDirection.SIDE, torch = true, calibration = centers)
        assertEquals(true, lit.torch)
        assertEquals(centers, lit.calibration)
        assertEquals(dev.cubentia.domain.recognition.LightDirection.SIDE, lit.lightDirection)
    }

    @Test fun `each saved hold adds its torch state and leaves the other holds unchanged`() {
        val device =
            dev.cubentia.domain.recognition
                .DeviceInfo("SM-S721U1", "samsung", "15", "b", 35)
        val manifest = groupManifest(device, "stla", "warm", dev.cubentia.domain.recognition.LightDirection.FRONT, torch = true)
        val one = withHold(manifest, 1, true)
        assertEquals(
            listOf(
                dev.cubentia.domain.recognition
                    .CornerHoldManifest(1, true),
            ),
            one.holds,
        )
        val two = withHold(one, 2, false)
        assertEquals(
            listOf(
                dev.cubentia.domain.recognition
                    .CornerHoldManifest(1, true),
                dev.cubentia.domain.recognition
                    .CornerHoldManifest(2, false),
            ),
            two.holds,
        )
        assertEquals(manifest.copy(holds = two.holds, countCheck = dev.cubentia.domain.recognition.CornerCountCheck.PENDING), two)
        // A retaken hold replaces only its own entry, kept in hold order; a camera without a flash records null.
        assertEquals(
            listOf(
                dev.cubentia.domain.recognition
                    .CornerHoldManifest(1, null),
                dev.cubentia.domain.recognition
                    .CornerHoldManifest(2, false),
            ),
            withHold(two, 1, null).holds,
        )
    }

    private fun face(vararg lightness: Double): dev.cubentia.domain.recognition.FrameStats =
        dev.cubentia.domain.recognition.FrameStats(
            lightness.map {
                dev.cubentia.domain.recognition
                    .CellReading(Lab(it, 0.0, 0.0), true)
            },
            0.0,
            0.0,
            0.0,
            0,
        )

    @Test fun `hold diagnostics carry the light check in the recorder's mode, the uncalibrated score, torch and settle`() {
        val settle =
            dev.cubentia.domain.recognition
                .CornerSettleDiagnostics(640, dev.cubentia.domain.recognition.SettleOutcome.SETTLED, "on+awb", 120)
        val top = face(*DoubleArray(9) { 80.0 })
        val shaded = face(*DoubleArray(9) { 40.0 })
        val lit = face(*DoubleArray(9) { 75.0 })
        // Uncalibrated: no valid calibration; the darker side face is 40 L* below the top, over the 27 L* cut.
        val uncalibrated = holdDiagnostics(listOf(top, shaded, lit), null, torch = null, settle = settle)
        assertEquals("uncalibrated", uncalibrated.lightMode)
        assertEquals(80.0, uncalibrated.topLevel!!, 1e-9)
        assertEquals(40.0, uncalibrated.leftLevel!!, 1e-9)
        assertEquals(40.0, uncalibrated.lightScore!!, 1e-9)
        assertEquals(false, uncalibrated.lightPassed)
        assertEquals(40.0, uncalibrated.uncalibratedScore!!, 1e-9)
        assertEquals(settle, uncalibrated.settle)
        assertEquals(null, uncalibrated.torch)
        // Calibrated against the recorder calibration: relative brightness, the ratio score; the uncalibrated score stays beside it.
        val centers =
            CalibrationCenters(
                Color.entries.associateWith { color ->
                    if (color ==
                        Color.WHITE
                    ) {
                        Lab(80.0, 0.0, 0.0)
                    } else {
                        Lab(
                            50.0,
                            60.0 * kotlin.math.cos(color.ordinal.toDouble()),
                            60.0 * kotlin.math.sin(color.ordinal.toDouble()),
                        )
                    }
                },
            )
        val calibrated = holdDiagnostics(listOf(top, lit, lit), centers, torch = false, settle = null)
        assertEquals("calibrated", calibrated.lightMode)
        assertEquals(1.0, calibrated.topLevel!!, 1e-9)
        assertEquals(80.0 / 75.0, calibrated.lightScore!!, 1e-9)
        assertEquals(true, calibrated.lightPassed)
        assertEquals(5.0, calibrated.uncalibratedScore!!, 1e-9)
        assertEquals(false, calibrated.torch)
        assertEquals(null, calibrated.settle)
        // A lit torch names the mode `torch`, as ScanDiagnostics does; the check is still measured.
        val torch = holdDiagnostics(listOf(top, lit, lit), centers, torch = true, settle = null)
        assertEquals("torch", torch.lightMode)
        assertEquals(calibrated.lightScore, torch.lightScore)
        // A face not measured: its level and the scores are null, and nothing passed or failed.
        val unmeasured = holdDiagnostics(listOf(top, null, lit), null, torch = null, settle = null)
        assertNull(unmeasured.leftLevel)
        assertNull(unmeasured.lightScore)
        assertNull(unmeasured.lightPassed)
        assertNull(unmeasured.uncalibratedScore)
        assertEquals(80.0, unmeasured.topLevel!!, 1e-9)
        assertNull(holdDiagnostics(emptyList(), null, null, null).topLevel)
    }

    @Test fun `a capture with no metering since the reset records manual-ev and the EV index, never a null settle`() {
        // GH-330: the tracker reset after the previous capture; an EV step does not request metering, so the capture then has none.
        val tracker =
            dev.cubentia.app.camera
                .SettleTracker()
        tracker.request(1_000)
        tracker.reset()
        val now = 5_000L
        val settled = tracker.outcome(now)?.let { it != dev.cubentia.app.camera.Settle.SETTLING }
        assertNull(settled)
        val manual = captureSettle(settled, null, tracker.elapsed(now), tracker.futureMs(), awbLocked = false)
        assertEquals(
            dev.cubentia.domain.recognition
                .CornerSettleDiagnostics(null, dev.cubentia.domain.recognition.SettleOutcome.MANUAL_EV, SETTLE_LOCK_NONE, null),
            manual,
        )
        val top = face(*DoubleArray(9) { 80.0 })
        val diagnostics = holdDiagnostics(listOf(top, top, top), null, torch = null, settle = manual, evIndex = 2)
        assertEquals(manual, diagnostics.settle)
        assertEquals(2, diagnostics.evIndex)
        assertNull(holdDiagnostics(listOf(top, top, top), null, null, manual).evIndex)
        // After hold 1 the white-balance lock stays held: a manual-ev hold then records the lock word `awb`.
        assertEquals(manual.copy(lock = SETTLE_LOCK_AWB), captureSettle(null, null, null, null, awbLocked = true))
        assertEquals("awb", SETTLE_LOCK_AWB)
        // A settled metering keeps its reported settle; one that left nothing reported is still never null.
        val reported =
            dev.cubentia.domain.recognition
                .CornerSettleDiagnostics(640, dev.cubentia.domain.recognition.SettleOutcome.TIMEOUT, "unsupported", 120)
        assertEquals(reported, captureSettle(true, reported, 900, 120, awbLocked = true))
        assertEquals(
            dev.cubentia.domain.recognition.SettleOutcome.MANUAL_EV,
            captureSettle(true, null, 900, 120, awbLocked = false).outcome,
        )
        // Still settling: the elapsed time and the future's part, with no lock held.
        assertEquals(
            dev.cubentia.domain.recognition
                .CornerSettleDiagnostics(300, dev.cubentia.domain.recognition.SettleOutcome.SETTLING, "none", 80),
            captureSettle(false, reported, 300, 80, awbLocked = true),
        )
    }

    @Test fun `the recorder torch is lit in corner mode while calibrating too, never in face mode or without a flash`() {
        assertTrue(recorderTorchOn(cornerMode = true, hasFlash = true, torchOn = true))
        assertFalse(recorderTorchOn(cornerMode = true, hasFlash = true, torchOn = false))
        assertFalse(recorderTorchOn(cornerMode = false, hasFlash = true, torchOn = true))
        assertFalse(recorderTorchOn(cornerMode = true, hasFlash = false, torchOn = true))
        assertFalse(recorderTorchOn(cornerMode = true, hasFlash = null, torchOn = true))
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

    @Test fun `a cube, light or torch change, or a mode change that switches the torch, recalibrates`() {
        fun change(
            cube: String = "stla",
            light: String = "warm",
            torch: Boolean = true,
            fromMode: Boolean = true,
            toMode: Boolean = true,
            hasFlash: Boolean? = true,
        ) = recalibrates("stla", cube, "warm", light, true, torch, fromMode, toMode, hasFlash)
        assertFalse(change())
        assertFalse(change(cube = " stla ", light = "warm "))
        assertTrue(change(cube = "stlb"))
        assertTrue(change(light = "cool"))
        assertTrue(change(torch = false))
        assertTrue(change(torch = false, hasFlash = false))
        // Face mode calibrated with the torch off; corner mode lights it on a flash device: calibrate again.
        assertTrue(change(fromMode = false, toMode = true))
        assertTrue(change(fromMode = true, toMode = false))
        // Without a flash unit the torch never lights, so a mode change alone keeps the calibration.
        assertFalse(change(fromMode = false, toMode = true, hasFlash = false))
        assertFalse(change(fromMode = false, toMode = true, hasFlash = null))
        // The torch off in both modes: a mode change does not switch it.
        assertFalse(recalibrates("stla", "stla", "warm", "warm", false, false, false, true, true))
    }

    @Test fun `a saved hold records the applied torch, falling back to the choice, and null without a flash`() {
        assertNull(holdTorch(recorded = null, applied = true))
        assertNull(holdTorch(recorded = null, applied = null))
        assertEquals(false, holdTorch(recorded = true, applied = false))
        assertEquals(true, holdTorch(recorded = false, applied = true))
        assertEquals(true, holdTorch(recorded = true, applied = null))
        assertEquals(false, holdTorch(recorded = false, applied = null))
    }

    private fun cornerFace(
        lightness: Double,
        unread: Int? = null,
    ) = dev.cubentia.domain.recognition.FrameStats(
        List(9) {
            if (it == unread) {
                null
            } else {
                dev.cubentia.domain.recognition
                    .CellReading(Lab(lightness, 0.0, 0.0), true)
            }
        },
        120.0,
        0.0,
        300.0,
        12,
    )

    @Test fun `a corner capture with nothing located is a no-cube miss with the detector-missed text`() {
        assertEquals(CornerMiss.NO_CUBE, cornerMiss(1, null))
        val none =
            dev.cubentia.app.camera
                .CornerFrame(false, List(3) { null })
        assertEquals(CornerMiss.NO_CUBE, cornerMiss(1, none))
        assertEquals("Detector missed: place the 7 corners (colors are not saved).", cornerMissStatus(CornerMiss.NO_CUBE))
    }

    @Test fun `a located corner whose faces fall below the evidence floors is too dark and suggests EV+`() {
        val located =
            dev.cubentia.app.camera
                .CornerFrame(false, List(3) { cornerFace(40.0) }, score = 0.7)
        assertEquals(CornerMiss.TOO_DARK, cornerMiss(1, located))
        val scoreOnly =
            dev.cubentia.app.camera
                .CornerFrame(false, List(3) { null }, score = 0.4)
        assertEquals(CornerMiss.TOO_DARK, cornerMiss(1, scoreOnly))
        val status = cornerMissStatus(CornerMiss.TOO_DARK)
        assertTrue(status.contains("EV+"))
        assertTrue(status.contains("colors are not saved"))
        val none =
            dev.cubentia.app.camera
                .CornerFrame(false, List(3) { null })
        assertTrue(cornerMiss(1, none) != cornerMiss(1, located))
        assertTrue(cornerMissStatus(cornerMiss(1, none)!!) != cornerMissStatus(cornerMiss(1, located)!!))
    }

    @Test fun `a detected corner with one face missing or dark is too dark`() {
        val dropped =
            dev.cubentia.app.camera
                .CornerFrame(true, listOf(cornerFace(80.0), null, cornerFace(80.0)), score = 0.9)
        assertEquals(CornerMiss.TOO_DARK, cornerMiss(1, dropped))
        val dim =
            dev.cubentia.app.camera.CornerFrame(
                true,
                listOf(cornerFace(80.0), cornerFace(40.0, unread = 0), cornerFace(80.0)),
                score = 0.9,
            )
        assertEquals(CornerMiss.TOO_DARK, cornerMiss(1, dim))
    }

    @Test fun `a detected corner whose faces have evidence but an unread cell is an unread-cell miss`() {
        val unread =
            dev.cubentia.app.camera.CornerFrame(
                true,
                listOf(cornerFace(80.0), cornerFace(80.0, unread = 3), cornerFace(80.0)),
                score = 0.9,
            )
        assertEquals(CornerMiss.UNREAD_CELL, cornerMiss(1, unread))
        assertEquals("A face had an unread cell: place the 7 corners (colors are not saved).", cornerMissStatus(CornerMiss.UNREAD_CELL))
    }

    @Test fun `a usable corner is no miss`() {
        val usable =
            dev.cubentia.app.camera
                .CornerFrame(true, List(3) { cornerFace(80.0) }, score = 0.9)
        assertTrue(cornerSamples(1, usable) != null)
        assertNull(cornerMiss(1, usable))
    }

    @Test fun `a keypoint-only save after an unread cell says the colors were not recorded`() {
        assertEquals(
            "Corner 2 colors NOT recorded: a face had an unread cell. Saved keypoints only as positive 0003; " +
                "Retake corner 2 to record them.",
            keypointsOnlySaved(3, 2, CornerMiss.UNREAD_CELL),
        )
    }

    @Test fun `a keypoint-only save after a too-dark miss says the colors were not recorded and points to EV+`() {
        assertEquals(
            "Corner 2 colors NOT recorded: faces too dark, tap EV+ first. Saved keypoints only as positive 0003; " +
                "Retake corner 2 to record them.",
            keypointsOnlySaved(3, 2, CornerMiss.TOO_DARK),
        )
    }

    @Test fun `a keypoint-only save after no cube says the colors were not recorded`() {
        assertEquals(
            "Corner 1 colors NOT recorded: the detector found no cube. Saved keypoints only as positive 0003; " +
                "Retake corner 1 to record them.",
            keypointsOnlySaved(3, 1, CornerMiss.NO_CUBE),
        )
    }

    @Test fun `a deliberate keypoint-only save keeps the plain status`() {
        assertEquals("Saved positive 0003 (keypoints only).", keypointsOnlySaved(3, 1, null))
    }

    @Test fun `the corner button offers Retake after a missed corner and Capture otherwise`() {
        assertEquals("Retake corner 2", cornerCaptureLabel(2, retake = true))
        assertEquals("Capture corner 2", cornerCaptureLabel(2, retake = false))
    }

    @Test fun `an undetected zero-score frame that still carries faces is too dark`() {
        val zeroScore =
            dev.cubentia.app.camera
                .CornerFrame(false, List(3) { cornerFace(40.0) }, score = 0.0)
        assertEquals(CornerMiss.TOO_DARK, cornerMiss(1, zeroScore))
    }

    @Test fun `CornerReader output on a dull gray frame is too dark when located and no cube when not`() {
        val image = java.awt.image.BufferedImage(240, 320, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = java.awt.Color(100, 100, 100)
        graphics.fillRect(0, 0, 240, 320)
        graphics.dispose()
        val yuv = image.toCornerFrame()
        val guide = CornerGuide(grid(240, 320, 0), 0)
        val located =
            CornerReader.read(
                yuv,
                guide,
                detector =
                    CornerDetector {
                        _,
                        g,
                        ->
                        CornerLocator.Location(g.quads(g.vertices()), 0.5)
                    },
            )
        assertEquals(CornerMiss.TOO_DARK, cornerMiss(1, located))
        val missed = CornerReader.read(yuv, guide, detector = CornerDetector { _, _ -> null })
        assertEquals(CornerMiss.NO_CUBE, cornerMiss(1, missed))
    }

    /*
     * Story 10.28: the nine-of-each-colour count check, the refusal, the override reason and the side-face pre-fill lock.
     */

    /** A hold's labels with every face its own centre colour (a solved cube over both holds); all entered, none reviewed. */
    private fun solvedHold(hold: Int): CornerHoldLabels {
        val plan =
            dev.cubentia.domain.recognition.QuickScanPlan.holds[hold - 1]
        return CornerHoldLabels(
            faces = plan.map { it.face },
            colors = plan.map { step -> List(9) { step.centerColor } },
            sources = plan.map { List(9) { TruthSource.ENTERED } },
            reviewed = plan.map { List(9) { false } },
        )
    }

    /** [labels] with sticker [index] of face [faceIndex] set to [color] as an unreviewed pre-fill. */
    private fun withPrefilled(
        labels: CornerHoldLabels,
        faceIndex: Int,
        index: Int,
        color: Color,
    ) = labels.copy(
        colors =
            labels.colors.mapIndexed { f, face ->
                if (f ==
                    faceIndex
                ) {
                    face.mapIndexed { i, c -> if (i == index) color else c }
                } else {
                    face
                }
            },
        sources =
            labels.sources.mapIndexed { f, face ->
                if (f == faceIndex) face.mapIndexed { i, s -> if (i == index) TruthSource.PREFILLED else s } else face
            },
    )

    /** Where the BLUE-centred face sits in the plan: its hold and face index. */
    private fun blueFace(): Pair<Int, Int> =
        dev.cubentia.domain.recognition.QuickScanPlan.holds
            .withIndex()
            .firstNotNullOf { (hold, plan) ->
                plan.indexOfFirst { it.centerColor == Color.BLUE }.takeIf { it >= 0 }?.let { (hold + 1) to it }
            }

    @Test fun `a balanced group passes the count check over both holds, centres included`() {
        val check = checkNotNull(colorCount(mapOf(1 to solvedHold(1), 2 to solvedHold(2))))
        assertTrue(check.passed)
        assertEquals(Color.entries.associateWith { 9 }, check.counts)
        assertTrue(check.over.isEmpty() && check.under.isEmpty())
    }

    @Test fun `10 White and 8 Blue is refused, naming both, and rings the pre-filled White on its face`() {
        val (hold, faceIndex) = blueFace()
        val holds = mapOf(1 to solvedHold(1), 2 to solvedHold(2)).toMutableMap()
        holds[hold] = withPrefilled(holds.getValue(hold), faceIndex, 8, Color.WHITE)
        val check = checkNotNull(colorCount(holds))
        assertFalse(check.passed)
        assertEquals(mapOf(Color.WHITE to 10), check.over)
        assertEquals(mapOf(Color.BLUE to 8), check.under)
        assertEquals("Not saved: White 10 (over) · Blue 8 (under)", countRefusal(check))
        val faces = overCountedPrefills(holds, check.over.keys)
        assertEquals(1, faces.size)
        val face = faces.single()
        assertEquals(hold, face.hold)
        assertEquals(faceIndex, face.faceIndex)
        assertEquals(holds.getValue(hold).faces[faceIndex], face.face)
        assertEquals(setOf(8), face.ringed)
        assertEquals(holds.getValue(hold).colors[faceIndex], face.colors)
        // An entered (not pre-filled) over colour is not ringed: no face is listed.
        val entered = holds.toMutableMap()
        entered[hold] = holds.getValue(hold).copy(sources = solvedHold(hold).sources)
        assertEquals(emptyList<RefusalFace>(), overCountedPrefills(entered, check.over.keys))
    }

    @Test fun `faces with over-coloured pre-fills are listed in hold order, then face index`() {
        val holds =
            mapOf(
                2 to withPrefilled(withPrefilled(solvedHold(2), 2, 0, Color.WHITE), 1, 3, Color.WHITE),
                1 to withPrefilled(solvedHold(1), 1, 5, Color.WHITE),
            )
        val faces = overCountedPrefills(holds, setOf(Color.WHITE))
        assertEquals(listOf(1 to 1, 2 to 1, 2 to 2), faces.map { it.hold to it.faceIndex })
        assertEquals(listOf(setOf(5), setOf(3), setOf(0)), faces.map { it.ringed })
    }

    @Test fun `the count check waits for both holds and uses the latest capture of a retaken hold`() {
        assertNull(colorCount(emptyMap()))
        assertNull(colorCount(mapOf(1 to solvedHold(1))))
        assertNull(colorCount(mapOf(2 to solvedHold(2))))
        val (hold, faceIndex) = blueFace()
        val bad = withPrefilled(solvedHold(hold), faceIndex, 0, Color.WHITE)
        val holds = mutableMapOf(1 to solvedHold(1), 2 to solvedHold(2))
        holds[hold] = bad
        assertFalse(checkNotNull(colorCount(holds)).passed)
        // Retaking the hold replaces its labels; the check reruns on the latest capture of each hold.
        holds[hold] = solvedHold(hold)
        assertTrue(checkNotNull(colorCount(holds)).passed)
    }

    @Test fun `the next hold after a write is the first hold not complete, else 3`() {
        assertEquals(1, nextCornerHold(emptySet()))
        assertEquals(2, nextCornerHold(setOf(1)))
        assertEquals(1, nextCornerHold(setOf(2)))
        assertEquals(3, nextCornerHold(setOf(1, 2)))
    }

    @Test fun `an override needs a reason that is not blank after trimming and writes it to the manifest`() {
        assertNull(overrideReason(""))
        assertNull(overrideReason("   "))
        assertNull(overrideReason(" \t\n "))
        assertEquals("peeling sticker", overrideReason("  peeling sticker "))
        val device =
            dev.cubentia.domain.recognition
                .DeviceInfo("TB128XU", "LENOVO", "13", "b", 33)
        val manifest = withHold(groupManifest(device, "stla", "warm", dev.cubentia.domain.recognition.LightDirection.FRONT), 1, null)
        val refused =
            ColorCountCheck(
                Color.entries.associateWith {
                    if (it == Color.WHITE) {
                        10
                    } else if (it == Color.BLUE) {
                        8
                    } else {
                        9
                    }
                },
            )
        val passed = ColorCountCheck(Color.entries.associateWith { 9 })
        val overridden = withCountCheck(manifest, refused, "  peeling sticker ")
        assertEquals(dev.cubentia.domain.recognition.CornerCountCheck.OVERRIDDEN, overridden.countCheck)
        assertEquals("peeling sticker", overridden.overrideReason)
        assertTrue(overridden.saved)
        listOf(null, "", "   ").forEach { blank ->
            val kept = withCountCheck(manifest, refused, blank)
            assertEquals(dev.cubentia.domain.recognition.CornerCountCheck.REFUSED, kept.countCheck)
            assertNull(kept.overrideReason)
            assertFalse(kept.saved)
        }
        val ok = withCountCheck(manifest, passed, "ignored")
        assertEquals(dev.cubentia.domain.recognition.CornerCountCheck.PASSED, ok.countCheck)
        assertNull(ok.overrideReason)
        // A written hold leaves the group pending again, without a reason.
        val again = withHold(overridden, 2, null)
        assertEquals(dev.cubentia.domain.recognition.CornerCountCheck.PENDING, again.countCheck)
        assertNull(again.overrideReason)
        assertFalse(again.saved)
    }

    @Test fun `a side face stays locked until each pre-fill is tapped or colour-picked, and face 0 never locks`() {
        val prefilled = List(9) { if (it == 4) null else Color.WHITE }
        val entry = TruthEntry().withPrefill(prefilled)
        // Automatic selection of the blank centre is not a review.
        assertEquals(4, entry.selected)
        assertEquals(List(9) { false }, entry.reviewed)
        assertEquals(listOf("Confirm locked: tap the 8 pre-filled stickers not checked yet"), cornerFaceLocks(1, entry))
        assertEquals(listOf("Confirm locked: tap the 8 pre-filled stickers not checked yet"), cornerFaceLocks(2, entry))
        assertEquals(emptyList<String>(), cornerFaceLocks(0, entry))
        // A palette pick on the auto-selected blank fills it; the next auto-selection is not a review either.
        val centred = entry.set(Color.BLUE, keepSamePrefill = true)
        assertEquals(TruthSource.ENTERED, centred.sources[4])
        assertEquals(listOf("Confirm locked: tap the 8 pre-filled stickers not checked yet"), cornerFaceLocks(1, centred))
        // A tap reviews a pre-fill; picking its own colour keeps it PREFILLED and reviewed.
        val tapped = centred.tap(0)
        assertEquals(0, tapped.selected)
        assertTrue(tapped.reviewed[0])
        assertEquals(TruthSource.PREFILLED, tapped.sources[0])
        assertEquals(listOf("Confirm locked: tap the 7 pre-filled stickers not checked yet"), cornerFaceLocks(1, tapped))
        val same = centred.select(1).set(Color.WHITE, keepSamePrefill = true)
        assertEquals(TruthSource.PREFILLED, same.sources[1])
        assertTrue(same.reviewed[1])
        assertEquals(listOf("Confirm locked: tap the 7 pre-filled stickers not checked yet"), cornerFaceLocks(2, same))
        // Select alone (automatic) never reviews; without keepSamePrefill the same colour counts as entered.
        assertFalse(centred.select(2).reviewed[2])
        assertEquals(TruthSource.ENTERED, centred.select(2).set(Color.WHITE).sources[2])
        // A different colour on a pre-fill is entered and reviewed.
        val changed = centred.select(3).set(Color.RED, keepSamePrefill = true)
        assertEquals(TruthSource.ENTERED, changed.sources[3])
        assertTrue(changed.reviewed[3])
        val all = (0 until 9).fold(centred) { acc, index -> acc.tap(index) }
        assertEquals(emptyList<String>(), cornerFaceLocks(1, all))
        assertEquals(emptyList<String>(), cornerFaceLocks(2, all))
        // Fresh entries and prefills start unreviewed.
        assertEquals(List(9) { false }, TruthEntry().reviewed)
        // Suggest again re-prefills the pre-filled cells unreviewed; only the entered centre keeps its review.
        assertEquals(List(9) { it == 4 }, all.suggestAgain(List(9) { Color.YELLOW }).reviewed)
        assertEquals(entry, entry.tap(9))
    }

    @Test fun `the pre-correction labels of the 10 corrected groups at add0c79e are all refused`() {
        val json = kotlinx.serialization.json.Json
        val snapshot =
            json
                .parseToJsonElement(
                    checkNotNull(
                        javaClass.getResourceAsStream("/recorder/pre-correction-labels-add0c79e.json"),
                    ).bufferedReader().readText(),
                ).let { it as kotlinx.serialization.json.JsonObject }

        fun kotlinx.serialization.json.JsonElement.text() = (this as kotlinx.serialization.json.JsonPrimitive).content

        fun kotlinx.serialization.json.JsonElement.list() = this as kotlinx.serialization.json.JsonArray
        val provenance = snapshot.getValue("provenance") as kotlinx.serialization.json.JsonObject
        assertTrue(provenance.getValue("commit").text().startsWith("add0c79e"))
        val groups =
            snapshot.getValue("groups").list().associate { element ->
                val group = element as kotlinx.serialization.json.JsonObject
                group.getValue("group").text() to
                    group.getValue("holds").list().associate { holdElement ->
                        val hold = holdElement as kotlinx.serialization.json.JsonObject
                        val faces = hold.getValue("faces").list().map { Face.valueOf(it.text()) }
                        hold.getValue("hold").text().toInt() to
                            CornerHoldLabels(
                                faces = faces,
                                colors = hold.getValue("truth").list().map { face -> face.list().map { Color.valueOf(it.text()) } },
                                sources =
                                    hold.getValue("sources").list().map { face ->
                                        face.list().map { TruthSource.valueOf(it.text().uppercase()) }
                                    },
                                reviewed = faces.map { List(9) { false } },
                            )
                    }
            }
        val corrections =
            json.parseToJsonElement(java.io.File("../docs/evidence/10-27/label-corrections-2026-11-23.json").readText())
                as kotlinx.serialization.json.JsonObject
        val corrected =
            corrections
                .getValue(
                    "corrections",
                ).list()
                .map { (it as kotlinx.serialization.json.JsonObject).getValue("group").text() }
        assertEquals(corrected.toSet(), groups.keys)
        assertEquals(10, groups.size)
        groups.forEach { (name, holds) ->
            val check = checkNotNull(colorCount(holds)) { "$name: both holds are needed" }
            assertFalse(check.passed, "$name was balanced before the corrections: ${check.counts}")
        }
        // The example the planning verified.
        assertEquals(
            "Not saved: White 10 (over) · Blue 8 (under)",
            countRefusal(checkNotNull(colorCount(groups.getValue("lenovo-tb128xu_stka_cool")))),
        )
    }
}
