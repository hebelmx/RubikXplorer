package dev.rubikxplorer.app.ui.solve

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.rubikxplorer.app.content.ContentStrings
import dev.rubikxplorer.app.preferences.LocalRenderStyle
import dev.rubikxplorer.app.render.Net2DRenderer
import dev.rubikxplorer.app.render.RenderStyle
import dev.rubikxplorer.app.ui.lesson.rememberReduceMotion
import dev.rubikxplorer.app.ui.playback.NET_ASPECT
import dev.rubikxplorer.app.ui.playback.NetPlayback
import dev.rubikxplorer.app.ui.playback.PlaybackFrameLoop
import dev.rubikxplorer.app.ui.playback.PlaybackTransport
import dev.rubikxplorer.app.ui.playback.PlaybackTransportState
import dev.rubikxplorer.app.ui.theme.Spacing
import dev.rubikxplorer.application.session.SessionKind
import dev.rubikxplorer.application.session.SessionState
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.playback.MoveNote
import dev.rubikxplorer.domain.text.Text

/**
 * Ephemeral state of one [SolutionPlayback] (FR-16): the shared [NetPlayback] over a
 * `SOLVE_PLAYBACK` session's Solution from its entry state. Position and speed live here, never in
 * the session, and are never checkpointed (AD-20). Use from the main thread.
 */
@Stable
class SolutionPlaybackState private constructor(
    private val playback: NetPlayback,
) : PlaybackTransportState by playback {
    internal constructor(session: SessionState, reduceMotion: Boolean) :
        this(NetPlayback(session.entry, session.solution, reduceMotion, Net2DRenderer(session.entry.toFacelets()))) {
        require(session.kind == SessionKind.SOLVE_PLAYBACK) { "not a Solve Playback session: ${session.kind}" }
    }

    /** The net the Solution plays on, starting at the session's entry state. */
    val renderer: Net2DRenderer get() = playback.renderer as Net2DRenderer

    /** The Solution being played. */
    val solution: List<Move> get() = playback.moves

    /** The generated note of the current Move (AD-20); null with no Moves. */
    val note: Text? get() = currentMove?.let(MoveNote::of)
}

/**
 * The state of a [SolutionPlayback] for a `SOLVE_PLAYBACK` [session]; new when the session's revision,
 * entry, Solution or [reduceMotion] changes, so a restarted session plays from its start again.
 */
@Composable
fun rememberSolutionPlaybackState(
    session: SessionState,
    reduceMotion: Boolean = rememberReduceMotion(),
): SolutionPlaybackState {
    require(session.kind == SessionKind.SOLVE_PLAYBACK) { "not a Solve Playback session: ${session.kind}" }
    return remember(session.revision, session.entry, session.solution, reduceMotion) { SolutionPlaybackState(session, reduceMotion) }
}

/**
 * A verified Solution (FR-16) on the 2D net with the shared playback transport (forward, back,
 * pause, replay, speed) and, under the current Move, its note from [MoveNote], resolved through
 * [strings]. The last step draws the solved state.
 */
@Composable
fun SolutionPlayback(
    state: SolutionPlaybackState,
    strings: ContentStrings,
    modifier: Modifier = Modifier,
    style: RenderStyle = LocalRenderStyle.current,
) {
    PlaybackFrameLoop(state)
    Column(
        modifier = modifier.testTag("solutionPlayback"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s2),
    ) {
        state.renderer.Content(style, Modifier.fillMaxWidth().weight(1f, fill = false).aspectRatio(NET_ASPECT))
        PlaybackTransport(state, note = state.note?.let(strings::resolve))
    }
}
