package dev.cubentia.app

import android.os.Build
import android.os.SystemClock
import android.util.Log
import dev.cubentia.application.usecase.Recognition
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.CornerCaptureReport
import dev.cubentia.domain.recognition.CornerHoldEvent
import dev.cubentia.domain.recognition.CornerHoldEventKind
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.FrameInspection
import dev.cubentia.domain.recognition.FrameQuality
import dev.cubentia.domain.recognition.FrameStats
import dev.cubentia.domain.recognition.PhysicalObservation
import dev.cubentia.domain.recognition.QuickScanController
import dev.cubentia.domain.recognition.QuickScanDiagnosis
import dev.cubentia.domain.recognition.ScanAssembler
import dev.cubentia.domain.recognition.ScanReview
import dev.cubentia.domain.recognition.ScanTrace
import dev.cubentia.domain.recognition.SelfCalibration
import dev.cubentia.domain.recognition.SideLight
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
    private val lastCornerLogMs = AtomicLong(Long.MIN_VALUE / 2)

    /**
     * One corner frame (rate-limited): detection, per-face shutter quality, and Story 10.19's light check (per-face light
     * level, the darker side face's difference to the top, the verdict and whether it is coached (lightHold);
     * lightMode=torch when the torch is lit and the check skipped), the torch and glare with the torch state. Numbers
     * only, never pixels or Cube States.
     */
    fun onCornerFrame(
        nowMs: Long,
        detected: Boolean,
        faces: List<FrameStats?>,
        score: Double,
        light: SideLight.Verdict? = null,
        lightHeld: Boolean = false,
        torch: Boolean = false,
        centers: CalibrationCenters? = null,
    ) {
        // Story 10.21: a light state change inside the hysteresis band and a face's light level jumping between frames are
        // logged as they happen (the level lines at most once per interval), beside the rate-limited frame line.
        synchronized(lightTrace) {
            lightFlipLine(lightTrace.held, lightHeld, light)?.let { Log.d(TAG, it) }
            lightTrace.held = lightHeld
            if (light != null) {
                val lines = levelChangeLines(lightTrace.light, light, faces, centers)
                if (lines.isNotEmpty() && nowMs - lightTrace.levelLoggedMs >= INTERVAL_MS) {
                    lightTrace.levelLoggedMs = nowMs
                    lines.forEach { Log.d(TAG, it) }
                }
                lightTrace.light = light
            }
        }
        val last = lastCornerLogMs.get()
        if (nowMs - last < INTERVAL_MS || !lastCornerLogMs.compareAndSet(last, nowMs)) return
        Log.d(TAG, cornerFrameLine(detected, faces, score, light, lightHeld, torch))
    }

    /** The light state the last corner frame showed (Story 10.21), for the flip and level-change lines. */
    private class LightTrace {
        var held: Boolean? = null
        var light: SideLight.Verdict? = null
        var levelLoggedMs = Long.MIN_VALUE / 2
    }

    private val lightTrace = LightTrace()

    /**
     * Story 10.21: `quickScan light flip` when the light hold changed ([held] against [before]) on a frame whose score is
     * in the hysteresis band (between the mode's clear and hold cuts), where the hysteresis should keep it; else null.
     */
    internal fun lightFlipLine(
        before: Boolean?,
        held: Boolean,
        light: SideLight.Verdict?,
    ): String? {
        if (before == null || before == held || light?.inBand != true) return null
        val decimals = if (light.mode == SideLight.Mode.CALIBRATED) 3 else 1
        return listOf(
            "quickScan",
            "light",
            "flip",
            "held=$held",
            "lightScore=${format(light.score, decimals)}",
            "clearCut=${format(light.mode.clearCut, decimals)}",
            "holdCut=${format(light.mode.holdCut, decimals)}",
            "lightMode=${light.mode.name.lowercase()}",
        ).joinToString(" ")
    }

    /**
     * Story 10.21: one `quickScan light levelChange` line per face (top, left, right) whose light level moved by at least
     * [LEVEL_CHANGE_L] (uncalibrated L*) or [LEVEL_CHANGE_RATIO] (calibrated relative brightness) since [before], in the
     * same mode, with each sticker's nearest calibrated colour on chroma ([SideLight.nearestOnChroma], `-` without
     * [centers]). Colour letters and numbers only.
     */
    internal fun levelChangeLines(
        before: SideLight.Verdict?,
        now: SideLight.Verdict,
        faces: List<FrameStats?>,
        centers: CalibrationCenters?,
    ): List<String> {
        if (before == null || before.mode != now.mode) return emptyList()
        val calibrated = now.mode == SideLight.Mode.CALIBRATED
        val threshold = if (calibrated) LEVEL_CHANGE_RATIO else LEVEL_CHANGE_L
        val decimals = if (calibrated) 3 else 1
        val levels = listOf(before.topLevel to now.topLevel, before.leftLevel to now.leftLevel, before.rightLevel to now.rightLevel)
        return levels.mapIndexedNotNull { index, (from, to) ->
            if (from == null || to == null || kotlin.math.abs(to - from) < threshold) return@mapIndexedNotNull null
            val stickers =
                faces.getOrNull(index)?.cells.orEmpty().joinToString(",") { cell ->
                    val colour = cell?.value?.let { lab -> centers?.let { SideLight.nearestOnChroma(lab, it) } }
                    colour?.name?.take(1) ?: "-"
                }
            listOf(
                "quickScan",
                "light",
                "levelChange",
                "face=${FACE_NAMES[index]}",
                "from=${format(from, decimals)}",
                "to=${format(to, decimals)}",
                "lightMode=${now.mode.name.lowercase()}",
                "stickers=${stickers.ifEmpty { "-" }}",
            ).joinToString(" ")
        }
    }

    internal fun cornerFrameLine(
        detected: Boolean,
        faces: List<FrameStats?>,
        score: Double,
        light: SideLight.Verdict?,
        lightHeld: Boolean,
        torch: Boolean,
    ): String {
        val qualities = faces.map { it?.let(FrameQuality::assessShutter) }
        val glare = qualities.any { it == CaptureQuality.GLARE }

        val decimals = if (light?.mode == SideLight.Mode.CALIBRATED) 3 else 1

        fun level(value: Double?) = format(value, decimals)
        val verdict =
            when {
                light == null || light.score == null -> "-"
                light.passed -> "pass"
                else -> "fail"
            }
        val calibrated = light?.mode == SideLight.Mode.CALIBRATED
        return listOfNotNull(
            "quickScan",
            "frame",
            "detected=$detected",
            "quality=${qualities.joinToString(",") { it?.name ?: "-" }}",
            "score=${format(score)}",
            "light=${level(light?.topLevel)},${level(light?.leftLevel)},${level(light?.rightLevel)}",
            // Calibrated: the levels are relative brightness and only the ratio score decides (no L* difference).
            if (calibrated) null else "sideDiff=${level(light?.darkerSideDifference)}",
            "lightScore=${level(light?.score)}",
            "lightCheck=$verdict",
            // Story 10.21: a fail more than the hysteresis band's width above the hold cut.
            if (light?.farAboveCut == true) "lightTag=far-above-cut" else null,
            // PO 2026-11-21: with the torch lit the check is measured but skipped (it holds nothing).
            "lightMode=${if (light != null && torch) "torch" else light?.mode?.name?.lowercase() ?: "-"}",
            "lightHold=$lightHeld",
            "torch=${if (torch) "on" else "off"}",
            "glare=${if (!glare) {
                "-"
            } else if (torch) {
                "torch"
            } else {
                "plain"
            }}",
        ).joinToString(" ")
    }

    /**
     * One state change of a Quick Scan corner hold (Story 10.22), logged as it happens and never rate-limited: the hold,
     * the milliseconds since the hold began, and what changed (viewfinder entry, first frame, cube found or lost, pose
     * rejected or accepted, metering requested or settled, light fail or clear, steady start or restart, capture with
     * its trigger). Numbers and fixed words only, never pixels (AD-9).
     */
    fun onHoldEvent(event: CornerHoldEvent) {
        Log.d(TAG, holdEventLine(event))
        // Story 10.21: a hold beginning (its viewfinder, or the capture that ends the last) starts the light trace afresh,
        // and a clear made outside the check (torch lit, return from calibration) is not a flip on the next frame.
        when {
            event.kind == CornerHoldEventKind.VIEWFINDER || event.kind == CornerHoldEventKind.CAPTURE -> resetLightTrace()
            event.kind == CornerHoldEventKind.LIGHT_CLEAR && event.reason != null -> synchronized(lightTrace) { lightTrace.held = false }
        }
    }

    /** Forgets the last corner frame's light state (Story 10.21): a new run or hold compares against nothing. */
    private fun resetLightTrace() =
        synchronized(lightTrace) {
            lightTrace.held = null
            lightTrace.light = null
        }

    internal fun holdEventLine(event: CornerHoldEvent): String =
        listOfNotNull(
            "quickScan",
            event.kind.label,
            "hold=${event.hold}",
            "t=${event.elapsedMs}",
            event.reason?.let { "${if (event.kind == CornerHoldEventKind.CAPTURE) "trigger" else "reason"}=$it" },
            // Story 10.21: the few-confident wait's amount is a sticker count.
            event.amount?.let {
                if (event.reason == QuickScanController.POSE_FEW_CONFIDENT) "amount=${it.toInt()}" else "amount=${format(it, 3)}"
            },
            // Story 10.21 device session: the per-face counts the amount sums, and the count it is compared with.
            event.confident?.let { "perFace=${counts(it)}" },
            event.confident?.let { "need=${QuickScanController.MIN_CONFIDENT}" },
            event.timeout?.let { "timeout=$it" },
            event.durationMs?.let { "ms=$it" },
        ).joinToString(" ")

    /**
     * One line per Quick Scan corner capture (Story 10.19): the trigger (`auto` or `manual`), the hold's total time, its
     * time with the cube undetected, the time since the cube was first detected, and the light check at the moment of
     * capture (Story 10.22: score and `pass`, `fail` or `-` when unmeasured); then per face of the hold its centre
     * colour read, the CONFIDENT and UNCERTAIN sticker counts, whether it is kept or sent to the grid (`grid`), and why when sent (`missing`, the
     * shutter quality, `uncalibrated`, or the agreement-gate disagreements, close calls and a rejected centre correction).
     * Colour names and counts only, never pixels (AD-9).
     */
    fun onCornerCapture(report: CornerCaptureReport) {
        Log.d(TAG, cornerCaptureLine(report))
    }

    internal fun cornerCaptureLine(report: CornerCaptureReport): String =
        (
            listOf(
                "quickScan",
                "capture",
                "hold=${report.hold}",
                "trigger=${if (report.manual) "manual" else "auto"}",
                "totalMs=${report.totalMs}",
                "undetectedMs=${report.undetectedMs}",
                "sinceDetectedMs=${report.sinceDetectedMs ?: "-"}",
                "lightScore=${format(report.lightScore, 3)}",
                "light=${when (report.lightPassed) {
                    null -> "-"
                    true -> "pass"
                    false -> "fail"
                }}",
                // Story 10.21: whether the saved calibration matched this scan (`none` without one) and the lit torch.
                "calibration=${report.calibration?.label ?: "none"}",
                "torch=${if (report.torchLit) "lit" else "off"}",
            ) +
                report.faces.map { face ->
                    val reason =
                        when {
                            !face.grid -> {
                                "-"
                            }

                            face.quality == null -> {
                                "missing"
                            }

                            face.quality != CaptureQuality.ACCEPTABLE -> {
                                "quality=${face.quality}"
                            }

                            face.uncalibrated -> {
                                "uncalibrated"
                            }

                            else -> {
                                listOfNotNull(
                                    "disagree=${face.disagreements}",
                                    "closeCall=${face.closeCalls}",
                                    if (face.correctionRejected) "correction=rejected" else null,
                                ).joinToString("/")
                            }
                        }
                    "${face.face}:center=${face.center?.name ?: "-"},confident=${face.confident},uncertain=${face.uncertain}," +
                        "${if (face.grid) "grid" else "kept"},reason=$reason" +
                        (if (!face.grid && face.blanks > 0) ",blanks=${face.blanks}" else "")
                }
        ).joinToString(" ")

    /** Per face in [Face] order, `U:6,F:8`; `-` for none. */
    private fun counts(perFace: Map<Face, Int>): String =
        perFace.entries
            .sortedBy { it.key.ordinal }
            .joinToString(",") { (face, n) -> "$face:$n" }
            .ifEmpty { "-" }

    private var fewConfidentHold = 0
    private var fewConfidentLoggedMs = Long.MIN_VALUE / 2

    /**
     * Story 10.21 device session: a frame on which the few-confident wait held auto-capture, at most one line per
     * [INTERVAL_MS] per hold: the amount compared with the need, the per-face counts, and per face its quality, centre
     * read and why it is not CONFIDENT, the reference the reads used and why ([faceFields]).
     */
    fun onFewConfident(
        quick: QuickScanController,
        nowMs: Long,
    ) {
        if (!quick.fewConfident) return
        val count = quick.confidentCount ?: return
        synchronized(this) {
            if (count.hold == fewConfidentHold && nowMs - fewConfidentLoggedMs < INTERVAL_MS) return
            fewConfidentHold = count.hold
            fewConfidentLoggedMs = nowMs
        }
        Log.d(TAG, fewConfidentLine(count, quick.confidentDiagnoses()))
    }

    internal fun fewConfidentLine(
        count: QuickScanDiagnosis.ConfidentCount,
        faces: List<QuickScanDiagnosis.FaceDiagnosis>,
    ): String =
        (
            listOf(
                "quickScan",
                "fewConfident",
                "hold=${count.hold}",
                "amount=${count.amount}",
                "perFace=${counts(count.perFace)}",
                "need=${count.need}",
            ) + faces.map { face -> "${face.face}:" + faceFields(face).joinToString(",") }
        ).joinToString(" ")

    /**
     * Story 10.21 device session: after [onCornerCapture], per face of the capture one `quickScan faceRead` line (centre,
     * reference and decision, [faceReadLine]) and one `quickScan stickers` line (each sticker's two reads, [stickersLine]).
     */
    fun onCornerCaptureReads(quick: QuickScanController) {
        val hold = quick.lastCapture?.hold ?: return
        quick.captureDiagnoses().forEach { face ->
            Log.d(TAG, faceReadLine(hold, face))
            Log.d(TAG, stickersLine(hold, face))
        }
    }

    /**
     * One face's read as key=value fields: its shutter quality; its centre (`G?`: gated colour letter, `C` CONFIDENT or
     * `?` not, `-` unread), why the centre is not CONFIDENT (`guarded`: not plain, a logo or glare; `invalid`; `dark`;
     * `no-reference`: fewer than two centres to read against; `uncertain`; `-` when it is) and whether it is plain; the
     * reference the reads used (`saved-match`, `self`, `partial`, `none`) over which colours, and why: the saved
     * calibration's match (`none` without one), the guarded colours and those with no centre; and the CONFIDENT stickers
     * among all nine (`counted`, what the few-confident wait sums).
     */
    internal fun faceFields(face: QuickScanDiagnosis.FaceDiagnosis): List<String> =
        listOf(
            "quality=${face.quality ?: "-"}",
            "centre=${face.centre?.let(::readToken) ?: "-"}",
            "centreWhy=${face.centreIssue?.label ?: "-"}",
            "centrePlain=${face.centrePlain ?: "-"}",
            "ref=${face.reference.label}",
            "refColors=${colorLetters(face.referenceColors)}",
            "cal=${face.calibration?.label?.replace(' ', '-') ?: "none"}",
            "guarded=${colorLetters(face.guarded)}",
            "unresolved=${colorLetters(face.unresolved)}",
            "counted=${face.counted}",
        )

    /** `quickScan faceRead`: [faceFields], the judged CONFIDENT count, the decision (kept or grid, and why) and the blanks. */
    internal fun faceReadLine(
        hold: Int,
        face: QuickScanDiagnosis.FaceDiagnosis,
    ): String =
        (
            listOf("quickScan", "faceRead", "hold=$hold", "face=${face.face}") +
                faceFields(face) +
                listOf(
                    "confident=${face.confident}/${face.stickers.count { it.judged }}",
                    "decision=${if (face.gridReason == null) "kept" else "grid"}",
                    "gridWhy=${face.gridReason?.label ?: "-"}",
                    "blanks=${face.blanks.sorted().joinToString("/").ifEmpty { "-" }}",
                )
        ).joinToString(" ")

    /**
     * `quickScan stickers`: the nine stickers in viewfinder order, each `RC:14.2/R11.0`: the gated colour letter, `C`
     * CONFIDENT or `?` not, the first read's margin (ΔE to the second-nearest centre), the second read's colour letter and
     * margin; `~` marks a sticker not judged (a guarded centre), `-` one that could not be read.
     */
    internal fun stickersLine(
        hold: Int,
        face: QuickScanDiagnosis.FaceDiagnosis,
    ): String =
        listOf(
            "quickScan",
            "stickers",
            "hold=$hold",
            "face=${face.face}",
            "ref=${face.reference.label}",
            "s=${face.stickers.joinToString(",", transform = ::stickerToken)}",
        ).joinToString(" ")

    private fun readToken(sticker: QuickScanDiagnosis.StickerDiagnosis): String =
        sticker.color?.let { ScanTrace.letter(it) + if (sticker.confident) "C" else "?" } ?: "-"

    private fun stickerToken(sticker: QuickScanDiagnosis.StickerDiagnosis): String {
        val head = (if (sticker.judged) "" else "~") + readToken(sticker)
        if (sticker.color == null) return head
        return "$head:${format(sticker.firstMargin)}/${ScanTrace.letter(sticker.secondColor)}${format(sticker.secondMargin)}"
    }

    private fun colorLetters(colors: Set<Color>): String =
        colors.sortedBy { it.ordinal }.joinToString("") { ScanTrace.letter(it) }.ifEmpty { "-" }

    private fun faceList(faces: Collection<Face>): String = faces.joinToString(",").ifEmpty { "-" }

    /**
     * Story 10.21 device session: Quick Scan handed over (after the second corner or on *Scan face by face*): the faces
     * kept, those sent to the Guided grid with why, each kept face's blank positions (viewfinder order), whether it went
     * straight to review (`result=done`) and the calibration match.
     */
    fun onHandOver(report: QuickScanDiagnosis.HandOverReport?) {
        report?.let { Log.d(TAG, handOverLine(it)) }
    }

    internal fun handOverLine(report: QuickScanDiagnosis.HandOverReport): String =
        listOf(
            "quickScan",
            "handOver",
            "trigger=${report.trigger.label}",
            "result=${if (report.done) "done" else "fallback"}",
            "kept=${faceList(report.kept)}",
            "grid=${report.grid.entries.joinToString(",") { (face, why) -> "$face:${why.label}" }.ifEmpty { "-" }}",
            "blanks=${report.blanks.entries.joinToString(",") { (face, at) -> "$face:${at.sorted().joinToString("/")}" }.ifEmpty { "-" }}",
            "calibration=${report.calibration?.label?.replace(' ', '-') ?: "none"}",
        ).joinToString(" ")

    /**
     * Story 10.21 device session: one face captured in Quick Scan's face-by-face hand-over: which, the how-many-th, its
     * rotation and quality, the reference it reads against, and per sticker (frame order) the colour letter and `C`
     * CONFIDENT or `?` not.
     */
    fun onGuidedCapture(
        quick: QuickScanController,
        sample: FaceSample,
    ) {
        Log.d(TAG, guidedCaptureLine(quick.handOverRead(sample), quick.added.size))
    }

    internal fun guidedCaptureLine(
        read: QuickScanDiagnosis.GuidedFaceRead,
        number: Int,
    ): String =
        listOf(
            "guided",
            "capture",
            "face=${read.face}",
            "n=$number",
            "rot=${read.orientationDegrees}",
            "quality=${read.quality}",
            "ref=${read.reference.label}",
            "colours=${read.readings.joinToString("") { ScanTrace.letter(it?.color) }}",
            "confidence=${read.readings.joinToString("") { reading ->
                when (reading?.confidence) {
                    null -> "-"
                    dev.cubentia.domain.recognition.StickerConfidence.CONFIDENT -> "C"
                    dev.cubentia.domain.recognition.StickerConfidence.UNCERTAIN -> "?"
                }
            }}",
        ).joinToString(" ")

    /** Story 10.21 device session: the face-by-face hand-over ended ([outcome] `done` or `backOut`) with the faces kept and added. */
    fun onGuidedEnd(
        outcome: String,
        kept: Collection<Face>,
        added: Collection<Face>,
    ) {
        Log.d(TAG, guidedEndLine(outcome, kept, added))
    }

    internal fun guidedEndLine(
        outcome: String,
        kept: Collection<Face>,
        added: Collection<Face>,
    ): String =
        listOf(
            "guided",
            "end",
            "outcome=$outcome",
            "kept=${faceList(kept.sortedBy { it.ordinal })}",
            "added=${faceList(added)}",
        ).joinToString(" ")

    /** Quick Scan's calibration offer showed (Story 10.19): at corner [hold], after an uncalibrated light hold of [heldMs]. */
    fun onCalibrationOffer(
        hold: Int,
        heldMs: Long?,
    ) {
        Log.d(TAG, listOf("quickScan", "calibrationOffer", "show", "hold=$hold", "heldMs=${heldMs ?: "-"}").joinToString(" "))
    }

    /** A Quick Scan metering action: whether the camera supported it and whether it covers the cube outline or the grid centre. */
    fun onMetering(
        supported: Boolean,
        onOutline: Boolean,
    ) {
        Log.d(
            TAG,
            listOf("quickScan", "metering", "supported=$supported", "region=${if (onOutline) "outline" else "centre"}").joinToString(" "),
        )
    }

    /**
     * The settle signal after a metering change: how long it took, or that it hit the cap, and the exposure lock taken
     * then (Story 10.22: `on`, `on+awb` or `unsupported`; that is the lock asked for, [onExposureLockReleased] says
     * whether the camera took it). [futureMs] is the part of the wait the metering future took (`-` unknown).
     */
    fun onMeteringSettled(
        settleMs: Long,
        timeout: Boolean,
        lock: String,
        futureMs: Long? = null,
    ) {
        Log.d(
            TAG,
            listOf("quickScan", "metering", "settleMs=$settleMs", "timeout=$timeout", "lock=$lock", "futureMs=${futureMs ?: "-"}")
                .joinToString(" "),
        )
    }

    /**
     * An exposure lock ended (capture, re-meter, cube lost, leaving the corner view): [confirmed] when a capture result
     * reported AE LOCKED while it was wanted, and how long it was wanted (Story 10.22).
     */
    fun onExposureLockReleased(
        confirmed: Boolean,
        heldMs: Long?,
    ) {
        Log.d(TAG, listOf("quickScan", "exposureLock", "released", "confirmed=$confirmed", "heldMs=${heldMs ?: "-"}").joinToString(" "))
    }

    /** The bound camera's flash unit and the torch state applied. */
    fun onTorch(
        hasFlash: Boolean,
        torch: Boolean,
    ) {
        Log.d(TAG, listOf("quickScan", "torch", "hasFlash=$hasFlash", "torch=${if (torch) "on" else "off"}").joinToString(" "))
    }

    fun onQuickScanRun(
        started: Long?,
        fallbackFaces: Int,
        repromptFaces: Int,
        initialCorrections: Map<Int, Color>?,
        outcome: String,
        state: List<Color?>?,
        initial: List<Color?>?,
    ) {
        val corrections = initialCorrections?.count { (index, color) -> color != initial?.get(index) } ?: 0
        run(
            "quickScan",
            "elapsedMs=${elapsed(started)}",
            "fallbackFaces=$fallbackFaces",
            "repromptFaces=$repromptFaces",
            "corrections=$corrections",
            "outcome=$outcome",
            "state=${letters(state)}",
            "initial=${letters(initial)}",
        )
    }

    // The Android stubs used by plain JVM ViewModel tests provide no device model.
    private val device = Build.MODEL?.replace(Regex("[^A-Za-z0-9-]"), "-")?.ifEmpty { "-" }

    private fun run(
        feature: String,
        vararg fields: String,
    ) {
        val model = device ?: return
        Log.d(TAG, (listOf(feature, "run") + fields + "device=$model").joinToString(" "))
    }

    private fun letters(colors: List<Color?>?): String = colors?.joinToString("", transform = ScanTrace::letter) ?: "-"

    private fun elapsed(start: Long?): String = start?.let { (SystemClock.elapsedRealtime() - it).toString() } ?: "-"

    fun startRun(): Long? {
        resetLightTrace()
        return device?.let { SystemClock.elapsedRealtime() }
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

    /** Story 10.21: the light-level moves a `levelChange` line reports (uncalibrated L*, calibrated relative brightness). */
    private const val LEVEL_CHANGE_L = 5.0
    private const val LEVEL_CHANGE_RATIO = 0.05
    private val FACE_NAMES = listOf("top", "left", "right")
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
