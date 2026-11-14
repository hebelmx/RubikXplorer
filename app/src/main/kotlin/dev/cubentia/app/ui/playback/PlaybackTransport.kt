package dev.cubentia.app.ui.playback

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.semantics.stateDescription
import dev.cubentia.app.R
import dev.cubentia.app.render.CubeRenderer
import dev.cubentia.app.render.PresentationItem
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.playback.PlaybackController
import dev.cubentia.domain.playback.PlaybackItem
import dev.cubentia.domain.playback.PlaybackSpeed
import dev.cubentia.domain.playback.PlaybackUpdate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
 * it never touches the session and is never checkpointed. Use from the main thread. The owner
 * supplies [renderer], which must already show [start]. An owner that asks [moveIndexOf] supplies
 * [oldestLiveRevision]: the oldest revision the renderer may still present, or null once it is idle;
 * without it no mapping is kept.
 */
@Stable
class NetPlayback(
    start: CubeState,
    moves: List<Move>,
    reduceMotion: Boolean,
    val renderer: CubeRenderer,
    private val oldestLiveRevision: (() -> Long?)? = null,
) : PlaybackTransportState {
    private var controller by mutableStateOf(PlaybackController.of(start, moves, reduceMotion))

    /** Increases with every submitted item, as [PresentationItem.revision] requires. */
    private var revision = 0L

    /**
     * The index in [moves] of each Animate item's Move, by its revision, for items the renderer may
     * still present; drained items are discarded on the next submit.
     */
    private val animatedIndex = HashMap<Long, Int>()

    /** How many revisions [moveIndexOf] still maps. */
    internal val mappedRevisions: Int get() = animatedIndex.size

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

    /**
     * [scrubTo] that does not queue: everything outstanding is abandoned and [show] receives the
     * snapped boundary, for a renderer able to replace what it is presenting.
     */
    fun scrubNow(
        fraction: Double,
        show: (Facelets) -> Unit,
    ) {
        if (size == 0) return
        controller = controller.scrubTo(fraction).controller
        animatedIndex.clear()
        show(controller.state.toFacelets())
    }

    override fun setSpeed(speed: PlaybackSpeed) = apply(controller.withSpeed(speed))

    override fun advance(elapsedMillis: Long) = apply(controller.advance(elapsedMillis))

    /**
     * The index in [moves] of the Move the item submitted with [revision] animates: the Move played
     * forward, or the undone Move on Step Back; null for a Snap or an unknown revision.
     */
    fun moveIndexOf(revision: Long): Int? = animatedIndex[revision]

    private fun apply(update: PlaybackUpdate) {
        var from = controller.position
        controller = update.controller
        if (update.items.isEmpty()) return
        val live = oldestLiveRevision?.invoke()
        if (live == null) animatedIndex.clear() else animatedIndex.keys.removeIf { it < live }
        renderer.submit(
            update.items.map { item ->
                val presentation = item.toPresentation(++revision)
                if (oldestLiveRevision != null && item is PlaybackItem.Animate) {
                    animatedIndex[revision] = if (item.position > from) item.position - 1 else item.position
                }
                from = item.position
                presentation
            },
        )
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
    onScrub: ((Double) -> Unit)? = null,
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
        if (onScrub != null && any) {
            val description = stringResource(R.string.playback_scrub_description)
            val counter = stringResource(R.string.playback_counter, state.position, state.size)
            Slider(
                value = state.position.toFloat(),
                onValueChange = { value ->
                    val boundary = value.roundToInt().coerceIn(0, state.size)
                    if (boundary != state.position || state.isPlaying) onScrub(boundary.toDouble() / state.size)
                },
                valueRange = 0f..state.size.toFloat(),
                steps = state.size - 1,
                colors =
                    SliderDefaults.colors(
                        thumbColor = tokens.inkPrimary,
                        activeTrackColor = tokens.inkPrimary,
                        inactiveTrackColor = tokens.hairline,
                    ),
                modifier =
                    Modifier.testTag("playback:scrub").semantics {
                        contentDescription = description
                        stateDescription = counter
                    },
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
                modifier =
                    Modifier
                        .padding(
                            horizontal = Spacing.s4,
                        ).testTag("playback:note")
                        .semantics { liveRegion = LiveRegionMode.Polite },
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
