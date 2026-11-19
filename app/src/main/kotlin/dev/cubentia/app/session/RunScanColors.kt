package dev.cubentia.app.session

import dev.cubentia.application.session.SessionEvent
import dev.cubentia.application.session.SessionState
import dev.cubentia.application.session.SyncState

/*
 * Run-scan colors (GH #172): the six centers the accepted Guided 6-face Scan that started a physical run measured, used
 * as the reference for 2-face Quick Checks in that run when no Color Calibration is saved. They are not Session State
 * and never persisted; [SessionHost] holds them in memory and sets and expires them in `dispatch` from these rules.
 */

/** Whether a start-scan [event] that [SessionHost] applied (`before` → `after`) may set new run-scan colors. */
internal fun setsRunScanColors(
    before: SessionState,
    event: SessionEvent,
    after: SessionState,
): Boolean =
    after !== before &&
        after.isActive &&
        after.syncState != SyncState.PHYSICAL_AWAITING_CONFIRM &&
        (event is SessionEvent.EditorEntered || (event is SessionEvent.SolvePlaybackStarted && event.fromAcceptedScan))

/**
 * Whether held run-scan colors survive [event] (`before` → `after`; an ignored event keeps them): they expire when the session ends or goes idle,
 * when a session starts, restores or ends, when the cube awaits confirmation again, or on a switch back to virtual.
 */
internal fun keepsRunScanColors(
    before: SessionState,
    event: SessionEvent,
    after: SessionState,
): Boolean =
    // An event the reducer ignored changes nothing, so it expires nothing either.
    after === before ||
        (
            after.isActive &&
                !startsOrRestores(event) &&
                after.syncState != SyncState.PHYSICAL_AWAITING_CONFIRM &&
                !(before.syncState != SyncState.VIRTUAL && after.syncState == SyncState.VIRTUAL)
        )

private fun startsOrRestores(event: SessionEvent): Boolean =
    event is SessionEvent.LessonStarted ||
        event is SessionEvent.PracticeStarted ||
        event is SessionEvent.SolvePlaybackStarted ||
        event is SessionEvent.SessionRestored ||
        event is SessionEvent.CheckpointRestored ||
        event is SessionEvent.SessionEnded ||
        event is SessionEvent.SolvePlaybackEnded
