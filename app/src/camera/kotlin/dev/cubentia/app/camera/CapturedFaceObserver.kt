package dev.cubentia.app.camera

import dev.cubentia.domain.recognition.QuickScanClassification

/**
 * Story 10.23 seam: an optional observer of Quick Scan's captured faces, bound only in debug builds that compose the shadow
 * sticker reader (`src/camera/debug/sticker`). Like [CornerDetector] it is a `@BindsOptionalOf` binding: with none bound
 * the capture path keeps no face crop and behaves exactly as before.
 *
 * It only observes. Every call returns nothing, and nothing it does reaches the Kotlin reads, the agreement gate, the keep
 * rule, `ScanAssembler` or the review (AD-9: the Kotlin classifier stays the reader of record). Calls come on the
 * analyzer thread (except [onScanEnded], on main); an implementation does its own work off that thread.
 */
internal interface CapturedFaceObserver {
    /**
     * A Quick Scan's corner view opened for hold 1: scan [scan] starts. Ids increase per process; this call is queued on the
     * analyzer thread while [onScanEnded] comes on main, so a start for an id already ended is stale and must be ignored.
     */
    fun onScanStarted(scan: Long)

    /**
     * Corner [hold] was captured. [crops] are the capturing frame's three unwarped faces in [CornerFrame.faces] order
     * (`[U/D, F/L, R/B]`), null where a quad could not be unwarped; [reports] are the capture's Kotlin reads of the faces
     * the hold judged (kept faces are absent). The crops are in memory only and must be dropped by [onScanEnded].
     */
    fun onCornerCaptured(
        hold: Int,
        crops: List<YuvFrame?>,
        reports: List<QuickScanClassification.FaceReport>,
    )

    /** Scan [scan] ended (finished or destroyed): queued work and every crop reference are dropped. */
    fun onScanEnded(scan: Long)
}
