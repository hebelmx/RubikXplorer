package dev.cubentia.app.camera.recorder

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.ClassificationResult
import dev.cubentia.domain.recognition.DeviceInfo
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.LiveFixtureJson
import dev.cubentia.domain.recognition.NearestCenterClassifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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

    private fun capture(truth: List<Color>) =
        fixtureRecord(device, " GAN stickerless ", " Normal indoor ", Instant.parse("2026-11-20T10:00:00Z"), calibration, sample, truth)

    @Test
    fun `capture saves the operator truth, not the prediction, under the device cube light directory`() {
        val truth = List(9) { if (it == 4) Color.WHITE else Color.RED }
        val prediction = (NearestCenterClassifier.predict(sample, calibration) as ClassificationResult.Classified).value.colors
        assertNotEquals(prediction, truth)

        val file = writeFixture(root, capture(truth))

        assertEquals(File(root, "Fixtures/live/tb128fu_gan-stickerless_normal-indoor/F.json"), file)
        val saved = LiveFixtureJson.decode(file.readText())
        assertEquals(truth, saved.labeledSample.truth)
        assertEquals(sample, saved.labeledSample.sample)
        assertEquals(calibration, saved.calibration)
        assertEquals(device, saved.device)
        assertEquals("GAN stickerless", saved.cube)
        assertEquals("Normal indoor", saved.light)
        assertEquals("2026-11-20T10:00:00Z", saved.capturedAt)
    }

    @Test
    fun `recapture replaces the face file`() {
        writeFixture(root, capture(List(9) { Color.RED }))
        val file = writeFixture(root, capture(List(9) { Color.WHITE }))
        assertEquals(List(9) { Color.WHITE }, LiveFixtureJson.decode(file.readText()).labeledSample.truth)
        assertEquals(listOf("F.json"), file.parentFile.list()!!.toList())
    }
}
