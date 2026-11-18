package dev.cubentia.app.ui.recognize

import dev.cubentia.app.scan.CaseCaptureOutcome
import dev.cubentia.application.usecase.Recognition
import dev.cubentia.application.usecase.RecognizeCase
import dev.cubentia.domain.content.CaseSet
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.lastlayer.CaseStateResult
import dev.cubentia.domain.lastlayer.LastLayerMatcher
import dev.cubentia.domain.recognition.CornerView
import dev.cubentia.domain.text.Text
import org.junit.Assert.assertEquals
import org.junit.Test

class RecognizeFlowViewModelTest {
    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
    private val recognize = checkNotNull(RecognizeCase.of(bundle.cases, bundle.algorithms))
    private val first = corner(Color.GREEN, Color.ORANGE)
    private val opposite = corner(Color.BLUE, Color.RED)

    @Test
    fun anExtraAngleIntersectsWithSeveralButANoFitRescanStartsFresh() {
        val flow = RecognizeFlowViewModel()
        capture(flow, listOf(first, opposite))
        assertEquals(Recognition.Several(bundle.cases.map { it.id }.toSet()), result(flow))

        // From several, one more corner keeps the earlier candidates in play.
        capture(flow, listOf(first))
        assertEquals(Recognition.Several(bundle.cases.map { it.id }.toSet()), result(flow))

        // Two non-opposite corners: no fit.
        capture(flow, listOf(first, first))
        assertEquals(Recognition.NoFit, result(flow))

        // From no fit, the rescan discards the earlier (empty) candidates instead of intersecting with them.
        capture(flow, listOf(first, opposite))
        assertEquals(Recognition.Several(bundle.cases.map { it.id }.toSet()), result(flow))
    }

    @Test
    fun anExtraAngleKeepsTheCasesTheFirstPairRuledOutExcluded() {
        val flow = RecognizeFlowViewModel()
        // A PLL Case with the side rows unseen: every PLL Case fits, no OLL Case does.
        val pll = bundle.algorithms.first { it.isPrimary && bundle.cases.single { c -> c.id == it.caseId }.set == CaseSet.PLL }
        val pair = corners(stateOf(pll)).map { it.copy(left = centerOnly(it.left), right = centerOnly(it.right)) }
        capture(flow, pair)
        val plls =
            bundle.cases
                .filter { it.set == CaseSet.PLL }
                .map { it.id }
                .toSet()
        assertEquals(Recognition.Several(plls), result(flow))

        // One more corner of an OLL 45 cube fits OLL Cases only; intersected with the PLL set nothing is left.
        val oll = bundle.algorithms.single { it.id.value == "alg-oll-45-a" }
        val extra = corners(stateOf(oll)).first()
        check(recognize.fromCorners(listOf(extra)) != Recognition.NoFit)
        capture(flow, listOf(extra))
        assertEquals(Recognition.NoFit, result(flow))
    }

    @Test
    fun aSingleCornerWithoutEarlierCandidatesNamesNothing() {
        val flow = RecognizeFlowViewModel()
        capture(flow, listOf(first))
        assertEquals(RecognizePhase.Idle, flow.phase.value)
        assertEquals(false, flow.pending.value)
    }

    @Test
    fun cancelKeepsThePhaseAndTheEditorRoutesCarryTheirNotes() {
        val flow = RecognizeFlowViewModel()
        flow.onCapture(CaseCaptureOutcome.Cancelled)
        assertEquals(RecognizePhase.Idle, flow.phase.value)
        flow.onCapture(CaseCaptureOutcome.FellBackToEditor)
        assertEquals(RecognizePhase.Editing(Text("msg.solve.scan-fell-back")), flow.phase.value)
        flow.scanDenied()
        assertEquals(RecognizePhase.Editing(Text("msg.solve.scan-camera-denied")), flow.phase.value)
    }

    private fun capture(
        flow: RecognizeFlowViewModel,
        views: List<CornerView>,
    ) {
        flow.onCapture(CaseCaptureOutcome.Captured(views))
        flow.evaluate(recognize)
    }

    private fun stateOf(algorithm: dev.cubentia.domain.content.Algorithm): CubeState =
        (LastLayerMatcher.caseState(algorithm, Color.YELLOW) as CaseStateResult.Derived).state

    private fun centerOnly(face: List<Color?>): List<Color?> = face.mapIndexed { index, color -> color.takeIf { index == 4 } }

    private fun corners(state: CubeState): List<CornerView> =
        listOf(Face.F, Face.B).map { front ->
            val stickers =
                state
                    .reoriented(Orientation.of(Face.D, front))
                    .toFacelets()
                    .colors
                    .chunked(9)
            CornerView(stickers[Face.U.ordinal], stickers[Face.F.ordinal], stickers[Face.R.ordinal])
        }

    private fun result(flow: RecognizeFlowViewModel): Recognition = (flow.phase.value as RecognizePhase.Result).recognition

    private fun corner(
        left: Color,
        right: Color,
    ): CornerView {
        fun face(center: Color) = List(9) { center.takeIf { _ -> it == 4 } }
        return CornerView(face(Color.YELLOW), face(left), face(right))
    }
}
