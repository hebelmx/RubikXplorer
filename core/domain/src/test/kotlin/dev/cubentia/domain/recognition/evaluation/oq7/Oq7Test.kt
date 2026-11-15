package dev.cubentia.domain.recognition.evaluation.oq7

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.ClassificationResult
import dev.cubentia.domain.recognition.DeviceInfo
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.LabeledFaceSample
import dev.cubentia.domain.recognition.LiveFixtureJson
import dev.cubentia.domain.recognition.LiveFixtureRecord
import dev.cubentia.domain.recognition.NearestCenterClassifier
import dev.cubentia.domain.recognition.evaluation.FixtureGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One test per row of the Story 10.13 I/O matrix, plus `K-cal` parity with `NearestCenterClassifier`. */
class Oq7Test {
    @TempDir lateinit var temporary: File

    /** Six centers spread along a on L50, 40 ΔE apart. */
    private val centers: Map<Color, Lab> = Color.entries.associateWith { Lab(50.0, it.ordinal * 40.0 - 100.0, 0.0) }

    private fun at(
        color: Color,
        offset: Double,
    ) = centers.getValue(color).let { Lab(it.l, it.a + offset, it.b) }

    @Test
    fun `clear win - nearest 5, second 30 is confident`() {
        val custom =
            Color.entries.associateWith { Lab(50.0, 200.0, 0.0) } +
                mapOf(Color.WHITE to Lab(50.0, 0.0, 0.0), Color.YELLOW to Lab(50.0, 35.0, 0.0))
        assertEquals(Prediction(Color.WHITE, Confidence.CONFIDENT), NearestCenter.predict(Lab(50.0, 5.0, 0.0), custom))
    }

    @Test
    fun `close call - nearest 20, second 28 is uncertain`() {
        val custom =
            Color.entries.associateWith { Lab(50.0, 200.0, 0.0) } +
                mapOf(Color.RED to Lab(50.0, 20.0, 0.0), Color.ORANGE to Lab(50.0, -28.0, 0.0))
        assertEquals(Prediction(Color.RED, Confidence.UNCERTAIN), NearestCenter.predict(Lab(50.0, 0.0, 0.0), custom))
    }

    @Test
    fun `confidently wrong counts a confident misread only`() {
        val group = group("g", Session.A2, emptyList())
        val confident = ScoredSticker(Variant.K_CAL, group, Face.U, 0, Color.WHITE, Prediction(Color.YELLOW, Confidence.CONFIDENT))
        val uncertain = ScoredSticker(Variant.K_CAL, group, Face.U, 1, Color.WHITE, Prediction(Color.YELLOW, Confidence.UNCERTAIN))
        val right = ScoredSticker(Variant.K_CAL, group, Face.U, 2, Color.WHITE, Prediction(Color.WHITE, Confidence.CONFIDENT))
        assertEquals(
            Metrics(classified = 3, correct = 1, confidentlyWrong = 1, uncertain = 1),
            Metrics.of(listOf(confident, uncertain, right)),
        )
        assertTrue(confident.confidentlyWrong)
        assertFalse(uncertain.confidentlyWrong)
    }

    @Test
    fun `bad white replaces WHITE only`() {
        val injected = Calibrations.withWhite(centers)
        assertEquals(Lab(76.0, 20.0, 71.0), injected[Color.WHITE])
        assertEquals(centers.filterKeys { it != Color.WHITE }, injected.filterKeys { it != Color.WHITE })
        assertEquals(centers.keys.toList(), injected.keys.toList())
    }

    @Test
    fun `sweep midpoint blends recorded and bad white`() {
        assertEquals(Lab(82.0, 7.0, 27.5), Calibrations.blend(Lab(88.0, -6.0, -16.0), 0.5))
        assertEquals(Lab(88.0, -6.0, -16.0), Calibrations.blend(Lab(88.0, -6.0, -16.0), 0.0))
        assertEquals(Calibrations.BAD_WHITE, Calibrations.blend(Lab(88.0, -6.0, -16.0), 1.0))
    }

    @Test
    fun `self calibration keys each face center by its truth`() {
        val readings = Face.entries.zip(Color.entries).map { (face, color) -> CenterReading(face, color, at(color, 1.0)) }
        val result = Calibrations.self(readings)
        assertEquals(SelfCalibration.Centers(Color.entries.associateWith { at(it, 1.0) }), result)
    }

