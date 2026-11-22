package dev.cubentia.app.ui.solve

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import dev.cubentia.app.R
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.preferences.LocalPreferenceStore
import dev.cubentia.app.preferences.LocalRenderStyle
import dev.cubentia.app.render.Cube3DRenderer
import dev.cubentia.app.render.CubeRenderer
import dev.cubentia.app.render.Net2DRenderer
import dev.cubentia.app.render.RenderStyle
import dev.cubentia.app.render.ViewRotatedBadge
import dev.cubentia.app.scan.QuickCheckCapture
import dev.cubentia.app.ui.freecube.ToolbarButton
import dev.cubentia.app.ui.freecube.ViewControls
import dev.cubentia.app.ui.lesson.rememberReduceMotion
import dev.cubentia.app.ui.playback.MoveStrip
import dev.cubentia.app.ui.playback.NET_ASPECT
import dev.cubentia.app.ui.playback.NetPlayback
import dev.cubentia.app.ui.playback.PlaybackFrameLoop
import dev.cubentia.app.ui.playback.PlaybackMoveLabel
import dev.cubentia.app.ui.playback.PlaybackSegmentLabel
import dev.cubentia.app.ui.playback.PlaybackTransport
import dev.cubentia.app.ui.playback.PlaybackTransportState
import dev.cubentia.app.ui.playback.playbackControlsScroll
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.application.preference.PlaybackView
import dev.cubentia.application.preference.Preference
import dev.cubentia.application.session.CubeMode
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.application.session.SessionKind
import dev.cubentia.application.session.SessionState
import dev.cubentia.application.session.SyncState
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.playback.MoveNote
import kotlin.time.Instant
import dev.cubentia.domain.text.Text as ContentText

/**
 * Ephemeral state of one [SolutionPlayback] (FR-16): the shared [NetPlayback] over a
 * `SOLVE_PLAYBACK` session's Solution from its entry state. Position and speed live here, never in
 * the session, and are never checkpointed (AD-20). Use from the main thread.
 */
