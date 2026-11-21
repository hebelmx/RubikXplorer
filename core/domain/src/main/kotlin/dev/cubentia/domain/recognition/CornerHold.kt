package dev.cubentia.domain.recognition

/**
 * Metering, coaching and steady-hold detection shared by both corner capture modes.
 *
 * Story 10.19 adds, for Quick Scan corners only (the case capture passes neither input and behaves as before):
 * - a light hold driven by a [SideLight.Verdict] per frame: it engages on the first detected frame whose check fails and
 *   blocks auto and manual capture; the sides-dark coaching shows after [SIDES_DARK_COACHING_MS] of continuous failing
 *   with the cube detected and outranks every other corner coaching; the hold (and the line with it) clears once the
 *   check passes for [LIGHT_CLEAR_MS] while steady, and the steady count restarts. Losing the cube clears the line at
 *   once and restarts the failing timer, but the hold stays until a pass-and-steady window after the cube is found again;
 * - an external settle signal: after each metering change a capture waits until the adapter reports the exposure
 *   settled, or [SETTLE_CAP_MS] passed, instead of the fixed [SETTLE_MS]; a manual press while settling is queued and
 *   fires once settled, unless the light hold engages or the cube is lost first;
 * - the torch state, which turns glare coaching into torch-glare coaching.
 */
internal class CornerHold {
    enum class Decision { Wait, Remeter, Capture }

    /** What a manual press does now. */
    enum class Press { Take, Queued, Ignored }

    var latest = List<FrameStats?>(3) { null }
        private set
    var unlocated = false
        private set
    private var meteredAt: Long? = null
    private var reference: List<Lab?>? = null
    private var steadySince = 0L
    private var undetectedSince: Long? = null
    var coaching: Coaching? = null
        private set

    /** True while the light hold blocks capture. */
    var lightHeld = false
        private set

    /** When the running light hold engaged; null when none is held. */
    var lightHeldSince: Long? = null
        private set
    private var lightFailingSince: Long? = null
    private var lightPassingSince: Long? = null
    private var lightReference: List<Lab?>? = null
    private var sidesDark = false

    /** The settle signal is external (Quick Scan corners) since the last frame that said so. */
    private var externalSettle = false
    private var settledSinceMeter = false
    private var pendingPress = false

    /** True while a metering change has not settled on a detected cube (a capture waits). */
    val settling: Boolean get() = !unlocated && meteredAt != null && !settledSinceMeter

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
        latest = faces
        unlocated = !detected
        externalSettle = settled != null
        if (detected) {
            undetectedSince = null
        } else if (undetectedSince == null) {
            undetectedSince = nowMs
        }
        val values = values()
        if (detected && !allowAuto(values)) {
            // Time spent on a view that may not auto-capture never counts toward the next steady hold.
            reference = null
            return Decision.Wait
        }
        val lightCleared = updateLight(detected, light, values, nowMs)
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
            meteredAt = null
            settledSinceMeter = false
            reference = null
            pendingPress = false
            return Decision.Wait
        }
        val metered = meteredAt
        if (metered == null || lightCleared) {
            // A metering change: the first detected frame of an episode, or the light hold just cleared.
            meteredAt = nowMs
            settledSinceMeter = false
            reference = null
            return Decision.Remeter
        }
        if (!settledSinceMeter) {
            settledSinceMeter =
                if (settled == null) nowMs - metered >= SETTLE_MS else settled || nowMs - metered >= SETTLE_CAP_MS
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
        if (baseline == null || changed(values, baseline, GuidedScanController.STEADY_DELTA_E)) {
            reference = values
            steadySince = nowMs
            return Decision.Wait
        }
        return if (nowMs - steadySince >= STEADY_MS) Decision.Capture else Decision.Wait
    }

    /**
     * A manual press: ignored while the light hold blocks capture, queued while an external settle signal is pending on
     * a detected cube (it fires from [onFrame] once settled), otherwise taken now.
     */
    fun press(): Press =
        when {
            lightHeld -> {
                Press.Ignored
            }

            externalSettle && settling -> {
                pendingPress = true
                Press.Queued
            }

            else -> {
                Press.Take
            }
        }

    /** A metering change outside the frame loop (the torch was toggled): the next detected frame re-meters. */
    fun remeter() {
        meteredAt = null
        settledSinceMeter = false
        reference = null
    }

    /** Returns true when the light hold cleared on this frame. */
    private fun updateLight(
        detected: Boolean,
        light: SideLight.Verdict?,
        values: List<Lab?>,
        nowMs: Long,
    ): Boolean {
        if (!detected) {
            // The line clears at once; the hold stays until a pass-and-steady window after the cube is found again.
            sidesDark = false
            lightFailingSince = null
            lightPassingSince = null
            lightReference = null
            return false
        }
        light ?: return false
        if (!light.passed) {
            if (!lightHeld) {
                lightHeld = true
                lightHeldSince = nowMs
            }
            lightPassingSince = null
            lightReference = null
            val failing = lightFailingSince ?: nowMs.also { lightFailingSince = it }
            if (nowMs - failing >= SIDES_DARK_COACHING_MS) sidesDark = true
            return false
        }
        lightFailingSince = null
        if (!lightHeld) return false
        val prior = lightReference
        val since = lightPassingSince
        if (since == null || prior == null || changed(values, prior, GuidedScanController.STEADY_DELTA_E)) {
            lightReference = values
            lightPassingSince = nowMs
            return false
        }
        if (nowMs - since < LIGHT_CLEAR_MS) return false
        clearLight()
        return true
    }

    private fun clearLight() {
        lightHeld = false
        lightHeldSince = null
        sidesDark = false
        lightFailingSince = null
        lightPassingSince = null
        lightReference = null
    }

    fun reset() {
        latest = List(3) { null }
        unlocated = false
        meteredAt = null
        settledSinceMeter = false
        reference = null
        undetectedSince = null
        coaching = null
        pendingPress = false
        clearLight()
    }

    companion object {
        /** The fixed settle wait when the adapter reports no settle signal. */
        const val SETTLE_MS = 600L

        /** The longest a capture waits for the external settle signal after a metering change. */
        const val SETTLE_CAP_MS = 1500L
        const val STEADY_MS = 1000L
        const val UNDETECTED_COACHING_MS = 3000L

        /** Continuous failing with the cube detected before the sides-dark coaching shows. */
        const val SIDES_DARK_COACHING_MS = 1000L

        /** The light check must pass this long while steady to clear the light hold. */
        const val LIGHT_CLEAR_MS = 500L
    }

    fun values(): List<Lab?> = latest.flatMap { face -> List(FrameQuality.CELLS) { face?.cells?.getOrNull(it)?.value } }

    fun changed(
        now: List<Lab?>,
        prior: List<Lab?>,
        threshold: Double,
    ): Boolean = now.zip(prior).any { (a, b) -> a != null && b != null && StickerClassifier.deltaE(a, b) >= threshold }
}
