package dev.cubentia.app.camera.session

import dev.cubentia.app.camera.ContinuousCaptureLatency
import dev.cubentia.app.camera.ContinuousFrameTap
import dev.cubentia.app.camera.ContinuousLatencyBaseline
import dev.cubentia.app.camera.ContinuousModelIdentity
import dev.cubentia.app.camera.ContinuousSessionCapture
import dev.cubentia.app.camera.ContinuousSessionSaved
import dev.cubentia.app.camera.ContinuousSessionSetup
import dev.cubentia.app.camera.ContinuousSessionWriterFactory
import dev.cubentia.app.camera.QuickScanLeave
import dev.cubentia.app.camera.QuickScanSessionHost
import dev.cubentia.app.camera.StickerCenterDetection
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.continuous.ContinuousScanCue
import dev.cubentia.domain.recognition.continuous.ContinuousScanExport
import dev.cubentia.domain.recognition.continuous.ContinuousScanHandoff
import dev.cubentia.domain.recognition.continuous.ContinuousScanSnapshot
import dev.cubentia.domain.recognition.continuous.Point2D
import dev.cubentia.domain.recognition.continuous.Quad2D
import dev.cubentia.domain.recognition.continuous.TrackedQuadOverlay
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionFormat
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionJson
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionLabels
import dev.cubentia.domain.recognition.continuous.session.SessionCountCheck
import dev.cubentia.domain.recognition.continuous.session.SessionOutcome
import dev.cubentia.domain.recognition.continuous.session.SessionPartition
import dev.cubentia.domain.recognition.continuous.session.SessionStopReason
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.Executor

/**
 * Story 10.34: the Quick Scan session controller against a fake screen, with the real writer under a temp root and inline
 * executors. One test per path of the story's UI decisions (answers 3-16, W1-W10).
 */
class QuickScanSessionControllerTest {
    @TempDir lateinit var root: File

    private val inline = Executor { it.run() }
    private var nowMs = 1_000L
    private val logs = mutableListOf<String>()
    private val partial = ContinuousScanExport.Partial(ContinuousScanHandoff(emptyMap()))
    private val complete = ContinuousScanExport.Complete(CubeState.SOLVED, CubeState.SOLVED.toFacelets(), ContinuousScanHandoff(emptyMap()))
    private val quad = Quad2D(listOf(Point2D(10.0, 10.0), Point2D(60.0, 12.0), Point2D(58.0, 62.0), Point2D(9.0, 60.0)))
    private val detection = StickerCenterDetection(emptyList(), 480, 640, 1.0, 0.4, 32, 0)

    /** The Quick Scan screen as the session sees it: what it was asked to do, and the taps it handed out. */
    private class Host : QuickScanSessionHost {
        var live = true
        var dialog = false
        var busy = false
        var export: ContinuousScanExport = ContinuousScanExport.Partial(ContinuousScanHandoff(emptyMap()))
        val calls = mutableListOf<String>()
        var tap: ContinuousFrameTap? = null
        var baselineTap: ContinuousFrameTap? = null

        override val liveScanShown get() = live
        override val hostDialogShown get() = dialog
        override val modelIdentity = ContinuousModelIdentity("sticker_centers_v2_int8.tflite", "d18f", 256, "XNNPACK", 2)

        override fun startCapture(
            tap: ContinuousFrameTap,
            started: () -> Unit,
        ): Boolean {
            if (busy) return false
            calls += "startCapture"
            this.tap = tap
            started()
            return true
        }

        /** When set, the next export is held (as when the view model's scope is cancelled before it delivers). */
        var holdExports = false
        var heldExport: (() -> Unit)? = null

        override fun export(consume: (ContinuousScanExport) -> Unit): Boolean {
            if (busy) return false
            calls += "export"
            val result = export
            if (holdExports) heldExport = { consume(result) } else consume(result)
            return true
        }

        override fun resetMap() {
            calls += "resetMap"
        }

        override fun startBaseline(
            tap: ContinuousFrameTap,
            started: () -> Unit,
        ): Boolean {
            calls += "startBaseline"
            baselineTap = tap
            started()
            return true
        }

        override fun endBaseline(ended: () -> Unit) {
            calls += "endBaseline"
            baselineTap = null
            ended()
        }