@Stable
class SolutionPlaybackState private constructor(
    session: SessionState,
    private val playback: NetPlayback,
    val reduceMotion: Boolean,
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
        initialView: PlaybackView = defaultPlaybackView(session),
        leftPhysicalAt: Int? = null,
        opensOnNet: Boolean = false,
    ) : this(
        session,
        origin(session).let { NetPlayback(it, session.solution, reduceMotion, initialView.rendererAt(it.toFacelets())) },
        reduceMotion,
        onEvent,
        clock,
    ) {
        this.leftPhysicalAt = leftPhysicalAt
        this.opensOnNet = opensOnNet
        if (leftPhysicalAt != null && session.isPhysical) alignToExpected(rebuild = true)
        require(session.kind == SessionKind.SOLVE_PLAYBACK) { "not a Solve Playback session: ${session.kind}" }
    }

    private var start: CubeState = origin(session)

    /**
     * The position held when the Learner last left physical mode, to resume there among equal states; kept by
     * [rememberSolutionPlaybackState] across recreation through [onLeftPhysical].
     */
    private var leftPhysicalAt: Int? = null

    /**
     * Whether this playback opened on the 2D net whatever the saved view (an Algorithm practice, Story 14.2 AC1); it then
     * follows the saved view only once that changes, and the saved view itself is left as it is.
     */
    internal var opensOnNet: Boolean = false
        private set

    /** Told the position each time the Learner leaves physical mode. */
    internal var onLeftPhysical: (Int) -> Unit = {}

    var session: SessionState by mutableStateOf(session)
        internal set

    val renderer: CubeRenderer get() = playback.renderer

    val view: PlaybackView
        get() = if (renderer is Cube3DRenderer) PlaybackView.CUBE_3D else PlaybackView.NET_2D

    fun showView(view: PlaybackView) {
        if (view != this.view) playback.replaceRenderer(view::rendererAt)
    }

    /** The Solution being played. */
    val solution: List<Move> get() = playback.moves

    /** Whether the session is currently in physical mode (Story 8.5, AD-20). */
    val isPhysical: Boolean get() = session.isPhysical

    override val currentIndex: Int?
        get() = if (isPhysical) position.takeIf { it < size } else playback.currentIndex

    fun seekTo(fraction: Double) {
        if (isPhysical) return
        val cube = renderer as? Cube3DRenderer
        if (cube != null) {
            playback.scrubNow(fraction, cube::snapNow)
        } else {
            playback.scrubNow(fraction) { playback.replaceRenderer(PlaybackView.NET_2D::rendererAt) }
        }
    }

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

    /**
     * The name of the current step's segment among [labels] (AD-20), used only while [labels] hold the Moves being
     * played; null with no current step (at the end in physical mode).
     */
    fun segmentOf(labels: SegmentLabels?): ContentText? = labels?.takeIf { it.moves == solution }?.at(currentIndex)

    /** The generated note of the current Move (AD-20); null with no Moves or when at end in physical mode. */
    val note: ContentText? get() = currentMove?.let(MoveNote::of)

    init {
        // A state rebuilt mid-follow (e.g. after Activity recreation) starts at the host's Expected State, not at 0.
        if (session.isPhysical) alignToExpected(rebuild = true)
    }

    fun followOnMyCube() {
        if (isPhysical) return
        playback.pause()
        alignToExpected()
        dispatch(SessionEvent.ModeSwitched(CubeMode.PHYSICAL, clock()))
    }

    /**
     * Moves to the position whose state is the session's Expected State. A Solution can pass the same state more than
     * once (an Algorithm practice from solved holds the same cube at k and 2n - k, and ends where it starts), so among
     * equal states, on a physical [rebuild] after Moves were confirmed since the switch, it prefers one whose preceding
     * Moves are those; otherwise the position held when the Learner left physical mode, else the first.
     */
    private fun alignToExpected(rebuild: Boolean = false) {
        val expectedPosition =
            if (size > 0) {
                val matches = (0..size).filter { index -> start.apply(solution.take(index)) == session.expected }
                val done = session.history.done
                matches
                    .takeIf { rebuild && done.isNotEmpty() }
                    ?.firstOrNull { index -> index >= done.size && solution.subList(index - done.size, index) == done }
                    ?: matches.firstOrNull { it == leftPhysicalAt }
                    ?: matches.firstOrNull()
                    ?: 0
            } else {
                0
            }
        if (playback.position != expectedPosition) {
            val fraction = expectedPosition.toDouble() / size
            if (rebuild) {
                val build = view::rendererAt
                playback.scrubNow(fraction) { playback.replaceRenderer(build) }
            } else {
                playback.scrubTo(fraction)
            }
        }
    }

    fun switchToVirtual() {
        if (!isPhysical) return
        leftPhysicalAt = position
        onLeftPhysical(position)
        dispatch(SessionEvent.ModeSwitched(CubeMode.VIRTUAL, clock()))
    }

    /**
     * The session revision a physical Undo was dispatched at, until the host acknowledges it with a
     * later revision. Until then the undo barrier in [session] is stale, so Back stays disabled (AD-3).
     */
    private var undoPendingFrom: Long? by mutableStateOf(null)

    /**
     * Whether [stepBack] moves: never past the start, and in physical mode never across the host's undo
     * barrier nor before the host has acknowledged the previous Back.
     */
    val canStepBack: Boolean
        get() = position > 0 && (!isPhysical || (undoPendingFrom == null && session.history.canUndo))

    override fun stepBack() {
        if (!canStepBack) return
        playback.stepBack()
        if (isPhysical) {
            undoPendingFrom = session.revision
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
    }

    internal fun updateSession(newSession: SessionState) {
        if (newSession.revision >= session.revision) {
            val oldSession = session
            session = newSession
            undoPendingFrom?.let { if (newSession.revision > it || !newSession.isPhysical) undoPendingFrom = null }
            if (newSession.revision > oldSession.revision &&
                !newSession.isPhysical &&
                !oldSession.isPhysical &&
                newSession.restoreNote == null &&
                oldSession.restoreNote == null
            ) {
                playback.pause()
                playback.scrubTo(0.0)
                start = newSession.entry
            }
        }
    }

    private companion object {
        /**
         * The cube the Solution starts from. A mode switch restarts session history, so after
         * physical → virtual the session's entry is the cube part-way through the Solution; a state
         * rebuilt then (e.g. on Activity recreation) recovers the start by undoing the confirmed prefix
         * of the verified Solution.
         */
        fun origin(session: SessionState): CubeState =
            (0..session.solution.size)
                .asSequence()
                .map { done ->
                    session.entry.apply(
                        session.solution
                            .take(done)
                            .asReversed()
                            .map(Move::inverse),
                    )
                }.firstOrNull { it.apply(session.solution).isSolved } ?: session.entry
    }
}

private fun defaultPlaybackView(session: SessionState): PlaybackView = if (session.isPhysical) PlaybackView.NET_2D else PlaybackView.CUBE_3D

