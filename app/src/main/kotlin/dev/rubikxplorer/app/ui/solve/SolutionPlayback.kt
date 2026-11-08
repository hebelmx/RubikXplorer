package dev.rubikxplorer.app.ui.solve

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.rubikxplorer.app.R
import dev.rubikxplorer.app.content.ContentStrings
import dev.rubikxplorer.app.preferences.LocalRenderStyle
import dev.rubikxplorer.app.render.Net2DRenderer
import dev.rubikxplorer.app.render.RenderStyle
import dev.rubikxplorer.app.ui.lesson.FrameLabel
import dev.rubikxplorer.app.ui.lesson.rememberReduceMotion
import dev.rubikxplorer.app.ui.playback.NET_ASPECT
import dev.rubikxplorer.app.ui.playback.NetPlayback
import dev.rubikxplorer.app.ui.playback.PlaybackFrameLoop
import dev.rubikxplorer.app.ui.playback.PlaybackTransport
import dev.rubikxplorer.app.ui.playback.PlaybackTransportState
import dev.rubikxplorer.app.ui.theme.RubikTheme
import dev.rubikxplorer.app.ui.theme.Spacing
import dev.rubikxplorer.application.session.CubeMode
import dev.rubikxplorer.application.session.SessionEvent
import dev.rubikxplorer.application.session.SessionKind
import dev.rubikxplorer.application.session.SessionState
import dev.rubikxplorer.application.session.reduce
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.playback.MoveNote
import dev.rubikxplorer.domain.text.Text
import kotlin.time.Instant

/**
 * Ephemeral state of one [SolutionPlayback] (FR-16): the shared [NetPlayback] over a
 * `SOLVE_PLAYBACK` session's Solution from its entry state. Position and speed live here, never in
 * the session, and are never checkpointed (AD-20). Use from the main thread.
 */
@Stable
class SolutionPlaybackState private constructor(
    session: SessionState,
    private val playback: NetPlayback,
    internal var onEvent: (SessionEvent) -> Unit = {},
    internal var clock: () -> Instant = {
        kotlin.time.Clock.System
            .now()
    },
) : PlaybackTransportState by playback {
    internal constructor(
        session: SessionState,
        reduceMotion: Boolean,
        onEvent: (SessionEvent) -> Unit = {},
        clock: () -> Instant = {
            kotlin.time.Clock.System
                .now()
        },
    ) : this(
        session,
        NetPlayback(session.entry, session.solution, reduceMotion, Net2DRenderer(session.entry.toFacelets())),
        onEvent,
        clock,
    ) {
        require(session.kind == SessionKind.SOLVE_PLAYBACK) { "not a Solve Playback session: ${session.kind}" }
    }

    var session: SessionState by mutableStateOf(session)
        internal set

    /** The net the Solution plays on, starting at the session's entry state. */
    val renderer: Net2DRenderer get() = playback.renderer as Net2DRenderer

    /** The Solution being played. */
    val solution: List<Move> get() = playback.moves

    /** Whether the session is currently in physical mode (Story 8.5, AD-20). */
    val isPhysical: Boolean get() = session.isPhysical

    /**
     * In physical mode, reflects the step to be performed (`moves[position]`).
     * In virtual mode, reflects the Move last played, or the first before any has played.
     */
    override val currentMove: Move?
        get() =
            if (isPhysical) {
                playback.moves.getOrNull(playback.position)
            } else {
                playback.currentMove
            }

    /** The generated note of the current Move (AD-20); null with no Moves or when at end in physical mode. */
    val note: Text? get() = currentMove?.let(MoveNote::of)

    fun followOnMyCube() {
        if (isPhysical) return
        playback.pause()
        if (playback.position > 0) {
            playback.scrubTo(0.0)
        }
        dispatch(SessionEvent.ModeSwitched(CubeMode.PHYSICAL, clock()))
    }

    fun switchToVirtual() {
        if (!isPhysical) return
        dispatch(SessionEvent.ModeSwitched(CubeMode.VIRTUAL, clock()))
    }

    override fun stepBack() {
        if (position == 0) return
        playback.stepBack()
        if (isPhysical && session.history.canUndo) {
            dispatch(SessionEvent.Undo(clock()))
        }
    }

    fun confirmStep() {
        if (!isPhysical || atEnd) return
        val move = currentMove ?: return
        playback.stepForward()
        dispatch(SessionEvent.PhysicalConfirmed(move, clock()))
    }

    private fun dispatch(event: SessionEvent) {
        onEvent(event)
        session = reduce(session, event).state
    }

    internal fun updateSession(newSession: SessionState) {
        if (newSession.revision > session.revision) {
            val oldSession = session
            session = newSession
            if (!newSession.isPhysical &&
                !oldSession.isPhysical &&
                newSession.restoreNote == null &&
                oldSession.restoreNote == null
            ) {
                playback.pause()
                playback.scrubTo(0.0)
            }
        }
    }
}