        override fun releaseHandOff() {
            calls += "releaseHandOff"
        }
    }

    /** The debug writer factory with test caps; it remembers every writer it made. */
    private inner class Writers(
        private val frameCap: Int = ContinuousSessionFormat.FRAME_CAP,
        private val sizeCap: Long = ContinuousSessionFormat.SIZE_CAP_BYTES,
    ) : ContinuousSessionWriterFactory {
        val made = mutableListOf<ContinuousSessionWriter>()
        private val files = DebugContinuousSessionWriterFactory({ root }, "c".repeat(40), "2.3.2")

        override fun create(setup: ContinuousSessionSetup): ContinuousSessionCapture =
            ContinuousSessionWriter(root, setup, "c".repeat(40), "2.3.2", frameCap = frameCap, sizeCapBytes = sizeCap).also { made += it }

        override fun baseline(deviceModel: String) = files.baseline(deviceModel)

        override fun saveBaseline(
            deviceModel: String,
            analyzerMs: List<Double>,
        ) = files.saveBaseline(deviceModel, analyzerMs)

        override fun writeLabels(
            saved: ContinuousSessionSaved,
            labels: ContinuousSessionLabels,
        ) = files.writeLabels(saved, labels)
    }

    private val host = Host()
    private var writers = Writers()

    private fun controller(
        w: Writers = writers,
        io: Executor = inline,
        onRelease: () -> Unit = {},
    ): QuickScanSessionController {
        writers = w
        return QuickScanSessionController(
            host = host,
            writers = w,
            deviceModel = "TB128XU",
            deviceManufacturer = "LENOVO",
            io = io,
            main = inline,
            onRelease = onRelease,
            clockMs = { nowMs },
            now = { LocalDateTime.of(2026, 11, 26, 9, 15, 2) },
            log = { logs += it },
        )
    }

    @AfterEach
    fun stopWriters() = writers.made.forEach { if (it.tempDir.exists() || it.recording) runCatching { it.discard() } }

    private fun snapshot(index: Int) =
        ContinuousScanSnapshot.Scanning(
            index,
            Face.entries.associateWith { List(9) { null } },
            emptyList(),
            listOf(TrackedQuadOverlay(null, Face.F, quad)),
            false,
            ContinuousScanCue.TurnRemaining(6),
            0,
            3,
            0,
            0,
        )

    /** Frames as the engine hands them to the recording tap. */
    private fun frames(
        count: Int,
        from: Int = 0,
    ) {
        val tap = checkNotNull(host.tap)
        repeat(count) {
            val i = from + it
            val buffer = tap.modelInputBuffer(i)
            tap.onFrame(i, emptyList(), snapshot(i), detection, buffer != null)
            tap.frameDone(i * 33_000_000L, 10_000_000L, 90, 640, 480, 0, 0, 640, 480)
        }
        (tap as ContinuousSessionWriter).awaitWritten()
    }

    private val QuickScanSessionController.capture get() = writers.made.last()

    private fun recording(c: QuickScanSessionController = controller()): QuickScanSessionController {
        c.rec()
        assertTrue(c.phase is SessionPhase.Recording)
        return c
    }

    private fun tempDirs() = root.listFiles().orEmpty().filter { it.name.startsWith(ContinuousSessionFormat.TEMP_PREFIX) }

    // ---- Rec, indicator, sheet ----

    @Test
    fun `Rec clears the map and records, the indicator counts and Rec reads Stop`() {
        val c = controller()
        assertTrue(c.recAvailable)
        c.rec()
        assertEquals(listOf("startCapture"), host.calls)
        assertTrue(c.phase is SessionPhase.Recording)
        assertFalse(c.recAvailable)
        assertFalse(c.holdsAnalysis)
        frames(5)
        c.tick()
        assertEquals("● REC 5 · 0 skips", QuickScanSessionController.recIndicator(c.status))
        assertTrue(c.capture.tempDir.isDirectory)
        assertEquals(
            "20261126-091502-tb128xu",
            c.capture.tempDir.name
                .removePrefix(ContinuousSessionFormat.TEMP_PREFIX),
        )
    }

