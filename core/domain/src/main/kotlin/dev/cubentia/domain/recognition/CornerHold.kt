package dev.cubentia.domain.recognition

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Metering, coaching and steady-hold detection shared by both corner capture modes.
 *
 * Story 10.19 added, for Quick Scan corners only (the case capture passes none of these inputs and behaves as before):
 * - a light check driven by a [SideLight.Verdict] per frame;
 * - an external settle signal: after each metering change a capture waits until the adapter reports the exposure
 *   settled, or [SETTLE_CAP_MS] passed, instead of the fixed [SETTLE_MS]; a manual press while settling is queued and
 *   fires once settled, unless the cube is lost first;
 * - the torch state, which turns glare coaching into torch-glare coaching. A torch toggle is a metering change that
 *   starts only once the adapter applied the physical torch change ([awaitTorch], then [torchApplied], or
 *   [SETTLE_CAP_MS] at most): until then nothing is captured and a manual press is queued;
 * - a detection grace (device session 2026-11-21, S24 FE): the cube counts as lost only once it has been undetected for
 *   [LOST_MS]. A shorter gap keeps the metering, its settle state, the steady window and a queued press.
 *
 * Story 10.22 (PO 2026-11-22) changes, again for Quick Scan corners only ([faceSteady]):
 * - the light check is advisory. [lightHeld] now means "the light check is failing and coached": it starts on the first
 *   failing frame once metering has settled (never on an episode's first detected frame nor while a metering change
 *   settles), shows the sides-dark coaching after [SIDES_DARK_COACHING_MS] of failing with the cube detected, and clears
 *   once the check passed for [LIGHT_CLEAR_MS] in total since the last failing verdict (undetected frames and frames
 *   with a face unscored are neutral). It never blocks auto-capture or the manual shutter, never drops a queued press,
 *   and clearing it neither re-meters nor restarts the steady window;
 * - the steady window is judged by outline movement: it restarts when the largest corner displacement from the window's
 *   reference frame, over the reference outline's bounding-box diagonal, reaches [OUTLINE_STEADY_FRACTION], when
 *   another face is shown ([FACE_CHANGE_CENTRES] plain centres changed), or (PO 2026-11-22, after device session 1)
 *   when a face has fewer than [COLOR_STEADY_CELLS] sampled cells within [COLOR_STEADY_DELTA_E] of the reference: loose
 *   enough that an exposure change on a still cube does not restart it;
 * - re-meter triggers besides a lost cube and a torch change: the outline leaving the metered region
 *   ([REMETER_OUTLINE_FRACTION]) and the faces' mean brightness changing by [REMETER_LUMA_FRACTION] since the metering
 *   settled. The adapter holds its exposure lock between metering changes, so the domain only says "re-meter";
 * - a timeline of the hold's state changes ([drainMarks]) for the debug diagnostics.
 *
 * Every 10.22 threshold is derived from the recorded bursts; see docs/evidence/10-22/README.md.
 */
