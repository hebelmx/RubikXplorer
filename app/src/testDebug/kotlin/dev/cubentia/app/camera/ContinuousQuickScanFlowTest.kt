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
import dev.cubentia.domain.recognition.continuous.ContinuousFaceletMapper
import dev.cubentia.domain.recognition.continuous.ContinuousScanCue
import dev.cubentia.domain.recognition.continuous.ContinuousScanHandoff
import dev.cubentia.domain.recognition.continuous.ContinuousScanSnapshot
import dev.cubentia.domain.recognition.continuous.FaceSlot
import dev.cubentia.domain.recognition.continuous.Point2D
import dev.cubentia.domain.recognition.continuous.Quad2D
import dev.cubentia.domain.recognition.continuous.QuadObservation
import dev.cubentia.domain.recognition.continuous.RgbColor
import dev.cubentia.domain.text.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A hand-off of [faces] of the solved cube. [centresOnly] is what the mapper exports below six sides: each face holds its
 * centre and eight unread stickers (their Labs measured), since it commits no other sticker before it has seen all six
 * centres.
 */
internal fun testHandoff(
    faces: Collection<Face>,
    anchored: Boolean = true,
    blank: FaceSlot? = null,
    blanks: Set<FaceSlot> = setOfNotNull(blank),
    centresOnly: Boolean = false,
): ContinuousScanHandoff {
    val state = CubeState.SOLVED.toFacelets().colors
    return ContinuousScanHandoff(
        faces.associateWith { face ->
            val colors =
                List(9) { i ->
                    state[face.ordinal * 9 + i].takeUnless { FaceSlot(face, i) in blanks || (centresOnly && i != FaceSample.CENTER_INDEX) }
                }
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
        // As the mapper exports them below six sides (centres only), the Editor gets the centres and Guided keeps no face.
        val centres = testHandoff(listOf(Face.U, Face.F), centresOnly = true)
        assertEquals(centres.toFallbackStickers(), unanchored.fallback(centres))
        assertEquals(2, checkNotNull(unanchored.fallback(centres)).count { it != null })
        assertTrue(unanchored.keptForGuided(centres).faces.isEmpty())
        val mapped = testHandoff(listOf(Face.U, Face.F, Face.R), blank = FaceSlot(Face.R, 0))
        val more = unanchored.feed(testSnapshot(mapped))
        assertEquals(CountAnnouncement(3, 1), more.announcement)
        assertEquals(listOf(ContinuousQuickScanFlow.Pulse.Tick), more.pulsesSince(unanchored))
        assertTrue(more.confirmClose)
        assertEquals(mapped.toFallbackStickers(), more.fallback(mapped))
        // A face with an unread sticker is asked again at any count, not only at six sides.
        assertEquals(setOf(Face.U, Face.F), more.keptForGuided(mapped).faces.keys)
        val fewer = more.feed(testSnapshot(testHandoff(listOf(Face.U, Face.F))))
        assertEquals(2, fewer.count)
        assertEquals(more.announcement, fewer.announcement)
        assertTrue(fewer.pulsesSince(more).isEmpty())
        assertEquals(CountAnnouncement(3, 2), fewer.feed(testSnapshot(mapped)).announcement)
    }

    @Test fun outlinesEaseEveryCornerAndExpireOnTheThirdMiss() {
        val snap = testSnapshot(testHandoff(emptyList()))

        // A small square at (x, x) (an outline needs visible area, GH #415).
        fun quad(x: Double) =
            ContinuousOverlayQuad(
                null,
                Face.F,
                List(4) { i ->
                    Point2D(
                        x +
                            if (i == 1 ||
                                i == 2
                            ) {
                                0.01
                            } else {
                                0.0
                            },
                        x + if (i >= 2) 0.01 else 0.0,
                    )
                },
            )
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

    /** GH #415: an axis-aligned quad from ([left], [top]) of [width]×[height] in view fractions. */
    private fun rect(
        left: Double,
        top: Double,
        width: Double = 0.4,
        height: Double = 0.4,
        face: Face = Face.F,
    ) = ContinuousOverlayQuad(
        null,
        face,
        listOf(Point2D(left, top), Point2D(left + width, top), Point2D(left + width, top + height), Point2D(left, top + height)),
    )

    private fun ContinuousQuickScanFlow.frames(
        n: Int,
        snapshot: ContinuousScanSnapshot,
        overlays: List<ContinuousOverlayQuad>,
        analysed: Boolean = true,
    ): ContinuousQuickScanFlow = (1..n).fold(this) { flow, _ -> flow.next(ContinuousFrame(snapshot, overlays, analysed = analysed), 250L) }

    private val moveIntoFrame = Text("msg.quick-scan.continuous.move-into-frame")

    @Test fun aFaceWhollyOffThePreviewHasNoOutlineButItsStickersStay() {
        val snap = testSnapshot(testHandoff(listOf(Face.U)), ContinuousScanCue.TiltAdjacent)
        val onScreen = rect(0.2, 0.2, face = Face.U)
        // All four corners right of x = 1.
        val offScreen = rect(1.1, 0.2)
        val flow = ContinuousQuickScanFlow().feed(snap, listOf(onScreen, offScreen))
        assertEquals(setOf(OutlineKey(Face.U, anchored = false)), flow.outlines.keys)
        assertEquals(snap, flow.snapshot)
        assertEquals(snap.net, flow.snapshot?.net)
        // An outline drawn last frame goes at once when its face leaves the preview.
        val left = flow.feed(snap, listOf(rect(1.2, 0.2, face = Face.U)))
        assertTrue(left.outlines.isEmpty())
        // A face partly in view keeps its outline (drawing is clipped to the pane).
        assertEquals(
            setOf(OutlineKey(Face.F, anchored = false)),
            ContinuousQuickScanFlow().feed(snap, listOf(rect(0.8, 0.2))).outlines.keys,
        )
    }

    @Test fun mostlyOutOfViewForThreeAnalysedFramesShowsTheFramingCue() {
        val snap = testSnapshot(testHandoff(listOf(Face.U)), ContinuousScanCue.TiltAdjacent)
        // 75 % of the quad's area is right of the preview.
        val mostlyOff = listOf(rect(0.9, 0.2))
        val one = ContinuousQuickScanFlow().frames(1, snap, mostlyOff)
        val two = one.frames(1, snap, mostlyOff)
        assertEquals(ContinuousScanCue.TiltAdjacent.toText(), one.cueText)
        assertEquals(ContinuousScanCue.TiltAdjacent.toText(), two.cueText)
        val three = two.frames(1, snap, mostlyOff)
        assertEquals(moveIntoFrame, three.cueText)
        // The mapper's own cue keeps its meaning for the pulses and the hand-over.
        assertEquals(ContinuousScanCue.TiltAdjacent, three.cue)
        // Summed over all quads: one in view and one mostly out is under half out.
        assertFalse(ViewClip.mostlyOutOfView(listOf(rect(0.1, 0.1).viewCorners, rect(0.9, 0.2).viewCorners)))
        assertEquals(
            ContinuousScanCue.TiltAdjacent.toText(),
            ContinuousQuickScanFlow().frames(5, snap, listOf(rect(0.1, 0.1), rect(0.9, 0.2))).cueText,
        )
    }

    @Test fun backInViewTheMapperCueReturnsAfterTheThirdAnalysedFrame() {
        val snap = testSnapshot(testHandoff(listOf(Face.U)), ContinuousScanCue.TiltAdjacent)
        val shown = ContinuousQuickScanFlow().frames(3, snap, listOf(rect(0.9, 0.2)))
        assertEquals(moveIntoFrame, shown.cueText)
        for (back in listOf(listOf(rect(0.2, 0.2)), emptyList(), listOf(rect(0.7, 0.2)))) {
            val two = shown.frames(2, snap, back)
            assertEquals(moveIntoFrame, two.cueText)
            assertEquals(ContinuousScanCue.TiltAdjacent.toText(), two.frames(1, snap, back).cueText)
        }
        // A frame mostly out again restarts the count to clear.
        val interrupted = shown.frames(2, snap, emptyList()).frames(1, snap, listOf(rect(0.9, 0.2))).frames(2, snap, emptyList())
        assertEquals(moveIntoFrame, interrupted.cueText)
    }

    @Test fun theMapperCuesAheadOfTheFramingCueWin() {
        val tilt = testSnapshot(testHandoff(listOf(Face.U)), ContinuousScanCue.TiltAdjacent)
        val shown = ContinuousQuickScanFlow().frames(3, tilt, listOf(rect(0.9, 0.2)))
        assertEquals(moveIntoFrame, shown.cueText)
        for (cue in listOf(ContinuousScanCue.LayerTwistDetected, ContinuousScanCue.AllVerified, ContinuousScanCue.BringIntoView)) {
            val faces = if (cue == ContinuousScanCue.AllVerified) Face.entries else listOf(Face.U)
            val flow = shown.frames(1, testSnapshot(testHandoff(faces), cue), listOf(rect(0.9, 0.2)))
            assertTrue(flow.framingShown)
            assertEquals(cue.toText(), flow.cueText)
        }
        // The mapper's other cues come after it.
        for (cue in listOf(
            ContinuousScanCue.TurnRemaining(2),
            ContinuousScanCue.TiltAwayLight,
            ContinuousScanCue.ResolveConflict(Face.R),
        )) {
            assertEquals(moveIntoFrame, shown.frames(1, testSnapshot(testHandoff(listOf(Face.U)), cue), listOf(rect(0.9, 0.2))).cueText)
        }
    }

    @Test fun skippedFramesNeitherAdvanceNorResetTheFramingCount() {
        val snap = testSnapshot(testHandoff(listOf(Face.U)), ContinuousScanCue.TiltAdjacent)
        val held = ContinuousQuickScanFlow().frames(2, snap, listOf(rect(0.9, 0.2)))
        val skipped = held.frames(4, snap, emptyList(), analysed = false)
        assertEquals(ContinuousScanCue.TiltAdjacent.toText(), skipped.cueText)
        assertEquals(moveIntoFrame, skipped.frames(1, snap, listOf(rect(0.9, 0.2))).cueText)
        // Clearing too: two frames back in view, a skipped frame, then one more.
        val shown = skipped.frames(1, snap, listOf(rect(0.9, 0.2)))
        val clearing = shown.frames(2, snap, emptyList()).frames(1, snap, emptyList(), analysed = false)
        assertEquals(moveIntoFrame, clearing.cueText)
        assertEquals(ContinuousScanCue.TiltAdjacent.toText(), clearing.frames(1, snap, emptyList()).cueText)
    }

    @Test fun exactlyHalfOutOfViewIsNotMostlyOut() {
        val half = rect(0.5, 0.2, width = 1.0)
        assertFalse(ViewClip.mostlyOutOfView(listOf(half.viewCorners)))
        val snap = testSnapshot(testHandoff(listOf(Face.U)), ContinuousScanCue.TiltAdjacent)
        assertFalse(ContinuousQuickScanFlow().frames(5, snap, listOf(half)).framingShown)
        assertTrue(ViewClip.mostlyOutOfView(listOf(rect(0.51, 0.2, width = 1.0).viewCorners)))
        assertEquals(ContinuousQuickScanFlow.MOSTLY_OUT_OF_VIEW_FRACTION, 0.5, 0.0)
        assertEquals(3, ContinuousQuickScanFlow.FRAMING_CUE_FRAMES)
        // No quads, or no area: not out of view.
        assertFalse(ViewClip.mostlyOutOfView(emptyList()))
        assertFalse(ViewClip.mostlyOutOfView(listOf(List(4) { Point2D(2.0, 2.0) })))
        // A quad across a corner of the preview: a quarter of it in view.
        assertEquals(0.01, ViewClip.visibleArea(rect(-0.1, -0.1, 0.2, 0.2).viewCorners), 1e-12)
        assertEquals(0.0, ViewClip.visibleArea(rect(-0.5, 0.2, 0.3, 0.3).viewCorners), 0.0)
    }

    /** GH #409: each frame's debug overlay is carried as it came, and a frame without one clears it. */
    @Test fun theFramesDebugOverlayIsCarriedAndClearedWithIt() {
        val snap = testSnapshot(testHandoff(emptyList()))
        val debug =
            ContinuousDebugOverlay(
                listOf(DebugPeak(0.3, 0.4, 0.9f)),
                listOf(DebugLattice(List(9) { Point2D(0.5, 0.5) }, List(9) { it != 8 })),
                "peaks=1 fitted=1 sampled=1 kept=1 tracked=1",
            )
        val shown = ContinuousQuickScanFlow().next(ContinuousFrame(snap, emptyList(), debug), 0)
        assertEquals(debug, shown.debugOverlay)
        assertEquals(
            ContinuousDebugOverlay.SKIPPED,
            shown.next(ContinuousFrame(snap, emptyList(), ContinuousDebugOverlay.SKIPPED), 0).debugOverlay,
        )
        assertNull(shown.next(ContinuousFrame(snap, emptyList()), 0).debugOverlay)
        assertNull(ContinuousQuickScanFlow().next(ContinuousFrame(snap, emptyList()), 0).debugOverlay)
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

    @Test fun scanFaceByFaceBelowSixSidesAsksGuidedForEveryCentreOnlyFaceAndTheReviewHasNoBlanks() {
        (1..5).forEach { sides ->
            val centres = testHandoff(Face.entries.take(sides), centresOnly = true)
            val flow = ContinuousQuickScanFlow().feed(testSnapshot(centres))
            assertEquals(sides, flow.count)
            val kept = flow.keptForGuided(centres)
            assertTrue(kept.globalAnchored)
            assertTrue(kept.faces.isEmpty())
            // Guided captures every face it was asked for: nothing is left blank, and the cube can be accepted.
            val review = handoverReview(kept, (Face.entries - kept.faces.keys).map(::sample), null).review
            assertTrue(review.blanks.isEmpty())
            assertEquals(CubeState.SOLVED, review.accepted)
        }
    }

    /**
     * One frame of the U-F-R corner of a solved cube as the camera layer reports it: three quads (clockwise corners in
     * view pixels), each with its nine cell colours as that side is lit.
     */
    private fun solvedCornerView(): List<QuadObservation> =
        listOf(
            Triple(RgbColor(229.0, 230.0, 232.0), 0.49, listOf(285.5 to 119.4, 438.8 to 89.2, 356.3 to 212.6, 188.1 to 224.0)),
            Triple(RgbColor(142.0, 26.0, 29.0), 0.54, listOf(354.9 to 211.3, 439.1 to 92.1, 491.6 to 247.8, 434.5 to 378.8)),
            Triple(RgbColor(24.0, 119.0, 51.0), 0.82, listOf(278.0 to 363.9, 186.8 to 223.0, 355.9 to 211.9, 436.4 to 379.3)),
        ).map { (rgb, quality, corners) ->
            QuadObservation(
                quad = Quad2D(corners.map { (x, y) -> Point2D(x, y) }),
                cellRgbs = List(9) { rgb },
                cellQualities = List(9) { quality },
                sharpness = 1.0,
                hasGlare = false,
            )
        }

    @Test fun aRealMapperExportBelowSixSidesAsksGuidedForEveryFaceWithUnreadStickersAndTheReviewHasNoBlanks() {
        // The unchanged mapper on two frames of one corner: anchored with three centres confirmed and no other sticker
        // committed, as it commits none before it has seen all six centres.
        val mapper = ContinuousFaceletMapper()
        val snapshot = List(2) { mapper.processFrame(it, solvedCornerView()) }.last()
        val exported = mapper.export().handoff
        assertTrue(exported.globalAnchored)
        assertEquals(setOf(Face.U, Face.R, Face.F), exported.faces.keys)
        exported.faces.forEach { (face, data) ->
            assertEquals(List(9) { i -> Color.home(face).takeIf { i == FaceSample.CENTER_INDEX } }, data.colors)
        }
        val flow = ContinuousQuickScanFlow().feed(snapshot)
        assertEquals(3, flow.count)
        // Every mapped face holds unread stickers, so Guided is asked for all six and keeps none.
        val kept = flow.keptForGuided(exported)
        val asked = Face.entries - kept.faces.keys
        assertEquals(Face.entries.toList(), asked)
        val review = handoverReview(kept, asked.map(::sample), null).review
        assertTrue(review.blanks.isEmpty())
        assertEquals(CubeState.SOLVED, review.accepted)
        // Kept as exported, the three faces would reach the Review blank around their centres: an unread sticker is
        // never read from its measured Lab.
        assertEquals(24, handoverReview(exported, listOf(Face.B, Face.L, Face.D).map(::sample), null).review.blanks.size)
    }

    // Review and Correct (PO clarification Q8).

    /** A shown state with [sides] mapped and [stickers] set, anchored and still scanning unless said otherwise. */
    private fun shown(
        sides: Int,
        stickers: Int,
        anchored: Boolean = true,
    ) = ContinuousQuickScanFlow(
        snapshot =
            (testSnapshot(testHandoff(Face.entries, anchored)) as ContinuousScanSnapshot.Scanning)
                .copy(mappedFacesCount = sides, committedCount = stickers),
    )

    @Test fun reviewAndCorrectNeedsMoreThanEachNamedFractionOfTheSidesAndOfTheStickers() {
        // The last count that is NOT more than its fraction: today 3 of 6 sides and 27 of 54 stickers.
        val sides = (Face.entries.size * ContinuousQuickScanFlow.REVIEW_SIDES_FRACTION).toInt()
        val stickers = (54 * ContinuousQuickScanFlow.REVIEW_STICKERS_FRACTION).toInt()
        assertEquals(3 to 27, sides to stickers)
        assertTrue(shown(sides + 1, stickers + 1).reviewAndCorrectOffered())
        assertFalse(shown(sides, stickers + 1).reviewAndCorrectOffered())
        assertFalse(shown(sides + 1, stickers).reviewAndCorrectOffered())
        assertFalse(shown(sides, 54).reviewAndCorrectOffered())
        assertFalse(shown(6, stickers).reviewAndCorrectOffered())
        assertTrue(shown(6, 53).reviewAndCorrectOffered())
        // Before anchoring the shown count is 0, whatever the mapper counts; and with no frame yet nothing is offered.
        assertFalse(shown(6, 53, anchored = false).reviewAndCorrectOffered())
        assertFalse(ContinuousQuickScanFlow().reviewAndCorrectOffered())
    }

    @Test fun eachNamedFractionChangesTheReviewAndCorrectRule() {
        val four = shown(4, 28)
        assertTrue(four.reviewAndCorrectOffered())
        // More than three quarters of the sides is 5 or 6; more than three quarters of the stickers is 41 or more.
        assertFalse(four.reviewAndCorrectOffered(sidesFraction = 0.75))
        assertTrue(shown(5, 28).reviewAndCorrectOffered(sidesFraction = 0.75))
        assertFalse(four.reviewAndCorrectOffered(stickersFraction = 0.75))
        assertTrue(shown(4, 41).reviewAndCorrectOffered(stickersFraction = 0.75))
        // A lower fraction offers it earlier.
        assertFalse(shown(2, 20).reviewAndCorrectOffered())
        assertTrue(shown(2, 20).reviewAndCorrectOffered(sidesFraction = 0.25, stickersFraction = 0.25))
    }

    @Test fun reviewAndCorrectStaysUnderATwistHidesOnCompleteAndWhenANumberFallsBack() {
        val blanks = setOf(FaceSlot(Face.F, 3), FaceSlot(Face.U, 0))
        val handoff = testHandoff(Face.entries, blanks = blanks)
        val scanning = ContinuousQuickScanFlow().feed(testSnapshot(handoff, ContinuousScanCue.TiltRemainingStickers(52)))
        assertTrue(scanning.reviewAndCorrectOffered())
        // A latched twist keeps the net, so the action stays (judged on the kept net).
        val twist = scanning.feed(testSnapshot(handoff, ContinuousScanCue.LayerTwistDetected))
        assertTrue(twist.reviewAndCorrectOffered())
        // The completion hold: Review opens by itself.
        val complete = scanning.feed(testSnapshot(testHandoff(Face.entries), ContinuousScanCue.AllVerified))
        assertEquals(6, complete.count)
        assertFalse(complete.reviewAndCorrectOffered())
        // Either number falling back hides it again: three sides (27 stickers), then four sides.
        val fewer = scanning.feed(testSnapshot(testHandoff(Face.entries.take(3))))
        assertFalse(fewer.reviewAndCorrectOffered())
        assertTrue(fewer.feed(testSnapshot(testHandoff(Face.entries.take(4)))).reviewAndCorrectOffered())
    }

    @Test fun theConflictedStickersOfTheShownSnapshotAreTheReviewsStickersToCheckFirst() {
        // Conflicted: F3 (review index 21) and U0 (index 0); unread: D8 (index 35). All three are blank in the export.
        val conflicts = listOf(FaceSlot(Face.F, 3), FaceSlot(Face.U, 0))
        val handoff = testHandoff(Face.entries, blanks = conflicts.toSet() + FaceSlot(Face.D, 8))
        val flow = ContinuousQuickScanFlow().feed(testSnapshot(handoff, ContinuousScanCue.ResolveConflict(Face.F), conflicts))
        assertEquals(listOf(0, 21), flow.conflictedReviewIndices.toList())
        assertTrue(ContinuousQuickScanFlow().conflictedReviewIndices.isEmpty())
        val review = ScanReview.from(handoff, flow.conflictedReviewIndices)
        assertEquals(listOf(0, 21, 35), review.blanks)
        assertEquals(0, review.selected)
        val next = review.paint(Color.WHITE)
        assertEquals(21, next.selected)
        assertEquals(35, next.paint(Color.GREEN).selected)
        // The early review's mark and its stickers to check survive a retake; the retaken face's stickers leave the set.
        val early = GuidedScanPhase.Review(review, early = true)
        val retaken = GuidedScanFlow.reviewAfterRetake(early, listOf(sample(Face.F)))
        assertTrue(retaken.early)
        assertEquals(setOf(0), retaken.review.checkFirst)
        assertEquals(listOf(0, 35), retaken.review.blanks)
        assertEquals(0, retaken.review.selected)
        assertFalse(GuidedScanPhase.Review(review).early)
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
