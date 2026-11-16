package dev.cubentia.domain.recognition.evaluation.scan

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.recognition.LiveFixtureJson
import dev.cubentia.domain.recognition.ScanAssembler
import dev.cubentia.domain.recognition.ScanResult
import dev.cubentia.domain.recognition.SelfCalibration
import dev.cubentia.domain.recognition.StickerConfidence
import dev.cubentia.domain.recognition.evaluation.LiveFixtureLoader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ScanAccuracyTest {
    private val fixtures = FIXTURES
    private val report: ScanAccuracyReport get() = REPORT

    @Test
    fun `every committed record decodes unchanged with the new sample fields null`() {
        val files =
            fixtures
                .walkTopDown()
                .filter { it.isFile && it.extension == "json" }
                .sortedBy { it.path }
                .toList()
        assertEquals(72, files.size)
        files.forEach { file ->
            val record = LiveFixtureJson.decode(file.readText())
            val sample = record.labeledSample.sample
            assertEquals(1, sample.schemaVersion)
            assertNull(sample.centerColor)
            assertNull(sample.upNeighborColor)
            assertNull(sample.centerPlain)
            val encoded = LiveFixtureJson.encode(record)
            assertEquals(record, LiveFixtureJson.decode(encoded), file.path)
            // Same shape as the committed file, plus only the three defaulted sample fields.
            assertEquals(
                // Schema-1 files predate truthSources/frame, which encodeDefaults already wrote before this story.
                shape(Json.parseToJsonElement(file.readText())) + NEW_FIELDS +
                    if (record.schemaVersion == 1) SCHEMA_2_FIELDS else emptySet(),
                shape(Json.parseToJsonElement(encoded)),
                file.path,
            )
        }
    }

    private fun shape(
        element: JsonElement,
        path: String = "",
    ): Set<String> =
        when (element) {
            is JsonObject -> element.entries.flatMap { (key, value) -> shape(value, "$path/$key") + "$path/$key" }.toSet()
            is JsonArray -> element.flatMap { shape(it, "$path[]") }.toSet()
            else -> emptySet()
        }

    @Test
    fun `report covers the committed fixtures`() {
        assertEquals(12, report.groups.size)
        assertEquals(648, report.groups.sumOf { it.stickers })
        assertEquals(48, report.groups.sumOf { it.framedFaces })
        assertEquals(11, report.groups.count { it.pose.status == Pose.Status.UNIQUE })
        assertEquals(
            listOf("sm-s721u1_stkb_studio-a"),
            report.groups.filter { it.pose.status == Pose.Status.NOT_A_CUBE }.map { it.name },
        )
        assertEquals(12, report.runs(Input.CENTER_PIXEL).size)
        assertEquals(8, report.runs(Input.KOTLIN_READER).size)
        assertEquals(648, report.runs(Input.CENTER_PIXEL).sumOf { it.stickers.size })
        assertEquals(432, report.runs(Input.KOTLIN_READER).sumOf { it.stickers.size })
    }

    @Test
    fun `every complete fixture group assembles to Full in URFDLB order`() {
        report.runs.forEach { run ->
            val full = run.result as ScanResult.Full
            Face.entries.forEach { face -> assertEquals(Color.home(face), full.facelets.colors[face.ordinal * 9 + 4], run.group) }
            assertEquals(54, full.confidence.size)
        }
    }

    @Test
    fun `rendered report names every device and is stable`() {
        val rendered = ScanAccuracyMarkdown.render(report)
        assertEquals(rendered, ScanAccuracyMarkdown.render(report))
        assertTrue("| Galaxy Tab S7 FE (`SM-T738U`) | — | no fixtures |" in rendered, rendered)
        assertTrue("| Lenovo Tab M10 Plus 3rd Gen (`Lenovo TB128XU`) | Kotlin reader |" in rendered, rendered)
        assertTrue("| Galaxy S24 FE (`SM-S721U1`) | Center pixel |" in rendered, rendered)
        assertTrue("| **Total** | Kotlin reader |" in rendered, rendered)
        assertTrue("| lenovo-tb128xu_stka_studio | Session A | valid cube | Kotlin reader | — | no frames |" in rendered, rendered)
        assertTrue("### Logo-center guard" in rendered && "### Misreads" in rendered, rendered)
    }

    @Test
    fun `truth pose recovers the per-face turns of a scrambled cube`() {
        val facelets =
            CubeState.SOLVED
                .apply(
                    FaceTurn(Face.R, Turn.CLOCKWISE),
                    FaceTurn(Face.U, Turn.HALF),
                    FaceTurn(Face.F, Turn.COUNTERCLOCKWISE),
                    FaceTurn(Face.L, Turn.CLOCKWISE),
                ).toFacelets()
        val turns = Face.entries.associateWith { (it.ordinal * 3 + 1) % 4 }
        // Faces as captured: the net turned back counter-clockwise by each slot's turns.
        val captured =
            Face.entries.associateWith {
                TruthPose.rotateClockwise(
                    facelets.colors.subList(it.ordinal * 9, it.ordinal * 9 + 9),
                    4 - turns.getValue(it),
                )
            }
        val pose = TruthPose.recover(captured)
        assertEquals(Pose(turns, Pose.Status.UNIQUE), pose)
        assertEquals(facelets, TruthPose.assemble(captured, pose.turns))
        assertEquals(Color.home(Pose.NET_NEIGHBORS.getValue(Face.F)[turns.getValue(Face.F)]), pose.upNeighbor(Face.F))
        assertEquals(Pose.Status.NOT_A_CUBE, TruthPose.recover(captured - Face.U).status)
    }

    @Test
    fun `sRGB conversion matches the recorder formula end points`() {
        val white = FrameReader.lab(0xffffff)
        assertEquals(100.0, white.l, 0.01)
        assertEquals(0.0, white.a, 0.01)
        assertEquals(0.0, white.b, 0.01)
        assertEquals(0.0, FrameReader.lab(0x000000).l, 1e-9)
        val red = FrameReader.lab(0xff0000)
        assertEquals(53.24, red.l, 0.05)
        assertEquals(80.09, red.a, 0.1)
        assertEquals(67.20, red.b, 0.1)
    }

    @Test
    fun `missing marker fails naming the file and leaves the document unchanged`(
        @TempDir dir: File,
    ) {
        val document = File(dir, "scan-accuracy.md").apply { writeText("# Report\n${ScanAccuracyMarkdown.START}\nold\n") }
        val failure = assertThrows(IllegalArgumentException::class.java) { ScanAccuracyRunner.run(fixtures, document) }
        assertTrue(document.path in failure.message.orEmpty(), failure.message)
        assertEquals("# Report\n${ScanAccuracyMarkdown.START}\nold\n", document.readText())
        assertThrows(IllegalArgumentException::class.java) { ScanAccuracyRunner.run(fixtures, File(dir, "absent.md")) }
    }

    @Test
    fun `replace rewrites only the span`() {
        val document = "intro\n${ScanAccuracyMarkdown.START}\nold\n${ScanAccuracyMarkdown.END}\noutro\n"
        assertEquals(
            "intro\n${ScanAccuracyMarkdown.START}\nnew\n${ScanAccuracyMarkdown.END}\noutro\n",
            ScanAccuracyMarkdown.replace(document, "new\n\n", "doc.md"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ScanAccuracyMarkdown.replace(document + ScanAccuracyMarkdown.END, "new", "doc.md")
        }
    }

    @Test
    fun `runner regenerates the span from the fixtures`(
        @TempDir dir: File,
    ) {
        val document =
            File(
                dir,
                "scan-accuracy.md",
            ).apply { writeText("# Report\n${ScanAccuracyMarkdown.START}\n${ScanAccuracyMarkdown.END}\n") }
        ScanAccuracyRunner.run(fixtures, document)
        // The runner writes exactly the render of the shared report (rendering itself is pinned stable above).
        assertEquals(
            ScanAccuracyMarkdown.replace(
                "# Report\n${ScanAccuracyMarkdown.START}\n${ScanAccuracyMarkdown.END}\n",
                ScanAccuracyMarkdown.render(report),
                "doc",
            ),
            document.readText(),
        )
    }

    @Test
    fun `scored totals are pinned`() {
        val kotlin = ScanMetrics.of(report.runs(Input.KOTLIN_READER))
        assertEquals(listOf(432, 432, 0), listOf(kotlin.stickers, kotlin.correct, kotlin.confidentlyWrong))
        assertEquals(listOf(8, 8, 8, 7), listOf(kotlin.scans, kotlin.full, kotlin.allCorrect, kotlin.fullValid))
        val pixel = ScanMetrics.of(report.runs(Input.CENTER_PIXEL))
        assertEquals(listOf(648, 646, 2), listOf(pixel.stickers, pixel.correct, pixel.confidentlyWrong))
        val guarded =
            report.runs(Input.KOTLIN_READER).filter { (it.calibration as SelfCalibration.Calibrated).guarded.isNotEmpty() }.map { it.group }
        assertEquals(listOf("lenovo-tb128xu_stka_studio-a", "lenovo-tb128xu_stla_studio-a", "sm-s721u1_stla_studio-a"), guarded)
        assertTrue("for 432 stickers" in ScanAccuracyMarkdown.render(report))
    }

    @Test
    fun `every prompted-pose-valid scan yields the same Facelets in any rotation`() {
        assertEquals(report.runs.size, report.anyRotation.size)
        report.anyRotation.forEach { run ->
            assertTrue(run.samples.all { it.upNeighborColor == null }, run.prompted.group)
            if (run.prompted.valid) {
                assertTrue(run.sameFacelets, "${run.prompted.group} ${run.prompted.input}")
                assertEquals(emptyList<Face>(), run.reprompt, "${run.prompted.group} ${run.prompted.input}")
            }
        }
        // The seeded turns really turn the faces.
        assertTrue(report.anyRotation.any { run -> run.turns.values.any { it != 0 } })
    }

    @Test
    fun `any-rotation re-prompt counts are pinned and rendered per device`() {
        val kotlin = report.anyRotation(Input.KOTLIN_READER)
        val pixel = report.anyRotation(Input.CENTER_PIXEL)
        assertEquals(
            listOf(8, 7, 7, 1),
            listOf(
                kotlin.size,
                kotlin.count {
                    it.prompted.valid
                },
                kotlin.count { it.sameFacelets },
                kotlin.count { it.needsReprompt },
            ),
        )
        assertEquals(
            listOf(12, 10, 11, 2),
            listOf(
                pixel.size,
                pixel.count {
                    it.prompted.valid
                },
                pixel.count { it.sameFacelets },
                pixel.count { it.needsReprompt },
            ),
        )
        val rendered = ScanAccuracyMarkdown.render(report)
        assertTrue("### Any rotation (Story 10.14)" in rendered, rendered)
        assertTrue("| **Total** | Kotlin reader | 8 | 7 | 7 | 1 | 12.50% |" in rendered, rendered)
        assertTrue("| Galaxy S24 FE (`SM-S721U1`) | Center pixel | 6 | 4 | 5 | 2 | 33.33% |" in rendered, rendered)
    }

    @Test
    fun `one six-face assembly in any rotation takes under 50 ms`() {
        // A resolving scan, and the slow path: a scan with no valid state (all 4,096 kept as best-candidate bookkeeping).
        val resolving = report.anyRotation.first { it.prompted.valid }
        val unresolved = report.anyRotation.first { !it.prompted.valid }
        listOf(resolving, unresolved).forEach { run ->
            val calibration = (SelfCalibration.from(run.samples) as SelfCalibration.Calibrated).calibration
            repeat(WARM_UP) { ScanAssembler.assemble(run.samples, calibration) }
            val times =
                List(TIMED) {
                    val start = System.nanoTime()
                    ScanAssembler.assemble(run.samples, calibration)
                    (System.nanoTime() - start) / NANOS_PER_MS
                }.sorted()
            val median = times[TIMED / 2]
            assertTrue(median < 50.0, "${run.prompted.group}: median $median ms")
        }
    }

    @Test
    fun `metrics count confidently wrong and need stickers to meet the target`() {
        fun sticker(
            read: Color,
            confidence: StickerConfidence,
        ) = ScoredSticker(Face.U, 0, Color.WHITE, read, confidence)
        val stickers =
            listOf(
                sticker(Color.WHITE, StickerConfidence.CONFIDENT),
                sticker(Color.BLUE, StickerConfidence.CONFIDENT),
                sticker(Color.BLUE, StickerConfidence.UNCERTAIN),
            )
        val run = REPORT.runs.first().copy(stickers = stickers)
        val metrics = ScanMetrics.of(listOf(run))
        assertEquals(listOf(3, 1, 1, 1), listOf(metrics.stickers, metrics.correct, metrics.confidentlyWrong, metrics.uncertain))
        assertFalse(metrics.meetsTarget)
        val clean = ScanMetrics.of(listOf(run.copy(stickers = stickers.take(1))))
        assertEquals(0, clean.confidentlyWrong)
        assertTrue(clean.meetsTarget)
        val empty = ScanMetrics.of(emptyList())
        assertEquals(0, empty.stickers)
        assertFalse(empty.meetsTarget)
    }

    private companion object {
        const val WARM_UP = 20
        const val TIMED = 11
        const val NANOS_PER_MS = 1_000_000.0
        val FIXTURES = File("../../Fixtures/live")

        /** Built once for the class: decoding 48 frames and searching 12 poses is the slow part. */
        val REPORT: ScanAccuracyReport by lazy {
            ScanAccuracy.build(LiveFixtureLoader.load(FIXTURES)) { group, record ->
                record.frame?.let { FrameReader.read(File(File(FIXTURES, group.name), it)) }
            }
        }
        val SCHEMA_2_FIELDS = setOf("/truthSources", "/frame")
        val NEW_FIELDS = listOf("centerColor", "upNeighborColor", "centerPlain").map { "/labeledSample/sample/$it" }.toSet()
    }
}
