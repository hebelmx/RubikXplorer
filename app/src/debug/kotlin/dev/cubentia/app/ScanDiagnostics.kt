package dev.cubentia.app

import android.os.Build
import android.os.SystemClock
import android.util.Log
import dev.cubentia.application.usecase.Recognition
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.FrameInspection
import dev.cubentia.domain.recognition.FrameQuality
import dev.cubentia.domain.recognition.FrameStats
import dev.cubentia.domain.recognition.PhysicalObservation
import dev.cubentia.domain.recognition.ScanAssembler
import dev.cubentia.domain.recognition.ScanReview
import dev.cubentia.domain.recognition.ScanTrace
import dev.cubentia.domain.recognition.SelfCalibration
import dev.cubentia.domain.recognition.continuous.ContinuousScanSnapshot
import dev.cubentia.domain.recognition.continuous.UnreadSlot
import dev.cubentia.domain.tutor.Certainty
import dev.cubentia.domain.tutor.PlacementOutcome
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * debug build (device session B2): when a frame with nine readable cells is not ACCEPTABLE for auto-capture, logs which
 * [FrameQuality] check failed and every measured value against its threshold under the [TAG] tag, at most one line per
 * [INTERVAL_MS], with the located face lattice (Story 10.14): its scale of the grid's side, center offset in grid cells
 * (frame axes) and search score, the face edges found and any framing coached. It also logs an ACCEPTABLE frame accepted
 * only as an outlined face, which waits the longer [dev.cubentia.domain.recognition.GuidedScanController.OUTLINED_STEADY_MS]
 * hold (PO 2026-11-16); `path=` names the path that accepts the seam check: `seams`, `outlined` or `-`. Numbers only, never pixels. Release and benchmark builds get a no-op.
 *
 * Story 10.7 device-fix round 1 (PO 2026-11-17): [onAssembly] logs one `assembly round=…` line per 6-face scan
 * assembly ([ScanTrace.describe]: branch, valid-state count, involved faces, chosen turns, each sample's read), and
 * [onQuickCheck] one `quickCheck …` line per applied result (54-letter observed vs expected, and the Certainty).
 */
object ScanDiagnostics {
    /** Log powers of two so a stream of bad frames cannot fill the log buffer. */
    fun onQuickScanFrameFailed(failedFrames: Int) {
        if (failedFrames > 0 && failedFrames.countOneBits() == 1) {
            Log.d(TAG, listOf("quickScan", "frameFailed", "count=$failedFrames").joinToString(" "))
        }
    }

    /** The bound camera's flash unit and the torch state applied. */
    fun onTorch(
        hasFlash: Boolean,
        torch: Boolean,
    ) {
        Log.d(TAG, listOf("quickScan", "torch", "hasFlash=$hasFlash", "torch=${if (torch) "on" else "off"}").joinToString(" "))
    }

    private val device = Build.MODEL?.replace(Regex("[^A-Za-z0-9-]"), "-")?.ifEmpty { "-" }

    /** GH #406: debug builds time the continuous Quick Scan's stages (`ContinuousStageTimings`); release creates no accumulator. */
    const val QUICK_SCAN_TIMINGS = true

    /**
     * GH #406: one `quickScan timing window …` line per timing window (at most one per 2 s of analysis), logged from the
     * analyzer thread: per-stage p50/p90/max in ms over the window's frames. Numbers and fixed words only.
     */
    fun onQuickScanTimingWindow(body: String) {
        if (device == null) return
        Log.d(TAG, listOf("quickScan", "timing", "window", body).joinToString(" "))
    }

    /**
     * GH #406: the `quickScan timing run … device=…` line over the whole continuous engine's life, logged immediately
     * before the `quickScan run` line it belongs to (`evaluateQuickScan` attaches it to that run); a null [body] (no
     * frame analysed) logs nothing.
     */
    fun onQuickScanTimingRun(body: String?) {
        if (body == null) return
        val model = device ?: return
        Log.d(TAG, listOf("quickScan", "timing", "run", body, "device=$model").joinToString(" "))
    }

    private fun run(
        feature: String,
        vararg fields: String,
    ) {
        val model = device ?: return
        Log.d(TAG, (listOf(feature, "run") + fields + "device=$model").joinToString(" "))
    }

    private fun letters(colors: List<Color?>?): String = colors?.joinToString("", transform = ScanTrace::letter) ?: "-"

    private fun elapsed(start: Long?): String = start?.let { (SystemClock.elapsedRealtime() - it).toString() } ?: "-"

    fun startRun(): Long? = device?.let { SystemClock.elapsedRealtime() }

    /**
     * One continuous Quick Scan run, logged once when its screen finishes (Story 10.32), in the format
     * `evaluateQuickScan` parses: the time since the screen opened, how many faces the Guided hand-over was asked for
     * ([fallbackFaces]; 0 when *Scan face by face* was not used), no re-prompts (the hand-over never re-prompts), the
     * review corrections that changed a sticker, and the outcome. A scan that ends from an early review (*Review and
     * Correct*: that review, or a retake or calibration opened from it) adds `earlyReview=1` after the outcome, so it is
     * told from a scan the camera finished; every other line is unchanged. Numbers and fixed words only: no frame,
     * pixel or sticker data.
     */
    fun onQuickScanRun(
        started: Long?,
        fallbackFaces: Int,
        corrections: Map<Int, Color>?,
        initial: List<Color?>?,
        outcome: String,
        earlyReview: Boolean = false,
    ) {
        val changed = corrections?.count { (index, color) -> color != initial?.get(index) } ?: 0
        run(
            "quickScan",
            "elapsedMs=${elapsed(started)}",
            "fallbackFaces=$fallbackFaces",
            "repromptFaces=0",
            "corrections=$changed",
            "outcome=$outcome",
            *listOfNotNull("earlyReview=1".takeIf { earlyReview }).toTypedArray(),
        )
    }

    fun onGuidedScanRun(
        started: Long?,
        repromptFaces: Int,
        review: ScanReview?,
        initial: List<Color?>?,
        corrections: Map<Int, Color>?,
        outcome: String,
    ) {
        val changed = corrections?.count { (index, color) -> color != initial?.get(index) } ?: 0
        run(
            "guidedScan",
            "elapsedMs=${elapsed(started)}",
            "repromptFaces=$repromptFaces",
            "corrections=$changed",
            "valid=${review?.accepted != null}",
            "outcome=$outcome",
            "state=${letters(review?.stickers)}",
            "initial=${letters(initial)}",
        )
    }

    private data class CheckCapture(
        val captures: Long,
        val elapsedMs: String,
    )

    @Volatile private var quickCheckCapture: CheckCapture? = null

    @Volatile private var caseCapturedAt: Long? = null

    @Volatile private var placementStartedAt: Long? = null

    private data class PlacementSuggestion(
        val elapsedMs: String,
        val outcome: String,
        val stage: String,
    )

    private var placementSuggestion: PlacementSuggestion? = null

    fun onQuickCheckCaptured(
        started: Long?,
        captures: Long,
    ) {
        quickCheckCapture = CheckCapture(captures, elapsed(started))
    }

    fun discardQuickCheckCapture() {
        quickCheckCapture = null
    }

    fun onQuickCheckRun(
        expected: CubeState,
        observation: PhysicalObservation?,
        certainty: Certainty?,
        matchesExpected: Boolean?,
    ) {
        val capture = quickCheckCapture
        discardQuickCheckCapture()
        val observed =
            when (observation) {
                null -> {
                    "-"
                }

                is PhysicalObservation.Full -> {
                    letters(observation.state.reoriented(Orientation.HOME).toFacelets())
                }

                is PhysicalObservation.Partial -> {
                    observation.faces.entries
                        .joinToString(",") { (face, colors) -> "$face:${letters(colors)}" }
                        .ifEmpty { "-" }
                }
            }
        run(
            "quickCheck",
            "kind=${observation?.let { it::class.simpleName } ?: "-"}",
            "captures=${capture?.captures ?: "-"}",
            "elapsedMs=${capture?.elapsedMs ?: "-"}",
            "certainty=${if (observation == null) "CANCELLED" else certainty ?: "-"}",
            "matches=${matchesExpected ?: "-"}",
            "observed=$observed",
            "expected=${letters(expected.toFacelets())}",
            "expectedHome=${letters(expected.reoriented(Orientation.HOME).toFacelets())}",
        )
    }

    fun onCaseCaptured() {
        caseCapturedAt = startRun()
    }

    fun discardCaseCapture() {
        caseCapturedAt = null
    }

    fun onRecognizeRun(
        result: Recognition?,
        captures: Int?,
        captureOutcome: String = "-",
    ) {
        val start = caseCapturedAt
        discardCaseCapture()
        val outcome =
            when (result) {
                is Recognition.Unique -> "UNIQUE"
                is Recognition.Several -> "SEVERAL"
                Recognition.NoFit -> "NO_FIT"
                Recognition.LastLayerSolved -> "LL_SOLVED"
                Recognition.NotAtLastLayer -> "NOT_AT_LL"
                null -> captureOutcome
            }
        val case = (result as? Recognition.Unique)?.caseId?.value ?: "-"
        val candidates =
            (result as? Recognition.Several)
                ?.caseIds
                ?.map { it.value }
                ?.sorted()
                ?.joinToString(",") ?: "-"
        run(
            "recognize",
            "elapsedMs=${elapsed(start.takeIf { result != null })}",
            "outcome=$outcome",
            "case=$case",
            "candidates=$candidates",
            "captures=${captures ?: "-"}",
        )
    }

    fun onPlacementScanAccepted() {
        onPlacementRun(null)
        placementStartedAt = startRun()
    }

    fun onPlacementSuggested(outcome: PlacementOutcome) {
        val started = placementStartedAt ?: return
        placementStartedAt = null
        val (kind, stage) =
            when (outcome) {
                PlacementOutcome.Solved -> "SOLVED" to "SOLVED"
                is PlacementOutcome.NearlySolved -> "NEARLY_SOLVED" to "NEARLY_SOLVED"
                is PlacementOutcome.NothingSolved -> "NOTHING_SOLVED" to outcome.stage.id.value
                is PlacementOutcome.NonWhite -> "NON_WHITE" to outcome.stage.id.value
                is PlacementOutcome.PartlySolved -> "PARTLY_SOLVED" to outcome.stage.id.value
                is PlacementOutcome.AtLastLayer -> "AT_LAST_LAYER" to outcome.stage.id.value
            }
        placementSuggestion = PlacementSuggestion(elapsed(started), kind, stage)
    }

    fun onPlacementRun(accepted: String?) {
        placementStartedAt = null
        val (time, outcome, stage) = placementSuggestion ?: return
        placementSuggestion = null
        run(
            "placement",
            "elapsedMs=$time",
            "outcome=$outcome",
            "suggested=$stage",
            "accepted=${accepted ?: "none"}",
        )
    }

    const val TAG = "CubentiaScanDiag"
    private const val INTERVAL_MS = 500L

    private val lastLogMs = AtomicLong(Long.MIN_VALUE / 2)

    fun onFrame(
        stats: FrameStats,
        nowMs: Long,
        latticeScale: Double,
        latticeDx: Double,
        latticeDy: Double,
        latticeScore: Double,
    ) {
        val inspection = FrameQuality.inspect(stats)
        if ((inspection.failed == null && !inspection.outlinedOnly) || inspection.readableCells != FrameQuality.CELLS) return
        val last = lastLogMs.get()
        if (nowMs - last < INTERVAL_MS || !lastLogMs.compareAndSet(last, nowMs)) return
        val lattice =
            listOf(
                "lattice=scale${format(latticeScale, 2)}/dx${format(latticeDx, 2)}/dy${format(latticeDy, 2)}/score${format(latticeScore)}",
                "faceEdges=${stats.faceEdges}/${FrameQuality.FACE_EDGES}",
                "framing=${stats.framing ?: "-"}",
            )
        Log.d(TAG, (listOf(describe(inspection)) + lattice).joinToString(","))
    }

    private const val UNREAD_INTERVAL_MS = 1000L

    private val unreadLock = Any()

    private var unreadLastMs = Long.MIN_VALUE / 2

    private var unreadLastBody: String? = null

    /** Test hook: forget the last `quickScan unread` line so each test starts ungated. */
    internal fun resetQuickScanUnread() =
        synchronized(unreadLock) {
            unreadLastMs = Long.MIN_VALUE / 2
            unreadLastBody = null
        }

    /**
     * GH #405: while a continuous Quick Scan is stalled with all six sides mapped (an anchored [ContinuousScanSnapshot.Scanning]
     * with `mappedFacesCount == 6`), logs one `quickScan unread …` line: per-kind counts of the unread non-centre slots
     * and, per slot, why it is unread with its numbers against the solve's own thresholds ([UnreadSlot] constants). A
     * line repeating the last logged one (frame aside) is suppressed, and so is any line within [UNREAD_INTERVAL_MS] of
     * the last logged or suppressed repeat; [unread] is called at most once per [UNREAD_INTERVAL_MS], never for a frame
     * the gate rejects. Numbers and fixed words only.
     */
    fun onQuickScanUnread(
        nowMs: Long,
        snapshot: ContinuousScanSnapshot,
        unread: () -> List<UnreadSlot>,
    ) {
        if (snapshot !is ContinuousScanSnapshot.Scanning || !snapshot.globalAnchored || snapshot.mappedFacesCount != 6) return
        synchronized(unreadLock) {
            if (nowMs - unreadLastMs < UNREAD_INTERVAL_MS) return
            val slots = unread()
            if (slots.isEmpty()) return
            val body =
                listOf(
                    "committed=${snapshot.committedCount}",
                    "notObserved=${slots.count { it is UnreadSlot.NotObserved }}",
                    "notReady=${slots.count { it is UnreadSlot.NotReady }}",
                    "belowMargin=${slots.count { it is UnreadSlot.BelowMargin }}",
                    "aboveDistance=${slots.count { it is UnreadSlot.AboveDistance }}",
                    "reopened=${slots.count { it is UnreadSlot.Reopened }}",
                    "slots=${slots.joinToString(",", transform = ::describeUnread)}",
                ).joinToString(" ")
            unreadLastMs = nowMs
            if (body == unreadLastBody) return
            unreadLastBody = body
            Log.d(TAG, listOf("quickScan", "unread", "frame=${snapshot.frameIndex}", body).joinToString(" "))
        }
    }

    private fun describeUnread(u: UnreadSlot): String {
        val why =
            when (u) {
                is UnreadSlot.NotObserved -> {
                    "notObserved"
                }

                is UnreadSlot.NotReady -> {
                    "notReady(q${format(u.qualitySum, 2)}/min${threshold(UnreadSlot.MIN_READY_QUALITY_SUM)}," +
                        "n${u.count}/min${UnreadSlot.MIN_READY_COUNT})"
                }

                is UnreadSlot.BelowMargin -> {
                    "belowMargin(d${format(u.bestD)}/max${threshold(UnreadSlot.MAX_PER_CELL_DISTANCE)}," +
                        "m${format(u.margin)}/min${threshold(UnreadSlot.MIN_PER_CELL_MARGIN)})"
                }

                is UnreadSlot.AboveDistance -> {
                    "aboveDistance(d${format(u.chosenD)}/max${threshold(UnreadSlot.MAX_BALANCED_DISTANCE)})"
                }

                is UnreadSlot.Reopened -> {
                    "reopened"
                }
            }
        return "${u.slot.face}${u.slot.cellIndex}:$why"
    }

    /** A threshold as written in the solve: `36` for 36.0, `0.55` for 0.55. */
    private fun threshold(value: Double): String = if (value == Math.rint(value)) value.toLong().toString() else value.toString()

    /**
     * Logs how [samples] assemble at [round] (`first`: the first round, which decides the re-prompt; `final`: what review
     * gets), calibrated as the scan is (self-calibration over [saved]).
     */
    fun onAssembly(
        round: String,
        samples: List<FaceSample>,
        saved: CalibrationCenters?,
    ) {
        val line =
            when (val calibration = SelfCalibration.from(samples, saved)) {
                is SelfCalibration.Calibrated -> ScanAssembler.trace(samples, calibration.calibration).describe()
                is SelfCalibration.NeedsCalibration -> "branch=NEEDS_CALIBRATION"
            }
        Log.d(TAG, listOf("assembly", "round=$round", "samples=${samples.size}", line).joinToString(","))
    }

    /** Logs a Quick Check result against [expected]: the observation's colors (54 for Full, per face for Partial) and the [certainty]. */
    fun onQuickCheck(
        expected: CubeState,
        observation: PhysicalObservation,
        certainty: Certainty?,
    ) {
        val seen =
            when (observation) {
                is PhysicalObservation.Full -> {
                    "observed=${letters(observation.state.toFacelets())}"
                }

                is PhysicalObservation.Partial -> {
                    "observedFaces=" +
                        observation.faces.entries.joinToString(",") { (face, colors) ->
                            "$face:${colors.joinToString("") { ScanTrace.letter(it) }}"
                        }
                }
            }
        val home = expected.reoriented(Orientation.HOME).toFacelets()
        val fields =
            listOf(
                "quickCheck",
                "kind=${observation::class.simpleName}",
                seen,
                "expected=${letters(expected.toFacelets())}",
                "expectedHome=${letters(home)}",
                "certainty=${certainty ?: "-"}",
            )
        Log.d(TAG, fields.joinToString(","))
    }

    private fun letters(facelets: Facelets): String = facelets.colors.joinToString("") { ScanTrace.letter(it) }

    /** One line of `key=value` fields, comma-separated; `value/minN` or `value/maxN` shows the threshold. */
    private fun describe(inspection: FrameInspection): String =
        with(inspection) {
            listOf(
                "failed=$failed",
                "quality=$quality",
                "readable=$readableCells/${FrameQuality.CELLS}",
                "plainOuter=$plainOuterCells/min${FrameQuality.MIN_PLAIN_OUTER_CELLS}",
                "seams=$seams/min${FrameQuality.MIN_SEAMS}",
                "path=${if (seams >= FrameQuality.MIN_SEAMS) {
                    "seams"
                } else if (outlinedOnly) {
                    "outlined"
                } else {
                    "-"
                }}",
                "stickerCells=$stickerCells/min${FrameQuality.MIN_STICKER_CELLS}",
                "stickerIf=C>=${FrameQuality.STICKER_MIN_CHROMA}|L>=${FrameQuality.WHITE_STICKER_MIN_L}",
                "glare=${format(glareFraction, 3)}/max${FrameQuality.MAX_GLARE_FRACTION}",
                "sharpness=${format(sharpness)}/min${FrameQuality.MIN_SHARPNESS}",
                "luma=${format(meanLuma)}/min${FrameQuality.MIN_MEAN_LUMA}",
            ).plus(chroma.indices.map { "c$it=C${format(chroma[it])}/L${format(lightness[it])}" })
                .joinToString(",")
        }

    private fun format(
        value: Double?,
        decimals: Int = 1,
    ): String = value?.let { String.format(Locale.ROOT, "%.${decimals}f", it) } ?: "-"
}
