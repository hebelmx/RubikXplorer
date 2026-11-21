package dev.cubentia.domain.recognition

/** Metering, coaching and steady-hold detection shared by both corner capture modes. */
internal class CornerHold {
    enum class Decision { Wait, Remeter, Capture }

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

    fun onFrame(
        detected: Boolean,
        faces: List<FrameStats?>,
        nowMs: Long,
        clearWhenUnlocated: Boolean,
        allowAuto: (List<Lab?>) -> Boolean,
    ): Decision {
        require(faces.size == 3)
        latest = faces
        unlocated = !detected
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
        val qualities = latest.map { it?.let(FrameQuality::assessShutter) }
        val longUndetected = !detected && undetectedSince?.let { nowMs - it >= UNDETECTED_COACHING_MS } == true
        coaching =
            when {
                CaptureQuality.LOW_LIGHT in qualities -> Coaching.LOW_LIGHT
                CaptureQuality.GLARE in qualities -> Coaching.GLARE
                CaptureQuality.BLURRED in qualities -> Coaching.BLURRED
                longUndetected -> Coaching.PLAIN_LIGHT_SURFACE
                else -> null
            }
        if (!detected) {
            if (clearWhenUnlocated) latest = List(3) { null }
            meteredAt = null
            reference = null
            return Decision.Wait
        }
        val metered = meteredAt
        if (metered == null) {
            meteredAt = nowMs
            return Decision.Remeter
        }
        if (qualities.any { it != CaptureQuality.ACCEPTABLE } || nowMs - metered < GuidedScanController.SETTLE_MS) {
            reference = null
            return Decision.Wait
        }
        val baseline = reference
        if (baseline == null || changed(values, baseline, GuidedScanController.STEADY_DELTA_E)) {
            reference = values
            steadySince = nowMs
            return Decision.Wait
        }
        return if (nowMs - steadySince >= GuidedScanController.STEADY_MS) Decision.Capture else Decision.Wait
    }

    fun reset() {
        latest = List(3) { null }
        unlocated = false
        meteredAt = null
        reference = null
        undetectedSince = null
        coaching = null
    }

    companion object {
        const val UNDETECTED_COACHING_MS = 3000L
    }

    fun values(): List<Lab?> = latest.flatMap { face -> List(FrameQuality.CELLS) { face?.cells?.getOrNull(it)?.value } }

    fun changed(
        now: List<Lab?>,
        prior: List<Lab?>,
        threshold: Double,
    ): Boolean = now.zip(prior).any { (a, b) -> a != null && b != null && StickerClassifier.deltaE(a, b) >= threshold }
}
