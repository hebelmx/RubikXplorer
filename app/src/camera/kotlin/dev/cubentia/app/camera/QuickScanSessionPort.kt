package dev.cubentia.app.camera

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import dev.cubentia.domain.recognition.continuous.ContinuousScanExport

/*
 * Story 10.34 (AD-9 Frames, AD-15): the neutral port of the debug build's Quick Scan session recording. Interfaces and data
 * only, no Android I/O. [QuickScanSessionFactory] is `@BindsOptionalOf` ([QuickScanSessionOptionalModule]) and bound only by
 * `QuickScanSessionModule` in the debug camera source set: every other variant gets `Optional.empty()`, so Quick Scan
 * there has no session, no Rec control and no receiver, and behaves exactly as without this port.
 */

/** How the live Quick Scan screen is left while a session may be recording (answer 6 of the story's UI decisions). */
internal enum class QuickScanLeave { CLOSE, FACE_BY_FACE, ENTER_COLORS, REVIEW_AND_CORRECT }

/** Makes the one [QuickScanSession] of a Quick Scan screen (a `GuidedScanViewModel` in quick mode); main thread. */
internal fun interface QuickScanSessionFactory {
    fun create(host: QuickScanSessionHost): QuickScanSession
}

/** What the Quick Scan screen (its view model) offers a session; every member is called on the main thread. */
internal interface QuickScanSessionHost {
    /** Whether the live continuous scan is on screen (not a card, a review or the Guided hand-over). */
    val liveScanShown: Boolean

    /** Whether the screen's own discard question is open (a session sheet then waits behind it). */
    val hostDialogShown: Boolean

    /** The detector the engines run, for the session manifest. */
    val modelIdentity: ContinuousModelIdentity?

    /**
     * Clears the map as Reset does and, on the analyzer executor, makes the engine [ContinuousScanEngine.record] into [tap]
     * (frame index 0); [started] runs on main once frames go to [tap]. False (nothing done) while the screen is busy.
     */
    fun startCapture(
        tap: ContinuousFrameTap,
        started: () -> Unit,
    ): Boolean

    /** The engine's export, made on the analyzer executor and delivered on main; false (no delivery) while busy. */
    fun export(consume: (ContinuousScanExport) -> Unit): Boolean

    /** Clears the map as Reset does (a Reset the session held until its sheet was answered). */
    fun resetMap()

    /**
     * Swaps in a fresh engine recording into [tap] (the no-capture baseline run) and shows it like a new scan, keeping the
     * current engine and shown scan aside untouched; [started] runs on main once it analyses.
     */
    fun startBaseline(
        tap: ContinuousFrameTap,
        started: () -> Unit,
    ): Boolean

    /** Drops the baseline engine: the kept engine, map, count, cue and completion hold come back as they were. */
    fun endBaseline(ended: () -> Unit)

    /** The Scan Review hand-off the session held ([QuickScanSession.onCompleted]) continues. */
    fun releaseHandOff()
}

/**
 * The debug Quick Scan session recording of one Quick Scan screen. Hooks return whether the session took the action over
 * (the screen then does nothing more); they and [Overlay] run on main, [holdsAnalysis] is read on the analyzer thread too.
 */
internal interface QuickScanSession {
    /** True while the sheet, its discard question, the labeller or a held hand-off is up: no frame is analysed. */
    val holdsAnalysis: Boolean

    /** True while the Scan Review hand-off of a completed scan waits for the session (sheet, notice or labeller). */
    val holdsHandOff: Boolean

    /** True during the baseline run: Reset, Scan face by face, Review and Correct and Enter colors instead are disabled. */
    val locksActions: Boolean

    /** The scan completed: a recording stops (`complete`) and the sheet opens before the hand-off. */
    fun onCompleted(): Boolean

    /** Reset was tapped: a recording stops (`reset`) and the sheet opens; the map is cleared after Save or Discard. */
    fun onReset(): Boolean

    /** The screen is about to be left by [kind]: a recording or baseline run stops first. */
    fun onLeaving(kind: QuickScanLeave): Boolean

    /** The app went to the background: a recording or baseline run stops; the sheet shows on return. */
    fun onBackground(): Boolean

    /** The detector became unavailable: the screen is left for its card, so a pending session is discarded. */
    fun onDetectorUnavailable(): Boolean

    /** The screen is gone for good (its view model is cleared): an unsaved session is discarded, the receiver unregistered. */
    fun release()

    /** The Rec control, indicator, notice, sheet and labeller, drawn inside the Quick Scan camera area. */
    @Composable
    fun Overlay(scope: BoxScope)
}
