package dev.cubentia.domain.recognition

/**
 * Metering, coaching and steady-hold detection shared by both corner capture modes.
 *
 * Story 10.19 adds, for Quick Scan corners only (the case capture passes neither input and behaves as before):
 * - a light hold driven by a [SideLight.Verdict] per frame: it engages on the first detected frame whose check fails
 *   once metering on the cube has settled (never on an episode's first detected frame, still exposed on the whole scene,
 *   nor while a metering change settles: an exposure transient is not evidence of bad light) and
 *   blocks auto and manual capture; the sides-dark coaching shows after [SIDES_DARK_COACHING_MS] of continuous failing
 *   with the cube detected and outranks every other corner coaching; the hold (and the line with it) clears once the
 *   check passed for [LIGHT_CLEAR_MS] in total since the last failing verdict, and the steady count restarts. While held,
 *   only a failing verdict restarts that pass window: undetected frames and frames with a face unscored are neutral
 *   (they neither advance nor reset it), and movement does not restart it (the capture after the clear re-meters, waits
 *   for the settle and needs [STEADY_MS] steady; device session 2026-11-21, S24 FE). An undetected frame clears the line
 *   at once; once the cube is lost ([LOST_MS]) the failing timer restarts, but the hold stays until a pass window after
 *   the cube is found again. The caller skips the check while the torch is lit (PO 2026-11-21): it passes no verdict and
 *   clears a running hold with [clearLightHold];
 * - an external settle signal: after each metering change a capture waits until the adapter reports the exposure
 *   settled, or [SETTLE_CAP_MS] passed, instead of the fixed [SETTLE_MS]; a manual press while settling is queued and
 *   fires once settled, unless the light hold engages or the cube is lost first;
 * - a per-face steady rule (PO 2026-11-21, [faceSteady]): the steady window restarts only when two or more cells of one
 *   face move, so a single flickering cell no longer blocks auto-capture; [steadyRestart] reports why the steady window
 *   last restarted, for the debug diagnostics;
 * - the torch state, which turns glare coaching into torch-glare coaching. A torch toggle is a metering change that
 *   starts only once the adapter applied the physical torch change ([awaitTorch], then [torchApplied], or
 *   [SETTLE_CAP_MS] at most): until then nothing is captured and a manual press is queued;
 * - a detection grace (device session 2026-11-21, S24 FE): the cube counts as lost only once it has been undetected for
 *   [LOST_MS]. A shorter gap keeps the metering, its settle state, the steady window and a queued press.
 */