    @Test
    fun `self calibration excludes a group missing a face or repeating a center color`() {
        val readings = Face.entries.zip(Color.entries).map { (face, color) -> CenterReading(face, color, at(color, 0.0)) }
        val missing = Calibrations.self(readings.filter { it.face != Face.B })
        assertEquals(SelfCalibration.Excluded("missing face B"), missing)
        val repeated = Calibrations.self(readings.map { if (it.face == Face.R) it.copy(color = Color.WHITE) else it })
        assertTrue(repeated is SelfCalibration.Excluded && "share a WHITE center" in repeated.reason, "$repeated")
        val run = RecognitionApproaches.kSelf(listOf(group("short", Session.A, Face.entries.filter { it != Face.B }.map { record(it) })))
        assertTrue(run.stickers.isEmpty())
        assertEquals(listOf(GroupNote("short", "missing face B")), run.notes)
    }

    @Test
    fun `leave one cube out trains on the other three cubes`() {
        val examples = listOf("c1", "c2", "c3", "c4").flatMap { cube -> Color.entries.map { LabeledLab(cube, at(it, 0.0), it) } }
        val result = LeaveOneCubeOutTraining.run(examples)
        assertEquals(listOf("c1", "c2", "c3", "c4"), result.folds.map { it.heldOut })
        result.folds.forEach { fold ->
            assertEquals(3, fold.trainingCubes.size)
            assertFalse(fold.heldOut in fold.trainingCubes)
            assertEquals(18, fold.trainingSize)
            assertEquals(6, fold.testSize)
        }
        assertEquals(examples.size, result.predictions.size)
    }

    @Test
    fun `MLP is deterministic for the same data and seed`() {
        val examples = Color.entries.flatMap { color -> listOf(-3.0, 0.0, 3.0).map { at(color, it) to color } }
        val first = TinyMlp.train(examples)
        val second = TinyMlp.train(examples)
        val probes = Color.entries.map { at(it, 1.5) }
        assertEquals(probes.map { first.probabilities(it).toList() }, probes.map { second.probabilities(it).toList() })
        assertEquals(Color.entries, probes.map { first.predict(it).color })
        assertEquals(126, TinyMlp.PARAMETERS)
        assertEquals(504, TinyMlp.WEIGHT_BYTES)
    }

    @Test
    fun `schema 1 records report no frames for O variants`() {
        val groups =
            Observations.from(
                listOf(FixtureGroup("dev_cube_studio", Face.entries.map { record(it, schema = 1) })),
            ) { _, _ -> null }
        assertEquals(Session.A, groups.single().session)
        listOf(RecognitionApproaches.oCal(groups), RecognitionApproaches.oSelf(groups)).forEach { run ->
            assertTrue(run.stickers.isEmpty())
            assertEquals(listOf(GroupNote("dev_cube_studio", RecognitionApproaches.NO_FRAMES)), run.notes)
        }
        assertEquals(54, RecognitionApproaches.kCal(groups).stickers.size)
    }

