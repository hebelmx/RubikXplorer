package dev.cubentia.domain.recognition.continuous.session

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.StickerTruth
import dev.cubentia.domain.recognition.TruthSource
import dev.cubentia.domain.recognition.continuous.ContinuousFaceletMapper
import dev.cubentia.domain.recognition.continuous.ContinuousScanCue
import dev.cubentia.domain.recognition.continuous.ContinuousScanExport
import dev.cubentia.domain.recognition.continuous.ContinuousScanHandoff
import dev.cubentia.domain.recognition.continuous.ContinuousScanSnapshot
import dev.cubentia.domain.recognition.continuous.FaceSlot
import dev.cubentia.domain.recognition.continuous.Point2D
import dev.cubentia.domain.recognition.continuous.Quad2D
import dev.cubentia.domain.recognition.continuous.SyntheticCubeStream
import dev.cubentia.domain.recognition.continuous.TrackedQuadOverlay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ContinuousSessionJsonTest {
    @TempDir lateinit var temp: File

    private val quad = Quad2D(listOf(Point2D(1.5, 2.25), Point2D(10.0, 2.0), Point2D(10.125, 11.0), Point2D(0.1, 10.0 / 3)))

    private fun manifest() =
        ContinuousSessionManifest(
            sessionId = "20261126-101010-lenovo",
            createdAt = "2026-11-26T10:10:10Z",
            device = SessionDevice("TB128XU", "LENOVO"),
            cube = "GAN stickerless",
            lighting = "desk lamp",
            partition = SessionPartition.HELDOUT,
            model = SessionModel("sticker_centers_v2_int8.tflite", "d18f", "2.3.2"),
            buildCommit = "0".repeat(40),
            frameCount = 3,
            framesWithObservations = 1,
            imageEvery = 3,
            imageFrames = 1,
            geometry = SessionGeometry(0, 640, 480, 0, 0, 640, 480, 640, 480, 256, 0.4, 0, 32),
            timing = SessionTiming(10, 76, 66, 33.0),
            captureSkips = 1,
            droppedFrames = 2,
            stopReason = SessionStopReason.FRAME_CAP,
            outcome = SessionOutcome.LOST_TRACKING,
            latencyP50Ms = 31.5,
            latencyP95Ms = 44.0,
            latencyBaselineP50Ms = 30.0,
            defective = true,
            defectReasons = listOf("captureSkips=1"),
            bytes = 1234,
        )

    @Test
    fun `manifest frames and labels round-trip with every field`() {
        val m = manifest()
        assertEquals(m, ContinuousSessionJson.decodeManifest(ContinuousSessionJson.encodeManifest(m)))
        val frame =
            ContinuousSessionFrame(
                frameIndex = 2,
                timestampMs = 76,
                analyzerMs = 12.375,
                inferenceMs = 8.5,
                observations = emptyList(),
                peaks = listOf(SessionPeak(12.5, 40.25, 0.875f)),
                image = ContinuousSessionFormat.imageName(2),
            )
        assertEquals(frame, ContinuousSessionJson.decodeFrame(ContinuousSessionJson.encodeFrame(frame)))
        assertEquals("images/frame-00002.png", frame.image)
        val labels =
            ContinuousSessionLabels.of(
                List(54) {
                    StickerTruth(
                        if (it ==
                            0
                        ) {
                            null
                        } else {
                            Color.home(Face.entries[it / 9])
                        },
                        TruthSource.PREFILLED,
                        false,
                    )
                },
                " why ",
            )
        assertEquals(SessionCountCheck.OVERRIDDEN, labels.countCheck)
        assertEquals("why", labels.overrideReason)
        assertNull(labels.stickers[0].color)
        assertEquals(labels, ContinuousSessionJson.decodeLabels(ContinuousSessionJson.encodeLabels(labels)))
        assertEquals(SessionCountCheck.REFUSED, ContinuousSessionLabels.of(labels.stickers.map { it.toTruth() }, " ").countCheck)
        assertThrows(IllegalArgumentException::class.java) {
            ContinuousSessionLabels(stickers = emptyList(), countCheck = SessionCountCheck.PASSED)
        }
    }

    @Test
    fun `another schema version is rejected`() {
        val text = ContinuousSessionJson.encodeManifest(manifest().copy(schemaVersion = 2))
        assertThrows(IllegalArgumentException::class.java) { ContinuousSessionJson.decodeManifest(text) }
    }

    @Test
    fun `observations round-trip exactly`() {
        val frames = SyntheticCubeStream.generateSequence(SyntheticCubeStream.buildScenarios()[0]).take(5)
        frames.flatMap { it.detections }.forEach { observation ->
            val dto = SessionObservation.of(observation)
            val decoded =
                ContinuousSessionJson
                    .decodeFrame(
                        ContinuousSessionJson.encodeFrame(ContinuousSessionFrame(0, 0L, 0.0, observations = listOf(dto))),
                    ).observations
                    .single()
            assertEquals(observation, decoded.toDomain())
        }
    }

    @Test
    fun `every cue round-trips`() {
        val cues =
            listOf(
                ContinuousScanCue.BringIntoView,
                ContinuousScanCue.TurnRemaining(3),
                ContinuousScanCue.TiltAdjacent,
                ContinuousScanCue.KeepTurningRealign,
                ContinuousScanCue.TiltAwayLight,
                ContinuousScanCue.TiltAwayLightRemaining(2),
                ContinuousScanCue.ResolveConflict(Face.L),
                ContinuousScanCue.LayerTwistDetected,
                ContinuousScanCue.AllVerified,
                ContinuousScanCue.TiltRemainingStickers(40),
            )
        cues.forEach { assertEquals(it, SessionCue.of(it).toDomain()) }
        assertEquals("ResolveConflict(L)", SessionCue.of(ContinuousScanCue.ResolveConflict(Face.L)).toString())
        assertThrows(IllegalArgumentException::class.java) { SessionCue("Nope").toDomain() }
    }

    @Test
    fun `scanning and twist snapshots round-trip`() {
        val net = Face.entries.associateWith { face -> List(9) { if (it == 4) Color.home(face) else null } }
        val scanning =
            ContinuousScanSnapshot.Scanning(
                frameIndex = 7,
                net = net,
                conflictedSlots = listOf(FaceSlot(Face.R, 3)),
                trackedQuads = listOf(TrackedQuadOverlay(null, Face.F, quad), TrackedQuadOverlay(Face.U, Face.U, quad)),
                globalAnchored = true,
                cue = ContinuousScanCue.ResolveConflict(Face.R),
                committedCount = 6,
                mappedFacesCount = 2,
                invariantViolationCount = 1,
                pendingFragmentFaces = 1,
            )
        val twist =
            ContinuousScanSnapshot.TwistDetected(
                8,
                5,
                net,
                emptyList(),
                emptyList(),
                false,
                ContinuousScanCue.LayerTwistDetected,
                6,
                2,
                0,
                0,
            )
        listOf(scanning, twist).forEach { snapshot ->
            val dto = ContinuousSessionJson.decodeSnapshot(ContinuousSessionJson.encodeSnapshot(SessionSnapshot.of(snapshot)))
            assertEquals(snapshot, dto.toDomain())
        }
        assertEquals("R3", SessionSnapshot.of(scanning).conflictedSlots.single())
        assertThrows(IllegalArgumentException::class.java) { SessionSnapshot.colorOf('x') }
    }

    @Test
    fun `a complete snapshot keeps its cube state through the facelets string`() {
        val mapper = ContinuousFaceletMapper()
        val complete =
            SyntheticCubeStream
                .generateSequence(SyntheticCubeStream.buildScenarios()[0])
                .map { mapper.processFrame(it.frameIndex, it.detections) }
                .filterIsInstance<ContinuousScanSnapshot.Complete>()
                .first()
        val dto = SessionSnapshot.of(complete)
        assertEquals(SnapshotKind.COMPLETE, dto.kind)
        assertEquals(54, dto.state?.length)
        assertEquals(complete, ContinuousSessionJson.decodeSnapshot(ContinuousSessionJson.encodeSnapshot(dto)).toDomain())
    }

    @Test
    fun `outcome is derived from the final export and the last snapshot`() {
        val handoff = ContinuousScanHandoff(emptyMap())
        val tracked =
            ContinuousScanSnapshot.Scanning(
                1,
                emptyMap(),
                emptyList(),
                listOf(TrackedQuadOverlay(null, Face.F, quad)),
                false,
                ContinuousScanCue.TiltAdjacent,
                0,
                0,
                0,
                0,
            )
        val lost = tracked.copy(trackedQuads = emptyList())
        assertEquals(SessionOutcome.PARTIAL, SessionOutcome.derive(ContinuousScanExport.Partial(handoff), tracked))
        assertEquals(SessionOutcome.LOST_TRACKING, SessionOutcome.derive(ContinuousScanExport.Partial(handoff), lost))
        assertEquals(SessionOutcome.LOST_TRACKING, SessionOutcome.derive(ContinuousScanExport.Partial(handoff), null))
        assertEquals(SessionOutcome.FAILED, SessionOutcome.derive(ContinuousScanExport.TwistDetected(3, handoff), tracked))
    }

    @Test
    fun `only renamed directories with a manifest are complete sessions`() {
        val done = File(temp, "s1").apply { mkdirs() }
        File(done, ContinuousSessionFormat.MANIFEST).writeText(ContinuousSessionJson.encodeManifest(manifest()))
        val tmp = File(temp, ".tmp-s2").apply { mkdirs() }
        File(tmp, ContinuousSessionFormat.MANIFEST).writeText("{}")
        File(temp, "s3").mkdirs()
        File(temp, "file.txt").writeText("x")
        assertEquals(listOf("s1"), ContinuousSessionFormat.sessions(temp).map { it.name })
        assertTrue(ContinuousSessionFormat.isComplete(done))
        assertFalse(ContinuousSessionFormat.isComplete(tmp))
        assertEquals(manifest(), ContinuousSessionJson.readManifest(done))
        assertTrue(ContinuousSessionJson.readFrames(done).isEmpty())
        assertTrue(ContinuousSessionJson.readSnapshots(done).isEmpty())
        assertNull(ContinuousSessionJson.readLabels(done))
        assertTrue(ContinuousSessionFormat.sessions(File(temp, "missing")).isEmpty())
    }
}
