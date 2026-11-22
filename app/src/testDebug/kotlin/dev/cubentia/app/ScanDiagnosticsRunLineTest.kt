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
import dev.cubentia.domain.recognition.CornerHoldEventKind
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

    @Test fun `quick scan logs the light check, light hold, metering and torch as numbers only`() {
        val face =
            dev.cubentia.domain.recognition.FrameStats(
                List(9) {
                    dev.cubentia.domain.recognition
                        .CellReading(
                            dev.cubentia.domain.recognition
                                .Lab(80.0, 0.0, 0.0),
                            true,
                        )
                },
                120.0,
                0.5,
                100.0,
                12,
            )
        val dark =
            dev.cubentia.domain.recognition.SideLight
                .Verdict(false, 90.0, 55.0, 80.0)
        val line = ScanDiagnostics.cornerFrameLine(true, listOf(face, face, face), 0.9, dark, lightHeld = true, torch = true)
        assertEquals(
            "quickScan frame detected=true quality=GLARE,GLARE,GLARE score=0.9 light=90.0,55.0,80.0 sideDiff=35.0 " +
                "lightScore=35.0 lightCheck=fail lightMode=torch lightHold=true torch=on glare=torch",
            line,
        )
        val calibrated =
            dev.cubentia.domain.recognition.SideLight
                .Verdict(true, 1.2, 0.9, 1.1, dev.cubentia.domain.recognition.SideLight.Mode.CALIBRATED)
        val calibratedLine =
            ScanDiagnostics.cornerFrameLine(
                true,
                listOf(face, face, face),
                0.9,
                calibrated,
                lightHeld = false,
                torch = false,
            )
        assertTrue(
            calibratedLine,
            calibratedLine.contains("light=1.200,0.900,1.100 lightScore=1.200 lightCheck=pass lightMode=calibrated"),
        )
        val none = ScanDiagnostics.cornerFrameLine(false, listOf(null, null, null), 0.0, null, lightHeld = false, torch = false)
        assertTrue(none, none.endsWith("light=-,-,- sideDiff=- lightScore=- lightCheck=- lightMode=- lightHold=false torch=off glare=-"))
        ScanDiagnostics.onMetering(supported = false, onOutline = false)
        ScanDiagnostics.onMeteringSettled(settleMs = 1500, timeout = true, lock = "unsupported")
        ScanDiagnostics.onMeteringSettled(settleMs = 700, timeout = false, lock = "on+awb", futureMs = 120)
        ScanDiagnostics.onExposureLockReleased(confirmed = true, heldMs = 1105)
        ScanDiagnostics.onExposureLockReleased(confirmed = false, heldMs = null)
        ScanDiagnostics.onTorch(hasFlash = false, torch = false)
        val lines = ShadowLog.getLogsForTag(ScanDiagnostics.TAG).map { it.msg }
        assertEquals(
            listOf(
                "quickScan metering supported=false region=centre",
                "quickScan metering settleMs=1500 timeout=true lock=unsupported futureMs=-",
                "quickScan metering settleMs=700 timeout=false lock=on+awb futureMs=120",
                "quickScan exposureLock released confirmed=true heldMs=1105",
                "quickScan exposureLock released confirmed=false heldMs=-",
                "quickScan torch hasFlash=false torch=off",
            ),
            lines,
        )
    }

    @Test fun `quick scan logs every hold state change as it happens with the hold and its elapsed time, never rate-limited`() {
        fun event(
            elapsedMs: Long,
            kind: dev.cubentia.domain.recognition.CornerHoldEventKind,
            reason: String? = null,
            amount: Double? = null,
            timeout: Boolean? = null,
            durationMs: Long? = null,
            hold: Int = 1,
        ) = dev.cubentia.domain.recognition
            .CornerHoldEvent(hold, elapsedMs, kind, reason, amount, timeout, durationMs)
        listOf(
            event(0, CornerHoldEventKind.VIEWFINDER),
            event(412, CornerHoldEventKind.FIRST_FRAME),
            event(500, CornerHoldEventKind.CUBE_FOUND),
            event(500, CornerHoldEventKind.METERING_REQUESTED, reason = "first"),
            event(1300, CornerHoldEventKind.METERING_SETTLED, timeout = false, durationMs = 800),
            event(1300, CornerHoldEventKind.STEADY_START),
            // Three restarts within 100 ms: each is logged (the 10.19 line was limited to one per 500 ms).
            event(1380, CornerHoldEventKind.STEADY_RESTART, reason = "outline", amount = 0.1234),
            event(1420, CornerHoldEventKind.STEADY_RESTART, reason = "outline", amount = 0.2),
            event(1460, CornerHoldEventKind.STEADY_RESTART, reason = "face", amount = 31.0),
            event(1500, CornerHoldEventKind.LIGHT_FAIL, amount = 1.52),
            event(2100, CornerHoldEventKind.LIGHT_CLEAR, amount = 1.2, durationMs = 600),
            event(2200, CornerHoldEventKind.CUBE_LOST),
            event(2600, CornerHoldEventKind.CUBE_FOUND),
            event(2600, CornerHoldEventKind.METERING_REQUESTED, reason = "lost"),
            event(4100, CornerHoldEventKind.METERING_SETTLED, timeout = true, durationMs = 1500),
            event(5200, CornerHoldEventKind.CAPTURE, reason = "auto"),
            event(300, CornerHoldEventKind.POSE_REJECTED, reason = "centres", amount = 4.0, hold = 2),
            event(900, CornerHoldEventKind.POSE_ACCEPTED, hold = 2),
            event(2000, CornerHoldEventKind.CAPTURE, reason = "manual", hold = 2),
        ).forEach(ScanDiagnostics::onHoldEvent)
        assertEquals(
            listOf(
                "quickScan viewfinder hold=1 t=0",
                "quickScan firstFrame hold=1 t=412",
                "quickScan cubeFound hold=1 t=500",
                "quickScan meteringRequested hold=1 t=500 reason=first",
                "quickScan meteringSettled hold=1 t=1300 timeout=false ms=800",
                "quickScan steadyStart hold=1 t=1300",
                "quickScan steadyRestart hold=1 t=1380 reason=outline amount=0.123",
                "quickScan steadyRestart hold=1 t=1420 reason=outline amount=0.200",
                "quickScan steadyRestart hold=1 t=1460 reason=face amount=31.000",
                "quickScan lightFail hold=1 t=1500 amount=1.520",
                "quickScan lightClear hold=1 t=2100 amount=1.200 ms=600",
                "quickScan cubeLost hold=1 t=2200",
                "quickScan cubeFound hold=1 t=2600",
                "quickScan meteringRequested hold=1 t=2600 reason=lost",
                "quickScan meteringSettled hold=1 t=4100 timeout=true ms=1500",
                "quickScan capture hold=1 t=5200 trigger=auto",
                "quickScan poseRejected hold=2 t=300 reason=centres amount=4.000",
                "quickScan poseAccepted hold=2 t=900",
                "quickScan capture hold=2 t=2000 trigger=manual",
            ),
            ShadowLog.getLogsForTag(ScanDiagnostics.TAG).map { it.msg },
        )
    }

    @Test fun `quick scan logs each corner capture with its trigger, times and light, colour names and counts only`() {
        val report =
            dev.cubentia.domain.recognition.CornerCaptureReport(
                2,
                listOf(
                    dev.cubentia.domain.recognition.QuickScanClassification.FaceReport(
                        Face.D,
                        dev.cubentia.domain.cube.Color.YELLOW,
                        8,
                        0,
                        false,
                        dev.cubentia.domain.recognition.CaptureQuality.ACCEPTABLE,
                        false,
                        0,
                        0,
                        false,
                    ),
                    dev.cubentia.domain.recognition.QuickScanClassification.FaceReport(
                        Face.L,
                        dev.cubentia.domain.cube.Color.ORANGE,
                        5,
                        3,
                        true,
                        dev.cubentia.domain.recognition.CaptureQuality.ACCEPTABLE,
                        false,
                        1,
                        2,
                        true,
                    ),
                    dev.cubentia.domain.recognition.QuickScanClassification
                        .FaceReport(Face.B, null, 0, 0, true, null, false, 0, 0, false),
                ),
                manual = false,
                totalMs = 3210,
                undetectedMs = 400,
                sinceDetectedMs = 2900,
                lightScore = 1.4567,
                lightPassed = false,
            )
        ScanDiagnostics.onCornerCapture(report)
        // A manual capture with the cube never detected and the light unmeasured.
        ScanDiagnostics.onCornerCapture(
            dev.cubentia.domain.recognition
                .CornerCaptureReport(1, emptyList(), manual = true, totalMs = 900, undetectedMs = 900),
        )
        val lines = ShadowLog.getLogsForTag(ScanDiagnostics.TAG).map { it.msg }
        assertEquals(
            listOf(
                "quickScan capture hold=2 trigger=auto totalMs=3210 undetectedMs=400 sinceDetectedMs=2900 lightScore=1.457 light=fail " +
                    "D:center=YELLOW,confident=8,uncertain=0,kept,reason=- " +
                    "L:center=ORANGE,confident=5,uncertain=3,grid,reason=disagree=2/closeCall=1/correction=rejected " +
                    "B:center=-,confident=0,uncertain=0,grid,reason=missing",
                "quickScan capture hold=1 trigger=manual totalMs=900 undetectedMs=900 sinceDetectedMs=- lightScore=- light=-",
            ),
            lines,
        )
    }
}
