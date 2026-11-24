package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face

/** The named top, front-left and front-right poses, in the order the adapter returns them. */
object QuickScanPlan {
    val holds =
        listOf(
            listOf(ScanStep(Color.WHITE, Color.BLUE), ScanStep(Color.GREEN, Color.WHITE), ScanStep(Color.RED, Color.WHITE)),
            listOf(ScanStep(Color.YELLOW, Color.RED), ScanStep(Color.ORANGE, Color.YELLOW), ScanStep(Color.BLUE, Color.YELLOW)),
        )
}

sealed interface QuickScanEffect {
    data object Remeter : QuickScanEffect

    data class CornerCaptured(
        val hold: Int,
    ) : QuickScanEffect

    /** The hand-over to the Guided viewfinder started: it asks [faces], in [GuidedScanPlan.steps] order. */
    data class Fallback(
        val faces: List<Face>,
    ) : QuickScanEffect

    /**
     * The scan is complete: [samples] go to review. [blanks] (Story 10.21) names, per face kept from a corner with a
     * matching calibration, the judged stickers (viewfinder order) that stayed uncertain: review leaves them blank and
     * pre-selects them.
     */
    data class Done(
        val samples: List<FaceSample>,
        val blanks: Map<Face, Set<Int>> = emptyMap(),
        /** Story 10.21: the calibration review reads with: the saved one only when the scan matched it, else null. */
        val calibration: CalibrationCenters? = null,
    ) : QuickScanEffect
}

/**
 * How Quick Scan reads a corner face once the frame is unwarped: every sticker against one calibration, by the shared
 * close-call rule, with no agreement gate. Since Story 10.19 the hand-over decision uses [QuickScanClassification]
 * (gated), and the side-face replay (`QuickScanSideFaceReplayTest`) measures that path, not this one.
 */
object QuickScanReadout {
    /**
     * The judged readings against the capture's own [centers] when no full calibration exists yet (Story 10.17): only
     * the colors seen compete, so a sticker is CONFIDENT only when it clearly matches one of them; null for an invalid
     * sticker or fewer than two centers.
     */
    fun judgedAgainst(
        sample: FaceSample,
        centers: Map<Color, Lab>,
    ): List<StickerReading?> =
        sample.stickers
            .map { sticker -> if (centers.size >= 2 && sticker.isValid()) StickerClassifier.read(sticker, centers) else null }
            .filterIndexed { index, _ -> index != FaceSample.CENTER_INDEX || sample.centerPlain != false }

    /** One reading per sticker, null where the classifier is unavailable. */
    fun read(
        sample: FaceSample,
        calibration: CalibrationCenters,
    ): List<StickerReading?> =
        sample.stickers.map { (StickerClassifier.classify(it, calibration) as? StickerClassification.Classified)?.reading }

    /** The readings the fallback decision judges: all but a guarded (not plain) center, which marks no face for the grid. */
    fun judged(
        sample: FaceSample,
        calibration: CalibrationCenters,
    ): List<StickerReading?> =
        read(sample, calibration).filterIndexed { index, _ -> index != FaceSample.CENTER_INDEX || sample.centerPlain != false }
}

/**
 * One analyzer-thread state machine over neutral readings and the Guided hand-over controller (Story 10.17).
 *
 * The hand-over has one path for both entries, the *Scan face by face* switch ([switchToFaceByFace]) and the automatic
 * hand-over after the second corner: the captured faces that pass [needGrid] are kept ([keptFaces]; once kept a face
 * stays kept until the scan ends), the other corner samples are dropped, and a Guided controller asks only the other
 * faces in [GuidedScanPlan.steps] order, any rotation, never a named top and never a second round. Its Done is
 * [QuickScanEffect.Done] over [kept] + [added]: the kept samples keep their captured (named-top) pose, so
 * `ScanAssembler` places them once and searches each new face's quarter turns. [backOut] returns to the corner view.
 */