internal class CornerHold(
    /** Quick Scan corners: outline-based steadiness, drift re-metering and the timeline (Stories 10.19, 10.22). */
    private val faceSteady: Boolean = false,
) {
    enum class Decision { Wait, Remeter, Capture }

    /** What a manual press does now. */
    enum class Press { Take, Queued }

    var latest = List<FrameStats?>(3) { null }
        private set
    var unlocated = false
        private set

    /** True once the cube has been undetected for [LOST_MS] or more on the last frame (the short-gap grace ran out). */
    var lostNow = false
        private set
    private var meteredAt: Long? = null
    private var reference: List<Lab?>? = null

    /** The outline and the plain centres of the steady window's reference frame (Quick Scan corners). */
    private var referenceOutline: List<OutlinePoint>? = null
    private var referenceCentres: List<Lab?> = emptyList()
    private var referenceFaces: List<FrameStats?> = emptyList()
    private var steadySince = 0L
    private var undetectedSince: Long? = null
    var coaching: Coaching? = null
        private set

    /** Why the steady window restarted on the last frame; null when it did not (or [faceSteady] is off). */
    var steadyRestart: CornerSteadyRestart? = null
        private set

    /** True when the last frame's [Decision.Capture] came from a queued manual press. */
    var manualCapture = false
        private set

    /** True while the light check is failing and coached (advisory since Story 10.22: it blocks nothing). */
    var lightHeld = false
        private set

    /** When the running light fail began; null when none runs. */
    var lightHeldSince: Long? = null
        private set
    private var lightFailingSince: Long? = null

    /** Passing time accumulated toward [LIGHT_CLEAR_MS] since the last failing verdict while held. */
    private var passedMs = 0L

    /** The last passing frame of the running pass window; null after a neutral frame (the gap does not count). */
    private var lastPassAt: Long? = null
    private var sidesDark = false

    /** The settle signal is external (Quick Scan corners) since the last frame that said so. */
    private var externalSettle = false
    private var settledSinceMeter = false
    private var pendingPress = false

    /** A metering change was asked outside the frame loop ([remeter]); the next detected frame re-meters. */
    private var remeterPending = false

    /** When a torch change was asked that the adapter has not applied yet; null when none is pending. */
    private var torchPendingSince: Long? = null

    /** The outline the latest metering request was made on; null when the caller passes none. */
    private var meteredOutline: List<OutlinePoint>? = null

    /** The faces' mean luma on the frame the latest metering settled; null until then. */
    private var meteredLuma: List<Double?>? = null

    /** Why the next metering request is made, for the timeline. */
    private var meterReason = CornerMeterReason.FIRST

    /** True while the cube counts as present: from a detected frame until it is lost. */
    private var present = false
    private val marks = mutableListOf<CornerHoldMark>()

    /** True while a torch change or its re-meter is pending on a detected cube (a capture waits, a press is queued). */
    private val meteringPending: Boolean get() = !unlocated && (torchPendingSince != null || remeterPending)

    /** True while a metering change has not settled on a detected cube (a capture waits). */
    val settling: Boolean get() = meteringPending || (!unlocated && meteredAt != null && !settledSinceMeter)

    fun onFrame(
        detected: Boolean,
        faces: List<FrameStats?>,
        nowMs: Long,
        clearWhenUnlocated: Boolean,
        light: SideLight.Verdict? = null,
        settled: Boolean? = null,
        torchOn: Boolean = false,
        outline: List<OutlinePoint>? = null,
        allowAuto: (List<Lab?>) -> Boolean,
    ): Decision {
        require(faces.size == 3)
        steadyRestart = null
        manualCapture = false
        latest = faces
        unlocated = !detected
        externalSettle = settled != null
        if (detected) {
            undetectedSince = null
            if (!present) {
                present = true
                mark(nowMs, CornerHoldEventKind.CUBE_FOUND)
            }
        } else if (undetectedSince == null) {
            undetectedSince = nowMs
        }
        lostNow = lost(nowMs)
        if (lostNow && present) {
            present = false
            mark(nowMs, CornerHoldEventKind.CUBE_LOST)
        }
        val values = values()
        if (detected && !allowAuto(values)) {
            // Time spent on a view that may not auto-capture never counts toward the next steady hold.
            reference = null
            return Decision.Wait
        }
        val metering = meteredAt
        if (detected && metering != null && !settledSinceMeter) {
            // Settled on this frame counts before the light check, so a settled frame may start the light coaching.
            settledSinceMeter =
                if (settled == null) nowMs - metering >= SETTLE_MS else settled || nowMs - metering >= SETTLE_CAP_MS
            if (settledSinceMeter) {
                meteredLuma = faces.map { it?.meanLuma }
                // A timeout only when this hold's own cap released the wait before the adapter reported settled; the
                // adapter's settle line says whether its signal itself timed out.
                mark(nowMs, CornerHoldEventKind.METERING_SETTLED, timeout = true.takeIf { settled == false }, durationMs = nowMs - metering)
            }
        }
        updateLight(detected, light, nowMs)
        val qualities = latest.map { it?.let(FrameQuality::assessShutter) }
        val longUndetected = !detected && undetectedSince?.let { nowMs - it >= UNDETECTED_COACHING_MS } == true
        coaching =
            when {
                sidesDark -> Coaching.SIDES_DARK
                CaptureQuality.LOW_LIGHT in qualities -> Coaching.LOW_LIGHT
                CaptureQuality.GLARE in qualities -> if (torchOn) Coaching.TORCH_GLARE else Coaching.GLARE
                CaptureQuality.BLURRED in qualities -> Coaching.BLURRED
                longUndetected -> Coaching.PLAIN_LIGHT_SURFACE
                else -> null
            }
        if (!detected) {
            if (clearWhenUnlocated) latest = List(3) { null }
            // A short detection gap (one-frame flicker) is ignored: no re-meter, the settle and steady windows run on.
            if (!lost(nowMs)) return Decision.Wait
            if (meteredAt != null) meterReason = CornerMeterReason.LOST
            meteredAt = null
            settledSinceMeter = false
            reference = null
            pendingPress = false
            return Decision.Wait
        }
        val torchSince = torchPendingSince
        if (torchSince != null) {
            if (nowMs - torchSince < SETTLE_CAP_MS) {
                // The torch change is not applied yet: metering now would settle on the old exposure.
                reference = null
                return Decision.Wait
            }
            // The adapter never confirmed it: go on as if applied, at most the settle cap late.
            torchApplied()
        }
        val drift = if (meteredAt != null && settledSinceMeter) drifted(outline, faces) else null
        if (meteredAt == null || drift != null) {
            // A metering change: the first detected frame of an episode, a torch change, or the cube drifted (10.22).
            val reason = drift?.first ?: meterReason
            meteredAt = nowMs
            settledSinceMeter = false
            remeterPending = false
            reference = null
            meteredOutline = outline
            meteredLuma = null
            meterReason = CornerMeterReason.FIRST
            mark(nowMs, CornerHoldEventKind.METERING_REQUESTED, reason = reason.label, amount = drift?.second)
            return Decision.Remeter
        }
        if (pendingPress && settledSinceMeter) {
            pendingPress = false
            manualCapture = true
            return Decision.Capture
        }
        if (qualities.any { it != CaptureQuality.ACCEPTABLE } || !settledSinceMeter) {
            if (reference != null && settledSinceMeter) {
                mark(nowMs, CornerHoldEventKind.STEADY_RESTART, reason = "quality")
            }
            reference = null
            return Decision.Wait
        }
        val baseline = reference
        val restart = if (faceSteady && baseline != null) steadyMove(outline) else null
        val moved = if (faceSteady) restart != null else baseline != null && changed(values, baseline, GuidedScanController.STEADY_DELTA_E)
        if (baseline == null || moved) {
            steadyRestart = restart
            reference = values
            referenceOutline = outline
            referenceCentres = CenterCheck.centers(latest)
            referenceFaces = latest
            steadySince = nowMs
            if (restart == null) {
                mark(nowMs, CornerHoldEventKind.STEADY_START)
            } else {
                val reason = restart.reason.label + (restart.face?.let { " face=$it" } ?: "")
                mark(nowMs, CornerHoldEventKind.STEADY_RESTART, reason = reason, amount = restart.amount)
            }
            return Decision.Wait
        }
        return if (nowMs - steadySince >= STEADY_MS) Decision.Capture else Decision.Wait
    }

    /**
     * A manual press: queued while a torch change or its re-meter is pending or an external settle signal is pending on
     * a detected cube (it fires from [onFrame] once settled), otherwise taken now. The light check never holds it back.
     */
    fun press(): Press =
        if (meteringPending || (externalSettle && settling)) {
            pendingPress = true
            Press.Queued
        } else {
            Press.Take
        }

    /** A metering change outside the frame loop: the next detected frame re-meters, and a press until then is queued. */
    fun remeter() {
        meteredAt = null
        settledSinceMeter = false
        reference = null
        remeterPending = true
        meterReason = CornerMeterReason.TORCH
    }

    /** A torch change was asked at [nowMs]: nothing is metered or captured until [torchApplied] (or the settle cap). */
    fun awaitTorch(nowMs: Long) {
        torchPendingSince = nowMs
        reference = null
    }

    /** The adapter applied the pending torch change: the metering change (and its settle window) starts now. */
    fun torchApplied() {
        if (torchPendingSince == null) return
        torchPendingSince = null
        remeter()
    }

    /** True while a torch change waits for the adapter. */
    val torchPending: Boolean get() = torchPendingSince != null

    /** The timeline marks recorded since the last call (Quick Scan corners only), oldest first. */
    fun drainMarks(): List<CornerHoldMark> = marks.toList().also { marks.clear() }

    private fun mark(
        atMs: Long,
        kind: CornerHoldEventKind,
        reason: String? = null,
        amount: Double? = null,
        timeout: Boolean? = null,
        durationMs: Long? = null,
    ) {
        if (faceSteady) marks += CornerHoldMark(atMs, kind, reason, amount, timeout, durationMs)
    }

    /** The re-meter trigger the frame trips against the latest settled metering, with its measured amount; null when none. */
    private fun drifted(
        outline: List<OutlinePoint>?,
        faces: List<FrameStats?>,
    ): Pair<CornerMeterReason, Double>? {
        if (!faceSteady) return null
        val moved = CornerOutline.displacement(outline, meteredOutline)
        if (moved != null && moved >= REMETER_OUTLINE_FRACTION) return CornerMeterReason.OUTLINE to moved
        val change = lumaChange(faces.map { it?.meanLuma }, meteredLuma)
        if (change != null && abs(change) >= REMETER_LUMA_FRACTION) return CornerMeterReason.BRIGHTNESS to change
        return null
    }

    /** The relative change of the mean luma of the faces present in both readings; null when none is. */
    private fun lumaChange(
        now: List<Double?>,
        prior: List<Double?>?,
    ): Double? {
        val pairs = now.zip(prior ?: return null).mapNotNull { (a, b) -> if (a != null && b != null) a to b else null }
        val before = pairs.sumOf { it.second }
        if (pairs.isEmpty() || before <= 0.0) return null
        return pairs.sumOf { it.first } / before - 1.0
    }

    private fun updateLight(
        detected: Boolean,
        light: SideLight.Verdict?,
        nowMs: Long,
    ) {
        if (!detected) {
            // The line clears at once; once the cube is lost the failing timer restarts. The pass window is untouched (an
            // undetected frame is neutral) and the fail runs on until a pass window completes after the cube is found.
            sidesDark = false
            if (lost(nowMs)) lightFailingSince = null
            lastPassAt = null
            return
        }
        light ?: return
        if (lightHeld && light.score == null) {
            // A face was unreadable: no evidence either way, so the pass window neither advances nor resets.
            lastPassAt = null
            return
        }
        if (!light.passed) {
            if (!lightHeld && (settling || meteredAt == null)) {
                // The first detected frame of an episode (exposed on the whole scene, it re-meters onto the cube) or an
                // exposure transient while a metering change settles never starts the coaching (the verdict is reported).
                resetPassWindow()
                return
            }
            if (!lightHeld) {
                lightHeld = true
                lightHeldSince = nowMs
                mark(nowMs, CornerHoldEventKind.LIGHT_FAIL, amount = light.score)
            }
            resetPassWindow()
            val failing = lightFailingSince ?: nowMs.also { lightFailingSince = it }
            if (nowMs - failing >= SIDES_DARK_COACHING_MS) sidesDark = true
            return
        }
        // While failing, a short pass does not restart the failing timer: a flickering verdict still gets its coaching.
        if (!lightHeld) {
            lightFailingSince = null
            return
        }
        // Only consecutive passing frames add up: the time across a neutral frame never counts.
        lastPassAt?.let { passedMs += nowMs - it }
        lastPassAt = nowMs
        if (passedMs < LIGHT_CLEAR_MS) return
        val since = lightHeldSince
        clearLight()
        mark(nowMs, CornerHoldEventKind.LIGHT_CLEAR, amount = light.score, durationMs = since?.let { nowMs - it })
    }

    private fun resetPassWindow() {
        passedMs = 0L
        lastPassAt = null
    }

    /** True once the cube has been undetected for [LOST_MS] or more. */
    private fun lost(nowMs: Long): Boolean = undetectedSince?.let { nowMs - it >= LOST_MS } == true

    /** Clears a running light fail at [nowMs] (the torch was lit, PO 2026-11-21: the check is skipped while it is). */
    fun clearLightHold(nowMs: Long) {
        val since = lightHeldSince
        if (lightHeld) mark(nowMs, CornerHoldEventKind.LIGHT_CLEAR, reason = "torch", durationMs = since?.let { nowMs - it })
        clearLight()
    }

    private fun clearLight() {
        lightHeld = false
        lightHeldSince = null
        sidesDark = false
        lightFailingSince = null
        resetPassWindow()
    }

    /** A new hold or view: every state is forgotten. Marks not drained yet are kept. */
    fun reset() {
        latest = List(3) { null }
        unlocated = false
        lostNow = false
        meteredAt = null
        settledSinceMeter = false
        reference = null
        referenceOutline = null
        referenceCentres = emptyList()
        referenceFaces = emptyList()
        undetectedSince = null
        coaching = null
        steadyRestart = null
        manualCapture = false
        pendingPress = false
        remeterPending = false
        torchPendingSince = null
        meteredOutline = null
        meteredLuma = null
        meterReason = CornerMeterReason.FIRST
        present = false
        clearLight()
    }

    companion object {
        /** The fixed settle wait when the adapter reports no settle signal. */
        const val SETTLE_MS = 600L

        /** The longest a capture waits for the external settle signal (or a torch change) after a metering change. */
        const val SETTLE_CAP_MS = CornerSettle.CAP_MS
        const val STEADY_MS = 1000L
        const val UNDETECTED_COACHING_MS = 3000L

        /** Continuous failing with the cube detected before the sides-dark coaching shows. */
        const val SIDES_DARK_COACHING_MS = 1000L

        /** The light check must pass this long (in total since the last failing verdict) to clear the coaching. */
        const val LIGHT_CLEAR_MS = 500L

        /** Undetected this long, the cube is lost: metering, settle, the steady window and a queued press restart. */
        const val LOST_MS = 300L

        /**
         * The steady window restarts when a corner of the outline moved this share of the reference outline's
         * bounding-box diagonal (Story 10.22). Fitted once on the five recorded bursts, replaying the steady window: a
         * resting cube restarts it up to 0.06 and never from 0.07, the first hand-held burst never from 0.08, and the
         * fast-movement burst completes no window up to 0.10.
         */
        const val OUTLINE_STEADY_FRACTION = 0.10

        /**
         * The colour half of the steady rule (PO 2026-11-22, after device session 1: the outline rule alone captured
         * while the cube was still being positioned). On every face at least [COLOR_STEADY_CELLS] of the sampled cells
         * ([COLOR_SAMPLED_CELLS], and the centre when plain) must stay within this ΔE of the window's reference frame.
         * Looser than the pre-10.22 ΔE 5: still burst 1's exposure swing restarts the window twice at ΔE 10, never at 12.
         */
        const val COLOR_STEADY_DELTA_E = 12.0
        const val COLOR_STEADY_CELLS = 3

        /** The four edge cells of a face, sampled with its centre for the colour half of the steady rule. */
        val COLOR_SAMPLED_CELLS = listOf(1, 3, 5, 7)

        /**
         * The first face of [now] with fewer than [COLOR_STEADY_CELLS] sampled cells within [deltaE] of [reference], as
         * (face index, stable cells); null when every face is stable. The centre is sampled only when plain in both
         * frames; a cell missing in either frame is not stable.
         */
        internal fun unstableFace(
            now: List<FrameStats?>,
            reference: List<FrameStats?>,
            deltaE: Double = COLOR_STEADY_DELTA_E,
        ): Pair<Int, Int>? {
            for (face in now.indices) {
                val a = now[face]?.cells.orEmpty()
                val b = reference.getOrNull(face)?.cells.orEmpty()
                val centrePlain = a.getOrNull(FaceSample.CENTER_INDEX)?.plain == true && b.getOrNull(FaceSample.CENTER_INDEX)?.plain == true
                val sampled = if (centrePlain) COLOR_SAMPLED_CELLS + FaceSample.CENTER_INDEX else COLOR_SAMPLED_CELLS
                val stable =
                    sampled.count { cell ->
                        val x = a.getOrNull(cell)?.value
                        val y = b.getOrNull(cell)?.value
                        x != null && y != null && StickerClassifier.deltaE(x, y) <= deltaE
                    }
                if (stable < COLOR_STEADY_CELLS) return face to stable
            }
            return null
        }

        /** Plain centres that must change by [GuidedScanController.FACE_CHANGE_DELTA_E] for a different face to be shown. */
        const val FACE_CHANGE_CENTRES = 2

        /**
         * Re-meter when a corner of the outline is this share of the metered outline's diagonal away from where it was
         * metered (Story 10.22): the cube left the metered region. The hand-held bursts stay below 0.14 and the
         * fast-movement burst below 0.21 over ten seconds.
         */
        const val REMETER_OUTLINE_FRACTION = 0.30

        /**
         * Re-meter when the faces' mean luma changed by this share since the metering settled (Story 10.22). At one
         * exposure level the recorded bursts change by at most 0.13 (fast movement; 0.10 hand-held, 0.09 resting).
         */
        const val REMETER_LUMA_FRACTION = 0.25
    }

    fun values(): List<Lab?> = latest.flatMap { face -> List(FrameQuality.CELLS) { face?.cells?.getOrNull(it)?.value } }

    /**
     * Why the frame restarts the steady window (Quick Scan corners): the outline moved by [OUTLINE_STEADY_FRACTION] from
     * the reference frame, [FACE_CHANGE_CENTRES] plain centres changed colour (another face is shown), or a face lost
     * its third stable sampled cell ([unstableFace]); null otherwise.
     */
    private fun steadyMove(outline: List<OutlinePoint>?): CornerSteadyRestart? {
        val moved = CornerOutline.displacement(outline, referenceOutline)
        if (moved != null && moved >= OUTLINE_STEADY_FRACTION) return CornerSteadyRestart(CornerSteadyRestart.Reason.OUTLINE, moved)
        val deltas =
            CenterCheck.centers(latest).zip(referenceCentres).mapNotNull { (a, b) ->
                if (a != null && b != null) StickerClassifier.deltaE(a, b) else null
            }
        val changed = deltas.filter { it >= GuidedScanController.FACE_CHANGE_DELTA_E }
        if (changed.size >= FACE_CHANGE_CENTRES) return CornerSteadyRestart(CornerSteadyRestart.Reason.FACE, changed.max())
        return unstableFace(latest, referenceFaces)?.let { (face, stable) ->
            CornerSteadyRestart(CornerSteadyRestart.Reason.COLOR, stable.toDouble(), face)
        }
    }

    fun changed(
        now: List<Lab?>,
        prior: List<Lab?>,
        threshold: Double,
    ): Boolean = now.zip(prior).any { (a, b) -> a != null && b != null && StickerClassifier.deltaE(a, b) >= threshold }
}

