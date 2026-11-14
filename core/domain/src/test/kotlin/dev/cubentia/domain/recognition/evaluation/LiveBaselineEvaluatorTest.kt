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

    private fun record(face: Face) =
        LiveFixtureRecord(
            device = DeviceInfo("model", "maker", "17", "build", 37),
            cube = "cube",
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
        )

    private fun complete() = Face.entries.map(::record)

    private fun evaluate(records: List<LiveFixtureRecord>) = LiveBaselineEvaluator.evaluate(listOf(FixtureGroup("group", records)))

    @Test fun `complete accurate group meets gate`() {
        val result = evaluate(complete())
        assertEquals(BaselineGate.MET, result.gate)
        assertEquals(54, result.total.correct)
        assertEquals(54, result.total.stickers)
        assertEquals(6, result.total.correctFaces)
        assertEquals(0, result.total.confidentlyWrong)
        assertEquals(result.total.correct, result.devices.single().correct)
    }

    @Test fun `two misreads miss gate and count confidently wrong`() {
        val records = complete().toMutableList()
        val first = records[0]
        records[0] = first.copy(labeledSample = first.labeledSample.copy(truth = List(2) { Color.RED } + List(7) { Color.WHITE }))
        val result = evaluate(records)
        assertEquals(BaselineGate.MISSED, result.gate)
        assertEquals(52, result.total.correct)
        assertEquals(54, result.total.stickers)
        assertEquals(5, result.total.correctFaces)
        assertEquals(2, result.total.confidentlyWrong)
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

    private fun fixtures(): File =
        File(temporary, "live").apply {
            complete().forEach { record ->
                File(this, "$GROUP/${LiveFixturePaths.fileName(record.labeledSample.sample.face)}").apply {
                    parentFile.mkdirs()
                    writeText(LiveFixtureJson.encode(record))
                }
            }
        }

    @Test fun `main writes group device and total scores preserving surrounding bytes`() {
        val doc = document()
        main(arrayOf(fixtures().path, doc.path))
        val output = doc.readText()
        assertTrue(output.startsWith("before\r\n${LiveBaselineMarkdown.START}"))
        assertTrue(output.endsWith("${LiveBaselineMarkdown.END}\r\nafter"))
        listOf(
            "Group: $GROUP",
            "Device: maker model",
            "Total",
            "54/54",
            "6/6",
            "MET",
            "confidently wrong",
            "Coverage: 1 groups, 1 devices, 1 cubes",
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
        val records = complete().toMutableList()
        val first = records[0]
        records[0] = first.copy(labeledSample = first.labeledSample.copy(truth = List(2) { Color.RED } + List(7) { Color.WHITE }))
        val section = LiveBaselineMarkdown.render(evaluate(records))
        assertTrue("| Total | 52/54 | 96.2963% | 5/6 | 83.3333% | 2 | 0 | see groups |" in section, section)
        assertTrue("| Group: group | 52/54 | 96.2963% | 5/6 | 83.3333% | 2 | 0 | — |" in section, section)
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
}
