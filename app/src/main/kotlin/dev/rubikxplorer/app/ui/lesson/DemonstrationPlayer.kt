package dev.rubikxplorer.app.ui.lesson

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.rubikxplorer.app.R
import dev.rubikxplorer.app.render.Net2DRenderer
import dev.rubikxplorer.app.render.NetEmphasis
import dev.rubikxplorer.app.render.PresentationItem
import dev.rubikxplorer.app.render.RenderStyle
import dev.rubikxplorer.app.ui.theme.RubikTheme
import dev.rubikxplorer.app.ui.theme.Spacing
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.playback.Demonstration
import dev.rubikxplorer.domain.playback.DemonstrationStep
import dev.rubikxplorer.domain.playback.PlaybackController
import dev.rubikxplorer.domain.playback.PlaybackItem
import dev.rubikxplorer.domain.playback.PlaybackSpeed
import dev.rubikxplorer.domain.playback.PlaybackUpdate
import kotlin.math.max
import kotlin.math.min

/**
 * Ephemeral state of one [DemonstrationPlayer]: the pure [PlaybackController] over the
 * [demonstration]'s Moves and the [renderer] its items are submitted to. It never touches the
 * session (playback is not a session and is never checkpointed). Use from the main thread.
 */
@Stable
class DemonstrationPlayerState internal constructor(
    val demonstration: Demonstration,
    reduceMotion: Boolean,
) {
    /** The net the Demonstration plays on, starting at the Lesson's entry state. */
    val renderer: Net2DRenderer = Net2DRenderer(demonstration.start.toFacelets())

    private var controller by mutableStateOf(PlaybackController.of(demonstration.start, demonstration.moves, reduceMotion))

    /** Increases with every submitted item, as [PresentationItem.revision] requires. */
    private var revision = 0L

    /** Moves played so far (0..[size]). */
    val position: Int get() = controller.position

    /** Number of Moves in the Demonstration. */
    val size: Int get() = controller.size

    val isPlaying: Boolean get() = controller.isPlaying

    val atEnd: Boolean get() = controller.atEnd

    val speed: PlaybackSpeed get() = controller.speed

    private val focusIndex: Int get() = max(position - 1, 0)

    /** The step holding the current Move, or null when the Demonstration has no steps. */
    val focusedStep: DemonstrationStep?
        get() = demonstration.steps.getOrNull(demonstration.stepIndexAt(focusIndex))

    /** The Move last played, or the first Move before any has played; null with no Moves. */
    val currentMove: Move? get() = demonstration.moves.getOrNull(focusIndex)

    /** The focused step's emphasis: its Keep, involved and target pieces lit, the target outlined. */
    val emphasis: NetEmphasis?
        get() = if (size == 0) null else focusedStep?.focus?.let { NetEmphasis(lit = it.lit, highlighted = it.target) }

    fun play() = apply(controller.play())

    fun pause() = apply(controller.pause())

    fun togglePlay() = if (isPlaying) pause() else play()

    fun stepForward() = apply(controller.stepForward())

    fun stepBack() = apply(controller.stepBack())

    fun replay() = apply(controller.replay())

    fun seekToEnd() = apply(controller.seek(size))

    fun scrubTo(fraction: Double) = apply(controller.scrubTo(fraction))

    fun setSpeed(speed: PlaybackSpeed) = apply(controller.withSpeed(speed))

    /** Lets [elapsedMillis] of frame time pass. */
    fun advance(elapsedMillis: Long) = apply(controller.advance(elapsedMillis))

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

/** The state of a [DemonstrationPlayer] for [demonstration]; new when it or [reduceMotion] changes. */
@Composable
fun rememberDemonstrationPlayerState(
    demonstration: Demonstration,
    reduceMotion: Boolean = rememberReduceMotion(),
): DemonstrationPlayerState = remember(demonstration, reduceMotion) { DemonstrationPlayerState(demonstration, reduceMotion) }

/**
 * A Demonstration (FR-6) on the 2D net: the focused step's off-step pieces dimmed, its Keep,
 * involved and target pieces and the centers lit, the target outlined; below it the shared playback transport (|◀ ◀ ▶/❚❚ ▶ ▶|), speed, the
 * step counter and the current Move. Under reduce motion the Moves step instead of animating.
 */
@Composable
fun DemonstrationPlayer(
    state: DemonstrationPlayerState,
    modifier: Modifier = Modifier,
    style: RenderStyle = RenderStyle(),
) {
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
    Column(
        modifier = modifier.testTag("demonstrationPlayer"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s2),
    ) {
        // Takes what the transport leaves, so a short stage shrinks the net rather than the controls.
        state.renderer.Content(style, Modifier.fillMaxWidth().weight(1f, fill = false).aspectRatio(NET_ASPECT), state.emphasis)
        PlaybackTransport(state)
    }
}

@Composable
private fun PlaybackTransport(state: DemonstrationPlayerState) {
    val tokens = RubikTheme.tokens
    val styles = RubikTheme.textStyles
    val any = state.size > 0
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
        TransportButton("playback:end", R.string.playback_end, R.string.playback_end_description, any && !state.atEnd, state::seekToEnd)
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
private const val NET_ASPECT = 12f / 9f

private val SPEED_LABELS =
    listOf(
        PlaybackSpeed.HALF to R.string.playback_speed_half,
        PlaybackSpeed.NORMAL to R.string.playback_speed_normal,
        PlaybackSpeed.DOUBLE to R.string.playback_speed_double,
    )