    @Test
    fun `Stop opens the sheet, which only Save or Discard close, and holds the analysis`() {
        val c = recording()
        frames(4)
        c.stop()
        val stopped = c.phase as SessionPhase.Stopped
        assertEquals(SessionStopReason.OPERATOR, stopped.reason)
        assertEquals("Stopped: operator", QuickScanSessionController.stopLine(stopped.reason))
        assertTrue(c.sheetShown)
        assertTrue(c.holdsAnalysis)
        assertFalse(c.recAvailable)
        c.tick()
        assertEquals("4 frames · 0 skips · 0.0 MB", QuickScanSessionController.countsLine(c.status))
        assertEquals("Capture p50 10.0 ms · p95 10.0 ms", QuickScanSessionController.latencyLine(c.latency))
        assertEquals("No baseline yet", QuickScanSessionController.baselineLine(c.baseline))
        assertEquals("Outcome: partial", QuickScanSessionController.outcomeLine(c.outcome))
        // Discard… asks first; Cancel keeps the sheet.
        c.askDiscard()
        assertTrue(c.confirmDiscard)
        c.cancelDiscard()
        assertTrue(c.phase is SessionPhase.Stopped)
        assertTrue(c.sheetShown)
        // Rec and adb start are unavailable on the sheet.
        c.rec()
        assertTrue(c.phase is SessionPhase.Stopped)
        assertTrue(logs.any { it.startsWith("start ignored") })
    }

    @Test
    fun `Save writes the group with the sheet fields and shows Saved id, no baseline writes null`() {
        val c = recording()
        frames(4)
        c.stop()
        c.updateFields { it.copy(cube = "stla", lighting = "warm", partition = SessionPartition.HELDOUT) }
        c.save()
        val notice = checkNotNull(c.notice)
        assertEquals("Saved 20261126-091502-tb128xu", QuickScanSessionController.noticeLine(notice.saved))
        assertEquals(SessionPhase.Idle, c.phase)
        assertFalse(c.holdsAnalysis)
        assertTrue(c.recAvailable)
        val m = ContinuousSessionJson.readManifest(File(notice.saved.path))
        assertEquals("stla", m.cube)
        assertEquals("warm", m.lighting)
        assertEquals(SessionPartition.HELDOUT, m.partition)
        assertNull(m.latencyBaselineP50Ms)
        assertFalse(m.defective)
        assertTrue(tempDirs().isEmpty())
        // The next sheet is pre-filled with these values (answer 11).
        assertEquals("stla", SessionFieldMemory.last.cube)
        // The notice stays until the next Rec, which dismisses it.
        c.rec()
        assertNull(c.notice)
    }

    @Test
    fun `a defective session's notice names its reasons`() {
        writers.saveBaseline("TB128XU", listOf(5.0))
        val c = recording()
        frames(3)
        c.stop()
        c.tick()
        assertEquals("Baseline p50 5.0 ms", QuickScanSessionController.baselineLine(c.baseline))
        assertEquals(
            "Will be marked defective: capture p50 10.00 ms > 1.10 × baseline p50 5.00 ms",
            QuickScanSessionController.defectPreview(c.status, c.latency, c.baseline),
        )
        c.save()
        assertEquals(
            "Saved 20261126-091502-tb128xu · defective: capture p50 10.00 ms > 1.10 × baseline p50 5.00 ms",
            QuickScanSessionController.noticeLine(checkNotNull(c.notice).saved),
        )
        assertEquals(5.0, ContinuousSessionJson.readManifest(File(checkNotNull(c.notice).saved.path)).latencyBaselineP50Ms)
        assertEquals(
            "Will be marked defective: captureSkips=2",
            QuickScanSessionController.defectPreview(c.status?.copy(skips = 2), ContinuousCaptureLatency(null, null), null),
        )
    }

    @Test
    fun `Discard deletes the temp dir and closes the sheet`() {
        val c = recording()
        frames(4)
        c.stop()
        c.askDiscard()
        c.discard()
        assertEquals(SessionPhase.Idle, c.phase)
        assertFalse(c.confirmDiscard)
        assertTrue(root.listFiles().orEmpty().none { it.isDirectory })
        assertTrue(c.recAvailable)
    }

    // ---- Completion (answers 4, 13) ----