    @Test
    fun `AAR size reads arm64 native libraries and classes jar`() {
        val aar = File(temporary, "lib-1.0.aar")
        ZipOutputStream(aar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("jni/arm64-v8a/libx.so"))
            zip.write(ByteArray(1_000))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("jni/armeabi-v7a/liby.so"))
            zip.write(ByteArray(500))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.jar"))
            zip.write(ByteArray(200))
            zip.closeEntry()
        }
        val size = AarSize.read(aar)
        assertEquals(listOf("libx.so"), size.nativeLibraries)
        assertEquals(1_000L, size.nativeUncompressed)
        assertTrue(size.nativeCompressed in 1 until 1_000)
        assertEquals(200L, size.classesJar)
        assertEquals(1_600L, size.projectedAdded)
        val missing = assertThrows<IllegalArgumentException> { AarSize.read(File(temporary, "absent.aar")) }
        assertTrue("absent.aar" in missing.message.orEmpty())
    }

    @Test
    fun `missing end marker fails naming the file and leaves the document unchanged`() {
        val document = File(temporary, "oq7.md").apply { writeText("# Doc\n${Oq7Markdown.START}\nold\n") }
        val failure =
            assertThrows<IllegalArgumentException> {
                Oq7Runner.run(File(temporary, "no-fixtures"), document, emptyList(), frameFeatures = { error("unused") })
            }
        assertTrue(document.path in failure.message.orEmpty())
        assertEquals("# Doc\n${Oq7Markdown.START}\nold\n", document.readText())
        assertEquals(
            "a\n${Oq7Markdown.START}\nnew\n${Oq7Markdown.END}\nb",
            Oq7Markdown.replace("a\n${Oq7Markdown.START}\nold\n${Oq7Markdown.END}\nb", "new\n", "x.md"),
        )
    }

    @Test
    fun `K-cal equals NearestCenterClassifier on a fixture record`() {
        val record = LiveFixtureJson.decode(File(FIXTURES, "sm-s721u1_stka_studio-a/U.json").readText())
        val expected =
            (
                NearestCenterClassifier.predict(
                    record.labeledSample.sample,
                    record.calibration,
                ) as ClassificationResult.Classified
            ).value.colors
        val group = Observations.from(listOf(FixtureGroup("sm-s721u1_stka_studio-a", listOf(record)))) { _, _ -> null }
        assertEquals(expected, RecognitionApproaches.kCal(group).stickers.map { it.prediction.color })
        val bad = CalibrationCenters(Calibrations.withWhite(record.calibration.centers))
        val expectedBad =
            (
                NearestCenterClassifier.predict(
                    record.labeledSample.sample,
                    bad,
                ) as ClassificationResult.Classified
            ).value.colors
        assertEquals(expectedBad, RecognitionApproaches.kCal(group) { Calibrations.withWhite(it) }.stickers.map { it.prediction.color })
    }

    @Test
    fun `frame grid and histogram mode follow the recorder geometry`() {
        val cells = FrameGrid.cells(480, 422)
        assertEquals(9, cells.size)
        // 80% of 422 = 337.6 side, left 71.2, top 42.2, cell 112.53; ROI starts 20% into the cell.
        assertEquals(CellRoi(93, 64, 68, 68), cells[0])
        val white = List(80) { Lab(90.0, 0.0, 0.0) }
        val logo = List(20) { Lab(40.0, 60.0, 40.0) }
        val glare = List(10) { Lab(99.0, 0.0, 0.0) }
        val pixels = white + logo + glare
        val feature = LabHistogram.feature(pixels, LabHistogram.peak(pixels))
        assertEquals(Lab(90.0, 0.0, 0.0), feature.mode)
        assertEquals(0.8, feature.modeShare, 1e-9)
        assertEquals(80.0, feature.mean.l, 1e-9)
    }

    @Test
    fun `report renders every variant and is stable`() {
        val faces = Face.entries.zip(Color.entries).map { (face, color) -> record(face, center = color) }
        val cells = { color: Color -> List(9) { CellFeature(centers.getValue(color), centers.getValue(color), 1.0) } }
        val groups =
            Observations.from(
                listOf(
                    FixtureGroup("a_c1_studio-a", faces.map { it.copy(cube = "c1") }),
                    FixtureGroup("a_c2_studio-a", faces.map { it.copy(cube = "c2") }),
                ),
            ) { _, record -> cells(record.labeledSample.truth[4]) }
        val first = Oq7Markdown.render(Oq7Report.build(groups, emptyList()))
        val second = Oq7Markdown.render(Oq7Report.build(groups, emptyList()))
        assertEquals(first, second)
        Variant.entries.forEach { assertTrue("| ${it.id} | Total |" in first, it.id) }
    }

    @Test
    fun `leave one cube out never trains on the held-out cube`() {
        val agreed = listOf("c1", "c2", "c3").flatMap { cube -> Color.entries.map { LabeledLab(cube, at(it, 0.0), it) } }
        // The held-out cube labels every position as the next color, ten times over, so training on it would change the reads.
        val shifted = List(10) { Color.entries.map { LabeledLab("c4", at(it, 0.0), Color.entries[(it.ordinal + 1) % 6]) } }.flatten()
        val examples = agreed + shifted
        val result = LeaveOneCubeOutTraining.run(examples)
        val withoutHeldOut = TinyMlp.train(agreed.map { it.lab to it.truth })
        val withHeldOut = TinyMlp.train(examples.map { it.lab to it.truth })
        val heldOut = examples.indices.filter { examples[it].cube == "c4" }
        assertEquals(heldOut.map { withoutHeldOut.predict(examples[it].lab) }, heldOut.map { result.predictions[it] })
        assertTrue(heldOut.map { withHeldOut.predict(examples[it].lab).color } != heldOut.map { result.predictions[it].color })
    }

    @Test
    fun `O variants classify the cell mode and O-self calibrates from the center cells`() {
        // Modes mirror a (so the record calibration reads the mirrored color); means sit on the next color.
        fun mirror(color: Color) = Color.entries[Color.entries.size - 1 - color.ordinal]

        fun mode(color: Color) = Lab(80.0, centers.getValue(mirror(color)).a, 30.0)
        val records = Face.entries.map { record(it) }
        val groups =
            Observations.from(listOf(FixtureGroup("dev_cube_studio-a", records))) { _, record ->
                record.labeledSample.truth.map { truth ->
                    CellFeature(mean = at(Color.entries[(truth.ordinal + 1) % 6], 0.0), mode = mode(truth), modeShare = 1.0)
                }
            }
        val truth = records.flatMap { it.labeledSample.truth }
        assertEquals(truth.map(::mirror), RecognitionApproaches.oCal(groups).stickers.map { it.prediction.color })
        assertEquals(truth, RecognitionApproaches.oSelf(groups).stickers.map { it.prediction.color })
        // Bad white (L76 a20 b71) is nearer than BLUE to GREEN's mode (L80 a20 b30), and ORANGE's mode (a−100) loses its
        // WHITE center and falls to YELLOW (a−60); every other read is unchanged.
        val badWhite = mapOf(Color.GREEN to Color.WHITE, Color.ORANGE to Color.YELLOW)
        assertEquals(
            truth.map { badWhite[it] ?: mirror(it) },
            RecognitionApproaches.oCal(groups) { Calibrations.withWhite(it) }.stickers.map { it.prediction.color },
        )
    }

    @Test
    fun `logo report flags a low-share WHITE center`() {
        fun framed(cube: String) =
            Face.entries.map { record(it).copy(cube = cube) }.let { records ->
                FixtureGroup("dev_${cube}_studio-a", records)
            }
        val groups =
            Observations.from(listOf(framed("stka"), framed("stkb"))) { _, record ->
                val logo = record.cube == "stka" && record.labeledSample.truth[FaceSample.CENTER_INDEX] == Color.WHITE
                List(9) { index ->
                    val color = record.labeledSample.truth[index]
                    val share = if (logo && index == FaceSample.CENTER_INDEX) 0.3 else 1.0
                    CellFeature(at(color, 0.0), at(color, 0.0), share)
                }
            }
        val text = Oq7Markdown.render(Oq7Report.build(groups, emptyList()))
        val separation =
            "Fully separates the known logo centers (`stka`, `stla` WHITE): **yes** — " +
                "1 of 1 flagged, 0 false alarms on 11 other centers."
        assertTrue(separation in text, text)
        assertTrue("| dev_stka_studio-a | yes |" in text, text)
        assertTrue("| dev_stkb_studio-a | no |" in text, text)
    }

    @Test
    fun `histogram mode counts the peak bin and adjacent bins only`() {
        val dominant = List(60) { Lab(55.0, 40.0, -40.0) } // bin (5, 10, 5)
        val adjacent = List(25) { Lab(55.0, 56.0, -40.0) } // a bin 11
        val twoAway = List(15) { Lab(55.0, 72.0, -40.0) } // a bin 12
        val pixels = dominant + adjacent + twoAway
        val peak = LabHistogram.peak(pixels)
        assertEquals(HistogramBin(5, 10, 5), peak)
        val feature = LabHistogram.feature(pixels, peak)
        assertEquals(0.85, feature.modeShare, 1e-9)
        assertEquals((60 * 40.0 + 25 * 56.0) / 85, feature.mode.a, 1e-9)
        assertEquals(-40.0, feature.mode.b, 1e-9)
    }

    private fun group(
        name: String,
        session: Session,
        records: List<LiveFixtureRecord>,
    ) = GroupObservation(name, session, "maker model", "cube", records.map { FaceObservation(it, null) })

    private fun record(
        face: Face,
        schema: Int = 2,
        center: Color = Color.entries[face.ordinal],
    ): LiveFixtureRecord {
        val truth = List(9) { if (it == FaceSample.CENTER_INDEX) center else Color.entries[(face.ordinal + it) % 6] }
        return LiveFixtureRecord(
            schemaVersion = schema,
            device = DeviceInfo("model", "maker", "17", "build", 37),
            cube = "cube",
            light = "studio",
            capturedAt = "2026-11-15T00:00:00Z",
            calibration = CalibrationCenters(centers),
            labeledSample =
                LabeledFaceSample(
                    FaceSample(
                        face = face,
                        orientationDegrees = 0,
                        stickers = truth.map { at(it, 2.0) },
                        quality = CaptureQuality.ACCEPTABLE,
                    ),
                    truth,
                ),
            truthSources = if (schema == 1) emptyList() else List(9) { dev.cubentia.domain.recognition.TruthSource.ENTERED },
            frame = null,
        )
    }

    private companion object {
        val FIXTURES = File("../../Fixtures/live")
    }
}
