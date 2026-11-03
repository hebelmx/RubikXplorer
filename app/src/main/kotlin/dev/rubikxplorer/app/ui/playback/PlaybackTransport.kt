package dev.rubikxplorer.app.ui.playback

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.rubikxplorer.app.R
import dev.rubikxplorer.app.render.Net2DRenderer
import dev.rubikxplorer.app.render.PresentationItem
import dev.rubikxplorer.app.ui.theme.RubikTheme
import dev.rubikxplorer.app.ui.theme.Spacing
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.playback.PlaybackController
import dev.rubikxplorer.domain.playback.PlaybackItem
import dev.rubikxplorer.domain.playback.PlaybackSpeed
import dev.rubikxplorer.domain.playback.PlaybackUpdate
import kotlin.math.max
import kotlin.math.min

/** What the shared [PlaybackTransport] reads and drives (AD-20): one player over a Move list. */
@Stable
interface PlaybackTransportState {
    /** Moves played so far (0..[size]). */
    val position: Int

    /** Number of Moves. */
    val size: Int

    val isPlaying: Boolean

    val atEnd: Boolean

    val speed: PlaybackSpeed

    /** The Move last played, or the first Move before any has played; null with no Moves. */
    val currentMove: Move?

    fun togglePlay()

    fun stepForward()

    fun stepBack()

    fun replay()

    fun seekToEnd()

    fun setSpeed(speed: PlaybackSpeed)

    /** Lets [elapsedMillis] of frame time pass. */
    fun advance(elapsedMillis: Long)
}

/**
 * The pure [PlaybackController] over [moves] from [start] driving a [renderer] (AD-20). Ephemeral:
 * it never touches the session and is never checkpointed. Use from the main thread.
 */
@Stable
class NetPlayback(
    start: CubeState,
    moves: List<Move>,
    reduceMotion: Boolean,
) : PlaybackTransportState {
    /** The net the Moves play on, starting at [start]. */
    val renderer: Net2DRenderer = Net2DRenderer(start.toFacelets())

    private var controller by mutableStateOf(PlaybackController.of(start, moves, reduceMotion))

    /** Increases with every submitted item, as [PresentationItem.revision] requires. */
    private var revision = 0L

    /** The Moves played. */
    val moves: List<Move> get() = controller.moves

    override val position: Int get() = controller.position

    override val size: Int get() = controller.size

    override val isPlaying: Boolean get() = controller.isPlaying

    override val atEnd: Boolean get() = controller.atEnd

    override val speed: PlaybackSpeed get() = controller.speed

    /** The index of [currentMove]: the Move last played, or the first before any has played. */
    val focusIndex: Int get() = max(position - 1, 0)

    override val currentMove: Move? get() = controller.moves.getOrNull(focusIndex)

    fun play() = apply(controller.play())

    fun pause() = apply(controller.pause())

    override fun togglePlay() = if (isPlaying) pause() else play()

    override fun stepForward() = apply(controller.stepForward())

    override fun stepBack() = apply(controller.stepBack())

    override fun replay() = apply(controller.replay())

    override fun seekToEnd() = apply(controller.seek(size))

    fun scrubTo(fraction: Double) = apply(controller.scrubTo(fraction))

    override fun setSpeed(speed: PlaybackSpeed) = apply(controller.withSpeed(speed))

    override fun advance(elapsedMillis: Long) = apply(controller.advance(elapsedMillis))

    private fun apply(update: PlaybackUpdate) {
        controller = update.controller
        if (update.items.isNotEmpty()) renderer.submit(update.items.map { it.toPresentation(++revision) })
    }

    private fun PlaybackItem.toPresentation(revision: Long): PresentationItem =
        when (this) {
            is PlaybackItem.Animate -> PresentationItem.Animate(move, revision, target.toFacelets())
            is PlaybackItem.Snap -> PresentationItem.Snap(revision, target.toFacelets())
        }
}

/** Drives [state] from frame time while it plays, one Move per step at its speed. */
@Composable
fun PlaybackFrameLoop(state: PlaybackTransportState) {
    LaunchedEffect(state, state.isPlaying) {
        if (!state.isPlaying) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (state.isPlaying) {
            withFrameMillis { now ->
                state.advance(frameDelta(now, last, state.speed))
                last = now
            }
        }
    }
}

/**
 * The one playback transport (AD-20, UX-DR14): |◀ ◀ ▶/❚❚ ▶ ▶|, the speed choice, the step counter
 * and the current Move, with the optional [note] (already resolved) under the Move.
 */
@Composable
fun PlaybackTransport(
    state: PlaybackTransportState,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    val tokens = RubikTheme.tokens
    val styles = RubikTheme.textStyles
    val any = state.size > 0
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TransportButton("playback:restart", R.string.playback_restart, R.string.playback_restart_description, any, state::replay)
            TransportButton(
                "playback:back",
                R.string.playback_step_back,
                R.string.playback_step_back_description,
                state.position > 0,
                state::stepBack,
            )
            if (state.isPlaying) {
                TransportButton("playback:playPause", R.string.playback_pause, R.string.playback_pause_description, any, state::togglePlay)
            } else {
                TransportButton("playback:playPause", R.string.playback_play, R.string.playback_play_description, any, state::togglePlay)
            }
            TransportButton(
                "playback:forward",
                R.string.playback_step_forward,
                R.string.playback_step_forward_description,
                any && !state.atEnd,
                state::stepForward,
            )
            TransportButton(
                "playback:end",
                R.string.playback_end,
                R.string.playback_end_description,
                any && !state.atEnd,
                state::seekToEnd,
            )
        }
        val speedDescription = stringResource(R.string.playback_speed_description)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.selectableGroup().semantics { contentDescription = speedDescription },
        ) {
            SPEED_LABELS.forEach { (speed, label) ->
                val selected = state.speed == speed
                TextButton(
                    onClick = { state.setSpeed(speed) },
                    enabled = any,
                    modifier =
                        Modifier
                            .heightIn(min = Spacing.touchMin)
                            .testTag("playback:speed:${speed.name}")
                            .semantics {
                                this.selected = selected
                                role = Role.RadioButton
                            },
                ) {
                    Text(stringResource(label), color = if (selected) tokens.accent else tokens.inkSecondary)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s4)) {
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
        note?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.inkSecondary,
                // Announced when it changes, so TalkBack users hear the new Move's note.
                modifier = Modifier.testTag("playback:note").semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun TransportButton(
    tag: String,
    @StringRes glyph: Int,
    @StringRes description: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val spoken = stringResource(description)
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier =
            Modifier
                .heightIn(min = Spacing.touchMin)
                .testTag(tag)
                .semantics { contentDescription = spoken },
    ) {
        Text(stringResource(glyph), color = if (enabled) RubikTheme.tokens.inkPrimary else RubikTheme.tokens.inkDisabled)
    }
}

/**
 * The time a frame lets pass: [now] − [last], capped at one Move's step, so a stalled frame
 * (backgrounded app, jank) never emits a burst of Moves.
 */
internal fun frameDelta(
    now: Long,
    last: Long,
    speed: PlaybackSpeed,
): Long = min(now - last, speed.stepMillis)

/** Width over height of the 12 × 9 cross net. */
const val NET_ASPECT: Float = 12f / 9f

private val SPEED_LABELS =
    listOf(
        PlaybackSpeed.HALF to R.string.playback_speed_half,
        PlaybackSpeed.NORMAL to R.string.playback_speed_normal,
        PlaybackSpeed.DOUBLE to R.string.playback_speed_double,
    )
