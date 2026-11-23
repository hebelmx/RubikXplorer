package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.RecognitionFixtures.CALIBRATION
import dev.cubentia.domain.recognition.RecognitionFixtures.LABS
import dev.cubentia.domain.recognition.RecognitionFixtures.sample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ColorCalibrationTest {
    @Test fun `swatches require explicit keep in plan order and retake preserves plain prompt`() {
        var session = CalibrationSession()
        assertSame(session, session.keep())
        assertNull(session.calibration)
        GuidedScanPlan.steps.forEach { step ->
            assertEquals(step.centerColor, session.current)
            val rejected = session.offer(null)
            assertTrue(rejected.plainSticker)
            assertNull(rejected.pending)
            val uneven = rejected.offer(CellReading(LABS.getValue(step.centerColor), false))
            assertTrue(uneven.plainSticker)
            assertNull(uneven.pending)
            val offered = uneven.offer(CellReading(LABS.getValue(step.centerColor), true))
            assertNotNull(offered.pending)
            assertTrue(offered.plainSticker)
            assertEquals(session.kept, offered.kept)
            val retaken = offered.retake()
            assertNull(retaken.pending)
            assertTrue(retaken.plainSticker)
            session = retaken.offer(CellReading(LABS.getValue(step.centerColor), true)).keep()
            assertFalse(session.plainSticker)
        }
        assertNull(session.current)
        assertEquals(CALIBRATION, session.calibration)
        assertSame(session, session.keep())
        assertSame(session, session.offer(null))
        assertTrue(ColorPlausibility.valid(session.calibration!!))
    }

    @Test fun `bad white logo and nearby kept red are refused`() {
        var session = CalibrationSession()
        repeat(4) { session = session.offer(CellReading(LABS.getValue(session.current!!), true)).keep() }
        assertEquals(Color.WHITE, session.current)
        listOf(Lab(76.0, 20.0, 71.0), RecognitionFixtures.LOGO, Lab(Double.NaN, 0.0, 0.0)).forEach {
            assertNull(session.offer(CellReading(it, true)).pending)
        }
        assertNotNull(session.offer(CellReading(Lab(88.0, -6.0, -16.0), true)).pending)
        var duplicate = CalibrationSession()
        duplicate = duplicate.offer(CellReading(LABS.getValue(Color.GREEN), true)).keep()
        val red = Lab(50.0, 40.0, 40.0)
        duplicate = duplicate.offer(CellReading(red, true)).keep()
        duplicate = duplicate.offer(CellReading(LABS.getValue(Color.BLUE), true)).keep()
        assertTrue(ColorPlausibility.plausible(Color.ORANGE, red))
        assertNull(duplicate.offer(CellReading(red, true)).pending)
    }

    @Test fun `plausibility boundaries and invalid centers`() {
        LABS.forEach { (color, lab) -> assertTrue(ColorPlausibility.plausible(color, lab)) }
        assertTrue(ColorPlausibility.plausible(Color.RED, Lab(50.0, 40.0, -5.0)))
        Color.entries.forEach { assertFalse(ColorPlausibility.plausible(it, Lab(-1.0, 0.0, 0.0))) }
        assertFalse(ColorPlausibility.valid(CalibrationCenters(emptyMap())))
        assertFalse(ColorPlausibility.valid(CalibrationCenters(LABS + (Color.WHITE to Lab(20.0, 0.0, 0.0)))))
        assertFalse(ColorPlausibility.valid(CalibrationCenters(LABS + (Color.ORANGE to LABS.getValue(Color.RED)))))
    }

    private fun solved(logos: Set<Color> = emptySet()) =
        Face.entries.map { face ->
            val color = Color.home(face)
            sample(color, plain = color !in logos, face = face)
        }

    @Test fun `offer covers missing many blanks and first guarded scan and saved colors fill guarded centers`() {
        val plain = solved()
        assertFalse(CalibrationOffer.offered(plain, null, ScanReview.fromSamples(plain)))
        val guarded = solved(setOf(Color.WHITE))
        assertTrue(CalibrationOffer.offered(guarded, null, ScanReview.fromSamples(guarded)))
        assertFalse(CalibrationOffer.offered(guarded, CALIBRATION, ScanReview.fromSamples(guarded, CALIBRATION)))
        val two = solved(setOf(Color.WHITE, Color.RED))
        assertTrue(CalibrationOffer.offered(two, null, ScanReview.fromSamples(two)))
        assertTrue(ScanReview.fromSamples(two).blanks.isNotEmpty())
        assertTrue(ScanReview.fromSamples(two, CALIBRATION).blanks.isEmpty())
        assertEquals(emptyList<Face>(), GuidedScanController.defaultReprompt(two, CALIBRATION))
        val five = ScanReview(List(54) { if (it in listOf(0, 1, 2, 3, 5)) null else Color.home(Face.entries[it / 9]) }, 0)
        val six = ScanReview(five.stickers.toMutableList().also { it[6] = null }, 0)
        assertFalse(CalibrationOffer.offered(plain, CALIBRATION, five))
        assertTrue(CalibrationOffer.offered(plain, CALIBRATION, six))
    }

    @Test fun `every live non-center sticker is plausible for its truth color`() {
        val files = File("../../Fixtures/live").walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        assertTrue(files.isNotEmpty())
        files.forEach { file ->
            val labeled = LiveFixtureJson.decode(file.readText()).labeledSample
            labeled.truth.forEachIndexed { index, color ->
                if (index !=
                    4
                ) {
                    assertTrue(
                        ColorPlausibility.plausible(color, labeled.sample.stickers[index]),
                        "${file.path}: $index $color ${labeled.sample.stickers[index]}",
                    )
                }
            }
        }
    }

    /**
     * Story 10.20 (deferred pastel-cube check): a corner group's recorder calibration (the manifest's, else its records')
     * must pass [ColorPlausibility.valid], and every labeled non-centre sticker must be [ColorPlausibility.plausible] for
     * its truth colour. Returns the problems, each naming the group.
     */
    private fun pastelProblems(group: File): List<String> =
        buildList {
            val records =
                group
                    .listFiles()
                    .orEmpty()
                    .filter { it.name.matches(Regex("""corner-\d+\.json""")) }
                    .sortedBy { it.name }
                    .map { CornerFixtureJson.decode(it.readText()) }
            val manifest =
                File(group, CornerFixturePaths.MANIFEST).takeIf { it.isFile }?.let { CornerFixtureJson.decodeManifest(it.readText()) }
            val calibration = manifest?.calibration ?: records.firstOrNull()?.calibration
            if (calibration == null ||
                !ColorPlausibility.valid(calibration)
            ) {
                add("${group.name}: the recorder calibration is not plausible: $calibration")
            }
            records.forEach { record ->
                record.labeledSamples.forEach { labeled ->
                    labeled.truth.forEachIndexed { index, color ->
                        val lab = labeled.sample.stickers[index]
                        if (index != FaceSample.CENTER_INDEX && !ColorPlausibility.plausible(color, lab)) {
                            add("${group.name} hold ${record.hold} ${labeled.sample.face}[$index]: $color is not plausible for $lab")
                        }
                    }
                }
            }
        }

    @Test fun `every pastel corner group's calibration and stickers are plausible`() {
        val root = File("../../Fixtures/corner")
        val index = File(root, CornerFixturePaths.GROUP_INDEX)
        if (!index.isFile) {
            println("ColorCalibrationTest: no group index (${index.path}); no pastel corner group to check yet")
            return
        }
        val pastel = CornerFixtureJson.decodeIndex(index.readText()).groups.filter { it.pastel }
        if (pastel.isEmpty()) println("ColorCalibrationTest: the group index marks no pastel group")
        val problems = pastel.flatMap { pastelProblems(File(root, it.group)) }
        val unexpected = problems.filterNot { problem -> KNOWN_PASTEL_PROBLEMS.any { problem.startsWith(it) } }
        assertTrue(unexpected.isEmpty(), unexpected.joinToString("\n"))
        // Strict: once Story 10.27 fixes a known problem, this fails until the entry is removed.
        val fixed = KNOWN_PASTEL_PROBLEMS.filterNot { known -> problems.any { it.startsWith(known) } }
        assertTrue(fixed.isEmpty(), "known pastel problems no longer reported; remove them from KNOWN_PASTEL_PROBLEMS: $fixed")
    }

    private companion object {
        /**
         * Expected failures of the fixed-window pastel check, tracked by Story 10.27 (clean the corner fixture labels and
         * judge pastel stickers against the cube's own colours, SCP 2026-11-23). Story 10.27 removes these entries.
         */
        val KNOWN_PASTEL_PROBLEMS = listOf("lenovo-tb128xu_stlb_bar-warm hold 2 L[8]: BLUE is not plausible")
    }

    @Test fun `the pastel check passes a plausible group and names an implausible sticker or calibration`(
        @TempDir root: File,
    ) {
        val group = File(root, "sm-s721u1_stlp_warm").also { it.mkdirs() }
        val plan = QuickScanPlan.holds.first()

        fun record(stickers: (Color) -> List<Lab>) =
            CornerFixtureRecord(
                device = DeviceInfo("SM-S721U1", "samsung", "15", "b", 35),
                cube = "stlp",
                light = "warm",
                capturedAt = "2026-11-23T10:00:00Z",
                calibration = CALIBRATION,
                hold = 1,
                labeledSamples =
                    plan.map { step ->
                        LabeledFaceSample(
                            FaceSample(
                                face = step.face,
                                orientationDegrees = 0,
                                stickers = stickers(step.centerColor),
                                quality = CaptureQuality.ACCEPTABLE,
                                centerColor = step.centerColor,
                                upNeighborColor = step.upNeighborColor,
                            ),
                            List(9) { step.centerColor },
                        )
                    },
                truthSources = List(3) { List(9) { TruthSource.ENTERED } },
                lightDirection = LightDirection.FRONT,
            )
        val file = File(group, CornerFixturePaths.fileName(1))
        file.writeText(CornerFixtureJson.encode(record { color -> List(9) { LABS.getValue(color) } }))
        assertEquals(emptyList<String>(), pastelProblems(group))
        // A washed-out (pastel) sticker read as gray is not plausible for its colour.
        file.writeText(CornerFixtureJson.encode(record { color -> List(9) { if (it == 0) Lab(70.0, 2.0, 2.0) else LABS.getValue(color) } }))
        val problems = pastelProblems(group)
        assertEquals(2, problems.size, problems.joinToString("\n"))
        assertTrue(problems.all { it.startsWith("sm-s721u1_stlp_warm hold 1 ") && "[0]" in it }, problems.joinToString("\n"))
        // The manifest's calibration wins over the records'.
        val manifest =
            CornerGroupManifest(
                CornerFixturePaths.PIPELINE_10_22,
                LightDirection.FRONT,
                DeviceInfo("SM-S721U1", "samsung", "15", "b", 35),
                "stlp",
                "warm",
                calibration = CalibrationCenters(LABS + (Color.ORANGE to LABS.getValue(Color.RED))),
            )
        File(group, CornerFixturePaths.MANIFEST).writeText(CornerFixtureJson.encodeManifest(manifest))
        assertTrue(pastelProblems(group).first().startsWith("sm-s721u1_stlp_warm: the recorder calibration is not plausible"))
    }
}
