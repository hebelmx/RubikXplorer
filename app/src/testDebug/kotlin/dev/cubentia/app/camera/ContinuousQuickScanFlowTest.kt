package dev.cubentia.app.camera

import dev.cubentia.app.ScanRouteTest
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.ScanReview
import dev.cubentia.domain.recognition.StickerConfidence
import dev.cubentia.domain.recognition.continuous.ContinuousFaceHandoff
import dev.cubentia.domain.recognition.continuous.ContinuousScanCue
import dev.cubentia.domain.recognition.continuous.ContinuousScanHandoff
import dev.cubentia.domain.recognition.continuous.ContinuousScanSnapshot
import dev.cubentia.domain.recognition.continuous.FaceSlot
import dev.cubentia.domain.recognition.continuous.Point2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun testHandoff(
    faces: Collection<Face>,
    anchored: Boolean = true,
    blank: FaceSlot? = null,
): ContinuousScanHandoff {
    val state = CubeState.SOLVED.toFacelets().colors
    return ContinuousScanHandoff(
        faces.associateWith { face ->
            val colors = List(9) { i -> state[face.ordinal * 9 + i].takeUnless { FaceSlot(face, i) == blank } }
            ContinuousFaceHandoff(
                face,
                colors,
                colors.map { if (it == null) StickerConfidence.UNCERTAIN else StickerConfidence.CONFIDENT },
                List(9) { ScanRouteTest.labs.getValue(Color.home(face)) },
                true,
            )
        },
        CalibrationCenters(ScanRouteTest.labs).takeIf { faces.size == 6 },
        anchored,
    )
}

internal fun testSnapshot(
    handoff: ContinuousScanHandoff,
    cue: ContinuousScanCue = ContinuousScanCue.BringIntoView,
    conflicts: List<FaceSlot> = emptyList(),
): ContinuousScanSnapshot {
    val net = handoff.faces.mapValues { it.value.colors }
    val n = net.values.sumOf { row -> row.count { it != null } }
    return when (cue) {
        ContinuousScanCue.AllVerified -> {
            ContinuousScanSnapshot.Complete(0, CubeState.SOLVED, net, conflicts, emptyList(), cue, n, 6, 0)
        }

        ContinuousScanCue.LayerTwistDetected -> {
            ContinuousScanSnapshot.TwistDetected(
                1,
                1,
                net,
                conflicts,
                emptyList(),
                handoff.globalAnchored,
                cue,
                n,
                net.size,
                0,
                0,
            )
        }

        else -> {
            ContinuousScanSnapshot.Scanning(0, net, conflicts, emptyList(), handoff.globalAnchored, cue, n, net.size, 0, 0)
        }
    }
}

class ContinuousQuickScanFlowTest {
    private fun ContinuousQuickScanFlow.feed(
        snapshot: ContinuousScanSnapshot,
        overlays: List<ContinuousOverlayQuad> = emptyList(),
    ) = next(ContinuousFrame(snapshot, overlays), 250L)

    @Test fun anchoringAndCountTransitionsControlFeedbackAndExits() {
        val empty = ContinuousQuickScanFlow()
        val local = testHandoff(listOf(Face.U), anchored = false)
        val unanchored = empty.feed(testSnapshot(local))
        assertEquals(0, unanchored.count)
        assertFalse(unanchored.confirmClose)
        assertNull(unanchored.fallback(local))
        assertTrue(unanchored.keptForGuided(local).faces.isEmpty())
        // The exits follow the fresh export, not the shown count (which may be one frame older): an anchored export with
        // faces seeds the Editor and keeps its faces although the screen still shows 0/6, and an empty one seeds nothing.
        val fresh = testHandoff(listOf(Face.U, Face.F))
        assertEquals(fresh.toFallbackStickers(), unanchored.fallback(fresh))
        assertEquals(fresh, unanchored.keptForGuided(fresh))
        assertNull(unanchored.fallback(testHandoff(emptyList())))
        val mapped = testHandoff(listOf(Face.U, Face.F, Face.R), blank = FaceSlot(Face.R, 0))
        val more = unanchored.feed(testSnapshot(mapped))
        assertEquals(CountAnnouncement(3, 1), more.announcement)
        assertEquals(listOf(ContinuousQuickScanFlow.Pulse.Tick), more.pulsesSince(unanchored))
        assertTrue(more.confirmClose)
        assertEquals(mapped.toFallbackStickers(), more.fallback(mapped))
        assertEquals(mapped, more.keptForGuided(mapped))
        val fewer = more.feed(testSnapshot(testHandoff(listOf(Face.U, Face.F))))
        assertEquals(2, fewer.count)
        assertEquals(more.announcement, fewer.announcement)
        assertTrue(fewer.pulsesSince(more).isEmpty())
        assertEquals(CountAnnouncement(3, 2), fewer.feed(testSnapshot(mapped)).announcement)
    }