    @Test
    fun `completion stops the recording and opens the sheet before the hand-off, Save holds it until the notice is dismissed`() {
        val c = recording()
        frames(3)
        host.export = complete
        assertTrue(c.onCompleted())
        assertEquals(SessionStopReason.COMPLETE, (c.phase as SessionPhase.Stopped).reason)
        assertTrue(c.holdsHandOff)
        assertTrue(c.holdsAnalysis)
        // The completion hold asks again (a recomposition): still held.
        assertTrue(c.onCompleted())
        c.save()
        assertEquals(SessionOutcome.COMPLETE, checkNotNull(c.notice).saved.outcome)
        assertTrue(c.holdsHandOff)
        assertTrue(c.holdsAnalysis)
        assertFalse(c.recAvailable)
        assertFalse("releaseHandOff" in host.calls)
        c.dismissNotice()
        assertFalse(c.holdsHandOff)
        assertEquals("releaseHandOff", host.calls.last())
        assertFalse(c.onCompleted())
    }

    @Test
    fun `after a completed session's Save, closing the labeller continues the hand-off`() {
        val c = recording()
        frames(3)
        host.export = complete
        c.onCompleted()
        c.save()
        c.openLabeller()
        assertNotNull(c.labeller)
        assertTrue(c.holdsAnalysis)
        // Pre-filled from the complete export's canonical net.
        assertEquals(CubeState.SOLVED.toFacelets().colors, sessionTruth(checkNotNull(c.labeller).faces).map { it.color })
        c.closeLabeller()
        assertNull(c.labeller)
        assertEquals("releaseHandOff", host.calls.last())
        assertFalse(c.holdsHandOff)
    }

    @Test
    fun `a completed session's Discard continues the hand-off at once`() {
        val c = recording()
        frames(3)
        c.onCompleted()
        c.askDiscard()
        c.discard()
        assertEquals("releaseHandOff", host.calls.last())
        assertFalse(c.holdsHandOff)
        assertNull(c.notice)
    }

    @Test
    fun `without a recording, completion is not held`() {
        val c = controller()
        assertFalse(c.onCompleted())
        assertFalse(c.holdsHandOff)
    }

    // ---- Caps (answer 7) ----

    @Test
    fun `the frame cap stops the recording by itself and opens the sheet`() {
        val c = recording(controller(Writers(frameCap = 5)))
        frames(7)
        c.tick()
        val stopped = c.phase as SessionPhase.Stopped
        assertEquals("Stopped: frame cap", QuickScanSessionController.stopLine(stopped.reason))
        assertTrue(c.sheetShown)
        c.save()
        assertEquals(5, ContinuousSessionJson.readManifest(File(checkNotNull(c.notice).saved.path)).frameCount)
    }

    @Test
    fun `the size cap stops the recording by itself and opens the sheet`() {
        val c = recording(controller(Writers(sizeCap = ContinuousSessionWriter.MANIFEST_RESERVE_BYTES + 2_000)))
        frames(30)
        c.tick()
        assertEquals("Stopped: size cap", QuickScanSessionController.stopLine((c.phase as SessionPhase.Stopped).reason))
    }

    @Test
    fun `a cap that stopped the writer before the scan completed still holds and then releases the hand-off on Discard`() {
        val c = recording(controller(Writers(frameCap = 5)))
        frames(7)
        // The writer stopped at its cap; the overlay's tick has not seen it yet when the scan completes.
        assertTrue(c.onCompleted())
        assertEquals(SessionStopReason.FRAME_CAP, (c.phase as SessionPhase.Stopped).reason)
        assertTrue(c.holdsHandOff)
        c.askDiscard()
        c.discard()
        assertEquals("releaseHandOff", host.calls.last())
        assertFalse(c.holdsHandOff)
        assertFalse(c.holdsAnalysis)
    }

    @Test
    fun `a cap that stopped the writer before Reset still clears the map after Save`() {
        val c = recording(controller(Writers(frameCap = 5)))
        frames(7)
        assertTrue(c.onReset())
        val stopped = c.phase as SessionPhase.Stopped
        assertEquals(SessionStopReason.FRAME_CAP, stopped.reason)
        assertTrue(stopped.resetRequested)
        c.save()
        assertEquals("resetMap", host.calls.last())
        assertFalse("releaseHandOff" in host.calls)
    }

