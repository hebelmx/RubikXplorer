package dev.rubikxplorer.app.ui.lesson

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
import dev.rubikxplorer.app.render.Cube3DRenderer
import dev.rubikxplorer.app.render.NetEmphasis
import dev.rubikxplorer.app.render.RenderStyle
import dev.rubikxplorer.app.ui.playback.NetPlayback
import dev.rubikxplorer.app.ui.playback.PlaybackFrameLoop
import dev.rubikxplorer.app.ui.playback.PlaybackTransport
import dev.rubikxplorer.app.ui.playback.PlaybackTransportState
import dev.rubikxplorer.app.ui.theme.Spacing
import dev.rubikxplorer.domain.playback.Demonstration
import dev.rubikxplorer.domain.playback.DemonstrationStep
import dev.rubikxplorer.domain.playback.PlaybackSpeed
import dev.rubikxplorer.app.ui.playback.frameDelta as sharedFrameDelta

/**
 * Ephemeral state of one [DemonstrationPlayer]: the shared [NetPlayback] over the
 * [demonstration]'s Moves and the [renderer] its items are submitted to. It never touches the
 * session (playback is not a session and is never checkpointed). Use from the main thread.
 */
@Stable
class DemonstrationPlayerState private constructor(
    val demonstration: Demonstration,
    private val playback: NetPlayback,
    val reduceMotion: Boolean,
) : PlaybackTransportState by playback {
    internal constructor(demonstration: Demonstration, reduceMotion: Boolean) :
        this(
            demonstration,
            NetPlayback(demonstration.start, demonstration.moves, reduceMotion, Cube3DRenderer(demonstration.start.toFacelets())),
            reduceMotion,
        )

    /** The net the Demonstration plays on, starting at the Lesson's entry state. */
    val renderer: Cube3DRenderer get() = playback.renderer as Cube3DRenderer

    /** The step holding the current Move, or null when the Demonstration has no steps. */
    val focusedStep: DemonstrationStep?
        get() = demonstration.steps.getOrNull(demonstration.stepIndexAt(playback.focusIndex))

    /** The focused step's emphasis: its Keep, involved and target pieces lit, the target outlined. */
    val emphasis: NetEmphasis?
        get() = if (size == 0) null else focusedStep?.focus?.let { NetEmphasis(lit = it.lit, highlighted = it.target) }

    fun play() = playback.play()

    fun pause() = playback.pause()

    fun scrubTo(fraction: Double) = playback.scrubTo(fraction)
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
    PlaybackFrameLoop(state)
    Column(
        modifier = modifier.testTag("demonstrationPlayer"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s2),
    ) {
        // Takes what the transport leaves, so a short stage shrinks the net rather than the controls.
        state.renderer.Content(
            style,
            Modifier.fillMaxWidth().weight(1f, fill = false).aspectRatio(1f),
            emphasis = state.emphasis,
            arrow = state.currentMove,
            reduceMotion = state.reduceMotion,
        )
        PlaybackTransport(state)
    }
}

/** The shared transport's frame cap ([sharedFrameDelta]), kept here for the Demonstration's tests. */
internal fun frameDelta(
    now: Long,
    last: Long,
    speed: PlaybackSpeed,
): Long = sharedFrameDelta(now, last, speed)
