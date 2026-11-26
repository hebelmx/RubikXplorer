package dev.cubentia.domain.recognition

/**
 * Metering, coaching and steady-hold detection of Case capture's corner view: re-meter on the first detected frame of an
 * episode, wait [SETTLE_MS], then capture once the cells stayed steady for [STEADY_MS]. A detection gap shorter than
 * [LOST_MS] is ignored; a longer one restarts metering and the steady window.
 */
internal class CornerHold {
    enum class Decision { Wait, Remeter, Capture }

    var latest = List<FrameStats?>(3) { null }
        private set
    var coaching: Coaching? = null
        private set
    private var meteredAt: Long? = null
    private var undetectedSince: Long? = null
    private var reference: List<Lab?>? = null
    private var steadySince = 0L

    fun onFrame(
        detected: Boolean,
        faces: List<FrameStats?>,
        nowMs: Long,
        clearWhenUnlocated: Boolean,
        allowAuto: (List<Lab?>) -> Boolean,
    ): Decision {
        require(faces.size == 3)
        latest = faces
        undetectedSince = if (detected) null else undetectedSince ?: nowMs
        val values = values()
        if (detected && !allowAuto(values)) {
            reference = null
            return Decision.Wait
        }
        val qualities = faces.map { it?.let(FrameQuality::assessShutter) }
        val missingMs = undetectedSince?.let { nowMs - it } ?: 0L
        coaching =
            when {
                CaptureQuality.LOW_LIGHT in qualities -> Coaching.LOW_LIGHT
                CaptureQuality.GLARE in qualities -> Coaching.GLARE
                CaptureQuality.BLURRED in qualities -> Coaching.BLURRED
                missingMs >= UNDETECTED_COACHING_MS -> Coaching.PLAIN_LIGHT_SURFACE
                else -> null
            }
        if (!detected) {
            if (clearWhenUnlocated) latest = List(3) { null }
            // A short detection gap (one-frame flicker) is ignored: no re-meter, the settle and steady windows run on.
            if (missingMs >= LOST_MS) {
                meteredAt = null
                reference = null
            }
            return Decision.Wait
        }
        val metered = meteredAt
        if (metered == null) {
            meteredAt = nowMs
            return Decision.Remeter
        }
        if (nowMs - metered < SETTLE_MS || qualities.any { it != CaptureQuality.ACCEPTABLE }) {
            reference = null
            return Decision.Wait
        }
        val baseline = reference
        if (baseline == null || changed(values, baseline, GuidedScanController.STEADY_DELTA_E)) {
            reference = values
            steadySince = nowMs
        }
        return if (nowMs - steadySince >= STEADY_MS) Decision.Capture else Decision.Wait
    }

    companion object {
        /** The fixed settle wait after a metering change. */
        const val SETTLE_MS = 600L
        const val STEADY_MS = 1000L
        const val UNDETECTED_COACHING_MS = 3000L

        /** Undetected this long, the cube is lost: metering and the steady window restart. */
        const val LOST_MS = 300L
    }

    fun reset() {
        latest = List(3) { null }
        coaching = null
        undetectedSince = null
        reference = null
        meteredAt = null
    }

    fun values(): List<Lab?> = latest.flatMap { face -> List(FrameQuality.CELLS) { face?.cells?.getOrNull(it)?.value } }

    fun changed(
        now: List<Lab?>,
        prior: List<Lab?>,
        threshold: Double,
    ): Boolean = now.zip(prior).any { (a, b) -> a != null && b != null && StickerClassifier.deltaE(a, b) >= threshold }
}

/** Settle timing shared by the domain's corner hold and the camera adapter's settle signal (Story 10.19). */
object CornerSettle {
    /**
     * The longest a corner capture of the debug fixture recorder waits for the camera's settle signal after a metering
     * change (and for a torch change to apply). Defined once, for [dev.cubentia.domain.recognition] callers and the adapter.
     */
    const val CAP_MS = 1500L
}
