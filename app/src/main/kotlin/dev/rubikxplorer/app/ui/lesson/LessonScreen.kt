package dev.rubikxplorer.app.ui.lesson

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import dev.rubikxplorer.app.R
import dev.rubikxplorer.app.content.CatalogEntry
import dev.rubikxplorer.app.content.ContentStrings
import dev.rubikxplorer.app.render.MoveIntent
import dev.rubikxplorer.app.render.Net2DRenderer
import dev.rubikxplorer.app.render.NetEmphasis
import dev.rubikxplorer.app.render.PresentationItem
import dev.rubikxplorer.app.render.RenderStyle
import dev.rubikxplorer.app.session.SessionHost
import dev.rubikxplorer.app.session.toItems
import dev.rubikxplorer.app.ui.freecube.MoveKeyBar
import dev.rubikxplorer.app.ui.freecube.ToolbarButton
import dev.rubikxplorer.app.ui.shell.WindowWidthClass
import dev.rubikxplorer.app.ui.theme.RubikTheme
import dev.rubikxplorer.app.ui.theme.Spacing
import dev.rubikxplorer.application.session.SessionEvent
import dev.rubikxplorer.application.session.SessionState
import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Facelets
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.playback.Demonstration
import dev.rubikxplorer.domain.playback.DemonstrationResult
import dev.rubikxplorer.domain.predicate.Piece
import dev.rubikxplorer.domain.tutor.RecoveryOffer
import dev.rubikxplorer.domain.tutor.TeachingMode
import dev.rubikxplorer.domain.tutor.Tutor
import dev.rubikxplorer.domain.tutor.TutorDecision
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Instant

/**
 * The Lesson task flow (FR-5, FR-6): a top bar with close over the stage and the [LessonPanel],
 * stacked when compact and side by side from medium width. Opening it starts [entry]'s Lesson in
 * [host] unless the session already holds it, so a configuration change never restarts it. System
 * back and the close button call [onClose] at once; so does Next once the Objective is met.
 *
 * The screen decides nothing about teaching: every reveal, Notice, feedback and Next flag comes from
 * the session's [TutorDecision]. The switcher offers only [modes] (the shell drops a mode whose
 * feature flag is off).
 */
@Composable
fun LessonScreen(
    host: SessionHost,
    entry: CatalogEntry,
    strings: ContentStrings,
    modes: List<TeachingMode>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lesson = entry.lesson
    val session by host.state.collectAsState()
    BackHandler(onBack = onClose)
    LaunchedEffect(lesson.id) {
        if (host.state.value.lesson
                ?.id != lesson.id
        ) {
            host.dispatch { SessionEvent.LessonStarted(lesson, entry.tutorConfig, it) }
        }
    }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(RubikTheme.tokens.surfaceBase)
                .safeDrawingPadding()
                .testTag("lesson"),
    ) {
        LessonTopBar(strings.resolve(entry.stage.title), onClose)
        val decision = session.decision
        val demonstration = remember(lesson) { (Demonstration.of(lesson) as? DemonstrationResult.Ready)?.demonstration }
        if (session.lesson?.id == lesson.id && decision != null && demonstration != null) {
            LessonBody(host, session, decision, lesson, demonstration, strings, modes, onClose)
        }
    }
}

@Composable
private fun LessonTopBar(
    title: String,
    onClose: () -> Unit,
) {
    val tokens = RubikTheme.tokens
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = TOP_BAR_HEIGHT)
                .padding(horizontal = Spacing.s1)
                .testTag("lesson:topBar"),
    ) {
        IconButton(
            onClick = onClose,
            colors = IconButtonDefaults.iconButtonColors(contentColor = tokens.inkPrimary),
            modifier = Modifier.size(Spacing.touchMin).testTag("lesson:close"),
        ) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.lesson_close))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = tokens.inkPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Spacing.s2).semantics { heading() },
        )
    }
}