/**
 * Why a Quick Scan corner's steady window restarted (Story 10.22): the outline moved ([amount] is the largest corner
 * displacement over the reference outline's diagonal), another face is shown ([amount] is the largest centre ΔE), or
 * the colours of [face] (index, 0 top) are not stable ([amount] is how many of its sampled cells still are).
 */
data class CornerSteadyRestart(
    val reason: Reason,
    val amount: Double,
    val face: Int? = null,
) {
    enum class Reason(
        val label: String,
    ) {
        OUTLINE("outline"),
        FACE("face"),
        COLOR("color"),
    }
}

/** One point of the detected cube outline in analysis-frame pixels: plain coordinates, never pixels (AD-9). */
data class OutlinePoint(
    val x: Double,
    val y: Double,
)

/** Outline geometry shared by the steady rule and the re-meter trigger (Story 10.22). */
object CornerOutline {
    /** The diagonal of the bounding box of [points]; 0 for none. */
    fun diagonal(points: List<OutlinePoint>): Double {
        if (points.isEmpty()) return 0.0
        return hypot(points.maxOf { it.x } - points.minOf { it.x }, points.maxOf { it.y } - points.minOf { it.y })
    }

    /**
     * The largest displacement of a point of [now] from the same point of [reference], over the diagonal of
     * [reference]'s bounding box; null when either outline is missing or empty, they differ in size, or [reference] has
     * no extent.
     */
    fun displacement(
        now: List<OutlinePoint>?,
        reference: List<OutlinePoint>?,
    ): Double? {
        if (now.isNullOrEmpty() || reference.isNullOrEmpty() || now.size != reference.size) return null
        val diagonal = diagonal(reference)
        if (diagonal <= 0.0) return null
        return now.zip(reference).maxOf { (a, b) -> hypot(a.x - b.x, a.y - b.y) } / diagonal
    }
}