/**
 * The state of a [SolutionPlayback] for a `SOLVE_PLAYBACK` [session]; new when the session's revision,
 * entry, Solution or [reduceMotion] changes, so a restarted session plays from its start again.
 */
@Composable
fun rememberSolutionPlaybackState(
    session: SessionState,
    reduceMotion: Boolean = rememberReduceMotion(),
    onEvent: (SessionEvent) -> Unit = {},
    clock: () -> Instant = {
        kotlin.time.Clock.System
            .now()
    },
): SolutionPlaybackState {
    require(session.kind == SessionKind.SOLVE_PLAYBACK) { "not a Solve Playback session: ${session.kind}" }
    val state =
        remember(session.entry, session.solution, reduceMotion) {
            SolutionPlaybackState(session, reduceMotion, onEvent, clock)
        }
    state.updateSession(session)
    state.onEvent = onEvent
    state.clock = clock
    return state
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
    val tokens = RubikTheme.tokens

    Column(
        modifier = modifier.testTag("solutionPlayback"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s2),
    ) {
        state.session.restoreNote?.let { note ->
            Text(
                text = strings.resolve(note),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.inkSecondary,
                modifier =
                    Modifier
                        .padding(horizontal = Spacing.s4, vertical = Spacing.s2)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("playback:restoreNote"),
            )
        }

        Box(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).aspectRatio(NET_ASPECT),
        ) {
            state.renderer.Content(style, Modifier.fillMaxSize())
            if (state.isPhysical) {
                FrameLabel(syncState = state.session.syncState)
            }
        }

        if (state.isPhysical) {
            PhysicalPlaybackControls(state, strings)
        } else {
            PlaybackTransport(state, note = state.note?.let(strings::resolve))
            Box(modifier = Modifier.testTag("playback:followOnCube")) {
                Button(
                    onClick = state::followOnMyCube,
                    shape = MaterialTheme.shapes.medium,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = tokens.accent,
                            contentColor = tokens.onAccent,
                        ),
                    modifier =
                        Modifier
                            .heightIn(min = Spacing.touchMin)
                            .testTag("playback:followOnMyCube"),
                ) {
                    Text(strings.resolve(Text("msg.playback.follow-on-my-cube")))
                }
            }
        }
    }
}

@Composable
private fun PhysicalPlaybackControls(
    state: SolutionPlaybackState,
    strings: ContentStrings,
    modifier: Modifier = Modifier,
) {
    val tokens = RubikTheme.tokens
    val styles = RubikTheme.textStyles

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s2),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s4),
        ) {
            Text(
                stringResource(R.string.playback_counter, state.position, state.size),
                style = styles.data,
                color = tokens.inkSecondary,
                modifier = Modifier.testTag("playback:counter"),
            )
            Text(
                state.currentMove?.let(Notation::format).orEmpty(),
                style = styles.notation,
                color = tokens.inkPrimary,
                modifier = Modifier.testTag("playback:move"),
            )
        }

        state.note?.let { noteText ->
            Text(
                strings.resolve(noteText),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.inkSecondary,
                modifier = Modifier.testTag("playback:note").semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
        ) {
            val backSpoken = stringResource(R.string.playback_step_back_description)
            TextButton(
                onClick = state::stepBack,
                enabled = state.position > 0,
                modifier =
                    Modifier
                        .heightIn(min = Spacing.touchMin)
                        .testTag("playback:back")
                        .semantics { contentDescription = backSpoken },
            ) {
                Text(
                    stringResource(R.string.playback_step_back),
                    color = if (state.position > 0) tokens.inkPrimary else tokens.inkDisabled,
                )
            }

            Button(
                onClick = state::confirmStep,
                enabled = !state.atEnd && state.size > 0,
                shape = MaterialTheme.shapes.medium,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = tokens.accent,
                        contentColor = tokens.onAccent,
                        disabledContainerColor = tokens.surfaceStage,
                        disabledContentColor = tokens.inkDisabled,
                    ),
                modifier =
                    Modifier
                        .heightIn(min = Spacing.touchMin)
                        .testTag("playback:done"),
            ) {
                Text(stringResource(R.string.physical_done))
            }
        }

        if (state.atEnd) {
            Text(
                text = strings.resolve(Text("msg.playback.cube-should-be-solved")),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.inkPrimary,
                modifier =
                    Modifier
                        .padding(horizontal = Spacing.s4, vertical = Spacing.s2)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("playback:solvedNow"),
            )
        }

        val physicalModeLabel = stringResource(R.string.physical_mode)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
        ) {
            TextButton(
                onClick = state::switchToVirtual,
                modifier =
                    Modifier
                        .heightIn(min = Spacing.touchMin)
                        .testTag("playback:switchToVirtual"),
            ) {
                Text(physicalModeLabel, color = tokens.inkSecondary)
            }
            Switch(
                checked = state.isPhysical,
                onCheckedChange = { if (!it) state.switchToVirtual() else state.followOnMyCube() },
                modifier =
                    Modifier
                        .testTag("playback:physicalModeSwitch")
                        .semantics { contentDescription = physicalModeLabel },
            )
        }
    }
}
