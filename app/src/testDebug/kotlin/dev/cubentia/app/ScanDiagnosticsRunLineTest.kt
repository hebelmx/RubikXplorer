package dev.cubentia.app

import dev.cubentia.app.scan.CaseCaptureOutcome
import dev.cubentia.app.scan.ScanOutcome
import dev.cubentia.app.ui.placement.PlacementFlowViewModel
import dev.cubentia.app.ui.recognize.RecognizeFlowViewModel
import dev.cubentia.application.usecase.Recognition
import dev.cubentia.domain.content.CaseId
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.recognition.ScanReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/** Story 10.8: the run lines `evaluateMatrix` parses, for the outcomes the route tests do not reach. */
@RunWith(RobolectricTestRunner::class)
class ScanDiagnosticsRunLineTest {
    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle

    @Before fun clear() = ShadowLog.clear()

    private fun runs(feature: String) =
        ShadowLog.getLogsForTag(ScanDiagnostics.TAG).map { it.msg }.filter { it.startsWith("$feature run ") }

    @Test fun `a guided scan closed before any review logs not valid and no state`() {
        ScanDiagnostics.onGuidedScanRun(null, 0, null, null, null, "CANCELLED")
        val line = runs("guidedScan").single()
        assertTrue(
            line,
            Regex(
                "guidedScan run elapsedMs=- repromptFaces=0 corrections=0 valid=false outcome=CANCELLED state=- initial=- device=[A-Za-z0-9-]+",
            ).matches(line),
        )
    }

    @Test fun `a guided scan that falls back to the editor logs the review it left`() {
        val stickers = CubeState.SOLVED.toFacelets().colors
        val initial = stickers.mapIndexed { i, color -> if (i == 0) null else color }
        val review = ScanReview(initial, null)
        ScanDiagnostics.onGuidedScanRun(ScanDiagnostics.startRun(), 1, review, initial, emptyMap(), "FELL_BACK_TO_EDITOR")
        val line = runs("guidedScan").single()
        assertTrue(line, line.contains(" repromptFaces=1 corrections=0 valid=false outcome=FELL_BACK_TO_EDITOR state=-WWWWWWWW"))
        assertTrue(line, Regex("elapsedMs=\\d+ ").containsMatchIn(line))
    }

    @Test fun `recognize logs several candidates, a unique Case, a cancel and an editor fallback`() {
        ScanDiagnostics.onCaseCaptured()
        ScanDiagnostics.onRecognizeRun(Recognition.Several(setOf(CaseId("case-oll-28"), CaseId("case-oll-27"))), 2)
        ScanDiagnostics.onCaseCaptured()
        ScanDiagnostics.onRecognizeRun(Recognition.Unique(CaseId("case-pll-ua")), 1)
        val flow = RecognizeFlowViewModel()
        flow.onCapture(CaseCaptureOutcome.Cancelled)
        flow.onCapture(CaseCaptureOutcome.FellBackToEditor)
        val lines = runs("recognize")
        assertEquals(4, lines.size)
        assertTrue(
            lines[0],
            Regex(
                "recognize run elapsedMs=\\d+ outcome=SEVERAL case=- candidates=case-oll-27,case-oll-28 captures=2 device=\\S+",
            ).matches(lines[0]),
        )
        assertTrue(
            lines[1],
            Regex("recognize run elapsedMs=\\d+ outcome=UNIQUE case=case-pll-ua candidates=- captures=1 device=\\S+").matches(lines[1]),
        )
        assertTrue(lines[2], lines[2].startsWith("recognize run elapsedMs=- outcome=CANCELLED case=- candidates=- captures=- "))
        assertTrue(lines[3], lines[3].startsWith("recognize run elapsedMs=- outcome=EDITOR case=- candidates=- captures=- "))
    }

    @Test fun `placement logs the suggestion kind and none when the Learner leaves`() {
        val flow = PlacementFlowViewModel()
        // R U R' keeps the yellow cross but breaks the white one, and is three turns from solved: a non-white cross at First cross.
        val threeTurns =
            CubeState.SOLVED.apply(
                FaceTurn(Face.R, Turn.CLOCKWISE),
                FaceTurn(Face.U, Turn.CLOCKWISE),
                FaceTurn(Face.R, Turn.COUNTERCLOCKWISE),
            )
        flow.onScanOutcome(ScanOutcome.Accepted(threeTurns))
        flow.evaluateScan(bundle.stages)
        flow.reset()
        val line = runs("placement").single()
        assertTrue(
            line,
            Regex("placement run elapsedMs=\\d+ outcome=NON_WHITE suggested=stage-02-first-cross accepted=none device=\\S+").matches(line),
        )
    }

    @Test fun `a placement suggestion from manual entry is never logged`() {
        val flow = PlacementFlowViewModel()
        flow.editorAccepted(CubeState.SOLVED, bundle.stages)
        flow.reset()
        assertTrue(runs("placement").isEmpty())
    }

    @Test fun `a quick scan run logs the line evaluateQuickScan parses and no sticker data`() {
        val solved = CubeState.SOLVED.toFacelets().colors
        val initial = solved.mapIndexed { i, color -> if (i == 0) null else color }
        // One correction changed a sticker; one repeated what the scan already read.
        ScanDiagnostics.onQuickScanRun(ScanDiagnostics.startRun(), 3, mapOf(0 to solved[0], 1 to solved[1]), initial, "ACCEPTED")
        ScanDiagnostics.onQuickScanRun(null, 0, null, null, "CANCELLED")
        val lines = runs("quickScan")
        assertEquals(2, lines.size)
        assertTrue(
            lines[0],
            Regex(
                "quickScan run elapsedMs=\\d+ fallbackFaces=3 repromptFaces=0 corrections=1 outcome=ACCEPTED device=[A-Za-z0-9-]+",
            ).matches(lines[0]),
        )
        assertTrue(
            lines[1],
            Regex(
                "quickScan run elapsedMs=- fallbackFaces=0 repromptFaces=0 corrections=0 outcome=CANCELLED device=[A-Za-z0-9-]+",
            ).matches(lines[1]),
        )
        // The pattern of core/domain/src/evaluation/.../quickscan/QuickScanMeasurement.kt, which :app cannot depend on.
        val parsed =
            Regex(
                "quickScan run elapsedMs=(\\d+|-) fallbackFaces=(\\d+) repromptFaces=(\\d+) corrections=(\\d+) " +
                    "outcome=(ACCEPTED|CANCELLED|FELL_BACK_TO_EDITOR)(?: [A-Za-z][A-Za-z0-9]*=[^\\s=]+)*\\s*$",
            )
        lines.forEach { assertTrue(it, parsed.containsMatchIn(it)) }
    }

    @Test fun `torch diagnostics contain numbers only`() {
        ScanDiagnostics.onTorch(hasFlash = false, torch = false)
        val lines = ShadowLog.getLogsForTag(ScanDiagnostics.TAG).map { it.msg }
        assertEquals(
            listOf(
                "quickScan torch hasFlash=false torch=off",
            ),
            lines,
        )
    }
}
