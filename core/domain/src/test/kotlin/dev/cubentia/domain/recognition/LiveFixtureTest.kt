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

    @Test
    fun `schema 4 adds labels, keypoints and light direction and still decodes schema 3`() {
        val legacy =
            CornerFixtureRecord(
                schemaVersion = 3,
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
        assertEquals(legacy, CornerFixtureJson.decode(CornerFixtureJson.encode(legacy)))
        val points = List(7) { listOf(0.1 * (it + 1), 0.5) }
        val current =
            legacy.copy(
                schemaVersion = 4,
                lightDirection = LightDirection.FRONT,
                keypoints = points,
                detectorKeypoints = points,
                torch = true,
            )
        assertEquals(current, CornerFixtureJson.decode(CornerFixtureJson.encode(current)))
        // A positive the detector missed keeps its manual keypoints without color samples; a negative carries neither.
        val missed = current.copy(labeledSamples = emptyList(), truthSources = emptyList(), detectorKeypoints = null)
        assertEquals(missed, CornerFixtureJson.decode(CornerFixtureJson.encode(missed)))
        val negative = missed.copy(label = CornerLabel.NEGATIVE, keypoints = null)
        assertEquals(negative, CornerFixtureJson.decode(CornerFixtureJson.encode(negative)))
        // A sample-less capture may carry no calibration at all.
        val bare = negative.copy(calibration = CalibrationCenters(emptyMap()))
        assertEquals(bare, CornerFixtureJson.decode(CornerFixtureJson.encode(bare)))
        val partial =
            CalibrationCenters(
                record.calibration.centers -
                    record.calibration.centers.keys
                        .first(),
            )
        listOf(
            legacy.copy(keypoints = points),
            legacy.copy(lightDirection = LightDirection.BACK),
            legacy.copy(torch = false),
            current.copy(keypoints = points.dropLast(1)),
            current.copy(keypoints = points.map { listOf(1.5, 0.5) }),
            negative.copy(keypoints = points),
            missed.copy(truthSources = List(3) { record.truthSources }),
            // A sample-less positive needs its manual corners, and a calibration it carries must still be valid.
            missed.copy(keypoints = null),
            missed.copy(calibration = partial),
            negative.copy(calibration = partial),
            current.copy(schemaVersion = 5),
        ).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { CornerFixtureJson.decode(CornerFixtureJson.encode(bad)) }
        }
    }

    @Test
    fun `the group manifest and keypoint fixtures round-trip`() {
        val manifest = CornerGroupManifest(CornerFixturePaths.PIPELINE_10_19, LightDirection.BACK, record.device, "stla", "cool")
        assertEquals(manifest, CornerFixtureJson.decodeManifest(CornerFixtureJson.encodeManifest(manifest)))
        val torchOn = manifest.copy(torch = true)
        assertEquals(torchOn, CornerFixtureJson.decodeManifest(CornerFixtureJson.encodeManifest(torchOn)))
        assertTrue("\"lightDirection\": \"back\"" in CornerFixtureJson.encodeManifest(manifest))
        val points = List(7) { listOf(0.5, 0.1 * (it + 1)) }
        val positive =
            CornerKeypointFixture(
                frame = CornerFixturePaths.keypointFrameName(3),
                label = CornerLabel.POSITIVE,
                keypoints = points,
                device = record.device,
                cube = "stla",
                light = "cool",
                lightDirection = LightDirection.FRONT,
                capturedAt = record.capturedAt,
            )
        assertEquals("0003.png", positive.frame)
        assertEquals("0003.json", CornerFixturePaths.keypointFileName(3))
        val encoded = CornerFixtureJson.encodeKeypoints(positive)
        assertTrue("\"label\": \"positive\"" in encoded && "\"kind\": \"corner-keypoints\"" in encoded, encoded)
        assertEquals(positive, CornerFixtureJson.decodeKeypoints(encoded))
        val negative = positive.copy(label = CornerLabel.NEGATIVE, keypoints = null)
        assertEquals(negative, CornerFixtureJson.decodeKeypoints(CornerFixtureJson.encodeKeypoints(negative)))
        listOf(positive.copy(keypoints = null), negative.copy(keypoints = points), positive.copy(kind = "other")).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { CornerFixtureJson.decodeKeypoints(CornerFixtureJson.encodeKeypoints(bad)) }
        }
    }

    @Test
    fun `story 10-20 manifest, hold diagnostics and side light round-trip, and older files still decode`() {
        val manifest =
            CornerGroupManifest(
                CornerFixturePaths.PIPELINE_10_22,
                LightDirection.SIDE,
                record.device,
                "stla",
                "warm",
                torch = true,
                calibration = record.calibration,
                holds = listOf(CornerHoldManifest(1, true), CornerHoldManifest(2, false)),
            )
        val encoded = CornerFixtureJson.encodeManifest(manifest)
        assertTrue("\"lightDirection\": \"side\"" in encoded && "\"pipeline\": \"10.22\"" in encoded, encoded)
        assertEquals(manifest, CornerFixtureJson.decodeManifest(encoded))
        // A 10.19 manifest written before Story 10.20 has neither calibration nor holds.
        val old =
            """
            {"pipeline": "10.19", "lightDirection": "front", "device": {"model": "m", "manufacturer": "x", "osRelease": "13",
            "osBuild": "b", "sdkInt": 33}, "cube": "stla", "light": "warm", "torch": null}
            """.trimIndent()
        val decoded = CornerFixtureJson.decodeManifest(old)
        assertEquals(null, decoded.calibration)
        assertEquals(emptyList<CornerHoldManifest>(), decoded.holds)
        assertEquals(LightDirection.FRONT, decoded.lightDirection)

        val points = List(7) { listOf(0.1 * (it + 1), 0.5) }
        val diagnostics =
            CornerHoldDiagnostics(
                topLevel = 1.0,
                leftLevel = 0.9,
                rightLevel = null,
                lightScore = null,
                lightPassed = null,
                lightMode = "torch",
                uncalibratedScore = null,
                torch = true,
                settle = CornerSettleDiagnostics(640, SettleOutcome.SETTLED, "on+awb", 120),
            )
        val hold =
            CornerFixtureRecord(
                device = record.device,
                cube = "stla",
                light = "warm",
                capturedAt = record.capturedAt,
                calibration = record.calibration,
                hold = 1,
                labeledSamples = emptyList(),
                truthSources = emptyList(),
                lightDirection = LightDirection.SIDE,
                keypoints = points,
                torch = true,
                diagnostics = diagnostics,
            )
        val holdJson = CornerFixtureJson.encode(hold)
        assertTrue("\"outcome\": \"settled\"" in holdJson && "\"lightMode\": \"torch\"" in holdJson, holdJson)
        assertEquals(hold, CornerFixtureJson.decode(holdJson))
        val timeout =
            hold.copy(
                diagnostics = diagnostics.copy(settle = CornerSettleDiagnostics(1500, SettleOutcome.TIMEOUT, "unsupported")),
            )
        assertEquals(timeout, CornerFixtureJson.decode(CornerFixtureJson.encode(timeout)))
        // GH-330: a hold with no metering records manual-ev and the EV index in use; both round-trip.
        val manualEv =
            hold.copy(
                diagnostics =
                    diagnostics.copy(settle = CornerSettleDiagnostics(null, SettleOutcome.MANUAL_EV, "none"), evIndex = 2),
            )
        val manualJson = CornerFixtureJson.encode(manualEv)
        assertTrue("\"outcome\": \"manual-ev\"" in manualJson && "\"evIndex\": 2" in manualJson, manualJson)
        assertEquals(manualEv, CornerFixtureJson.decode(manualJson))
        // A record written before GH-330 has no evIndex and decodes with none.
        val withoutEv = holdJson.replace(Regex(",\\s*\"evIndex\": null"), "")
        assertTrue("evIndex" !in withoutEv, withoutEv)
        assertEquals(hold, CornerFixtureJson.decode(withoutEv))
        // A schema-4 record without diagnostics (Story 10.19) still decodes, with none.
        val withoutDiagnostics = holdJson.substringBefore(",\n    \"diagnostics\"") + "\n}"
        assertEquals(hold.copy(diagnostics = null), CornerFixtureJson.decode(withoutDiagnostics))
        // Schema 3 carries no Story 10.20 diagnostics.
        assertThrows(IllegalArgumentException::class.java) {
            CornerFixtureJson.decode(
                CornerFixtureJson.encode(hold.copy(schemaVersion = 3, lightDirection = null, keypoints = null, torch = null)),
            )
        }
    }

    @Test
    fun `the group index round-trips and rejects a bad split, kind, schema or a group listed twice`() {
        val index =
            CornerGroupIndex(
                groups =
                    listOf(
                        CornerGroupIndexEntry("sm-s721u1_stla_warm", CornerSplit.HELDOUT),
                        CornerGroupIndexEntry("sm-s721u1_stlp_warm", CornerSplit.TUNING, pastel = true),
                    ),
            )
        val encoded = CornerFixtureJson.encodeIndex(index)
        assertTrue("\"kind\": \"corner-group-index\"" in encoded && "\"split\": \"heldout\"" in encoded, encoded)
        assertEquals(index, CornerFixtureJson.decodeIndex(encoded))
        assertEquals(CornerSplit.TUNING, index.entry("sm-s721u1_stlp_warm")?.split)
        assertEquals(null, index.entry("missing"))
        // The pastel flag is optional.
        val minimal = """{"kind": "corner-group-index", "schemaVersion": 1, "groups": [{"group": "a", "split": "tuning"}]}"""
        assertEquals(CornerGroupIndexEntry("a", CornerSplit.TUNING), CornerFixtureJson.decodeIndex(minimal).groups.single())
        listOf(
            minimal.replace("tuning", "training"),
            minimal.replace("corner-group-index", "corner-keypoints"),
            minimal.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
            minimal.replace("\"a\"", "\" \""),
            """{"kind": "corner-group-index", "schemaVersion": 1, "groups": [{"group": "a", "split": "tuning"}, {"group": "a", "split": "heldout"}]}""",
        ).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { CornerFixtureJson.decodeIndex(bad) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CornerFixtureJson.encodeIndex(CornerGroupIndex(groups = index.groups + index.groups.first()))
        }
    }

    @Test
    fun `story 10-28 count check, override reason and reviewed flags round-trip, and older files count as saved`() {
        val manifest =
            CornerGroupManifest(
                CornerFixturePaths.PIPELINE_10_22,
                LightDirection.FRONT,
                record.device,
                "stla",
                "warm",
                countCheck = CornerCountCheck.OVERRIDDEN,
                overrideReason = "a sticker is peeling; counted by hand",
            )
        val encoded = CornerFixtureJson.encodeManifest(manifest)
        assertTrue("\"countCheck\": \"overridden\"" in encoded && "\"overrideReason\": \"a sticker is peeling" in encoded, encoded)
        assertEquals(manifest, CornerFixtureJson.decodeManifest(encoded))
        assertTrue("saved" !in encoded, encoded)
        mapOf(
            null to true,
            CornerCountCheck.PASSED to true,
            CornerCountCheck.OVERRIDDEN to true,
            CornerCountCheck.PENDING to false,
            CornerCountCheck.REFUSED to false,
        ).forEach { (check, saved) -> assertEquals(saved, manifest.copy(countCheck = check).saved, "$check") }
        CornerCountCheck.entries.forEach { check ->
            val json = CornerFixtureJson.encodeManifest(manifest.copy(countCheck = check))
            assertTrue("\"countCheck\": \"${check.name.lowercase()}\"" in json, json)
        }
        // A manifest written before Story 10.28 has no count check and counts as saved.
        val old =
            """
            {"pipeline": "10.22", "lightDirection": "front", "device": {"model": "m", "manufacturer": "x", "osRelease": "13",
            "osBuild": "b", "sdkInt": 33}, "cube": "stla", "light": "warm", "torch": null, "holds": [{"hold": 1, "torch": null}]}
            """.trimIndent()
        val decoded = CornerFixtureJson.decodeManifest(old)
        assertEquals(null, decoded.countCheck)
        assertEquals(null, decoded.overrideReason)
        assertTrue(decoded.saved)

        // Reviewed flags: one list of nine per labeled sample; older records have none.
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
        val withoutReviewed = CornerFixtureJson.encode(corner).replace(Regex(",\\s*\"reviewed\": null"), "")
        assertTrue("reviewed" !in withoutReviewed, withoutReviewed)
        assertEquals(corner, CornerFixtureJson.decode(withoutReviewed))
        val reviewed = corner.copy(reviewed = List(3) { face -> List(9) { it == face } })
        assertEquals(reviewed, CornerFixtureJson.decode(CornerFixtureJson.encode(reviewed)))
        listOf(corner.copy(reviewed = List(2) { List(9) { true } }), corner.copy(reviewed = List(3) { List(8) { true } })).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { CornerFixtureJson.decode(CornerFixtureJson.encode(bad)) }
        }
    }

    @Test
    fun `story 10-28 overridden groups are listed apart, used only when included, and validated`() {
        val index =
            CornerGroupIndex(
                groups = listOf(CornerGroupIndexEntry("a", CornerSplit.HELDOUT)),
                overridden =
                    listOf(
                        CornerOverriddenEntry("b", CornerSplit.TUNING, "peeling sticker"),
                        CornerOverriddenEntry("c", CornerSplit.HELDOUT, "logo centre", included = true, pastel = true),
                    ),
            )
        assertEquals(index, CornerFixtureJson.decodeIndex(CornerFixtureJson.encodeIndex(index)))
        assertEquals(
            listOf(CornerGroupIndexEntry("a", CornerSplit.HELDOUT), CornerGroupIndexEntry("c", CornerSplit.HELDOUT, pastel = true)),
            index.used(),
        )
        assertEquals(listOf("b"), index.skippedOverrides())
        assertEquals(CornerSplit.HELDOUT, index.entry("c")?.split)
        assertEquals(null, index.entry("b"))
        // An index written before Story 10.28 has no overridden list.
        val minimal = """{"kind": "corner-group-index", "schemaVersion": 1, "groups": [{"group": "a", "split": "tuning"}]}"""
        assertEquals(emptyList<CornerOverriddenEntry>(), CornerFixtureJson.decodeIndex(minimal).overridden)
        listOf(
            index.copy(overridden = listOf(CornerOverriddenEntry("b", CornerSplit.TUNING, "  "))),
            index.copy(overridden = listOf(CornerOverriddenEntry("b", CornerSplit.TUNING, ""))),
            index.copy(overridden = listOf(CornerOverriddenEntry(" ", CornerSplit.TUNING, "reason"))),
            index.copy(overridden = listOf(CornerOverriddenEntry("a", CornerSplit.TUNING, "listed in both"))),
            index.copy(overridden = index.overridden + index.overridden.first()),
        ).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { CornerFixtureJson.encodeIndex(bad) }
        }
    }

    @Test
    fun `story 10-28 a group is used when saved, skipped when unsaved, and an override only when the PO includes it`() {
        val manifest = CornerGroupManifest(CornerFixturePaths.PIPELINE_10_22, LightDirection.FRONT, record.device, "stla", "warm")
        val overridden = manifest.copy(countCheck = CornerCountCheck.OVERRIDDEN, overrideReason = "reason")
        val index =
            CornerGroupIndex(
                groups = listOf(CornerGroupIndexEntry("a", CornerSplit.TUNING)),
                overridden =
                    listOf(
                        CornerOverriddenEntry("in", CornerSplit.TUNING, "reason", included = true),
                        CornerOverriddenEntry("out", CornerSplit.TUNING, "reason"),
                    ),
            )
        assertEquals(CornerGroupUse.USED, CornerGroupUse.of("legacy", null, index))
        assertEquals(CornerGroupUse.USED, CornerGroupUse.of("a", manifest, index))
        assertEquals(CornerGroupUse.USED, CornerGroupUse.of("a", manifest.copy(countCheck = CornerCountCheck.PASSED), null))
        assertEquals(CornerGroupUse.UNSAVED, CornerGroupUse.of("a", manifest.copy(countCheck = CornerCountCheck.PENDING), index))
        assertEquals(CornerGroupUse.UNSAVED, CornerGroupUse.of("a", manifest.copy(countCheck = CornerCountCheck.REFUSED), index))
        assertEquals(CornerGroupUse.USED, CornerGroupUse.of("in", overridden, index))
        assertEquals(CornerGroupUse.OVERRIDE_SKIPPED, CornerGroupUse.of("out", overridden, index))
        assertEquals(CornerGroupUse.OVERRIDE_SKIPPED, CornerGroupUse.of("unlisted", overridden, index))
        assertEquals(CornerGroupUse.OVERRIDE_SKIPPED, CornerGroupUse.of("in", overridden, null))
    }
}