/** Why a corner hold asked for metering (Story 10.22 timeline). */
enum class CornerMeterReason(
    val label: String,
) {
    FIRST("first"),
    LOST("lost"),
    TORCH("torch"),
    OUTLINE("outline"),
    BRIGHTNESS("brightness"),
}

/** The state changes of a Quick Scan corner hold that the debug diagnostics log (Story 10.22). */
enum class CornerHoldEventKind(
    val label: String,
) {
    VIEWFINDER("viewfinder"),
    FIRST_FRAME("firstFrame"),
    CUBE_FOUND("cubeFound"),
    CUBE_LOST("cubeLost"),
    POSE_REJECTED("poseRejected"),
    POSE_ACCEPTED("poseAccepted"),
    METERING_REQUESTED("meteringRequested"),
    METERING_SETTLED("meteringSettled"),
    LIGHT_FAIL("lightFail"),
    LIGHT_CLEAR("lightClear"),
    STEADY_START("steadyStart"),
    STEADY_RESTART("steadyRestart"),
    CAPTURE("capture"),
}

/** A timeline mark of [CornerHold] at absolute time [atMs]; [QuickScanController] turns it into a [CornerHoldEvent]. */
internal data class CornerHoldMark(
    val atMs: Long,
    val kind: CornerHoldEventKind,
    val reason: String? = null,
    val amount: Double? = null,
    val timeout: Boolean? = null,
    val durationMs: Long? = null,
)

/**
 * One state change of Quick Scan corner hold [hold], [elapsedMs] after the hold began (Story 10.22). Numbers and fixed
 * words only, never pixels or Cube States (AD-9): [reason] names a cause, [amount] its measured value, [timeout] whether
 * a settle ended at the cap, [durationMs] how long the ended state lasted.
 */
data class CornerHoldEvent(
    val hold: Int,
    val elapsedMs: Long,
    val kind: CornerHoldEventKind,
    val reason: String? = null,
    val amount: Double? = null,
    val timeout: Boolean? = null,
    val durationMs: Long? = null,
)

/** Settle timing shared by the domain's corner hold and the camera adapter's settle signal (Story 10.19). */
object CornerSettle {
    /**
     * The longest a Quick Scan corner capture waits for the settle signal after a metering change (and for a torch change
     * to apply). Defined once: the camera adapter's settle signal times out at the same cap.
     */
    const val CAP_MS = 1500L
}