private fun PlaybackView.rendererAt(facelets: Facelets): CubeRenderer =
    when (this) {
        PlaybackView.CUBE_3D -> Cube3DRenderer(facelets)
        PlaybackView.NET_2D -> Net2DRenderer(facelets)
    }

/**
 * The state of a [SolutionPlayback] for a `SOLVE_PLAYBACK` [session]. Host updates keep the
 * transport alive across mode switches (which replace session history); a restarted virtual
 * session rewinds it. A different Solution or [reduceMotion] creates a new transport.
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
    opensOnNet: Boolean = false,
): SolutionPlaybackState {
    require(session.kind == SessionKind.SOLVE_PLAYBACK) { "not a Solve Playback session: ${session.kind}" }
    val initialView =
        if (opensOnNet) PlaybackView.NET_2D else LocalPreferenceStore.current.preferences.value.playbackView ?: defaultPlaybackView(session)
    // Where the Learner left physical mode, saved so a recreated state resolves equal states the same way.
    var leftPhysicalAt by rememberSaveable(session.solution) { mutableStateOf<Int?>(null) }
    val state =
        remember(session.solution, reduceMotion) {
            SolutionPlaybackState(session, reduceMotion, onEvent, clock, initialView, leftPhysicalAt, opensOnNet)
        }
    state.onLeftPhysical = { leftPhysicalAt = it }
    state.updateSession(session)
    state.onEvent = onEvent
    state.clock = clock
    return state
}

/**
 * A verified Solution (FR-16) in 3D or on the 2D net with the shared playback transport (forward, back,
 * pause, replay, speed) and, under the current Move, its note from [MoveNote], resolved through
 * [strings]. With [segments] for the Solution being played, the current segment's Stage or Case name shows above
 * the Move counter. The last step draws the solved state.
 */
@Composable
fun SolutionPlayback(
    state: SolutionPlaybackState,
    strings: ContentStrings,
    modifier: Modifier = Modifier,
    style: RenderStyle = LocalRenderStyle.current,
    quickCheck: QuickCheckCapture? = null,
    requestQuickCheck: (Boolean) -> Unit = {},
    resolve: ResolveState = ResolveState(),
    onUseMyCube: () -> Unit = {},
    segments: SegmentLabels? = null,
) {
    val store = LocalPreferenceStore.current
    val preferences by store.preferences.collectAsState()
    // A playback that opens on the net follows the saved view only once it changes from the one it opened under.
    val openedUnder = remember(state) { preferences.playbackView }
    if (!state.opensOnNet || preferences.playbackView != openedUnder) {
        state.showView(preferences.playbackView ?: defaultPlaybackView(state.session))
    }
    PlaybackFrameLoop(state)
    val tokens = RubikTheme.tokens
    val segment = state.segmentOf(segments)?.let(strings::resolve)

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

        val renderer = state.renderer
        val cube = renderer as? Cube3DRenderer
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            val target = if (cube != null) PlaybackView.NET_2D else PlaybackView.CUBE_3D
            val icon = if (cube != null) R.drawable.ic_view_net else R.drawable.ic_view_cube
            val label = if (cube != null) "btn.playback.show-net" else "btn.playback.show-cube"
            ToolbarButton(icon, strings.resolve(ContentText(label)), "playback:viewSwitch", true) {
                store.update(Preference.PlaybackViewChoice(target))
                state.showView(target)
            }
            if (cube != null) ViewControls(cube)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val aspect = if (cube != null) 1f else NET_ASPECT
            val width = minOf(maxWidth, maxHeight * aspect)
            Box(
                Modifier
                    .size(width, width / aspect)
                    .semantics { isTraversalGroup = true },
            ) {
                when (renderer) {
                    // A new adapter (a re-solve, or a view switch) must not reuse the old one's remembered GL surface.
                    is Cube3DRenderer -> {
                        key(renderer) {
                            renderer.Content(
                                style,
                                Modifier.fillMaxSize(),
                                arrow = state.currentMove,
                                reduceMotion = state.reduceMotion,
                                summary = null,
                                freeViewRotation = true,
                                strings = strings,
                            )
                        }
                    }

                    is Net2DRenderer -> {
                        renderer.Content(style, Modifier.fillMaxSize())
                    }
                }
                if (cube != null) {
                    FlowRow(
                        itemVerticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                        modifier =
                            Modifier.semantics {
                                isTraversalGroup = true
                                traversalIndex = -1f
                            },
                    ) {
                        ViewRotatedBadge(cube, strings)
                    }
                }
            }
        }

        val controls: @Composable () -> Unit = {
            if (state.isPhysical) {
                if (quickCheck != null) {
                    state.session.quickCheck?.let {
                        dev.cubentia.app.ui.lesson
                            .CertaintyChip(it.certainty)
                    }
                    state.session.quickCheckRetry?.let {
                        Text(
                            strings.resolve(it),
                            modifier = Modifier.testTag("quickCheck:retry"),
                        )
                    }
                    if (state.session.syncState == SyncState.PHYSICAL_MISMATCH &&
                        state.session.observed != null
                    ) {
                        dev.cubentia.app.ui.lesson
                            .SeenStrip(state.session, strings)
                    }
                    androidx.compose.material3.IconButton(
                        onClick = { requestQuickCheck(false) },
                        modifier = Modifier.testTag("playback:quickCheck"),
                    ) {
                        androidx.compose.material3.Icon(
                            androidx.compose.ui.res
                                .painterResource(R.drawable.ic_camera),
                            contentDescription = strings.resolve(ContentText("btn.physical.quick-check")),
                        )
                    }
                    if (state.session.offersFullScan) {
                        TextButton(onClick = { requestQuickCheck(true) }, modifier = Modifier.testTag("playback:scanAll")) {
                            Text(strings.resolve(ContentText("btn.physical.scan-all-faces")))
                        }
                    }
                    // A Full mismatch re-solves from the observed cube and restarts playback from it (PO 2026-11-17).
                    if (state.session.canResolveObserved) {
                        if (resolve.running) {
                            androidx.compose.material3.LinearProgressIndicator(Modifier.testTag("playback:resolving"))
                        } else {
                            resolve.error?.let {
                                Text(
                                    strings.resolve(it),
                                    modifier =
                                        Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(
                                            "playback:resolveError",
                                        ),
                                )
                            }
                            TextButton(onClick = onUseMyCube, modifier = Modifier.testTag("playback:useMyCube")) {
                                Text(strings.resolve(ContentText("btn.physical.use-my-cube")))
                            }
                        }
                    }
                }
                PhysicalPlaybackControls(state, strings, preferences.movePictures, segment)
            } else {
                PlaybackTransport(
                    state,
                    strings,
                    note = state.note?.let(strings::resolve),
                    onScrub = state::seekTo,
                    prominentCounter = true,
                    segment = segment,
                )
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
                        Text(text = strings.resolve(ContentText("msg.playback.follow-on-my-cube")))
                    }
                }
            }
        }
        key(state.isPhysical) {
            Column(
                Modifier.fillMaxWidth().playbackControlsScroll(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.s2),
            ) { controls() }
        }
    }
}

