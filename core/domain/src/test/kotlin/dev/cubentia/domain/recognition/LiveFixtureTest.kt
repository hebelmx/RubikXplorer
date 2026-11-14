package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LiveFixtureTest {
    @Test
    fun `slug is portable and rejects blank parts`() {
        assertEquals(
            "sm-x306b_gan-stickerless_normal-indoor",
            LiveFixturePaths.directoryName("SM-X306B", "GAN Stickerless!", "Normal indoor"),
        )
        assertEquals("a_b_c", LiveFixturePaths.directoryName("--A--", " B ", "C"))
        listOf("", "  ", "!!!").forEach { blank ->
            assertThrows(IllegalArgumentException::class.java) { LiveFixturePaths.directoryName(blank, "cube", "light") }
            assertThrows(IllegalArgumentException::class.java) { LiveFixturePaths.directoryName("device", blank, "light") }
            assertThrows(IllegalArgumentException::class.java) { LiveFixturePaths.directoryName("device", "cube", blank) }
        }
        Face.entries.forEach { assertEquals("${it.name}.json", LiveFixturePaths.fileName(it)) }
    }

    @Test
    fun `record round trips with complete metadata`() {
        val record =
            LiveFixtureRecord(
                device = DeviceInfo("model", "maker", "17", "build", 37),
                cube = "cube",
                light = "indoor",
                capturedAt = "2026-11-14T12:00:00Z",
                calibration =
                    CalibrationCenters(
                        Color.entries.associateWith {
                            Lab(50.0, it.ordinal.toDouble(), 0.0)
                        },
                    ),
                labeledSample =
                    LabeledFaceSample(
                        FaceSample(
                            face = Face.U,
                            orientationDegrees = 0,
                            stickers =
                                List(9) {
                                    Lab(50.0, 0.0, 0.0)
                                },
                            quality = CaptureQuality.ACCEPTABLE,
                        ),
                        List(9) { Color.WHITE },
                    ),
            )
        assertEquals(record, LiveFixtureJson.decode(LiveFixtureJson.encode(record)))
        assertThrows(
            IllegalArgumentException::class.java,
        ) { LiveFixtureJson.decode(LiveFixtureJson.encode(record.copy(schemaVersion = 2))) }
    }
}