class QuickScanController(
    private val saved: CalibrationCenters? = null,
    /** Whether [onFrame] runs the [SideLight] check (Story 10.19); off only in tests about other behavior. */
    private val lightCheck: Boolean = true,
) {
    private val samples = linkedMapOf<Face, FaceSample>()

    /** Story 10.21: whether the torch was lit at each corner face's capture (the calibration match is judged against it). */
    private val captureTorch = mutableMapOf<Face, Boolean>()

    /** Story 10.21: the judged stickers each kept corner face leaves blank, frozen when it was kept. */
    private val keptBlanks = mutableMapOf<Face, Set<Int>>()
    private val keptSet = linkedSetOf<Face>()
    private val addedSamples = linkedMapOf<Face, FaceSample>()
    private val corner = CornerHold(faceSteady = true)

    /** The corner just captured, until the view is confirmed to show another one ([TURN_CONFIRM_FRAMES]). */
    private var previous: List<FrameStats?>? = null

    /** Consecutive detected frames on which the view showed another corner than [previous]. */
    private var turnFrames = 0

    /** Why the pose check rejected the last detected frame; null when it passed. Changes are logged on the timeline. */
    private var poseRejected: String? = null

    /** When the running hold began (viewfinder entry, or the previous capture); null until then. */
    private var holdStartMs: Long? = null
    private var firstFrameSeen = false
    private var firstDetectedMs: Long? = null
    private var undetectedMs = 0L

    /** The previous frame of the running hold, for the undetected time. */
    private var holdFrameMs: Long? = null
    private val events = mutableListOf<CornerHoldEvent>()

    /**
     * The pose-unchanged check that blocked the last detected corner frame (PO 2026-11-21, Story 10.19); null when it
     * passed or did not run. While it blocks, nothing is captured (auto or manual) and the steady count does not start.
     */
    var centerBlock: CenterCheck? = null
        private set

    /** The three centre cells (Lab, by screen position) captured at each hold, for the pose-unchanged [CenterCheck]. */
    private val holdCenters = mutableMapOf<Int, List<Lab?>>()

    /** What the last corner capture read and decided per face, for the debug diagnostics; null before the first. */
    var lastCapture: CornerCaptureReport? = null
        private set

    /** Why the last frame restarted the corner steady window (the outline moved or another face shows); null when it did not. */
    val steadyRestart: CornerSteadyRestart? get() = corner.steadyRestart

    /** The corner hold the hand-over returns to on [backOut]; null when no hand-over was started since the last one. */
    private var resumeHold: Int? = null
    var hold = 1
        private set

    /**
     * The hold's coaching. Story 10.21: the confident-count wait adds no line of its own (the hold's prompt shows as
     * usual); [fewConfident] says when it is waiting.
     */
    val coaching: Coaching? get() = corner.coaching

    /** True when the last frame's automatic capture waited for too few CONFIDENT stickers ([MIN_CONFIDENT], Story 10.21). */
    var fewConfident = false
        private set

    /**
     * Debug diagnostics (Story 10.21 device session): the few-confident wait's count on the last frame, per face; null
     * when the wait did not run on it. Its amount is exactly the number compared with [MIN_CONFIDENT].
     */
    var confidentCount: QuickScanDiagnosis.ConfidentCount? = null
        private set

    /**
     * Story 10.21 (device session 2026-11-24): the few-confident wait's per-face counts on the frames of the current steady
     * window within [CONFIDENT_WINDOW_MS], oldest first. The wait decides on the frame with the most, so a one-frame dip
     * (a centre's plain flag flickering under a torch hot spot) neither blocks nor restarts the steady window.
     */
    private val confidentWindow = ArrayDeque<Pair<Long, Map<Face, Int>>>()

    /** Whether this hold already logged a frame the wait skipped for want of a colour reference. */
    private var fewConfidentSkipLogged = false

    /** Debug diagnostics: the last hand-over (kept and grid faces, why, blanks); null before the first. */
    var lastHandOver: QuickScanDiagnosis.HandOverReport? = null
        private set

    /** The faces a read was judged among, for the debug diagnostics computed on demand. */
    private class ReadSnapshot(
        val faces: List<FaceSample>,
        val merged: List<FaceSample>,
        val torch: Map<Face, Boolean>,
    )

    private var confidentSnapshot: ReadSnapshot? = null
    private var captureSnapshot: ReadSnapshot? = null

    /** Debug diagnostics: how each face the last few-confident wait counted reads ([confidentCount]); empty when none ran. */
    fun confidentDiagnoses(): List<QuickScanDiagnosis.FaceDiagnosis> = diagnoses(confidentSnapshot)

    /** Debug diagnostics: how each face of the last corner capture ([lastCapture]) reads; empty before the first. */
    fun captureDiagnoses(): List<QuickScanDiagnosis.FaceDiagnosis> = diagnoses(captureSnapshot)

    private fun diagnoses(snapshot: ReadSnapshot?): List<QuickScanDiagnosis.FaceDiagnosis> =
        snapshot?.let { s -> s.faces.map { QuickScanClassification.diagnose(it, s.merged, saved, s.torch) } }.orEmpty()

    /**
     * Debug diagnostics: how a hand-over capture [sample] reads against the self-calibration over the kept and added
     * faces (with [saved] only when it matched the corner faces, as review reads).
     */
    fun handOverRead(sample: FaceSample): QuickScanDiagnosis.GuidedFaceRead {
        val corners = captured
        val usable =
            saved.takeIf { corners.isEmpty() || QuickScanClassification.matchAll(corners, it, captureTorch) == CalibrationMatch.Match }
        val all = (kept + added).filter { it.face != sample.face } + sample
        return QuickScanDiagnosis.guidedRead(sample, all, usable)
    }

    /**
     * Whether the torch is wanted (Story 10.19): glare coaching then says to turn it off. The adapter sets it. Story 10.21:
     * the light check is skipped, a light fail cleared and the calibration offer withheld only while it is [torchLit].
     */
    var torchOn = false
        private set

    /** Story 10.21: whether the torch is physically lit: wanted and applied by the camera. A pending or failed torch is off. */
    var torchLit = false
        private set

    /** True while the light check is failing and coached; since Story 10.22 it holds back no capture. */
    val lightHeld: Boolean get() = corner.lightHeld

    /**
     * The last corner frame's [SideLight] verdict (calibrated when [saved] is valid); null when undetected or unmeasured.
     * Still measured with the torch lit, for the diagnostics, but then it holds nothing.
     */
    var light: SideLight.Verdict? = null
        private set

    /** True while a torch change waits for the adapter to apply it (nothing is captured meanwhile). */
    val torchPending: Boolean get() = corner.torchPending

    /** When the running light fail began; null when none runs. */
    val lightHeldSince: Long? get() = corner.lightHeldSince

    /**
     * The light check runs uncalibrated: no saved calibration matches the corner in view (Story 10.21: torch tag and
     * centres, [QuickScanClassification.match]); a mismatch behaves as when no calibration exists.
     */
    private var uncalibrated = !SideLight.calibrated(saved)

    /** The time of the last corner frame; null before the first. */
    private var lastFrameMs: Long? = null

    /**
     * The calibration offer (PO 2026-11-21, Story 10.19): an uncalibrated light fail that has run [CALIBRATION_OFFER_MS]
     * without clearing, in the corner view, with the cube not lost and the torch off. It is never offered in calibrated
     * mode or with the torch lit, and goes when the fail clears, the cube is lost or the corner view ends. It only offers.
     */
    val calibrationOffered: Boolean
        get() {
            val since = corner.lightHeldSince ?: return false
            val now = lastFrameMs ?: return false
            return inCorner && uncalibrated && !torchLit && corner.lightHeld && !corner.lostNow && now - since >= CALIBRATION_OFFER_MS
        }

    /** True while a metering change waits for the settle signal on a detected cube. */
    val settling: Boolean get() = corner.settling
    var fallback: GuidedScanController? = null
        private set
    var fallbackFaces: List<Face> = emptyList()
        private set
    var repromptFaces: List<Face> = emptyList()
        private set

    /** The corner samples held now: before a hand-over every face read, during and after one only the kept faces. */
    val captured: List<FaceSample> get() = samples.values.toList()

    /** The faces a hand-over kept; empty before the first hand-over. */
    val keptFaces: Set<Face> get() = keptSet.toSet()

    /** The kept faces' corner samples, exactly as captured. */
    val kept: List<FaceSample> get() = samples.values.filter { it.face in keptSet }

    /** The faces captured in the running (or just finished) hand-over viewfinder. */
    val added: List<FaceSample> get() = addedSamples.values.toList()

    val inCorner: Boolean get() = hold <= QuickScanPlan.holds.size

    /**
     * One analysis frame of the corner view. The domain runs the [SideLight] check over [faces] itself (calibrated
     * against [saved] when it is valid, PO 2026-11-21 E); an undetected frame is not measured. [settled] is the adapter's
     * settle signal for the latest metering change (null: no signal, the fixed [CornerHold.SETTLE_MS] applies and a
     * manual press is queued only while a torch change is pending). [outline] is the detected cube outline (the twelve
     * quad points, analysis-frame pixels): the steady window and the re-meter triggers follow it (Story 10.22); without
     * one only a different face restarts the steady window.
     */
    fun onFrame(
        detected: Boolean,
        faces: List<FrameStats?>,
        nowMs: Long,
        settled: Boolean? = null,
        outline: List<OutlinePoint>? = null,
    ): List<QuickScanEffect> {
        if (!inCorner) return emptyList()
        val start = holdStartMs ?: nowMs.also { holdStartMs = it }
        if (!firstFrameSeen) {
            firstFrameSeen = true
            event(CornerHoldEventKind.FIRST_FRAME, nowMs)
        }
        if (!detected) undetectedMs += nowMs - (holdFrameMs ?: start)
        holdFrameMs = nowMs
        lastFrameMs = nowMs
        centerBlock = null
        fewConfident = false
        confidentCount = null
        // Story 10.21: the hold in view with the faces captured before it, at the torch lit now, decides the match.
        val frame = if (detected) frameSamples(faces, hold, kept = true) else emptyList()
        val merged = captured.filter { old -> frame.none { it.face == old.face } } + frame
        val torch = captureTorch + frame.associate { it.face to torchLit }
        val usable = saved.takeIf { QuickScanClassification.matchAll(merged, it, torch) == CalibrationMatch.Match }
        if (detected) uncalibrated = usable == null
        val light = if (detected && lightCheck) SideLight.check(faces, usable) else null
        this.light = light

        // A change of the pose check's verdict goes on the timeline, in order with the hold's own marks.
        fun pose(
            rejected: String?,
            amount: Double? = null,
            confident: Map<Face, Int>? = null,
        ) {
            // The capture's "since detected" time starts with the first detected frame that shows the corner to take.
            if (rejected == null && firstDetectedMs == null) firstDetectedMs = nowMs
            collectMarks()
            if (rejected != null && rejected != poseRejected) {
                event(
                    CornerHoldEventKind.POSE_REJECTED,
                    nowMs,
                    reason = rejected,
                    amount = amount ?: centerBlock?.deltaE?.filterNotNull()?.maxOrNull(),
                    confident = confident,
                )
            } else if (rejected == null && poseRejected != null) {
                event(CornerHoldEventKind.POSE_ACCEPTED, nowMs)
            }
            poseRejected = rejected
        }

        // The pose and turn checks passed; a few-confident wait stays rejected until enough stickers read CONFIDENT.
        fun poseOk() {
            if (poseRejected == POSE_FEW_CONFIDENT) {
                if (firstDetectedMs == null) firstDetectedMs = nowMs
                collectMarks()
            } else {
                pose(null)
            }
        }
        var waitAsked = false
        val decision =
            corner.onFrame(
                detected,
                faces,
                nowMs,
                clearWhenUnlocated = hold == 2,
                // PO 2026-11-21: the light check is skipped while the torch is lit (Story 10.21: physically lit).
                light = light.takeUnless { torchLit },
                settled = settled,
                torchOn = torchOn,
                outline = outline.takeIf { detected },
                enoughConfident = {
                    waitAsked = true
                    // Story 10.21: CONFIDENT judged stickers of the hold's faces, read with the faces captured before it
                    // (the same set the capture line counts); a face with no colour reference (< 2 centres) is not counted.
                    val counts = frame.associate { it.face to QuickScanClassification.confidentJudged(it, merged, saved, torch) }
                    val perFace = counts.mapNotNull { (face, n) -> n?.let { face to it } }.toMap()
                    confidentSnapshot = ReadSnapshot(frame, merged, torch)
                    // Nothing in view can be read: the count is unknowable, not low, so this frame adds nothing.
                    val unread = frame.isNotEmpty() && perFace.isEmpty()
                    if (!unread) confidentWindow.addLast(nowMs to perFace)
                    while (confidentWindow.isNotEmpty() &&
                        confidentWindow.first().first < nowMs - CONFIDENT_WINDOW_MS
                    ) {
                        confidentWindow.removeFirst()
                    }
                    val best = confidentWindow.maxByOrNull { it.second.values.sum() }?.second
                    confidentCount = best?.let { QuickScanDiagnosis.ConfidentCount(hold, it, MIN_CONFIDENT) }
                    // An unread frame with no counted frame in the window is never held (PO 2026-11-24).
                    val enough = best == null || best.values.sum() >= MIN_CONFIDENT
                    if (unread && best == null && !fewConfidentSkipLogged) {
                        fewConfidentSkipLogged = true
                        event(CornerHoldEventKind.FEW_CONFIDENT_SKIPPED, nowMs, reason = FEW_CONFIDENT_NO_REFERENCE)
                    }
                    fewConfident = !enough
                    if (!enough) {
                        pose(POSE_FEW_CONFIDENT, checkNotNull(best).values.sum().toDouble(), best)
                    } else if (poseRejected == POSE_FEW_CONFIDENT) {
                        pose(null)
                    }
                    enough
                },
            ) {
                val check = CenterCheck.of(hold, corner.latest, holdCenters.entries.firstOrNull { it.key != hold }?.value)
                val prior = previous
                if (check != null && check.blocks) {
                    // PO 2026-11-21: a hold still showing the other hold's centres (the cube not turned) never captures.
                    centerBlock = check
                    turnFrames = 0
                    pose(POSE_CENTRES)
                    false
                } else if (prior != null) {
                    // Story 10.22: one frame never clears the previous hold; the turn must show on consecutive frames.
                    turnFrames = if (turnedOver(corner.latest, prior)) turnFrames + 1 else 0
                    if (turnFrames >= TURN_CONFIRM_FRAMES) {
                        previous = null
                        poseOk()
                        true
                    } else {
                        pose(POSE_TURN)
                        false
                    }
                } else {
                    poseOk()
                    true
                }
            }
        collectMarks()
        // The smoothing window holds the current steady window's frames only: a view the wait was not asked on (pose,
        // quality, metering), a lost cube or a restarted window starts it afresh. A short detection gap keeps it.
        when {
            (detected && !waitAsked) || corner.lostNow -> confidentWindow.clear()
            corner.steadyRestart != null -> confidentWindow.removeAll { it.first != nowMs }
        }
        return when (decision) {
            CornerHold.Decision.Wait -> emptyList()
            CornerHold.Decision.Remeter -> listOf(QuickScanEffect.Remeter)
            CornerHold.Decision.Capture -> take(nowMs, manual = corner.manualCapture)
        }
    }

    /**
     * The corner view opened (or resumed) at [nowMs]: the hold's timeline starts here unless it already began. Without
     * this call a hold begins on its first frame.
     */
    fun viewEntered(nowMs: Long) {
        if (!inCorner || holdStartMs != null) return
        holdStartMs = nowMs
        event(CornerHoldEventKind.VIEWFINDER, nowMs)
    }

    /** The hold state changes recorded since the last call (Story 10.22), oldest first, for the debug diagnostics. */
    fun drainEvents(): List<CornerHoldEvent> {
        collectMarks()
        return events.toList().also { events.clear() }
    }

    private fun collectMarks() {
        corner.drainMarks().forEach { event(it.kind, it.atMs, it.reason, it.amount, it.timeout, it.durationMs) }
    }

    private fun event(
        kind: CornerHoldEventKind,
        atMs: Long,
        reason: String? = null,
        amount: Double? = null,
        timeout: Boolean? = null,
        durationMs: Long? = null,
        ofHold: Int = hold,
        confident: Map<Face, Int>? = null,
    ) {
        events += CornerHoldEvent(ofHold, atMs - (holdStartMs ?: atMs), kind, reason, amount, timeout, durationMs, confident)
        // Never unbounded when nobody drains (tests, release): the oldest marks go first.
        if (events.size > MAX_EVENTS) events.removeAt(0)
    }

    /** A new hold's timeline: it begins at [startMs] (null: on its first frame). */
    private fun beginHold(startMs: Long?) {
        collectMarks()
        holdStartMs = startMs
        firstFrameSeen = false
        firstDetectedMs = null
        undetectedMs = 0L
        holdFrameMs = null
        turnFrames = 0
        poseRejected = null
        confidentWindow.clear()
        fewConfidentSkipLogged = false
        if (startMs != null && inCorner) event(CornerHoldEventKind.VIEWFINDER, startMs)
    }

    /** The physical torch state the adapter reported on the last [syncTorch]; null before it reported one. */
    private var lastApplied: Boolean? = null

    /**
     * One frame's torch state (Story 10.19): [requested] is the torch the corner view wants (the Learner's choice on a
     * camera with a flash unit), [applied] what the adapter last applied physically (null: unknown yet). Coaching follows
     * [requested] at once. A torch change is a metering change, but it starts only once the physical torch matches: until
     * then nothing is captured and a press is queued (at most [SETTLE_CAP_MS], so a torch that never applies cannot
     * block capture). The same wait runs when the physical torch drifts from the wanted state (a camera reopen).
     */
    fun syncTorch(
        requested: Boolean,
        applied: Boolean?,
        nowMs: Long,
    ) {
        val changed = requested != torchOn
        val drifted = lastApplied == requested && applied != requested
        val wasLit = torchLit
        torchOn = requested
        torchLit = requested && applied == true
        lastApplied = applied
        // PO 2026-11-21: the torch turning on clears a running light fail at once (the check is skipped while it is lit).
        // Story 10.21: once it is physically lit, not when it is only asked for.
        if (torchLit && !wasLit) corner.clearLightHold(nowMs)
        when {
            changed || drifted -> if (applied == requested) corner.remeter() else corner.awaitTorch(nowMs)
            applied == requested -> corner.torchApplied()
        }
    }

    /**
     * The manual shutter at [nowMs] (null: the last frame's time): queued while the metering settles (it fires once
     * settled, unless the cube is lost first), otherwise the corner is taken now. A failing light check never holds it
     * back (Story 10.22); a view still showing the corner just captured does.
     */
    fun onShutter(nowMs: Long? = null): List<QuickScanEffect> {
        if (!inCorner || sameCornerShown || centerBlock != null) return emptyList()
        return when (corner.press()) {
            CornerHold.Press.Take -> {
                // Without a time: the last frame's, never before the hold began.
                val start = holdStartMs ?: 0L
                take(nowMs ?: maxOf(lastFrameMs ?: start, start), manual = true)
            }

            CornerHold.Press.Queued -> {
                emptyList()
            }
        }
    }

    /**
     * True while a detected view still shows the corner just captured: no frame since then showed another one. A
     * deliberate press needs only the current frame to show another corner; auto-capture needs [TURN_CONFIRM_FRAMES].
     */
    private val sameCornerShown: Boolean get() = previous != null && !corner.unlocated && turnFrames == 0

    private fun take(
        nowMs: Long,
        manual: Boolean,
    ): List<QuickScanEffect> {
        if (!inCorner || sameCornerShown) return emptyList()
        val verdict = light
        val start = holdStartMs ?: nowMs
        val taken = hold++
        // Fill only (PO Q2): a kept face never changes until review, where *Retake faces* fixes it.
        frameSamples(corner.latest, taken, kept = false).forEach { sample ->
            samples[sample.face] = sample
            captureTorch[sample.face] = torchLit
        }
        holdCenters[taken] = CenterCheck.centers(corner.latest)
        val merged = captured
        val reported = QuickScanPlan.holds[taken - 1].map { it.face }.filter { it !in keptSet }
        captureSnapshot = ReadSnapshot(merged.filter { it.face in reported }, merged, captureTorch.toMap())
        lastCapture =
            CornerCaptureReport(
                taken,
                QuickScanClassification.report(
                    merged,
                    QuickScanPlan.holds[taken - 1].map { it.face }.filter { it !in keptSet },
                    saved,
                    captureTorch,
                ),
                manual = manual,
                totalMs = nowMs - start,
                undetectedMs = undetectedMs,
                sinceDetectedMs = firstDetectedMs?.let { nowMs - it },
                lightScore = verdict?.score,
                lightPassed = verdict?.takeIf { it.score != null }?.passed,
                calibration = QuickScanClassification.matchAll(merged, saved, captureTorch),
                torchLit = torchLit,
            )
        collectMarks()
        event(CornerHoldEventKind.CAPTURE, nowMs, reason = if (manual) TRIGGER_MANUAL else TRIGGER_AUTO, ofHold = taken)
        if (inCorner) {
            previous = corner.latest.takeIf { faces -> faces.any { it?.cells?.any { cell -> cell != null } == true } }
        }
        corner.reset()
        // The next hold begins with this capture.
        beginHold(nowMs.takeIf { inCorner })
        val capture = QuickScanEffect.CornerCaptured(taken)
        if (inCorner) return listOf(capture)
        // Faces kept by an earlier hand-over are never asked again.
        repromptFaces = emptyList()
        fallbackFaces = needGrid(captured, Face.entries.filter { it !in keptSet })
        val asked =
            fallbackFaces.ifEmpty {
                repromptFaces = involvedFaces(captured, keptSet)
                repromptFaces
            }
        val reasons = gridReasons(asked, reprompt = fallbackFaces.isEmpty())
        val calibration = QuickScanClassification.matchAll(captured, saved, captureTorch)
        val next = if (asked.isEmpty()) done(captured) else handOver(asked.toSet(), resumeAt = taken)
        lastHandOver = handOverReport(QuickScanDiagnosis.HandOverTrigger.AUTO, reasons, next, calibration)
        return listOf(capture, next)
    }

    /** Why each face of [asked] goes to the grid (debug diagnostics), judged before the hand-over drops its sample. */
    private fun gridReasons(
        asked: List<Face>,
        reprompt: Boolean,
    ): Map<Face, QuickScanDiagnosis.GridReason> {
        val merged = captured
        return asked.associateWith { face ->
            val sample = samples[face]
            when {
                sample == null -> QuickScanDiagnosis.GridReason.MISSING
                reprompt -> QuickScanDiagnosis.GridReason.REPROMPT
                else -> QuickScanClassification.decide(sample, merged, saved, captureTorch).gridReason ?: REPROMPT_REASON
            }
        }
    }

    /** The hand-over just made ([effect]) as the debug diagnostics report it. */
    private fun handOverReport(
        trigger: QuickScanDiagnosis.HandOverTrigger,
        grid: Map<Face, QuickScanDiagnosis.GridReason>,
        effect: QuickScanEffect,
        calibration: CalibrationMatch?,
    ): QuickScanDiagnosis.HandOverReport {
        val stays = Face.entries.filter { it in samples && it !in grid }
        return QuickScanDiagnosis.HandOverReport(
            trigger = trigger,
            kept = stays,
            grid = grid,
            blanks = keptBlanks.filter { (face, blanks) -> face in stays && blanks.isNotEmpty() },
            done = effect is QuickScanEffect.Done,
            calibration = calibration,
        )
    }

    /**
     * The corner faces of [faces] at hold [number] with all nine cells, in hold order; faces kept by a hand-over are left
     * out unless [kept].
     */
    private fun frameSamples(
        faces: List<FrameStats?>,
        number: Int,
        kept: Boolean,
    ): List<FaceSample> {
        val steps = QuickScanPlan.holds.getOrNull(number - 1) ?: return emptyList()
        return steps.zip(faces).mapNotNull { (step, stats) ->
            val cells = stats?.cells?.filterNotNull() ?: return@mapNotNull null
            if (cells.size != ScanAssembler.STICKERS || (!kept && step.face in keptSet)) return@mapNotNull null
            FaceSample(
                face = step.face,
                orientationDegrees = 0,
                stickers = cells.map { it.value },
                quality = FrameQuality.assessShutter(stats),
                centerColor = step.centerColor,
                upNeighborColor = step.upNeighborColor,
                centerPlain = cells[FaceSample.CENTER_INDEX].plain,
            )
        }
    }

    /**
     * Back in the corner view from the calibration screen it offered (Story 10.21: saved, *Close* or Back): the light
     * coaching hold is cleared, so the offer needs a fresh [CALIBRATION_OFFER_MS] of failing light. (*Save* starts a new
     * controller with the new calibration.)
     */
    fun returnFromCalibration(nowMs: Long) {
        corner.clearLightHold(nowMs, reason = REASON_CALIBRATION)
    }

    /**
     * The scan's Done over [all], with the blanks of every face kept from a corner (frozen as kept) and the calibration
     * review reads with: [saved] only when it matched the corner faces (a mismatch reads as with none); with no corner
     * face (face by face from the start) the Guided grid's own use of [saved].
     */
    private fun done(all: List<FaceSample>): QuickScanEffect.Done {
        freezeBlanks(captured.map { it.face }.toSet())
        val corners = captured
        val calibration =
            saved.takeIf { corners.isEmpty() || QuickScanClassification.matchAll(corners, it, captureTorch) == CalibrationMatch.Match }
        return QuickScanEffect.Done(all, keptBlanks.filterValues { it.isNotEmpty() }, calibration)
    }

    /** Freezes the blanks of [faces] (kept corner faces) once, as decided now. */
    private fun freezeBlanks(faces: Set<Face>) {
        val merged = captured
        merged.filter { it.face in faces && it.face !in keptBlanks }.forEach { sample ->
            val decision = QuickScanClassification.decide(sample, merged, saved, captureTorch)
            if (decision.keep) keptBlanks[sample.face] = decision.blanks
        }
    }

    /**
     * *Scan face by face*: valid in the corner view (hold 1 or 2, also during a capture freeze). Keeps the captured faces
     * that [needGrid] passes, plus faces already kept, and hands the others over; null (no-op) once a hand-over runs or
     * the scan is done.
     */
    fun switchToFaceByFace(): QuickScanEffect? {
        if (!inCorner) return null
        val judged = captured.map { it.face }.filter { it !in keptSet }
        val doubtful = needGrid(captured, judged).toSet()
        val keep = keptSet + judged.filter { it !in doubtful }
        val asked = Face.entries.filter { it !in keep }.toSet()
        fallbackFaces = asked.toList()
        repromptFaces = emptyList()
        val reasons = gridReasons(asked.toList(), reprompt = false)
        val calibration = QuickScanClassification.matchAll(captured, saved, captureTorch)
        return handOver(asked, resumeAt = hold).also {
            lastHandOver = handOverReport(QuickScanDiagnosis.HandOverTrigger.FACE_BY_FACE, reasons, it, calibration)
        }
    }

    /** Each Guided capture is added beside the kept faces; the hand-over's Done is the combined scan. */
    fun onGuidedEffects(effects: List<GuidedScanEffect>): QuickScanEffect? {
        fallback ?: return null
        val capture = effects.filterIsInstance<GuidedScanEffect.Capture>().firstOrNull() ?: return null
        addedSamples[capture.sample.face] = capture.sample
        if (effects.none { it is GuidedScanEffect.Done }) return null
        fallback = null
        return done(kept + added)
    }

    /**
     * Back from the hand-over before review (PO Q1): the corner view at the hold it started from (hold 2 once both corners
     * were captured), the kept faces intact and every face captured in the hand-over dropped (all or nothing). The
     * resumed hold starts clean (Story 10.22): the turn-over state of the corner captured before the hand-over and the
     * resumed hold's own captured centres are dropped, so the corner in view captures again. False when no hand-over is
     * running or just finished.
     */
    fun backOut(): Boolean {
        val resume = resumeHold ?: return false
        if (inCorner) return false
        fallback = null
        addedSamples.clear()
        hold = resume.coerceAtMost(QuickScanPlan.holds.size)
        resumeHold = null
        previous = null
        holdCenters.remove(hold)
        centerBlock = null
        corner.reset()
        beginHold(null)
        return true
    }

    /** Keeps every held face not in [asked], drops the others and starts the Guided viewfinder over the rest. */
    private fun handOver(
        asked: Set<Face>,
        resumeAt: Int,
    ): QuickScanEffect {
        freezeBlanks(samples.keys - asked)
        keptSet += samples.keys.filter { it !in asked }
        samples.keys.retainAll(keptSet)
        captureTorch.keys.retainAll(keptSet)
        addedSamples.clear()
        resumeHold = resumeAt
        hold = QuickScanPlan.holds.size + 1
        corner.reset()
        beginHold(null)
        val plan = GuidedScanPlan.steps.filter { it.face !in keptSet }
        if (plan.isEmpty()) return done(kept)
        fallback =
            GuidedScanController(
                plan = plan,
                allowAdditionalReprompts = false,
                reprompt = { emptyList() },
                known = kept,
                initialDelayMs = HAND_OVER_DELAY_MS,
            )
        return QuickScanEffect.Fallback(plan.map { it.face })
    }

    private fun involvedFaces(
        merged: List<FaceSample>,
        kept: Set<Face>,
    ): List<Face> = QuickScanClassification.involvedFaces(merged, saved, kept, captureTorch)

    /**
     * Faces among [faces] that the Guided grid must read, by the Quick-Scan-only gated classification
     * ([QuickScanClassification.needGrid]): missing, not ACCEPTABLE, uncalibrated or with a sticker not CONFIDENT.
     */
    private fun needGrid(
        merged: List<FaceSample>,
        faces: List<Face>,
    ): List<Face> = QuickScanClassification.needGrid(merged, faces, saved, captureTorch)

    /**
     * True when the frame shows another corner than the one just taken (Story 10.22). Only plain centres count, so a
     * logo centre that reads unstably decides nothing: with two or more plain centres comparable, at least
     * [TURN_CENTRES] changed colour. With fewer, every comparable plain centre changed and so did at least half of the
     * comparable outer cells; a frame with nothing comparable to the capture counts as turned.
     */
    private fun turnedOver(
        now: List<FrameStats?>,
        prior: List<FrameStats?>,
    ): Boolean {
        val threshold = GuidedScanController.FACE_CHANGE_DELTA_E
        val centres =
            CenterCheck.centers(now).zip(CenterCheck.centers(prior)).mapNotNull { (a, b) ->
                if (a != null && b != null) StickerClassifier.deltaE(a, b) >= threshold else null
            }
        if (centres.size >= TURN_CENTRES) return centres.count { it } >= TURN_CENTRES
        val outer =
            now.zip(prior).flatMap { (a, b) ->
                (0 until FrameQuality.CELLS).filter { it != FaceSample.CENTER_INDEX }.mapNotNull { cell ->
                    val x =
                        a
                            ?.cells
                            ?.getOrNull(cell)
                            ?.value
                            ?.takeIf { it.isValid() }
                    val y =
                        b
                            ?.cells
                            ?.getOrNull(cell)
                            ?.value
                            ?.takeIf { it.isValid() }
                    if (x != null && y != null) StickerClassifier.deltaE(x, y) >= threshold else null
                }
            }
        return centres.all { it } && outer.count { it } * 2 >= outer.size
    }

    companion object {
        /** The hand-over viewfinder waits this long before its first auto-capture, so the Learner can turn the cube. */
        const val HAND_OVER_DELAY_MS = 2500L

        /** The settle cap of the shared corner hold ([CornerSettle.CAP_MS]). */
        const val SETTLE_CAP_MS = CornerSettle.CAP_MS

        /** How long an uncalibrated light fail runs without clearing before the calibration offer shows (PO 2026-11-21). */
        const val CALIBRATION_OFFER_MS = 5000L

        /** Consecutive detected frames that must show another corner before the previous hold is cleared (Story 10.22). */
        const val TURN_CONFIRM_FRAMES = 3

        /** Plain centres that must change colour for the view to show another corner. */
        const val TURN_CENTRES = 2

        /** [CornerHoldEventKind.POSE_REJECTED] reasons: the other hold's centres are shown, or the turn is not confirmed. */
        const val POSE_CENTRES = "centres"
        const val POSE_TURN = "turn"

        /** Story 10.21: too few CONFIDENT stickers in the hold for auto-capture; the amount is the count. */
        const val POSE_FEW_CONFIDENT = "few-confident"

        /**
         * Story 10.21: the CONFIDENT stickers (of the hold's 27) an automatic capture waits for. Fitted on the 10.20
         * tuning split (`QuickScanGateTuningTest`): the largest N up to 6 that every positive tuning hold's captured
         * frame reaches under its scan-time calibration. The manual shutter never waits for it.
         */
        const val MIN_CONFIDENT = 6

        /**
         * Story 10.21 (PO 2026-11-24): the few-confident wait decides on the best count over the frames of the current
         * steady window within this long, so one frame's dip (30-100 ms apart on device) cannot clear the window, while a
         * hold whose count stays below [MIN_CONFIDENT] still blocks.
         */
        const val CONFIDENT_WINDOW_MS = 500L

        /** [CornerHoldEventKind.FEW_CONFIDENT_SKIPPED] reason: fewer than two reference centres, nothing readable. */
        const val FEW_CONFIDENT_NO_REFERENCE = "no-reference"

        /** A face asked though the gate kept it: the assembly was ambiguous or did not fit (debug diagnostics). */
        private val REPROMPT_REASON = QuickScanDiagnosis.GridReason.REPROMPT

        /** [CornerHoldEventKind.LIGHT_CLEAR] reason when the light hold is cleared on return from calibration. */
        const val REASON_CALIBRATION = "calibration"

        /** [CornerHoldEventKind.CAPTURE] reasons. */
        const val TRIGGER_AUTO = "auto"
        const val TRIGGER_MANUAL = "manual"

        /** The most undrained timeline events kept. */
        const val MAX_EVENTS = 256
    }
}