    @Test
    fun `release during a Save whose export never arrived discards the temp dir`() {
        val c = recording()
        frames(3)
        c.stop()
        host.holdExports = true
        c.save()
        assertTrue(c.phase is SessionPhase.Busy)
        assertEquals(1, tempDirs().size)
        c.release()
        assertTrue(tempDirs().isEmpty())
        assertTrue(root.listFiles().orEmpty().none { it.isDirectory })
    }

    @Test
    fun `a writer made after release is discarded without throwing on main`() {
        val queued = mutableListOf<Runnable>()
        var shut = false
        val io =
            Executor { task ->
                if (shut) throw java.util.concurrent.RejectedExecutionException("io is shut down")
                queued += task
            }
        val c = controller(io = io, onRelease = { shut = true })
        c.rec()
        assertEquals(SessionPhase.Starting, c.phase)
        c.release()
        // The writer is made only now, after release shut io down: its discard runs inline.
        queued.removeAt(0).run()
        val made = writers.made.single()
        assertFalse(made.recording)
        assertTrue(tempDirs().isEmpty())
        assertEquals(SessionPhase.Idle, c.phase)
    }

    // ---- Reset (answer 5) ----

    @Test
    fun `Reset while recording stops it and clears the map only after Save`() {
        val c = recording()
        frames(2)
        assertTrue(c.onReset())
        assertEquals("Stopped: reset", QuickScanSessionController.stopLine((c.phase as SessionPhase.Stopped).reason))
        assertFalse("resetMap" in host.calls)
        c.save()
        assertEquals("resetMap", host.calls.last())
    }

    @Test
    fun `Reset while recording clears the map after Discard too`() {
        val c = recording()
        frames(2)
        c.onReset()
        c.askDiscard()
        c.discard()
        assertEquals("resetMap", host.calls.last())
    }

    @Test
    fun `Reset without a recording is the screen's own`() {
        assertFalse(controller().onReset())
    }

    @Test
    fun `Reset on a saved completed scan's notice drops the held hand-off, so the live scan runs again`() {
        val c = recording()
        frames(3)
        host.export = complete
        c.onCompleted()
        c.save()
        assertTrue(c.holdsHandOff)
        assertFalse(c.onReset(), "the screen clears its map")
        assertFalse(c.holdsHandOff)
        assertFalse(c.holdsAnalysis)
        assertTrue(c.recAvailable)
        assertNotNull(c.notice)
        c.dismissNotice()
        assertFalse("releaseHandOff" in host.calls, "the cleared scan hands nothing off")
    }

    @Test
    fun `Close on a saved completed scan's notice keeps the hand-off held, so a cancelled discard returns to the notice`() {
        val c = recording()
        frames(3)
        host.export = complete
        c.onCompleted()
        c.save()
        assertFalse(c.onLeaving(QuickScanLeave.CLOSE))
        assertTrue(c.holdsHandOff)
        assertTrue(c.onCompleted(), "the completion hold asks again after the cancelled discard")
        // A leave that really goes elsewhere drops it.
        assertFalse(c.onLeaving(QuickScanLeave.FACE_BY_FACE))
        assertFalse(c.holdsHandOff)
    }

    // ---- Leaving (answer 6, W7) ----

    private fun leaving(kind: QuickScanLeave) {
        val c = recording()
        frames(2)
        assertFalse(c.onLeaving(kind), "the screen still acts on $kind")
        assertEquals(SessionStopReason.OPERATOR, (c.phase as SessionPhase.Stopped).reason)
        // The screen is left: no sheet; once gone for good, the temp dir is deleted unanswered.
        host.live = false
        assertFalse(c.sheetShown)
        assertEquals(1, tempDirs().size)
        c.release()
        assertTrue(tempDirs().isEmpty(), "$kind left a .tmp- dir")
        assertTrue(root.listFiles().orEmpty().none { it.isDirectory })
    }

    @Test
    fun `Close or Back stops the recording, and leaving with the sheet unanswered discards it`() = leaving(QuickScanLeave.CLOSE)