internal class CornerHold(
    /** Quick Scan corners: a frame changed only when [MIN_MOVED_CELLS] or more cells of one face moved (Story 10.19). */
    private val faceSteady: Boolean = false,
) {
    enum class Decision { Wait, Remeter, Capture }

    /** What a manual press does now. */
    enum class Press { Take, Queued, Ignored }

    var latest = List<FrameStats?>(3) { null }
        private set
    var unlocated = false
        private set

    /** True once the cube has been undetected for [LOST_MS] or more on the last frame (the short-gap grace ran out). */
    var lostNow = false
        private set
    private var meteredAt: Long? = null
    private var reference: List<Lab?>? = null
    private var steadySince = 0L
    private var undetectedSince: Long? = null
    var coaching: Coaching? = null
        private set

    /** Why the steady window restarted on the last frame; null when it did not (or [faceSteady] is off). */
    var steadyRestart: CornerSteadyRestart? = null
        private set

    /** True while the light hold blocks capture. */
    var lightHeld = false
        private set

    /** When the running light hold engaged; null when none is held. */
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
        allowAuto: (List<Lab?>) -> Boolean,
    ): Decision {
        require(faces.size == 3)
        steadyRestart = null
        latest = faces
        unlocated = !detected
        externalSettle = settled != null
        if (detected) {
            undetectedSince = null
        } else if (undetectedSince == null) {
            undetectedSince = nowMs
        }
        lostNow = lost(nowMs)
        val values = values()
        if (detected && !allowAuto(values)) {
            // Time spent on a view that may not auto-capture never counts toward the next steady hold.
            reference = null
            return Decision.Wait
        }
        val metering = meteredAt
        if (detected && metering != null && !settledSinceMeter) {
            // Settled on this frame counts before the light check, so a settled frame may start a light hold.
            settledSinceMeter =
                if (settled == null) nowMs - metering >= SETTLE_MS else settled || nowMs - metering >= SETTLE_CAP_MS
        }
        val lightCleared = updateLight(detected, light, nowMs)
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
            meteredAt = null
            settledSinceMeter = false
            reference = null
            pendingPress = false
            return Decision.Wait
        }
        // The light hold engaging drops a queued press.
        if (lightHeld) pendingPress = false
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
        val metered = meteredAt
        if (metered == null || lightCleared) {
            // A metering change: the first detected frame of an episode, the light hold just cleared, or a torch change.
            meteredAt = nowMs
            settledSinceMeter = false
            remeterPending = false
            reference = null
            return Decision.Remeter
        }
        if (lightHeld) {
            reference = null
            pendingPress = false
            return Decision.Wait
        }
        if (pendingPress && settledSinceMeter) {
            pendingPress = false
            return Decision.Capture
        }
        if (qualities.any { it != CaptureQuality.ACCEPTABLE } || !settledSinceMeter) {
            reference = null
            return Decision.Wait
        }
        val baseline = reference
        val restart = if (faceSteady) baseline?.let { steadyMove(values, it) } else null
        if (baseline == null || (if (faceSteady) restart != null else changed(values, baseline, GuidedScanController.STEADY_DELTA_E))) {
            steadyRestart = restart
            reference = values
            steadySince = nowMs
            return Decision.Wait
        }
        return if (nowMs - steadySince >= STEADY_MS) Decision.Capture else Decision.Wait
    }

    /**
     * A manual press: ignored while the light hold blocks capture, queued while a torch change or its re-meter is pending
     * or an external settle signal is pending on a detected cube (it fires from [onFrame] once settled), otherwise taken
     * now.
     */
    fun press(): Press =
        when {
            lightHeld -> {
                Press.Ignored
            }

            meteringPending || (externalSettle && settling) -> {
                pendingPress = true
                Press.Queued
            }

            else -> {
                Press.Take
            }
        }

    /** A metering change outside the frame loop: the next detected frame re-meters, and a press until then is queued. */
    fun remeter() {
        meteredAt = null
        settledSinceMeter = false
        reference = null
        remeterPending = true
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

    /** Returns true when the light hold cleared on this frame. */
    private fun updateLight(
        detected: Boolean,
        light: SideLight.Verdict?,
        nowMs: Long,
    ): Boolean {
        if (!detected) {
            // The line clears at once; once the cube is lost the failing timer restarts. The pass window is untouched (an
            // undetected frame is neutral) and the hold stays until it completes after the cube is found again.
            sidesDark = false
            if (lost(nowMs)) lightFailingSince = null
            lastPassAt = null
            return false
        }
        light ?: return false
        if (lightHeld && light.score == null) {
            // A face was unreadable: no evidence either way, so the pass window neither advances nor resets.
            lastPassAt = null
            return false
        }
        if (!light.passed) {
            if (!lightHeld && (settling || meteredAt == null)) {
                // The first detected frame of an episode (exposed on the whole scene, it re-meters onto the cube) or an
                // exposure transient while a metering change settles never starts a hold (the verdict is still reported).
                resetPassWindow()
                return false
            }
            if (!lightHeld) {
                lightHeld = true
                lightHeldSince = nowMs
            }
            resetPassWindow()
            val failing = lightFailingSince ?: nowMs.also { lightFailingSince = it }
            if (nowMs - failing >= SIDES_DARK_COACHING_MS) sidesDark = true
            return false
        }
        // While held, a short pass does not restart the failing timer: a flickering verdict still gets its coaching.
        if (!lightHeld) {
            lightFailingSince = null
            return false
        }
        // Only consecutive passing frames add up: the time across a neutral frame never counts.
        lastPassAt?.let { passedMs += nowMs - it }
        lastPassAt = nowMs
        if (passedMs < LIGHT_CLEAR_MS) return false
        clearLight()
        return true
    }

    private fun resetPassWindow() {
        passedMs = 0L
        lastPassAt = null
    }

    /** True once the cube has been undetected for [LOST_MS] or more. */
    private fun lost(nowMs: Long): Boolean = undetectedSince?.let { nowMs - it >= LOST_MS } == true

    /** Clears a running light hold at once (the torch was lit, PO 2026-11-21: the check is skipped while it is). */
    fun clearLightHold() = clearLight()

    private fun clearLight() {
        lightHeld = false
        lightHeldSince = null
        sidesDark = false
        lightFailingSince = null
        resetPassWindow()
    }

    fun reset() {
        latest = List(3) { null }
        unlocated = false
        lostNow = false
        meteredAt = null
        settledSinceMeter = false
        reference = null
        undetectedSince = null
        coaching = null
        steadyRestart = null
        pendingPress = false
        remeterPending = false
        torchPendingSince = null
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

        /** The light check must pass this long (in total since the last failing verdict) to clear the light hold. */
        const val LIGHT_CLEAR_MS = 500L

        /** Undetected this long, the cube is lost: metering, settle, the steady window and a queued press restart. */
        const val LOST_MS = 300L

        /** With [faceSteady], the cells of one face that must move for a frame to count as changed. */
        const val MIN_MOVED_CELLS = 2
    }

    fun values(): List<Lab?> = latest.flatMap { face -> List(FrameQuality.CELLS) { face?.cells?.getOrNull(it)?.value } }

    /** The first face on which [MIN_MOVED_CELLS] or more cells moved by the steady threshold; null when none did. */
    private fun steadyMove(
        now: List<Lab?>,
        prior: List<Lab?>,
    ): CornerSteadyRestart? {
        for (face in 0 until 3) {
            val moves =
                (0 until FrameQuality.CELLS).mapNotNull { cell ->
                    val index = face * FrameQuality.CELLS + cell
                    val a = now.getOrNull(index)
                    val b = prior.getOrNull(index)
                    if (a == null || b == null) return@mapNotNull null
                    val delta = StickerClassifier.deltaE(a, b)
                    if (delta >= GuidedScanController.STEADY_DELTA_E) cell to delta else null
                }
            if (moves.size >= MIN_MOVED_CELLS) return CornerSteadyRestart(face, moves.map { it.first }, moves.maxOf { it.second })
        }
        return null
    }

    fun changed(
        now: List<Lab?>,
        prior: List<Lab?>,
        threshold: Double,
    ): Boolean = now.zip(prior).any { (a, b) -> a != null && b != null && StickerClassifier.deltaE(a, b) >= threshold }
}

/** Why a Quick Scan corner's steady window restarted: the [face] index (0 top), its moved [cells] and their [maxDeltaE]. */
data class CornerSteadyRestart(
    val face: Int,
    val cells: List<Int>,
    val maxDeltaE: Double,
)

/** Settle timing shared by the domain's corner hold and the camera adapter's settle signal (Story 10.19). */
object CornerSettle {
    /**
     * The longest a Quick Scan corner capture waits for the settle signal after a metering change (and for a torch change
     * to apply). Defined once: the camera adapter's settle signal times out at the same cap.
     */
    const val CAP_MS = 1500L
}
