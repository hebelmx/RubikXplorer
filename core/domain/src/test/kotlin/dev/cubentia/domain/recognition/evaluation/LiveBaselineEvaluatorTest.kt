package dev.cubentia.domain.recognition.evaluation

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.DeviceInfo
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.LabeledFaceSample
import dev.cubentia.domain.recognition.LiveFixtureJson
import dev.cubentia.domain.recognition.LiveFixturePaths
import dev.cubentia.domain.recognition.LiveFixtureRecord
import dev.cubentia.domain.recognition.TruthSource
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val GROUP = "model_cube_indoor"

class LiveBaselineEvaluatorTest {
    @TempDir lateinit var temporary: File
    private val centers = CalibrationCenters(Color.entries.associateWith { Lab(50.0, it.ordinal * 20.0, 0.0) })

    private fun record(
        face: Face,
        model: String = "model",
        cube: String = "cube",
    ) = LiveFixtureRecord(
        device = DeviceInfo(model, "maker", "17", "build", 37),
        cube = cube,
        light = "indoor",
        capturedAt = "2026-11-14T00:00:00Z",
        calibration = centers,
        labeledSample =
            LabeledFaceSample(
                FaceSample(
                    face = face,
                    orientationDegrees = 0,
                    stickers =
                        List(9) {
                            centers.centers.getValue(Color.WHITE)
                        },
                    quality = CaptureQuality.ACCEPTABLE,
                ),
                List(9) { Color.WHITE },
            ),
        truthSources = List(9) { TruthSource.ENTERED },
    )

    private fun complete(
        model: String = "model",
        cube: String = "cube",
    ) = Face.entries.map { record(it, model, cube) }

    private fun evaluate(records: List<LiveFixtureRecord>) = LiveBaselineEvaluator.evaluate(listOf(FixtureGroup("group", records)))

    /** Story 10.10 AC2 gating matrix: two devices × two cubes, six faces each. */
    private fun matrix(
        models: List<String> = listOf("model", "other"),
        cubes: List<String> = listOf("cube", "stickerless"),
    ) = models.flatMap { model -> cubes.map { cube -> FixtureGroup("${model}_$cube", complete(model, cube)) } }

    private fun misread(record: LiveFixtureRecord) =
        record.copy(labeledSample = record.labeledSample.copy(truth = List(2) { Color.RED } + List(7) { Color.WHITE }))

    private fun misreadMatrix() = matrix().map { group -> group.copy(records = listOf(misread(group.records[0])) + group.records.drop(1)) }

    @Test fun `complete accurate matrix meets gate`() {
        val result = LiveBaselineEvaluator.evaluate(matrix())
        assertEquals(BaselineGate.MET, result.gate)
        assertEquals(216, result.total.correct)
        assertEquals(216, result.total.stickers)
        assertEquals(24, result.total.correctFaces)
        assertEquals(0, result.total.confidentlyWrong)
        assertEquals(result.total.correct, result.devices.sumOf { it.correct })
        assertEquals(2, result.devices.size)
        assertEquals(2, result.cubes)
        assertTrue(result.missingMatrix.isEmpty())
    }

    @Test fun `misreads miss gate and count confidently wrong`() {
        val result = LiveBaselineEvaluator.evaluate(misreadMatrix())
        assertEquals(BaselineGate.MISSED, result.gate)
        assertEquals(208, result.total.correct)
        assertEquals(216, result.total.stickers)
        assertEquals(20, result.total.correctFaces)
        assertEquals(8, result.total.confidentlyWrong)
    }

    @Test fun `one device only is incomplete`() {
        val result = LiveBaselineEvaluator.evaluate(matrix(models = listOf("model")))
        assertEquals(BaselineGate.INCOMPLETE, result.gate)
        val section = LiveBaselineMarkdown.render(result)
        assertTrue("Gate: **INCOMPLETE**" in section, section)
        assertTrue("devices covering every cube: 1 of 2" in section, section)
    }

    @Test fun `one cube only is incomplete`() {
        val result = LiveBaselineEvaluator.evaluate(matrix(cubes = listOf("cube")))
        assertEquals(BaselineGate.INCOMPLETE, result.gate)
        assertTrue("cubes: 1 of 2 recorded (cube)" in LiveBaselineMarkdown.render(result))
    }