@Composable
private fun LessonBody(
    host: SessionHost,
    session: SessionState,
    decision: TutorDecision,
    lesson: Lesson,
    demonstration: Demonstration,
    strings: ContentStrings,
    modes: List<TeachingMode>,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    val player = rememberDemonstrationPlayerState(demonstration, reduceMotion)
    // The session's net, drawn from the current Cube State whenever the Lesson screen (re)enters.
    val renderer =
        remember(lesson.id) {
            Net2DRenderer(
                host.state.value.virtual
                    .toFacelets(),
            )
        }
    // The running Show me how demonstration, if any. Any session interaction stops it first and puts
    // the session's own state back on the net, so no obsolete demonstration frame is ever drawn.
    val reorientation = remember(lesson.id) { mutableStateOf<Job?>(null) }
    val stopReorientation = {
        reorientation.value?.let { job ->
            reorientation.value = null
            if (job.isActive) {
                job.cancel()
                val now = host.state.value
                renderer.submit(listOf(PresentationItem.Snap(now.revision, now.virtual.toFacelets())))
            }
        }
    }
    val send: ((Instant) -> SessionEvent) -> Unit = { event ->
        stopReorientation()
        scope.launch { renderer.submit(host.dispatch(event).presentation.toItems()) }
    }
    val pulse = rememberObjectivePulse(decision.satisfied, reduceMotion)
    val mode = session.teachingMode

    val step = stepCard(mode, session, decision, lesson, demonstration, player, strings)
    val panel: @Composable (Modifier) -> Unit = { panelModifier ->
        LessonPanel(
            objective = strings.resolve(lesson.objective),
            step = step,
            modes = modes,
            mode = mode,
            onModeChange = { chosen -> if (chosen != mode) send { SessionEvent.TeachingModeChanged(chosen, it) } },
            onShowMove = { send(SessionEvent::RevealRequested) },
            hintEnabled = !decision.reveal,
            onHint = { send(SessionEvent::RevealRequested) },
            nextEnabled = decision.advance,
            onNext = { if (decision.satisfied) onClose() else send(SessionEvent::StepAdvanced) },
            modifier = panelModifier,
            onShowMeHow = { rotations ->
                stopReorientation()
                reorientation.value = scope.launch { demonstrateReorientation(host, renderer, rotations, reduceMotion) }
            },
        )
    }
    val stage: @Composable (Modifier) -> Unit = { stageModifier ->
        Box(stageModifier.testTag("lesson:stage")) {
            when (mode) {
                TeachingMode.SHOW_ME -> {
                    DemonstrationPlayer(player, Modifier.fillMaxSize().padding(Spacing.s2))
                }

                TeachingMode.GUIDE_ME, TeachingMode.TEST_ME, TeachingMode.LET_ME_TRY -> {
                    val focus = demonstration.steps.getOrNull(session.stepIndex)?.focus
                    // Dimming and the target outline guide only in Guide me; the other Learner modes
                    // light the target just for the Objective-met pulse.
                    val emphasis =
                        when {
                            focus == null -> null
                            mode == TeachingMode.GUIDE_ME -> NetEmphasis(lit = focus.lit, highlighted = focus.target, pulse = pulse)
                            pulse > 0f -> NetEmphasis(lit = ALL_PIECES, highlighted = focus.target, pulse = pulse)
                            else -> null
                        }
                    LearnerStage(
                        host = host,
                        session = session,
                        renderer = renderer,
                        emphasis = emphasis,
                        strings = strings,
                        send = send,
                    )
                }
            }
        }
    }

    val stageColor = RubikTheme.tokens.surfaceStage
    val stageShape = MaterialTheme.shapes.large

    fun Modifier.stageSurface(): Modifier = padding(Spacing.s2).background(stageColor, stageShape)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth
        val height = maxHeight
        val widthClass = WindowWidthClass.of(width.value)
        when (widthClass) {
            WindowWidthClass.COMPACT -> {
                // The stage's own height (inside its gutter) is at least COMPACT_STAGE_MIN.
                val stageHeight = min(max(height * COMPACT_STAGE_FRACTION, COMPACT_STAGE_MIN + Spacing.s2 * 2), height)
                Column(Modifier.fillMaxSize()) {
                    stage(Modifier.fillMaxWidth().height(stageHeight).stageSurface())
                    panel(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()))
                }
            }

            WindowWidthClass.MEDIUM, WindowWidthClass.EXPANDED -> {
                val expanded = widthClass == WindowWidthClass.EXPANDED
                Row(Modifier.fillMaxSize()) {
                    stage(Modifier.fillMaxHeight().width(width * WIDE_STAGE_FRACTION).stageSurface())
                    panel(
                        Modifier
                            .weight(1f, fill = false)
                            .then(if (expanded) Modifier.widthIn(max = EXPANDED_PANEL_MAX) else Modifier)
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

/**
 * The step card's content for [mode]: Show me follows the Demonstration's focused step; Guide me,
 * Test me and Let me try follow the session's step, and only Guide me offers "Show the Move".
 */
private fun stepCard(
    mode: TeachingMode,
    session: SessionState,
    decision: TutorDecision,
    lesson: Lesson,
    demonstration: Demonstration,
    player: DemonstrationPlayerState,
    strings: ContentStrings,
): StepCardContent {
    val index: Int
    val notice: Boolean
    val moves: List<Move>?
    when (mode) {
        TeachingMode.SHOW_ME -> {
            index = demonstration.stepIndexAt(maxOf(player.position - 1, 0))
            val shown = demonstration.steps.getOrNull(index)
            notice = shown != null && player.position > 0 && player.position >= shown.firstMove + shown.moves.size
            moves = if (decision.reveal) shown?.moves.orEmpty() else null
        }

        TeachingMode.GUIDE_ME, TeachingMode.TEST_ME, TeachingMode.LET_ME_TRY -> {
            index = session.stepIndex.coerceIn(0, maxOf(lesson.steps.lastIndex, 0))
            notice = decision.advance
            moves =
                if (decision.reveal) {
                    demonstration.steps
                        .getOrNull(index)
                        ?.moves
                        .orEmpty()
                } else {
                    null
                }
        }
    }
    val step = lesson.steps.getOrNull(index)
    return StepCardContent(
        number = index + 1,
        count = lesson.steps.size,
        goal = step?.let { strings.resolve(it.goal) }.orEmpty(),
        keep = step?.let { strings.resolve(it.preserve) }.orEmpty(),
        watch = step?.let { strings.resolve(it.watch) }.orEmpty(),
        notice = if (notice) step?.let { strings.resolve(it.notice) } else null,
        moves = moves,
        objectiveMet = decision.satisfied,
        feedback = decision.feedback?.text?.let { strings.resolve(it) },
        diagnosis = decision.diagnosis?.let { strings.resolve(it.explanation) },
        recoveryPrompt = decision.diagnosis?.let { strings.resolve(it.recoveryPath.prompt) },
        showMeHow =
            decision.diagnosis
                ?.takeIf { RecoveryOffer.SHOW_ME_HOW in it.recoveryPath.offers }
                ?.let { Tutor.reorient(session.virtual.orientation, session.entry.orientation) },
        feedbackMove = session.revision,
        showMoveControl = mode == TeachingMode.GUIDE_ME,
    )
}

/**
 * The Learner's stage in Guide me, Test me and Let me try: Undo / Redo / Reset over the session's
 * net (drawn with [emphasis]: the step's focus in Guide me, only the Objective-met pulse otherwise)
 * and the Move key bar; Reset asks through [ResetConfirmation] when steps would be lost.
 */
@Composable
private fun LearnerStage(
    host: SessionHost,
    session: SessionState,
    renderer: Net2DRenderer,
    emphasis: NetEmphasis?,
    strings: ContentStrings,
    send: ((Instant) -> SessionEvent) -> Unit,
) {
    val tokens = RubikTheme.tokens
    var caption by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(Spacing.s2), verticalArrangement = Arrangement.spacedBy(Spacing.s1)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = caption.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.inkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(start = Spacing.s2)
                        .testTag("stage:caption")
                        .semantics { liveRegion = LiveRegionMode.Polite },
            )
            ToolbarButton(R.drawable.ic_undo, R.string.toolbar_undo, "toolbar:undo", session.history.canUndo) {
                caption = null
                send(SessionEvent::Undo)
            }
            ToolbarButton(R.drawable.ic_redo, R.string.toolbar_redo, "toolbar:redo", session.history.canRedo) {
                caption = null
                send(SessionEvent::Redo)
            }
            ToolbarButton(R.drawable.ic_reset, R.string.toolbar_reset, "toolbar:reset", canReset(session)) {
                caption = null
                send(SessionEvent::Reset)
            }
        }
        renderer.Content(RenderStyle(), Modifier.fillMaxWidth().weight(1f), emphasis)
        MoveKeyBar(
            onMove = { move ->
                caption = null
                send { SessionEvent.MoveCommitted(MoveIntent(move).move, it) }
            },
            onShowName = { caption = it },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.s1))
    }
    ResetConfirmation(host = host, resolve = strings::resolve, onTransition = { renderer.submit(it.presentation.toItems()) })
}