    @Test
    fun `Scan face by face stops the recording, and leaving with the sheet unanswered discards it`() = leaving(QuickScanLeave.FACE_BY_FACE)

    @Test
    fun `Enter colors instead stops the recording, and leaving with the sheet unanswered discards it`() =
        leaving(QuickScanLeave.ENTER_COLORS)

    @Test
    fun `Review and Correct stops the recording, and Back returns to the sheet as it was`() {
        val c = recording()
        frames(2)
        c.updateFields { it.copy(cube = "stkb") }
        assertFalse(c.onLeaving(QuickScanLeave.REVIEW_AND_CORRECT))
        host.live = false
        assertFalse(c.sheetShown)
        host.live = true
        assertTrue(c.sheetShown)
        assertEquals("stkb", c.fields.cube)
        assertEquals("Stopped: operator", QuickScanSessionController.stopLine((c.phase as SessionPhase.Stopped).reason))
    }

    @Test
    fun `the screen's own discard question hides the sheet until it is answered`() {
        val c = recording()
        c.onLeaving(QuickScanLeave.CLOSE)
        host.dialog = true
        assertFalse(c.sheetShown)
        host.dialog = false
        assertTrue(c.sheetShown)
    }

    @Test
    fun `backgrounding stops the recording and the sheet shows on return`() {
        val c = recording()
        frames(2)
        assertTrue(c.onBackground())
        assertEquals("Stopped: operator", QuickScanSessionController.stopLine((c.phase as SessionPhase.Stopped).reason))
        assertTrue(c.sheetShown)
        assertFalse(controller().onBackground())
    }

    @Test
    fun `the detector becoming unavailable stops and discards the recording`() {
        val c = recording()
        frames(2)
        assertTrue(c.onDetectorUnavailable())
        assertEquals(SessionPhase.Idle, c.phase)
        assertTrue(tempDirs().isEmpty())
        assertTrue(root.listFiles().orEmpty().none { it.isDirectory })
    }

    // ---- Baseline (answers 8, 14) ----

    private fun baselineRun(): QuickScanSessionController {
        val c = recording()
        frames(3)
        host.export = complete
        c.onCompleted()
        c.runBaseline()
        assertTrue(c.phase is SessionPhase.Baseline)
        assertNotNull(host.baselineTap)
        return c
    }

    private fun timeBaseline(frames: Int) {
        val tap = checkNotNull(host.baselineTap)
        repeat(frames) { tap.frameDone(it * 33_000_000L, (20 + it % 3) * 1_000_000L, 90, 640, 480, 0, 0, 640, 480) }
    }

    @Test
    fun `the baseline run stores a per-device p50 and p95 and the pending session comes back with its sheet and hold`() {
        val c = baselineRun()
        assertFalse(c.sheetShown)
        // The run analyses live, even though the pending completed session holds its hand-off.
        assertFalse(c.holdsAnalysis)
        assertTrue(c.holdsHandOff)
        assertTrue(c.locksActions)
        assertEquals("● Baseline 30 s", QuickScanSessionController.baselineIndicator(c.baselineSecondsLeft))
        // Reset, Scan face by face, Review and Correct and Enter colors instead are disabled during it.
        assertTrue(c.onReset())
        assertTrue(c.onLeaving(QuickScanLeave.FACE_BY_FACE))
        assertTrue(c.onLeaving(QuickScanLeave.REVIEW_AND_CORRECT))
        assertTrue(c.onLeaving(QuickScanLeave.ENTER_COLORS))
        assertTrue(c.phase is SessionPhase.Baseline)
        timeBaseline(30)
        nowMs += 10_000
        c.tick()
        assertEquals("● Baseline 20 s", QuickScanSessionController.baselineIndicator(c.baselineSecondsLeft))
        assertTrue(c.phase is SessionPhase.Baseline)
        nowMs += 20_000
        c.tick()
        assertEquals("endBaseline", host.calls.last())
        val back = c.phase as SessionPhase.Stopped
        assertSame(writers.made.single(), back.capture)
        assertEquals(SessionStopReason.COMPLETE, back.reason)
        assertTrue(c.holdsHandOff)
        assertTrue(c.holdsAnalysis)
        assertFalse(c.locksActions)
        val stored = checkNotNull(writers.baseline("TB128XU"))
        assertEquals(21.0, stored.p50Ms)
        assertEquals(22.0, stored.p95Ms)
        assertEquals("Baseline p50 21.0 ms", QuickScanSessionController.baselineLine(c.baseline))
        // Saved now, the session is judged against it.
        c.save()
        assertEquals(21.0, ContinuousSessionJson.readManifest(File(checkNotNull(c.notice).saved.path)).latencyBaselineP50Ms)
    }