/**
 * The pose-unchanged check of a corner hold (PO 2026-11-21, Story 10.19): the three visible centre cells against the
 * centre cells captured at the other hold in the same screen positions, compared in Lab with no colour classification (a
 * saved calibration made on another cube cannot block a capture, AC5). [deltaE] is per screen position, null where either
 * centre is missing, invalid or not plain (Story 10.22: a logo centre reads unstably and is left out). The check
 * [blocks] when at least [MIN_CENTRES] centres can be compared and every one lies within [POSE_MATCH_DELTA_E] of the
 * other hold's: the cube was not turned. Before the other hold was captured there is nothing to compare and nothing blocks.
 */
data class CenterCheck(
    val hold: Int,
    val deltaE: List<Double?>,
) {
    val blocks: Boolean
        get() {
            val compared = deltaE.filterNotNull()
            return compared.size >= MIN_CENTRES && compared.all { it < POSE_MATCH_DELTA_E }
        }

    companion object {
        /** Two centre cells closer than this (CIE76) are the same sticker seen again: the pose did not change. */
        const val POSE_MATCH_DELTA_E = 11.0

        /** The fewest comparable (plain) centres the check needs to block. */
        const val MIN_CENTRES = 2

        /** The check of [faces] at [hold] against [other], the other hold's captured centres; null when none was captured. */
        fun of(
            hold: Int,
            faces: List<FrameStats?>,
            other: List<Lab?>?,
        ): CenterCheck? {
            other ?: return null
            val now = centers(faces)
            return CenterCheck(
                hold,
                now.zip(other).map { (a, b) -> if (a != null && b != null) StickerClassifier.deltaE(a, b) else null },
            )
        }

        /** The centre cell of each of [faces], null where missing, invalid or not plain (a logo). */
        fun centers(faces: List<FrameStats?>): List<Lab?> =
            faces.map { stats ->
                stats
                    ?.cells
                    ?.getOrNull(FaceSample.CENTER_INDEX)
                    ?.takeIf { it.plain }
                    ?.value
                    ?.takeIf { it.isValid() }
            }
    }
}

/**
 * One corner capture's per-face outcome (Story 10.19 diagnostics): colour names and counts only, never pixels. Story
 * 10.22 adds what triggered it ([manual] shutter or automatic), the hold's [totalMs] since it began, its [undetectedMs],
 * the time since the first detected frame of the hold that passed the pose and turn checks ([sinceDetectedMs]: at hold 2
 * the cube turned to the other corner, not the first corner still in view; null when there was none) and the light
 * check at the moment of capture ([lightScore], [lightPassed]; null when unmeasured).
 */
data class CornerCaptureReport(
    val hold: Int,
    val faces: List<QuickScanClassification.FaceReport>,
    val manual: Boolean = false,
    val totalMs: Long = 0L,
    val undetectedMs: Long = 0L,
    val sinceDetectedMs: Long? = null,
    val lightScore: Double? = null,
    val lightPassed: Boolean? = null,
    val calibration: CalibrationMatch? = null,
    val torchLit: Boolean = false,
)