/**
 * *Show me how* for a turned cube: plays [rotations] on the session's net from the current Cube
 * State, one per [REORIENT_STEP_MILLIS] (stepped, not animated, under [reduceMotion]), holds the
 * Lesson's hold for a moment, then snaps the net back to the session's current state. A
 * demonstration only: it dispatches nothing, so the session (and its undo history) never changes
 * (AD-3, AD-5), and the net matches the session again once it ends. The caller cancels it before
 * any session interaction and restores the session's net itself.
 */
private suspend fun demonstrateReorientation(
    host: SessionHost,
    renderer: Net2DRenderer,
    rotations: List<Move>,
    reduceMotion: Boolean,
) {
    val held = host.state.value
    var shown = held.virtual
    rotations.forEach { rotation ->
        shown = shown.apply(rotation)
        val target = shown.toFacelets()
        val item =
            if (reduceMotion) {
                PresentationItem.Snap(held.revision, target)
            } else {
                PresentationItem.Animate(rotation, held.revision, target)
            }
        renderer.submit(listOf(item))
        awaitFrameMillis(REORIENT_STEP_MILLIS)
    }
    awaitFrameMillis(REORIENT_HOLD_MILLIS)
    val now = host.state.value
    renderer.submit(listOf(PresentationItem.Snap(now.revision, now.virtual.toFacelets())))
}