    private fun interrupted(stop: (QuickScanSessionController) -> Boolean) {
        val c = baselineRun()
        timeBaseline(10)
        stop(c)
        assertEquals("endBaseline", host.calls.last())
        assertNull(writers.baseline("TB128XU"), "an interrupted run stores nothing")
    }

    @Test
    fun `Close during the baseline run stops it without storing a value, then follows the leave`() =
        interrupted { c ->
            val handled = c.onLeaving(QuickScanLeave.CLOSE)
            assertFalse(handled)
            assertTrue(c.phase is SessionPhase.Stopped)
            handled
        }

    @Test
    fun `backgrounding during the baseline run stops it without storing a value`() =
        interrupted { c ->
            c.onBackground().also { assertTrue(c.phase is SessionPhase.Stopped) }
        }

    @Test
    fun `a detector failure during the baseline run stores nothing and discards the pending session`() =
        interrupted { c ->
            c.onDetectorUnavailable().also {
                assertEquals(SessionPhase.Idle, c.phase)
                assertTrue(tempDirs().isEmpty())
            }
        }

    @Test
    fun `the baseline run's completion never hands off`() {
        val c = baselineRun()
        assertTrue(c.onCompleted())
        assertTrue(c.phase is SessionPhase.Baseline)
    }

    // ---- Labeller (answer 15) ----

    @Test
    fun `labels are written beside the predictions through StickerTruthCheck, and closing returns to the notice`() {
        val c = recording()
        frames(3)
        host.export = complete
        c.stop()
        c.save()
        c.openLabeller()
        c.saveLabels()
        assertEquals("Save locked: tap the 54 pre-filled stickers not checked yet", checkNotNull(c.labeller).message)
        val tapped = checkNotNull(c.labeller).faces.map { face -> (0 until 9).fold(face) { f, i -> f.tap(i) } }
        c.updateLabeller(checkNotNull(c.labeller).copy(faces = tapped))
        c.saveLabels()
        assertNull(c.labeller)
        val notice = checkNotNull(c.notice)
        assertEquals("Labels saved · 9 of each colour.", notice.labelStatus)
        val labels = checkNotNull(ContinuousSessionJson.readLabels(File(notice.saved.path)))
        assertEquals(SessionCountCheck.PASSED, labels.countCheck)
        assertFalse("releaseHandOff" in host.calls)
    }

    @Test
    fun `a refused count offers Override with a reason or Keep as refused`() {
        val c = recording()
        frames(3)
        host.export = complete
        c.stop()
        c.save()
        c.openLabeller()
        val tapped = checkNotNull(c.labeller).faces.map { face -> (0 until 9).fold(face) { f, i -> f.tap(i) } }.toMutableList()
        tapped[0] = tapped[0].tap(0).set(null)
        c.updateLabeller(checkNotNull(c.labeller).copy(faces = tapped))
        c.saveLabels()
        val refusal = checkNotNull(checkNotNull(c.labeller).refusal)
        assertEquals("Not saved: White 8 (under) · 1 unknown", truthRefusal(refusal))
        c.writeLabels("logo sticker")
        val labels = checkNotNull(ContinuousSessionJson.readLabels(File(checkNotNull(c.notice).saved.path)))
        assertEquals(SessionCountCheck.OVERRIDDEN, labels.countCheck)
        assertEquals("logo sticker", labels.overrideReason)
        assertNull(labels.stickers[0].color)
        c.openLabeller()
        c.updateLabeller(checkNotNull(c.labeller).copy(faces = tapped))
        c.writeLabels(null)
        assertEquals(SessionCountCheck.REFUSED, ContinuousSessionJson.readLabels(File(checkNotNull(c.notice).saved.path))?.countCheck)
        assertEquals("Labels kept as refused; tuning and scoring skip them.", checkNotNull(c.notice).labelStatus)
    }

