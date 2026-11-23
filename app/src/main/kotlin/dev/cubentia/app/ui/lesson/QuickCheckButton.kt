package dev.cubentia.app.ui.lesson

import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import dev.cubentia.app.R
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.application.session.SyncState

/** Whether a Quick Check icon is highlighted (accent-filled); a test-only flag, not announced by TalkBack. */
val QuickCheckHighlightedKey = SemanticsPropertyKey<Boolean>("QuickCheckHighlighted")

/**
 * The Quick Check icon of a Lesson, Solution Playback or Algorithm practice (Story 14.3, #298): one button whose
 * colors switch, so TalkBack focus survives a highlight change. [highlighted] draws the accent container with an
 * onAccent icon, otherwise a transparent container with an inkSecondary icon; [label] ("Quick Check") is the same in
 * both states. [modifier] carries the screen's test tag.
 */
@Composable
fun QuickCheckButton(
    highlighted: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = RubikTheme.tokens
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.semantics { this[QuickCheckHighlightedKey] = highlighted },
        colors =
            if (highlighted) {
                IconButtonDefaults.filledIconButtonColors(containerColor = tokens.accent, contentColor = tokens.onAccent)
            } else {
                IconButtonDefaults.filledIconButtonColors(containerColor = Color.Transparent, contentColor = tokens.inkSecondary)
            },
    ) {
        Icon(painterResource(R.drawable.ic_camera), contentDescription = label)
    }
}

/**
 * Whether a Quick Check is suggested (EXPERIENCE.md Physical controls): in a mismatch, [afterMismatch] (until the next
 * *Done*), with a pending retry, or at a screen's [boundary] point.
 */
fun quickCheckSuggested(
    syncState: SyncState,
    afterMismatch: Boolean,
    retryPending: Boolean,
    boundary: Boolean,
): Boolean = syncState == SyncState.PHYSICAL_MISMATCH || afterMismatch || retryPending || boundary

/**
 * UI state (never in the session or a checkpoint, AD-3) holding whether a mismatch happened since the last *Done*: set
 * when the sync state enters `PHYSICAL_MISMATCH`, cleared only by [done] (PO 2026-11-22, #298), so *It matches after
 * all*, Undo / Back or a Quick Check that resolves the mismatch keep it.
 */
@Stable
class QuickCheckSuggestion internal constructor(
    private val afterMismatchState: MutableState<Boolean> = mutableStateOf(false),
) {
    /** Whether a mismatch happened since the last *Done*. */
    val afterMismatch: Boolean get() = afterMismatchState.value

    /** The Learner confirmed a Move with *Done*. */
    fun done() {
        afterMismatchState.value = false
    }

    internal fun mismatchEntered() {
        afterMismatchState.value = true
    }

    /** [quickCheckSuggested] with this tracker's after-mismatch flag. */
    fun suggested(
        syncState: SyncState,
        retryPending: Boolean,
        boundary: Boolean,
    ): Boolean = quickCheckSuggested(syncState, afterMismatch, retryPending, boundary)
}

/**
 * A [QuickCheckSuggestion] saved across recreation and reset for a new [key] (a Lesson, a Solution). It is set when
 * [syncState] enters `PHYSICAL_MISMATCH`; the effect is keyed by entering, so a *Done* tapped while still in the
 * mismatch is not undone.
 */
@Composable
fun rememberQuickCheckSuggestion(
    syncState: SyncState,
    key: Any?,
): QuickCheckSuggestion {
    val afterMismatch = rememberSaveable(key) { mutableStateOf(false) }
    // Whether the sync state was a mismatch when last seen, saved so recreation inside a mismatch does not set it again.
    var wasMismatch by rememberSaveable(key) { mutableStateOf(false) }
    val suggestion = remember(afterMismatch) { QuickCheckSuggestion(afterMismatch) }
    val inMismatch = syncState == SyncState.PHYSICAL_MISMATCH
    LaunchedEffect(inMismatch, suggestion) {
        if (inMismatch && !wasMismatch) suggestion.mismatchEntered()
        wasMismatch = inMismatch
    }
    return suggestion
}