/** Suspends for [millis] on the Compose frame clock, timed by a tween as the renderer's own animations are. */
private suspend fun awaitFrameMillis(millis: Long) {
    animate(0f, 1f, animationSpec = tween(millis.toInt(), easing = LinearEasing)) { _, _ -> }
}

/** Reset changes something: a Move to take back or redo, or a later step to return from. */
private fun canReset(session: SessionState): Boolean = session.history.canUndo || session.history.canRedo || session.stepIndex > 0

/**
 * The Objective-met pulse (0..1): a brief rise and fall of the target's `sticker-highlight` fill when
 * [satisfied] turns true while shown; always 0 under [reduceMotion].
 */
@Composable
private fun rememberObjectivePulse(
    satisfied: Boolean,
    reduceMotion: Boolean,
): Float {
    val pulse = remember { Animatable(0f) }
    var previous by remember { mutableStateOf(satisfied) }
    LaunchedEffect(satisfied) {
        val rose = satisfied && !previous
        previous = satisfied
        if (!rose || reduceMotion) {
            pulse.snapTo(0f)
            return@LaunchedEffect
        }
        pulse.animateTo(1f, tween(PULSE_MILLIS / 2, easing = LinearEasing))
        pulse.animateTo(0f, tween(PULSE_MILLIS / 2, easing = LinearEasing))
    }
    return pulse.value
}

/** Every edge and corner, so a pulse-only emphasis dims nothing. */
private val ALL_PIECES: Set<Piece> =
    CubeState.SOLVED.toFacelets().let { facelets -> (0 until Facelets.SIZE).mapNotNull { Piece.at(facelets, it) }.toSet() }

private const val PULSE_MILLIS = 600

/** Each *Show me how* Cube Rotation's slot: the renderer's flip ([Net2DRenderer.ITEM_MILLIS]) plus a pause. */
internal const val REORIENT_STEP_MILLIS = 600L

/** How long the net holds the Lesson's hold before returning to the session's state. */
internal const val REORIENT_HOLD_MILLIS = 1_200L
private const val COMPACT_STAGE_FRACTION = 0.45f
private const val WIDE_STAGE_FRACTION = 0.55f
private val COMPACT_STAGE_MIN: Dp = 280.dp
private val EXPANDED_PANEL_MAX: Dp = 560.dp
private val TOP_BAR_HEIGHT: Dp = 56.dp