    // ---- adb (answers 12, 16, W8) ----

    @Test
    fun `adb commands mirror the controls, unavailable ones are ignored and logged, save while recording stops first`() {
        val c = controller()
        c.command(QuickScanSessionCommand("save"))
        c.command(QuickScanSessionCommand("baseline"))
        c.command(QuickScanSessionCommand("stop"))
        assertEquals(SessionPhase.Idle, c.phase)
        assertEquals(listOf("save ignored", "baseline ignored", "stop ignored"), logs.map { it.substringBefore(":") })
        c.command(QuickScanSessionCommand("start", cube = "stlb", lighting = "low", partition = "tuning", label = "Run 2"))
        assertTrue(c.phase is SessionPhase.Recording)
        assertEquals("● REC 0 · 0 skips", QuickScanSessionController.recIndicator(c.status))
        assertEquals(SessionFields("stlb", "low", SessionPartition.TUNING), c.fields)
        frames(2)
        c.command(QuickScanSessionCommand("start"))
        assertTrue(logs.last().startsWith("start ignored"))
        c.command(QuickScanSessionCommand("save", lighting = "backlit"))
        val saved = checkNotNull(c.notice).saved
        assertEquals("20261126-091502-tb128xu-run-2", saved.sessionId)
        val m = ContinuousSessionJson.readManifest(File(saved.path))
        assertEquals("stlb", m.cube)
        assertEquals("backlit", m.lighting)
        assertEquals(SessionPartition.TUNING, m.partition)
        assertEquals(SessionStopReason.OPERATOR, m.stopReason)
    }

    @Test
    fun `adb discard while recording stops first, then discards, adb start dismisses the notice`() {
        val c = controller()
        c.command(QuickScanSessionCommand("start"))
        frames(2)
        c.command(QuickScanSessionCommand("discard"))
        assertEquals(SessionPhase.Idle, c.phase)
        assertTrue(root.listFiles().orEmpty().none { it.isDirectory })
        c.command(QuickScanSessionCommand("start"))
        frames(1)
        c.command(QuickScanSessionCommand("stop"))
        assertTrue(c.sheetShown)
        c.command(QuickScanSessionCommand("save"))
        assertNotNull(c.notice)
        c.command(QuickScanSessionCommand("start"))
        assertNull(c.notice)
        assertTrue(c.phase is SessionPhase.Recording)
        // Without the live scan (e.g. in a review) adb start is ignored.
        c.command(QuickScanSessionCommand("stop"))
        c.command(QuickScanSessionCommand("save"))
        host.live = false
        c.command(QuickScanSessionCommand("start"))
        assertTrue(logs.last().startsWith("start ignored"))
        c.command(QuickScanSessionCommand("rewind"))
        assertEquals("unknown command rewind", logs.last())
    }

    @Test
    fun `adb baseline runs from the sheet only`() {
        val c = controller()
        c.command(QuickScanSessionCommand("start"))
        c.command(QuickScanSessionCommand("baseline"))
        assertTrue(c.phase is SessionPhase.Recording)
        c.command(QuickScanSessionCommand("stop"))
        c.command(QuickScanSessionCommand("baseline"))
        assertTrue(c.phase is SessionPhase.Baseline)
    }

    @Test
    fun `release discards an unsaved session and keeps a saved one`() {
        val c = recording()
        frames(2)
        c.stop()
        c.save()
        val saved = checkNotNull(c.notice).saved
        c.release()
        assertTrue(File(saved.path).isDirectory)
        assertTrue(ContinuousSessionFormat.isComplete(File(saved.path)))
    }

    @Test
    fun `the baseline timing tap records analyzer times without capture`() {
        val tap = BaselineTimingTap(capacity = 2)
        assertNull(tap.modelInputBuffer(0))
        repeat(3) { tap.frameDone(0, (it + 1) * 1_000_000L, 0, 0, 0, 0, 0, 0, 0) }
        assertEquals(listOf(1.0, 2.0), tap.samplesMs())
        assertEquals(ContinuousLatencyBaseline("x", 1, 1.0, 1.0), ContinuousLatencyBaseline("x", 1, 1.0, 1.0))
    }
}