    @Test fun outlinesEaseEveryCornerAndExpireOnTheThirdMiss() {
        val snap = testSnapshot(testHandoff(emptyList()))

        fun quad(x: Double) = ContinuousOverlayQuad(null, Face.F, List(4) { i -> Point2D(x + i * 0.01, x) })
        val first = ContinuousQuickScanFlow().feed(snap, listOf(quad(0.2)))
        val next = first.feed(snap, listOf(quad(0.6)))
        quad(0.4).viewCorners.zip(next.outlines.getValue(OutlineKey(Face.F, anchored = false)).corners).forEach { (a, b) ->
            assertEquals(a.x, b.x, 1e-9)
            assertEquals(a.y, b.y, 1e-9)
        }
        val missing = next.feed(snap).feed(snap)
        assertEquals(2, missing.outlines.getValue(OutlineKey(Face.F, anchored = false)).missingFrames)
        assertTrue(missing.feed(snap).outlines.isEmpty())
        // The quad becomes anchored (local F is HOME R): its unanchored outline goes at once, and the anchored one
        // continues from it instead of jumping.
        val anchored = next.feed(snap, listOf(ContinuousOverlayQuad(Face.R, Face.F, quad(0.8).viewCorners)))
        assertEquals(setOf(OutlineKey(Face.R, anchored = true)), anchored.outlines.keys)
        quad(0.6).viewCorners.zip(anchored.outlines.getValue(OutlineKey(Face.R, anchored = true)).corners).forEach { (a, b) ->
            assertEquals(a.x, b.x, 1e-9)
            assertEquals(a.y, b.y, 1e-9)
        }
        // An unanchored quad with the same local label as another quad's HOME face keeps its own outline.
        val both =
            anchored.feed(
                snap,
                listOf(
                    ContinuousOverlayQuad(Face.R, Face.F, quad(0.8).viewCorners),
                    quad(0.1).let {
                        it.copy(localFace = Face.R)
                    },
                ),
            )
        assertEquals(setOf(OutlineKey(Face.R, anchored = true), OutlineKey(Face.R, anchored = false)), both.outlines.keys)
        // Completion stops the analysis: no outline stays frozen on screen during the one-second hold.
        val full = testHandoff(Face.entries)
        val complete = both.feed(testSnapshot(full, ContinuousScanCue.AllVerified), listOf(quad(0.3)))
        assertTrue(complete.outlines.isEmpty())
    }

    @Test fun conflictAndTwistKeepTheMapAndPulseOnlyOnTransition() {
        val blank = FaceSlot(Face.F, 3)
        val handoff = testHandoff(Face.entries, blank = blank)
        val prior = ContinuousQuickScanFlow().feed(testSnapshot(handoff))
        val conflict = prior.feed(testSnapshot(handoff, ContinuousScanCue.ResolveConflict(Face.L), listOf(FaceSlot(Face.R, 2))))
        assertEquals(setOf(Face.U, Face.L, Face.B, Face.D), conflict.keptForGuided(handoff).faces.keys)
        val full = testHandoff(Face.entries)
        val named = prior.feed(testSnapshot(full, ContinuousScanCue.ResolveConflict(Face.L)))
        assertEquals(Face.entries.toSet() - Face.L, named.keptForGuided(full).faces.keys)
        assertEquals(listOf(ContinuousQuickScanFlow.Pulse.Reject), conflict.pulsesSince(prior))
        val twist = conflict.feed(testSnapshot(handoff, ContinuousScanCue.LayerTwistDetected))
        assertEquals(listOf(ContinuousQuickScanFlow.Pulse.Reject), twist.pulsesSince(conflict))
        assertTrue(twist.pulsesSince(twist).isEmpty())
        assertTrue(twist.outlines.isEmpty())
        assertEquals(handoff.toFallbackStickers(), twist.fallback(handoff))
    }

    @Test fun completeHoldsForASecondAndRetakeUsesEvidenceWithoutSixSamples() {
        val full = testHandoff(Face.entries)
        val before = ContinuousQuickScanFlow().feed(testSnapshot(full))
        val complete = before.feed(testSnapshot(full, ContinuousScanCue.AllVerified))
        assertEquals(1250L, complete.reviewAtMs)
        assertEquals(listOf(ContinuousQuickScanFlow.Pulse.Confirm), complete.pulsesSince(before))
        val original = GuidedScanPhase.Review(ScanReview.from(full))
        val retaken = GuidedScanFlow.reviewAfterRetake(original, listOf(sample(Face.F)))
        assertEquals(CubeState.SOLVED.toFacelets().colors, retaken.review.stickers)
        assertNotNull(retaken.review.evidence)
    }

    @Test fun aRetakeAfterAHandOverCalibratesFromTheKeptCentresWithoutSixSamples() {
        val kept = testHandoff(listOf(Face.U, Face.R, Face.F))
        val review = handoverReview(kept, listOf(Face.B, Face.L, Face.D).map(::sample), null)
        assertEquals(3, review.samples.size)
        assertNotNull(review.review.evidence)
        val retaken = GuidedScanFlow.reviewAfterRetake(review, listOf(sample(Face.F)))
        assertEquals(CubeState.SOLVED.toFacelets().colors, retaken.review.stickers)
        assertNotNull(retaken.review.accepted)
    }

    @Test fun guidedAssemblyPreservesUnreadKeptStickersEvenWhenTheirLabsWouldClassify() {
        val blank = FaceSlot(Face.R, 0)
        val kept = testHandoff(listOf(Face.U, Face.R, Face.F), blank = blank)
        val added = listOf(Face.B, Face.L, Face.D).map(::sample)
        val review = handoverReview(kept, added, null).review
        assertEquals(null, review.stickers[Face.R.ordinal * 9])
        assertEquals(53, review.stickers.count { it != null })
        val partial = handoverReview(kept, emptyList(), null).review
        // The 26 kept stickers plus the three fixed home centres of the faces not scanned yet.
        assertEquals(29, partial.stickers.count { it != null })
        listOf(Face.B, Face.L, Face.D).forEach { face ->
            assertEquals(1, partial.stickers.subList(face.ordinal * 9, face.ordinal * 9 + 9).count { it != null })
        }
    }

    private fun sample(face: Face) =
        FaceSample(
            face = face,
            orientationDegrees = 0,
            stickers = List(9) { ScanRouteTest.labs.getValue(Color.home(face)) },
            quality = CaptureQuality.ACCEPTABLE,
            centerColor = Color.home(face),
            centerPlain = true,
        )
}
