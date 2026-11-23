package dev.cubentia.app.camera.recorder

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.ClassificationResult
import dev.cubentia.domain.recognition.CornerFixtureJson
import dev.cubentia.domain.recognition.CornerFixturePaths
import dev.cubentia.domain.recognition.CornerFixtureRecord
import dev.cubentia.domain.recognition.CornerHoldManifest
import dev.cubentia.domain.recognition.CornerLabel
import dev.cubentia.domain.recognition.CornerSettleDiagnostics
import dev.cubentia.domain.recognition.DeviceInfo
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.LightDirection
import dev.cubentia.domain.recognition.LiveFixtureJson
import dev.cubentia.domain.recognition.NearestCenterClassifier
import dev.cubentia.domain.recognition.SettleOutcome
import dev.cubentia.domain.recognition.TruthSource
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

class FixtureExportTest {
    @TempDir lateinit var root: File

    private val calibration = CalibrationCenters(Color.entries.associateWith { Lab(50.0, it.ordinal * 20.0, 0.0) })
    private val sample =
        FaceSample(
            face = Face.F,
            orientationDegrees = 90,
            stickers = List(9) { calibration.centers.getValue(Color.WHITE) },
            quality = CaptureQuality.ACCEPTABLE,
        )
    private val device = DeviceInfo("TB128FU", "LENOVO", "13", "TB128FU_S300123", 33)

    private val sources = List(9) { if (it < 4) TruthSource.PREFILLED else TruthSource.ENTERED }

    private fun capture(
        truth: List<Color>,
        hasFrame: Boolean = true,
    ) = fixtureRecord(
        device,
        " GAN stickerless ",
        " Normal indoor ",
        Instant.parse("2026-11-20T10:00:00Z"),
        calibration,
        sample,
        truth,
        sources,
        hasFrame,
    )

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)

    @Test
    fun `capture saves the operator truth, not the prediction, under the device cube light directory`() {
        val truth = List(9) { if (it == 4) Color.WHITE else Color.RED }
        val prediction = (NearestCenterClassifier.predict(sample, calibration) as ClassificationResult.Classified).value.colors
        assertNotEquals(prediction, truth)

        val file = writeFixture(root, capture(truth), jpeg)

        assertEquals(File(root, "Fixtures/live/tb128fu_gan-stickerless_normal-indoor/F.json"), file)
        val saved = LiveFixtureJson.decode(file.readText())
        assertEquals(2, saved.schemaVersion)
        assertEquals(sources, saved.truthSources)
        assertEquals("F.jpg", saved.frame)
        assertArrayEquals(jpeg, File(file.parentFile, "F.jpg").readBytes())
        assertEquals(truth, saved.labeledSample.truth)
        assertEquals(sample, saved.labeledSample.sample)
        assertEquals(calibration, saved.calibration)
        assertEquals(device, saved.device)
        assertEquals("GAN stickerless", saved.cube)
        assertEquals("Normal indoor", saved.light)
        assertEquals("2026-11-20T10:00:00Z", saved.capturedAt)
    }

    @Test
    fun `recapture replaces the face file and its frame`() {
        writeFixture(root, capture(List(9) { Color.RED }), byteArrayOf(9, 9))
        val file = writeFixture(root, capture(List(9) { Color.WHITE }), jpeg)
        assertEquals(List(9) { Color.WHITE }, LiveFixtureJson.decode(file.readText()).labeledSample.truth)
        assertEquals(listOf("F.jpg", "F.json"), file.parentFile.list()!!.sorted())
        assertArrayEquals(jpeg, File(file.parentFile, "F.jpg").readBytes())
    }

    @Test
    fun `recapture without a frame removes the stale image`() {
        writeFixture(root, capture(List(9) { Color.RED }), jpeg)
        val file = writeFixture(root, capture(List(9) { Color.RED }, hasFrame = false))
        assertNull(LiveFixtureJson.decode(file.readText()).frame)
        assertFalse(File(file.parentFile, "F.jpg").exists())
    }

    @Test
    fun `frame name and bytes must come together`() {
        assertThrows(IllegalArgumentException::class.java) { writeFixture(root, capture(List(9) { Color.RED }), null) }
        assertThrows(IllegalArgumentException::class.java) { writeFixture(root, capture(List(9) { Color.RED }, hasFrame = false), jpeg) }
        assertThrows(IllegalArgumentException::class.java) {
            fixtureRecord(
                device,
                "c",
                "l",
                Instant.EPOCH,
                calibration,
                sample,
                List(9) { Color.RED },
                List(8) { TruthSource.ENTERED },
                false,
            )
        }
    }

    @Test
    fun `a corner group's manifest and hold records round-trip on disk with their torch and diagnostics`() {
        val group = File(root, "Fixtures/corner/sm-s721u1_stla_warm").also { it.mkdirs() }
        val manifest = groupManifest(device, "stla", "warm", LightDirection.FRONT, torch = true, calibration = calibration)
        ensureGroupManifest(group, manifest)
        // An existing manifest is never replaced when the group folder is opened again.
        ensureGroupManifest(group, manifest.copy(light = "other"))
        assertEquals(manifest, CornerFixtureJson.decodeManifest(File(group, CornerFixturePaths.MANIFEST).readText()))

        val diagnostics =
            holdDiagnostics(
                List(3) { null },
                calibration,
                torch = true,
                settle = CornerSettleDiagnostics(700, SettleOutcome.TIMEOUT, "unsupported", null),
            )
        val record =
            CornerFixtureRecord(
                device = device,
                cube = "stla",
                light = "warm",
                capturedAt = "2026-11-23T10:00:00Z",
                calibration = calibration,
                hold = 1,
                labeledSamples = emptyList(),
                truthSources = emptyList(),
                label = CornerLabel.POSITIVE,
                lightDirection = LightDirection.FRONT,
                keypoints = List(7) { listOf(0.5, 0.1 * (it + 1)) },
                torch = true,
                diagnostics = diagnostics,
            )
        val file = writeCornerHold(group, record, jpeg)
        assertEquals("corner-1.json", file.name)
        assertEquals(record, CornerFixtureJson.decode(file.readText()))
        assertArrayEquals(jpeg, File(group, "corner-1.jpg").readBytes())
        assertEquals("torch", CornerFixtureJson.decode(file.readText()).diagnostics?.lightMode)

        recordHold(group, 1, true)
        val after = recordHold(group, 2, false)
        assertEquals(listOf(CornerHoldManifest(1, true), CornerHoldManifest(2, false)), after.holds)
        assertEquals(after, CornerFixtureJson.decodeManifest(File(group, CornerFixturePaths.MANIFEST).readText()))
        assertEquals(calibration, after.calibration)
        // A group without a manifest cannot take a hold: the caller reports it and keeps the capture.
        assertThrows(java.io.IOException::class.java) { recordHold(File(root, "missing"), 1, null) }
    }
}
