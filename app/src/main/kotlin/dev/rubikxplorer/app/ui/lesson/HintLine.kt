package dev.rubikxplorer.app.ui.lesson

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.rubikxplorer.app.render.Net2DRenderer
import dev.rubikxplorer.app.session.SessionHost
import dev.rubikxplorer.app.session.toItems
import dev.rubikxplorer.app.ui.theme.RubikTheme
import dev.rubikxplorer.application.session.SessionEvent
import dev.rubikxplorer.domain.playback.Demonstration
import dev.rubikxplorer.domain.tutor.HintRung

/**
 * The Hint Ladder's line for the shown rung (FR-11, UX-DR9): the conceptual clue, the target line or
 * the Demonstration line, already resolved from `HintLadder.line`. Primary ink in a polite live
 * region, so each climb is announced; nothing at rung 0 ([text] null).
 */
@Composable
internal fun HintLine(
    text: String?,
    modifier: Modifier = Modifier,
) {
    if (text == null) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = RubikTheme.tokens.inkPrimary,
        modifier = modifier.testTag("lesson:hint:line").semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * The Hint button's action: dispatches `HintRequested` and draws its presentation; when that climb
 * takes the shown rung (`SessionState.shownRung`) from [HintRung.TARGET] to [HintRung.DEMONSTRATION], plays the shown step's Moves on the net from the step's authored
 * start in [demonstration] ([demonstrateOnNet]), then snaps back to the session's state. The
 * playback dispatches nothing: the session's revision and undo history stay as they are. The caller
 * runs it as a cancellable job and stops it before any other session interaction.
 */
internal suspend fun climbHintLadder(
    host: SessionHost,
    renderer: Net2DRenderer,
    demonstration: Demonstration,
    reduceMotion: Boolean,
) {
    val before = host.state.value.shownRung
    val transition = host.dispatch(SessionEvent::HintRequested)
    renderer.submit(transition.presentation.toItems())
    val landed = transition.state
    if (before == HintRung.TARGET && landed.shownRung == HintRung.DEMONSTRATION) {
        demonstrateStep(host, renderer, demonstration, landed.stepIndex, reduceMotion)
    }
}

/**
 * Plays step [stepIndex]'s Moves on the net from the step's authored start in [demonstration]
 * ([demonstrateOnNet]); dispatches nothing. Used by the Hint Ladder's top rung and by adaptive
 * assistance's demonstrate level. The caller runs it as a cancellable job.
 */
internal suspend fun demonstrateStep(
    host: SessionHost,
    renderer: Net2DRenderer,
    demonstration: Demonstration,
    stepIndex: Int,
    reduceMotion: Boolean,
) {
    demonstration.steps.getOrNull(stepIndex)?.let { shown ->
        val from = demonstration.start.apply(demonstration.moves.take(shown.firstMove))
        demonstrateOnNet(host, renderer, from, shown.moves, reduceMotion)
    }
}