@Composable
private fun PhysicalPlaybackControls(
    state: SolutionPlaybackState,
    strings: ContentStrings,
    pictures: Boolean,
    segment: String?,
    modifier: Modifier = Modifier,
) {
    val tokens = RubikTheme.tokens
    val styles = RubikTheme.textStyles

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s2),
    ) {
        segment?.let { PlaybackSegmentLabel(it) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s4),
        ) {
            Text(
                stringResource(R.string.playback_counter, state.position, state.size),
                style = styles.notation.copy(fontFeatureSettings = "tnum"),
                color = tokens.inkPrimary,
                modifier = Modifier.testTag("playback:counter"),
            )
            PlaybackMoveLabel(state.currentMove, pictures)
        }

        state.note?.let { noteText ->
            Text(
                strings.resolve(noteText),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.inkSecondary,
                modifier =
                    Modifier
                        .padding(
                            horizontal = Spacing.s4,
                        ).testTag("playback:note")
                        .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        MoveStrip(state.moves, state.currentIndex, pictures, strings, onSelect = null)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
        ) {
            val backSpoken = stringResource(R.string.playback_step_back_description)
            TextButton(
                onClick = state::stepBack,
                enabled = state.canStepBack,
                modifier =
                    Modifier
                        .heightIn(min = Spacing.touchMin)
                        .testTag("playback:back")
                        .semantics { contentDescription = backSpoken },
            ) {
                Text(
                    stringResource(R.string.playback_step_back),
                    color = if (state.canStepBack) tokens.inkPrimary else tokens.inkDisabled,
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
                Text(text = strings.resolve(ContentText("btn.physical.done")))
            }
        }

        if (state.atEnd) {
            Text(
                text = strings.resolve(ContentText("msg.playback.cube-should-be-solved")),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.inkPrimary,
                modifier =
                    Modifier
                        .padding(horizontal = Spacing.s4, vertical = Spacing.s2)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("playback:solvedNow"),
            )
        }

        val physicalModeLabel = strings.resolve(ContentText("msg.physical.mode"))
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