    @Test fun `missing device cube cell is named`() {
        val result = LiveBaselineEvaluator.evaluate(matrix().filterNot { it.name == "other_stickerless" })
        assertEquals(BaselineGate.INCOMPLETE, result.gate)
        assertTrue("- maker other × stickerless (missing U, R, F, D, L, B)" in LiveBaselineMarkdown.render(result))
    }

    @Test fun `extra report-only device does not block verdict`() {
        val extra = FixtureGroup("tab_cube", complete("tab", "cube"))
        assertEquals(BaselineGate.MET, LiveBaselineEvaluator.evaluate(matrix() + extra).gate)
        assertEquals(BaselineGate.MISSED, LiveBaselineEvaluator.evaluate(misreadMatrix() + extra).gate)
    }

    @Test fun `unavailable faces and missing faces cannot yield verdict`() {
        val first = record(Face.U)
        listOf(
            first.copy(calibration = CalibrationCenters(centers.centers - Color.RED)),
            first.copy(labeledSample = first.labeledSample.copy(sample = first.labeledSample.sample.copy(quality = CaptureQuality.GLARE))),
        ).forEach { unavailable ->
            val result = evaluate(listOf(unavailable) + complete().filter { it.labeledSample.sample.face != Face.U })
            assertEquals(BaselineGate.INCOMPLETE, result.gate)
            assertEquals(45, result.total.stickers)
            assertEquals(1, result.total.unavailable)
            assertEquals(listOf(Face.U), result.groups.single().missing)
        }
        val missing = evaluate(complete().dropLast(1))
        assertEquals(BaselineGate.INCOMPLETE, missing.gate)
        assertEquals(listOf(Face.entries.last()), missing.groups.single().missing)
        assertEquals(BaselineGate.INCOMPLETE, LiveBaselineEvaluator.evaluate(emptyList()).gate)
    }

    private fun document() =
        File(temporary, "doc.md").apply {
            writeText("before\r\n${LiveBaselineMarkdown.START}\npending\n${LiveBaselineMarkdown.END}\r\nafter")
        }

    private fun fixtures(records: List<LiveFixtureRecord> = complete()): File =
        File(temporary, "live").apply {
            records.forEach { record ->
                val directory = LiveFixturePaths.directoryName(record.device.model, record.cube, record.light)
                File(this, "$directory/${LiveFixturePaths.fileName(record.labeledSample.sample.face)}").apply {
                    parentFile.mkdirs()
                    writeText(LiveFixtureJson.encode(record))
                }
            }
        }

    @Test fun `main writes group device and total scores preserving surrounding bytes`() {
        val doc = document()
        main(arrayOf(fixtures(matrix().flatMap { it.records }).path, doc.path))
        val output = doc.readText()
        assertTrue(output.startsWith("before\r\n${LiveBaselineMarkdown.START}"))
        assertTrue(output.endsWith("${LiveBaselineMarkdown.END}\r\nafter"))
        listOf(
            "Group: $GROUP",
            "Device: maker model",
            "Device: maker other",
            "Total",
            "216/216",
            "24/24",
            "Gate: **MET**",
            "confidently wrong",
            "Coverage: 4 groups, 2 devices, 2 cubes",
        ).forEach {
            assertTrue(it in output, output)
        }
    }

    @Test fun `multiple groups aggregate by device while incomplete group blocks gate`() {
        val result = LiveBaselineEvaluator.evaluate(listOf(FixtureGroup("one", complete()), FixtureGroup("two", complete().dropLast(1))))
        assertEquals(99, result.devices.single().stickers)
        assertEquals(99, result.total.correct)
        assertEquals(BaselineGate.INCOMPLETE, result.gate)
    }

    @Test fun `no fixtures and malformed JSON leave document untouched and name path`() {
        val doc = document()
        val original = doc.readBytes()
        val root = File(temporary, "absent")
        val missing = assertThrows(IllegalArgumentException::class.java) { main(arrayOf(root.path, doc.path)) }
        assertTrue(root.path in missing.message.orEmpty())
        assertArrayEquals(original, doc.readBytes())
        root.mkdirs()
        assertThrows(IllegalArgumentException::class.java) { main(arrayOf(root.path, doc.path)) }
        val bad =
            File(root, "$GROUP/U.json").apply {
                parentFile.mkdirs()
                writeText("{bad")
            }
        val malformed = assertThrows(IllegalArgumentException::class.java) { main(arrayOf(root.path, doc.path)) }
        assertTrue(bad.path in malformed.message.orEmpty())
        assertArrayEquals(original, doc.readBytes())
    }

