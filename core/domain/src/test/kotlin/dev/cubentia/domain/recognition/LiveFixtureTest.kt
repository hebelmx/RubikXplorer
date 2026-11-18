package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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

    private val record =
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
            truthSources = List(9) { if (it < 3) TruthSource.PREFILLED else TruthSource.ENTERED },
            frame = "U.jpg",
        )

    @Test
    fun `schema 2 record round trips with sources and frame`() {
        val encoded = LiveFixtureJson.encode(record)
        assertTrue("\"schemaVersion\": 2" in encoded, encoded)
        assertTrue("\"prefilled\"" in encoded && "\"entered\"" in encoded, encoded)
        assertTrue("\"frame\": \"U.jpg\"" in encoded, encoded)
        val decoded = LiveFixtureJson.decode(encoded)
        assertEquals(record, decoded)
        assertEquals(listOf(false, false, false) + List(6) { true }, (0..8).map(decoded::entered))
        assertEquals("U.jpg", LiveFixturePaths.frameName(Face.U))
    }

    @Test
    fun `schema 1 record decodes with every sticker entered`() {
        val legacy =
            LiveFixtureJson
                .encode(record.copy(schemaVersion = 1, truthSources = emptyList(), frame = null))
                .replace(Regex(",\\s*\"truthSources\": \\[\\s*]"), "")
                .replace(Regex(",\\s*\"frame\": null"), "")
        assertTrue("truthSources" !in legacy && "frame" !in legacy, legacy)
        val decoded = LiveFixtureJson.decode(legacy)
        assertEquals(1, decoded.schemaVersion)
        assertEquals(emptyList<TruthSource>(), decoded.truthSources)
        assertTrue((0..8).all(decoded::entered))
    }

    @Test
    fun `schema 2 needs nine sources and unknown versions are rejected`() {
        listOf(
            record.copy(truthSources = List(8) { TruthSource.ENTERED }),
            record.copy(truthSources = emptyList()),
            record.copy(schemaVersion = 1),
            record.copy(schemaVersion = 1, frame = null),
            record.copy(schemaVersion = 1, truthSources = emptyList()),
            record.copy(frame = "../x.jpg"),
            record.copy(schemaVersion = 3),
            record.copy(schemaVersion = 0),
        ).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { LiveFixtureJson.decode(LiveFixtureJson.encode(bad)) }
        }
        val eight =
            assertThrows(IllegalArgumentException::class.java) {
                LiveFixtureJson.decode(LiveFixtureJson.encode(record.copy(truthSources = List(8) { TruthSource.ENTERED })))
            }
        assertTrue("found 8" in eight.message.orEmpty(), eight.message)
    }

    @Test
    fun `schema 3 requires the three posed faces and nine truth sources each`() {
        val corner =
            CornerFixtureRecord(
                device = record.device,
                cube = record.cube,
                light = record.light,
                capturedAt = record.capturedAt,
                calibration = record.calibration,
                hold = 1,
                labeledSamples =
                    QuickScanPlan.holds.first().map { step ->
                        LabeledFaceSample(
                            FaceSample(
                                face = step.face,
                                orientationDegrees = 0,
                                stickers = List(9) { record.calibration.centers.getValue(step.centerColor) },
                                quality = CaptureQuality.ACCEPTABLE,
                                centerColor = step.centerColor,
                                upNeighborColor = step.upNeighborColor,
                            ),
                            List(9) { step.centerColor },
                        )
                    },
                truthSources = List(3) { record.truthSources },
            )
        assertEquals(corner, CornerFixtureJson.decode(CornerFixtureJson.encode(corner)))
        assertEquals("corner-2.json", CornerFixturePaths.fileName(2))
        assertEquals("model_cube_indoor", CornerFixturePaths.directoryName("MODEL", "cube", "indoor"))
        listOf(
            corner.copy(schemaVersion = 2),
            corner.copy(hold = 3),
            corner.copy(frame = "../corner-1.jpg"),
            corner.copy(labeledSamples = corner.labeledSamples.reversed()),
            corner.copy(labeledSamples = corner.labeledSamples.dropLast(1)),
            corner.copy(truthSources = List(3) { emptyList() }),
        ).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { CornerFixtureJson.decode(CornerFixtureJson.encode(bad)) }
        }
    }
}
