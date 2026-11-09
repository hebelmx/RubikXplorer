package dev.cubentia.app.ui.lesson

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.preferences.LocalRenderStyle
import dev.cubentia.app.render.Cube3DRenderer
import dev.cubentia.app.render.NetEmphasis
import dev.cubentia.app.render.RenderStyle
import dev.cubentia.app.ui.freecube.ViewControls
import dev.cubentia.app.ui.playback.NetPlayback
import dev.cubentia.app.ui.playback.PlaybackFrameLoop
import dev.cubentia.app.ui.playback.PlaybackTransport
import dev.cubentia.app.ui.playback.PlaybackTransportState
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.playback.Demonstration
import dev.cubentia.domain.playback.DemonstrationStep
import dev.cubentia.domain.playback.PlaybackSpeed
import dev.cubentia.domain.tutor.Certainty
import dev.cubentia.app.ui.playback.frameDelta as sharedFrameDelta

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
            Cube3DRenderer(demonstration.start.toFacelets()).let { renderer ->
                NetPlayback(demonstration.start, demonstration.moves, reduceMotion, renderer) { renderer.oldestLiveRevision }
            },
            reduceMotion,
        )

    /** The net the Demonstration plays on, starting at the Lesson's entry state. */
    val renderer: Cube3DRenderer get() = playback.renderer as Cube3DRenderer

    /**
     * The index of the Move being shown: the one the renderer is turning (the undone Move on Step
     * Back, the earlier Moves while rapid steps drain), else the current Move's once it is idle.
     */
    private val presentedIndex: Int
        get() = renderer.presentedRevision?.let(playback::moveIndexOf) ?: playback.focusIndex

    internal val shownMoveIndex: Int get() = presentedIndex

    /** The step holding the Move being shown, or null when the Demonstration has no steps. */
    val focusedStep: DemonstrationStep?
        get() = demonstration.steps.getOrNull(demonstration.stepIndexAt(presentedIndex))

    /** The focused step's emphasis: its Keep, involved and target pieces lit, the target outlined. */
    val emphasis: NetEmphasis?
        get() = if (size == 0) null else focusedStep?.focus?.let { NetEmphasis(lit = it.lit, highlighted = it.target) }

    /**
     * The Move shown, by the transport's Move label as by the arrow: the one the renderer is turning
     * (its inverse on Step Back, the earlier Moves while rapid steps drain), else the controller's
     * current Move once the renderer is idle.
     */
    override val currentMove: Move? get() = renderer.presentedMove ?: playback.currentMove

    /** The Move the arrow shows: the [currentMove]. */
    val arrow: Move? get() = currentMove

    fun play() = playback.play()

    fun pause() = playback.pause()

    /** Pauses on the nearest Move boundary and shows it at once, abandoning any turn still presenting. */
    fun scrubTo(fraction: Double) = playback.scrubNow(fraction, renderer::snapNow)
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
 * Above the cube the shared view controls turn and reset the camera, view-only as on the lesson
 * stage: they change neither the playback position nor the session. [summary], when given, is
 * what TalkBack reads for the cube (the shown state's Objective progress), and [certainty], when
 * given, adds the Lesson stage's frame label. TalkBack then walks frame label, cube, playback
 * controls (where the Learner stage has its Move key bar), then the view controls.
 */
@Composable
fun DemonstrationPlayer(
    state: DemonstrationPlayerState,
    strings: ContentStrings,
    modifier: Modifier = Modifier,
    style: RenderStyle = LocalRenderStyle.current,
    summary: String? = null,
    certainty: Certainty? = null,
) {
    PlaybackFrameLoop(state)
    Column(
        modifier = modifier.testTag("demonstrationPlayer").semantics { isTraversalGroup = true },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s2),
    ) {
        DemonstrationStage(state, strings, style, summary, certainty)
        PlaybackTransport(
            state,
            Modifier.semantics {
                isTraversalGroup = true
                traversalIndex = 2f
            },
        )
    }
}

@Composable
internal fun ColumnScope.DemonstrationStage(
    state: DemonstrationPlayerState,
    strings: ContentStrings,
    style: RenderStyle,
    summary: String?,
    certainty: Certainty?,
    showFrameLabel: Boolean = certainty != null,
    freeViewRotation: Boolean = false,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (showFrameLabel) FrameLabel(strings, certainty)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.semantics {
                isTraversalGroup = true
                traversalIndex = 3f
            },
        ) { ViewControls(state.renderer) }
    }
    // Takes what the view controls and the transport leave (as wide as it is tall, never wider
    // than its slot), so a short stage shrinks the net rather than overlapping the controls.
    state.renderer.Content(
        style,
        Modifier.weight(1f, fill = false).aspectRatio(1f).semantics { traversalIndex = 1f },
        emphasis = state.emphasis,
        arrow = state.arrow,
        reduceMotion = state.reduceMotion,
        summary = summary,
        freeViewRotation = freeViewRotation,
    )
}

/** The shared transport's frame cap ([sharedFrameDelta]), kept here for the Demonstration's tests. */
internal fun frameDelta(
    now: Long,
    last: Long,
    speed: PlaybackSpeed,
): Long = sharedFrameDelta(now, last, speed)