    @Test fun `rendered rows carry sticker and face percentages`() {
        val section = LiveBaselineMarkdown.render(LiveBaselineEvaluator.evaluate(misreadMatrix()))
        assertTrue("| Total | 208/216 | 96.2963% | 208/216 | 96.2963% | 20/24 | 83.3333% | 8 | 0 | see groups |" in section, section)
        assertTrue("| Group: model_cube | 52/54 | 96.2963% | 52/54 | 96.2963% | 5/6 | 83.3333% | 2 | 0 | — |" in section, section)
        assertTrue("Entered-only accuracy" in section, section)
        assertTrue("Gate: **MISSED**" in section, section)
    }

    @Test fun `empty capture directory counts as six missing faces`() {
        val root = fixtures()
        File(root, "other_cube_indoor").mkdirs()
        val result = LiveBaselineEvaluator.evaluate(LiveFixtureLoader.load(root))
        assertEquals(BaselineGate.INCOMPLETE, result.gate)
        assertEquals(Face.entries, result.groups.single { it.name == "other_cube_indoor" }.missing)
    }

    @Test fun `record in another device cube light directory is rejected`() {
        val root = fixtures()
        val stray = File(root, "$GROUP/U.json").copyTo(File(root, "other_cube_indoor/U.json"))
        val failure = assertThrows(IllegalArgumentException::class.java) { LiveFixtureLoader.load(root) }
        assertTrue(stray.path in failure.message.orEmpty(), failure.message)
    }

    @Test fun `missing or reversed markers fail without writing`() {
        val root = fixtures()
        listOf("no markers", LiveBaselineMarkdown.END + LiveBaselineMarkdown.START).forEach { text ->
            val doc = document().apply { writeText(text) }
            val failure = assertThrows(IllegalArgumentException::class.java) { main(arrayOf(root.path, doc.path)) }
            assertTrue(LiveBaselineMarkdown.START in failure.message.orEmpty())
            assertTrue(LiveBaselineMarkdown.END in failure.message.orEmpty())
            assertEquals(text, doc.readText())
        }
    }

    @Test fun `entered-only accuracy excludes prefilled stickers`() {
        // 54 stickers, 20 prefilled (first four faces: five each), one entered sticker misread.
        val records =
            complete().mapIndexed { faceIndex, record ->
                val sources = List(9) { if (faceIndex < 4 && it < 5) TruthSource.PREFILLED else TruthSource.ENTERED }
                val truth = if (faceIndex == 5) listOf(Color.RED) + List(8) { Color.WHITE } else record.labeledSample.truth
                record.copy(truthSources = sources, labeledSample = record.labeledSample.copy(truth = truth))
            }
        val total = evaluate(records).total
        assertEquals(53, total.correct)
        assertEquals(54, total.stickers)
        assertEquals(33, total.enteredCorrect)
        assertEquals(34, total.enteredStickers)
        val section = LiveBaselineMarkdown.render(evaluate(records))
        assertTrue("| Total | 53/54 | 98.1481% | 33/34 | 97.0588% |" in section, section)
    }

    @Test fun `no entered stickers render a dash, not zero percent`() {
        val records = complete().map { it.copy(truthSources = List(9) { TruthSource.PREFILLED }) }
        val section = LiveBaselineMarkdown.render(evaluate(records))
        assertTrue("| Total | 54/54 | 100.0000% | 0/0 | — |" in section, section)
    }

    @Test fun `schema 1 records count every sticker as entered`() {
        val legacy = complete().map { it.copy(schemaVersion = 1, truthSources = emptyList()) }
        val root = fixtures(legacy)
        val total = LiveBaselineEvaluator.evaluate(LiveFixtureLoader.load(root)).total
        assertEquals(54, total.enteredStickers)
        assertEquals(54, total.enteredCorrect)
    }

    @Test fun `schema 2 record with eight sources fails naming the file`() {
        val root = fixtures(complete().map { it.copy(truthSources = List(8) { TruthSource.ENTERED }) })
        val failure = assertThrows(IllegalArgumentException::class.java) { LiveFixtureLoader.load(root) }
        assertTrue(File(root, "$GROUP/B.json").path in failure.message.orEmpty(), failure.message)
    }
}
