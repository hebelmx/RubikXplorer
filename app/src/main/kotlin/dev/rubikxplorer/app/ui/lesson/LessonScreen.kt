package dev.rubikxplorer.app.ui.lesson

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import dev.rubikxplorer.app.R
import dev.rubikxplorer.app.content.CatalogEntry
import dev.rubikxplorer.app.content.ContentStrings
import dev.rubikxplorer.app.preferences.LocalPreferenceStore
import dev.rubikxplorer.app.preferences.LocalRenderStyle
import dev.rubikxplorer.app.render.Cube3DRenderer
import dev.rubikxplorer.app.render.CubeRenderer
import dev.rubikxplorer.app.render.NetEmphasis
import dev.rubikxplorer.app.render.PresentationItem
import dev.rubikxplorer.app.render.RenderStyle
import dev.rubikxplorer.app.session.SessionHost
import dev.rubikxplorer.app.session.toItems
import dev.rubikxplorer.app.ui.editor.ManualCubeEditor
import dev.rubikxplorer.app.ui.freecube.MoveKeyBar
import dev.rubikxplorer.app.ui.freecube.ToolbarButton
import dev.rubikxplorer.app.ui.freecube.ViewControls
import dev.rubikxplorer.app.ui.shell.WindowWidthClass
import dev.rubikxplorer.app.ui.theme.Radii
import dev.rubikxplorer.app.ui.theme.RubikTheme
import dev.rubikxplorer.app.ui.theme.Spacing
import dev.rubikxplorer.application.port.FlagLevel
import dev.rubikxplorer.application.session.CubeMode
import dev.rubikxplorer.application.session.GuidedRecovery
import dev.rubikxplorer.application.session.SessionEvent
import dev.rubikxplorer.application.session.SessionKind
import dev.rubikxplorer.application.session.SessionState
import dev.rubikxplorer.application.session.SyncState
import dev.rubikxplorer.application.usecase.PlanRecovery
import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.content.Stage
import dev.rubikxplorer.domain.content.StageId
import dev.rubikxplorer.domain.content.TextKey
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Facelets
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.input.MoveIntent
import dev.rubikxplorer.domain.playback.Demonstration
import dev.rubikxplorer.domain.playback.DemonstrationResult
import dev.rubikxplorer.domain.predicate.CubeSummary
import dev.rubikxplorer.domain.predicate.Piece
import dev.rubikxplorer.domain.tutor.AssistanceLevel
import dev.rubikxplorer.domain.tutor.Certainty
import dev.rubikxplorer.domain.tutor.HintLadder
import dev.rubikxplorer.domain.tutor.HintRung
import dev.rubikxplorer.domain.tutor.Mastery
import dev.rubikxplorer.domain.tutor.ProgressEvent
import dev.rubikxplorer.domain.tutor.Recovery
import dev.rubikxplorer.domain.tutor.RecoveryOffer
import dev.rubikxplorer.domain.tutor.StageProgress
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
 * feature flag is off). [planRecovery] runs a requested Guided recovery's search (tests inject one).
 * [adaptiveAssistance] is the `ADAPTIVE_ASSISTANCE` flag level the Lesson starts with: off, the Hint Ladder
 * is fixed and no Assistance line shows; BETA adds the beta label to the Assistance line (AD-18).
 */
@Composable
fun LessonScreen(
    host: SessionHost,
    entry: CatalogEntry,
    strings: ContentStrings,
    modes: List<TeachingMode>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    planRecovery: PlanRecovery = remember { PlanRecovery() },
    adaptiveAssistance: FlagLevel = FlagLevel.OFF,
    onPracticeNewScramble: () -> Unit = onClose,
    isPractice: Boolean = false,
    stages: List<Stage> = emptyList(),
    onNextStage: ((StageId) -> Unit)? = null,
    isSkipCheck: Boolean = false,
    skipCheckInstruction: String? = null,
    onSkipCheckPassed: (() -> Unit)? = null,
) {
    val preferenceStore = LocalPreferenceStore.current
    val lesson = entry.lesson
    val session by host.state.collectAsState()
    val attemptRecords by host.attemptRecords.collectAsState()
    val progressEvents by host.progressEvents.collectAsState()
    var initialCompleted by rememberSaveable(lesson.id.value) { mutableStateOf<Boolean?>(null) }
    var initialMastered by rememberSaveable(lesson.id.value) { mutableStateOf<Boolean?>(null) }
    var milestoneDismissed by rememberSaveable(lesson.id.value) { mutableStateOf(false) }

    val isPhysical = session.syncState != SyncState.VIRTUAL

    val currentEpochAttempts =
        remember(attemptRecords, progressEvents) {
            Mastery.currentEpochAttempts(attemptRecords, progressEvents)
        }
    val masteryProgress =
        remember(entry.stage, currentEpochAttempts, entry.tutorConfig.mastery) {
            Mastery.evaluate(entry.stage, currentEpochAttempts, entry.tutorConfig.mastery)
        }
    val isMasteryMilestone =
        !isPhysical &&
            initialMastered == false &&
            !milestoneDismissed &&
            session.lesson?.id == lesson.id &&
            (session.decision?.satisfied == true || session.objectiveMet) &&
            masteryProgress.isMastered

    val isFirstSolveMilestone =
        !isPhysical &&
            entry.lesson.id.value == "lesson-06-03" &&
            initialCompleted == false &&
            !milestoneDismissed &&
            session.lesson?.id == lesson.id &&
            (session.decision?.satisfied == true)

    val showMilestone = isFirstSolveMilestone || isMasteryMilestone

    BackHandler(onBack = {
        if (showMilestone) {
            milestoneDismissed = true
            onClose()
        } else {
            onClose()
        }
    })
    LaunchedEffect(lesson.id, lesson.entryState) {
        host.awaitRestored()
        preferenceStore.awaitLoaded()
        if (initialCompleted == null) {
            val epochEvents = StageProgress.currentEpochEvents(host.progressEvents.value)
            initialCompleted = epochEvents.any { it is ProgressEvent.LessonCompleted && it.lessonId == lesson.id }
        }
        if (initialMastered == null) {
            val epochAttempts = Mastery.currentEpochAttempts(host.attemptRecords.value, host.progressEvents.value)
            initialMastered = Mastery.evaluate(entry.stage, epochAttempts, entry.tutorConfig.mastery).isMastered
        }
        val practicing = isPractice || isSkipCheck || host.state.value.kind == SessionKind.PRACTICE
        val shouldStart =
            if (practicing) {
                host.state.value.lesson
                    ?.id != lesson.id || host.state.value.lesson
                    ?.entryState != lesson.entryState
            } else {
                host.state.value.lesson
                    ?.id != lesson.id
            }
        if (shouldStart) {
            if (practicing) {
                host.dispatch {
                    SessionEvent.PracticeStarted(
                        lesson,
                        entry.tutorConfig,
                        it,
                    )
                }
            } else {
                host.dispatch {
                    SessionEvent.LessonStarted(
                        lesson,
                        entry.tutorConfig,
                        it,
                        adaptiveAssistance != FlagLevel.OFF,
                        preferenceStore.preferences.value.lastTeachingMode
                            .takeIf { it in modes } ?: TeachingMode.DEFAULT,
                    )
                }
            }
        }
    }
    val scope = rememberCoroutineScope()
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(RubikTheme.tokens.surfaceBase)
                    .safeDrawingPadding()
                    .testTag("lesson"),
        ) {
            LessonTopBar(
                title = strings.resolve(entry.stage.title),
                isPhysical = isPhysical,
                onPhysicalToggle = { enabled ->
                    scope.launch {
                        host.dispatch {
                            SessionEvent.ModeSwitched(if (enabled) CubeMode.PHYSICAL else CubeMode.VIRTUAL, it)
                        }
                    }
                },
                onClose = onClose,
            )
            session.restoreNote?.let { note ->
                Text(strings.resolve(note), modifier = Modifier.testTag("lesson:restoreNote"))
            }
            if (skipCheckInstruction != null) {
                Text(
                    text = skipCheckInstruction,
                    style = MaterialTheme.typography.bodyMedium,
                    color = RubikTheme.tokens.inkSecondary,
                    modifier =
                        Modifier
                            .padding(horizontal = Spacing.s4, vertical = Spacing.s2)
                            .testTag("lesson:skipCheckInstruction"),
                )
            }
            val decision = session.decision
            val demonstration = remember(lesson) { (Demonstration.of(lesson) as? DemonstrationResult.Ready)?.demonstration }
            if (session.lesson?.id == lesson.id && decision != null && demonstration != null) {
                LessonBody(
                    host,
                    session,
                    decision,
                    lesson,
                    demonstration,
                    strings,
                    modes,
                    onClose,
                    adaptiveAssistance == FlagLevel.BETA,
                    isPractice = isPractice,
                    isSkipCheck = isSkipCheck,
                    onSkipCheckPassed = onSkipCheckPassed,
                )
            }
        }
        if (showMilestone) {
            val stageIndex = stages.indexOfFirst { it.id == entry.stage.id }
            val nextStage = if (stageIndex >= 0 && stageIndex < stages.size - 1) stages[stageIndex + 1] else null

            val headline =
                if (isFirstSolveMilestone) {
                    strings.resolve(TextKey("msg.milestone.first-solve"))
                } else {
                    strings.resolve(
                        dev.rubikxplorer.domain.text.Text(
                            "msg.milestone.stage-mastered",
                            mapOf("stage" to strings.resolve(entry.stage.title)),
                        ),
                    )
                }

            val offerNextStage =
                if (isMasteryMilestone && nextStage != null && onNextStage != null) {
                    {
                        milestoneDismissed = true
                        onNextStage(nextStage.id)
                    }
                } else {
                    null
                }

            val nextStageLabel =
                if (isMasteryMilestone && nextStage != null) {
                    strings.resolve(nextStage.title)
                } else {
                    null
                }

            FirstSolveMilestone(
                headline = headline,
                onPracticeNewScramble = {
                    milestoneDismissed = true
                    onPracticeNewScramble()
                },
                onContinue = {
                    milestoneDismissed = true
                    onClose()
                },
                nextStageLabel = nextStageLabel,
                onNextStage = offerNextStage,
            )
        }
        GuidedRecoveryRunner(host, planRecovery)
    }
}

@Composable
private fun LessonTopBar(
    title: String,
    isPhysical: Boolean,
    onPhysicalToggle: (Boolean) -> Unit,
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
        Spacer(Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .testTag("lesson:physicalMode")
                    .padding(horizontal = Spacing.s2),
        ) {
            Text(
                text = stringResource(R.string.physical_mode),
                style = MaterialTheme.typography.labelMedium,
                color = tokens.inkSecondary,
                modifier = Modifier.padding(end = Spacing.s2),
            )
            Switch(
                checked = isPhysical,
                onCheckedChange = onPhysicalToggle,
                modifier = Modifier.testTag("lesson:physicalModeSwitch"),
            )
        }
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
    beta: Boolean,
    isPractice: Boolean = false,
    isSkipCheck: Boolean = false,
    onSkipCheckPassed: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val isPhysical = session.syncState != SyncState.VIRTUAL
    var showManualEditor by rememberSaveable { mutableStateOf(false) }
    var showMoveKeyBar by rememberSaveable(isPhysical) { mutableStateOf(!isPhysical) }

    LaunchedEffect(isPhysical) {
        showMoveKeyBar = !isPhysical
    }

    BackHandler(enabled = showManualEditor) {
        showManualEditor = false
    }

    val reduceMotion = rememberReduceMotion()
    val player = rememberDemonstrationPlayerState(demonstration, reduceMotion)
    // The session's net, drawn from the current Cube State whenever the Lesson screen (re)enters.
    val renderer =
        remember(lesson.id) {
            Cube3DRenderer(
                host.state.value.virtual
                    .toFacelets(),
            )
        }
    // The running net demonstration (Show me how, or the Hint Ladder's top rung), if any. Any session
    // interaction stops it first and puts the session's own state back on the net, so no obsolete
    // demonstration frame is ever drawn.
    var playingMove by remember(lesson.id) { mutableStateOf<Move?>(null) }
    val onPlayingMove: (Move?) -> Unit = { playingMove = it }
    // The Cube State the running demonstration shows on the net, so its summary describes that cube.
    var playingState by remember(lesson.id) { mutableStateOf<CubeState?>(null) }
    val onPlayingState: (CubeState?) -> Unit = { playingState = it }
    val reorientation = remember(lesson.id) { mutableStateOf<Job?>(null) }
    val stopReorientation = {
        playingMove = null
        playingState = null
        reorientation.value?.let { job ->
            reorientation.value = null
            if (job.isActive) {
                job.cancel()
                val now = host.state.value
                renderer.submit(listOf(PresentationItem.Snap(now.revision, now.virtual.toFacelets())))
            }
        }
    }
    // Adaptive assistance's demonstrate level shows the turn (FR-13): when a step escalates to it, the
    // step's Moves play once on the net like the Hint Ladder's top rung, as the running demonstration,
    // so any session interaction stops it. It dispatches nothing; a new Lesson (no mistakes) re-arms it.
    val demonstrated = remember(lesson.id) { mutableStateOf(emptySet<Int>()) }
    val escalated = session.assistance == AssistanceLevel.DEMONSTRATE
    val shownStep = session.stepIndex
    LaunchedEffect(session.stepMistakes.isEmpty()) { if (session.stepMistakes.isEmpty()) demonstrated.value = emptySet() }
    LaunchedEffect(escalated, shownStep) {
        if (escalated && shownStep !in demonstrated.value) {
            demonstrated.value += shownStep
            stopReorientation()
            reorientation.value =
                scope.launch { demonstrateStep(host, renderer, demonstration, shownStep, reduceMotion, onPlayingMove, onPlayingState) }
        }
    }
    val send: ((Instant) -> SessionEvent) -> Unit = { event ->
        stopReorientation()
        scope.launch { renderer.submit(host.dispatch(event).presentation.toItems()) }
    }
    val pulse = if (isPhysical) 0f else rememberObjectivePulse(decision.satisfied, reduceMotion)
    val mode = session.teachingMode
    val stepMove =
        Tutor.nextMove(lesson, session.pathEntry, session.virtual)
            ?: demonstration.steps
                .getOrNull(session.stepIndex)
                ?.moves
                ?.firstOrNull()

    val step = stepCard(mode, session, decision, lesson, demonstration, player, strings, beta, isPractice || isSkipCheck)
    val panel: @Composable (Modifier) -> Unit = { panelModifier ->
        LessonPanel(
            objective = strings.resolve(lesson.objective),
            step = step,
            modes = if (isPractice || isSkipCheck || session.kind == SessionKind.PRACTICE) emptyList() else modes,
            mode = mode,
            onModeChange = { chosen -> if (chosen != mode) send { SessionEvent.TeachingModeChanged(chosen, it) } },
            onShowMove = { send(SessionEvent::RevealRequested) },
            hintRung = session.shownRung.ordinal,
            hintTop = session.assistance.top,
            hintEnabled = !isSkipCheck && HintLadder.canClimb(mode, session.shownRung, decision.advance, session.assistance.top),
            onHint = {
                stopReorientation()
                reorientation.value =
                    scope.launch { climbHintLadder(host, renderer, demonstration, reduceMotion, onPlayingMove, onPlayingState) }
            },
            nextEnabled = decision.advance || (isPhysical && (decision.satisfied || session.objectiveMet)),
            onNext = {
                if (isPhysical) showMoveKeyBar = false
                if (decision.satisfied || (isPhysical && session.objectiveMet)) {
                    if (isSkipCheck && onSkipCheckPassed != null) {
                        onSkipCheckPassed()
                    } else {
                        onClose()
                    }
                } else {
                    send(SessionEvent::StepAdvanced)
                }
            },
            modifier = panelModifier,
            onRecovery = { offer ->
                when (offer) {
                    RecoveryOffer.UNDO -> send(SessionEvent::Undo)

                    RecoveryOffer.TRY_AGAIN -> send(SessionEvent::RetryRequested)

                    RecoveryOffer.GUIDE_ME_BACK -> send(SessionEvent::GuidedRecoveryRequested)

                    // Show me how and re-entry have their own controls.
                    RecoveryOffer.SHOW_ME_HOW, RecoveryOffer.RE_ENTER -> Unit
                }
            },
            onUsualHelp = { send(SessionEvent::UsualHelpRequested) },
            onShowMeHow = { rotations ->
                stopReorientation()
                reorientation.value =
                    scope.launch {
                        demonstrateOnNet(host, renderer, host.state.value.virtual, rotations, reduceMotion, onPlayingMove, onPlayingState)
                    }
            },
            isPhysical = isPhysical,
            syncState = session.syncState,
            onConfirmMatches = { send { SessionEvent.PhysicalConfirmed(null, it) } },
            onConfirmEnter = { showManualEditor = true },
            onDone = {
                showMoveKeyBar = false
                stepMove?.let { move ->
                    send { SessionEvent.PhysicalConfirmed(move, it) }
                }
            },
            onDidSomethingElse = { showMoveKeyBar = true },
            onMismatchReported = { send { SessionEvent.MismatchReported(it) } },
        )
    }
    val stage: @Composable (Modifier) -> Unit = { stageModifier ->
        Box(stageModifier.testTag("lesson:stage")) {
            when (mode) {
                TeachingMode.SHOW_ME -> {
                    // The Demonstration's shown state, summarized against the Lesson's Objective like the Learner stage.
                    val shown = demonstration.start.apply(demonstration.moves.take(player.position))
                    DemonstrationPlayer(
                        player,
                        Modifier.fillMaxSize().padding(Spacing.s2),
                        summary = cubeSummary(shown, lesson, strings),
                        certainty = decision.certainty,
                    )
                }

                TeachingMode.GUIDE_ME, TeachingMode.TEST_ME, TeachingMode.LET_ME_TRY -> {
                    val focus = demonstration.steps.getOrNull(session.stepIndex)?.focus
                    // Adaptive assistance outlines the target from its explain level (FR-13).
                    val hinted = session.shownRung >= HintRung.TARGET || session.assistance >= AssistanceLevel.EXPLAIN
                    val hintTarget =
                        remember(
                            lesson,
                            session.stepIndex,
                            hinted,
                        ) { if (hinted) HintLadder.highlight(lesson, session.stepIndex) else emptySet() }
                    // Dimming and the target outline guide in Guide me (the Hint Ladder's target from
                    // rung 2); the other Learner modes outline the Hint Ladder's target from rung 2
                    // (everything lit), else light the target just for the Objective-met pulse. A
                    // shown Diagnosis outlines the pieces it disturbed first (UX-DR28: violet outline only),
                    // and a ready Guided recovery's preserved pieces before that (UX-DR11).
                    val disturbed = decision.diagnosis?.disturbed.orEmpty()
                    val planned = (session.guidedRecovery as? GuidedRecovery.Planned)?.pieces.orEmpty()
                    val emphasis =
                        when {
                            decision.recovery is Recovery.Ready && planned.isNotEmpty() -> {
                                NetEmphasis(lit = ALL_PIECES, highlighted = planned, pulse = pulse)
                            }

                            disturbed.isNotEmpty() -> {
                                NetEmphasis(lit = ALL_PIECES, highlighted = disturbed, pulse = pulse)
                            }

                            focus == null -> {
                                null
                            }

                            mode == TeachingMode.GUIDE_ME -> {
                                NetEmphasis(lit = focus.lit, highlighted = if (hinted) hintTarget else focus.target, pulse = pulse)
                            }

                            hinted -> {
                                NetEmphasis(lit = ALL_PIECES, highlighted = hintTarget, pulse = pulse)
                            }

                            pulse > 0f -> {
                                NetEmphasis(lit = ALL_PIECES, highlighted = focus.target, pulse = pulse)
                            }

                            else -> {
                                null
                            }
                        }
                    val isMismatch = session.syncState == SyncState.PHYSICAL_MISMATCH
                    val onMoveKey: (Move) -> Unit = { move ->
                        if (isPhysical) {
                            if (!isMismatch) {
                                showMoveKeyBar = false
                            }
                            send { SessionEvent.PhysicalConfirmed(move, it) }
                        } else {
                            send { SessionEvent.MoveCommitted(MoveIntent(move).move, it) }
                        }
                    }
                    LearnerStage(
                        host = host,
                        session = session,
                        renderer = renderer,
                        certainty = decision.certainty,
                        // A running demonstration's shown cube, so the summary agrees with Read faces and Solved.
                        summary = cubeSummary(playingState ?: session.virtual, lesson, strings),
                        emphasis = emphasis,
                        arrow =
                            playingMove
                                ?: if (session.shownRung >= HintRung.TARGET &&
                                    !decision.advance
                                ) {
                                    Tutor.nextMove(lesson, session.pathEntry, session.virtual)
                                } else {
                                    null
                                },
                        reduceMotion = reduceMotion,
                        strings = strings,
                        send = send,
                        showMoveKeyBar = if (isMismatch) true else (!isPhysical || showMoveKeyBar),
                        onMove = onMoveKey,
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
        if (showManualEditor) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(RubikTheme.tokens.surfaceBase)
                        .safeDrawingPadding()
                        .testTag("lesson:manualEditor"),
            ) {
                ManualCubeEditor(
                    strings = strings,
                    initial = session.expected.toFacelets(),
                    onAccept = { state ->
                        showManualEditor = false
                        send { SessionEvent.EditorEntered(state, it) }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** What TalkBack reads for a Lesson cube in [state]: its Objective progress, or null when there is none to tell. */
private fun cubeSummary(
    state: CubeState,
    lesson: Lesson,
    strings: ContentStrings,
): String? =
    CubeSummary
        .of(state, lesson.completion)
        .takeIf { it.isNotEmpty() }
        ?.joinToString("; ") { strings.resolve(it) }

/**
 * The step card's content for [mode]: Show me follows the Demonstration's focused step; Guide me,
 * Test me and Let me try follow the session's step, with its Hint Ladder line at the shown rung and
 * the Assistance line, and only Guide me offers "Show the Move".
 */
private fun stepCard(
    mode: TeachingMode,
    session: SessionState,
    decision: TutorDecision,
    lesson: Lesson,
    demonstration: Demonstration,
    player: DemonstrationPlayerState,
    strings: ContentStrings,
    beta: Boolean,
    isPractice: Boolean = false,
): StepCardContent {
    if (isPractice || session.kind == SessionKind.PRACTICE) {
        return StepCardContent(
            number = 1,
            count = 1,
            goal = strings.resolve(lesson.objective),
            keep = "",
            watch = "",
            notice = null,
            moves = null,
            objectiveMet = decision.satisfied || (session.syncState != SyncState.VIRTUAL && session.objectiveMet),
            feedback = null,
            diagnosis = null,
            recoveryPrompt = null,
            showMeHow = null,
            showMeHowLabel = "",
            feedbackMove = session.revision,
            showMoveControl = false,
            hint =
                when (session.shownRung) {
                    HintRung.NONE -> {
                        null
                    }

                    HintRung.CLUE -> {
                        strings.resolve(HintLadder.clue(lesson, 0))
                    }

                    HintRung.TARGET -> {
                        strings.resolve(
                            dev.rubikxplorer.domain.text
                                .Text("msg.practice.hint.target"),
                        )
                    }

                    HintRung.DEMONSTRATION -> {
                        null
                    }
                },
            recoveryActions = emptyList(),
            guidedRecovery = null,
            assistance = null,
            isPractice = true,
            isPhysical = session.syncState != SyncState.VIRTUAL,
            objectiveMetCopy = if (session.syncState != SyncState.VIRTUAL) strings.resolve(TextKey("msg.physical.objective-met")) else null,
        )
    }
    val index: Int
    val notice: Boolean
    val moves: List<Move>?
    var offPath = false
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
            // Adaptive assistance's guide level narrows the chips to the next authored Move (FR-13).
            val guiding = session.assistance == AssistanceLevel.GUIDE && session.noticeStep == null
            val guided = if (guiding) Tutor.nextMove(lesson, session.pathEntry, session.virtual) else null
            // Off the authored path there is no next Move to guide: no chips, and the Assistance line says to Undo back.
            offPath = guiding && guided == null && !decision.advance
            moves =
                when {
                    !decision.reveal || offPath -> {
                        null
                    }

                    guided != null -> {
                        listOf(guided)
                    }

                    else -> {
                        demonstration.steps
                            .getOrNull(index)
                            ?.moves
                            .orEmpty()
                    }
                }
        }
    }
    val step = lesson.steps.getOrNull(index)
    val diagnosisCandidate =
        decision.diagnosis?.candidates?.firstOrNull()?.moves?.let { moves ->
            if (moves.isNotEmpty()) {
                moves.joinToString(" ") { Notation.format(it) }
            } else {
                null
            }
        }
    return StepCardContent(
        number = index + 1,
        count = lesson.steps.size,
        goal = step?.let { strings.resolve(it.goal) }.orEmpty(),
        keep = step?.let { strings.resolve(it.preserve) }.orEmpty(),
        watch = step?.let { strings.resolve(it.watch) }.orEmpty(),
        notice = if (notice) step?.let { strings.resolve(it.notice) } else null,
        moves = moves,
        objectiveMet = decision.satisfied || (session.syncState != SyncState.VIRTUAL && session.objectiveMet),
        feedback = decision.feedback?.text?.let { strings.resolve(it) },
        diagnosis = decision.diagnosis?.let { strings.resolve(it.explanation) },
        recoveryPrompt = decision.diagnosis?.let { strings.resolve(it.recoveryPath.prompt) },
        showMeHow =
            decision.diagnosis
                ?.takeIf { RecoveryOffer.SHOW_ME_HOW in it.recoveryPath.offers }
                ?.let { Tutor.reorient(session.virtual.orientation, session.pathEntry.orientation) },
        showMeHowLabel = strings.resolve(SHOW_ME_HOW_LABEL),
        feedbackMove = session.revision,
        showMoveControl = mode == TeachingMode.GUIDE_ME,
        // Off the path the target line's "The Moves are below." would point at chips that are not shown.
        hint =
            if (mode == TeachingMode.SHOW_ME || (offPath && session.shownRung == HintRung.TARGET)) {
                null
            } else {
                HintLadder.line(lesson, index, session.shownRung)?.let { strings.resolve(it) }
            },
        recoveryActions = recoveryActions(session, decision, strings),
        guidedRecovery = guidedRecoveryContent(session.guidedRecovery, strings),
        assistance = if (mode == TeachingMode.SHOW_ME) null else assistanceContent(session, strings, offPath, beta),
        isPhysical = session.syncState != SyncState.VIRTUAL,
        objectiveMetCopy = if (session.syncState != SyncState.VIRTUAL) strings.resolve(TextKey("msg.physical.objective-met")) else null,
        candidate = diagnosisCandidate,
    )
}

/**
 * The shown Diagnosis's Recovery Path offers that the recovery prompt draws as buttons, in the
 * order offered: *Undo* (while there is a Move to take back), *Guide me back* (while
 * [SessionState.canGuideBack]) and *Try again*. *Show me how* keeps its own control in the Diagnosis line.
 */
private fun recoveryActions(
    session: SessionState,
    decision: TutorDecision,
    strings: ContentStrings,
): List<RecoveryAction> =
    decision.diagnosis
        ?.recoveryPath
        ?.offers
        .orEmpty()
        .mapNotNull { offer ->
            when (offer) {
                RecoveryOffer.UNDO -> RecoveryAction(offer, strings.resolve(UNDO_LABEL), session.history.canUndo)
                RecoveryOffer.GUIDE_ME_BACK -> RecoveryAction(offer, strings.resolve(GUIDE_ME_BACK_LABEL), session.canGuideBack)
                RecoveryOffer.TRY_AGAIN -> RecoveryAction(offer, strings.resolve(TRY_AGAIN_LABEL), enabled = true)
                RecoveryOffer.SHOW_ME_HOW, RecoveryOffer.RE_ENTER -> null
            }
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
    renderer: Cube3DRenderer,
    certainty: Certainty,
    summary: String?,
    emphasis: NetEmphasis?,
    arrow: Move?,
    reduceMotion: Boolean,
    strings: ContentStrings,
    send: ((Instant) -> SessionEvent) -> Unit,
    showMoveKeyBar: Boolean = true,
    onMove: (Move) -> Unit = {},
) {
    val tokens = RubikTheme.tokens
    var caption by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().padding(Spacing.s2).semantics {
            isTraversalGroup = true
        },
        verticalArrangement = Arrangement.spacedBy(Spacing.s1),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            FrameLabel(certainty, session.syncState)
            val isPhysical = session.syncState != SyncState.VIRTUAL
            if (session.syncState == SyncState.PHYSICAL_MISMATCH || session.syncState == SyncState.PHYSICAL_VERIFIED ||
                (isPhysical && session.lastDiagnosis != null)
            ) {
                CertaintyChip(certainty, Modifier.padding(start = Spacing.s2))
            }
            Row(
                Modifier.weight(1f).semantics {
                    isTraversalGroup = true
                    traversalIndex = 3f
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                            .semantics {
                                liveRegion = LiveRegionMode.Polite
                            },
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
                ViewControls(renderer)
            }
        }
        renderer.Content(
            LocalRenderStyle.current,
            Modifier.fillMaxWidth().weight(1f).semantics { traversalIndex = 1f },
            onMoveIntent = { intent ->
                caption = null
                if (session.syncState != SyncState.VIRTUAL) {
                    if (showMoveKeyBar) {
                        onMove(intent.move)
                    }
                } else {
                    onMove(intent.move)
                }
            },
            emphasis = emphasis,
            arrow = arrow,
            reduceMotion = reduceMotion,
            summary = summary,
        )
        if (showMoveKeyBar) {
            MoveKeyBar(
                onMove = { move ->
                    caption = null
                    onMove(move)
                },
                onShowName = { caption = it },
                modifier =
                    Modifier.fillMaxWidth().semantics {
                        isTraversalGroup = true
                        traversalIndex = 2f
                    },
            )
        }
        Spacer(Modifier.height(Spacing.s1))
    }
    ResetConfirmation(host = host, resolve = strings::resolve, onTransition = { renderer.submit(it.presentation.toItems()) })
}

/**
 * A demonstration on the session's net: from [from] (snapped to first when it is not the current
 * Cube State) plays [moves], one per [REORIENT_STEP_MILLIS] (stepped, not animated, under
 * [reduceMotion]), holds the last frame for a moment, then snaps the net back to the session's
 * current state. *Show me how* plays a turned cube's Cube Rotations from the current state; the Hint
 * Ladder's top rung plays the shown step's Moves from that step's authored start. A demonstration
 * only: it dispatches nothing, so the session (and its undo history) never changes (AD-3, AD-5),
 * and the net matches the session again once it ends. The caller cancels it before any session
 * interaction and restores the session's net itself.
 */
internal suspend fun demonstrateOnNet(
    host: SessionHost,
    renderer: CubeRenderer,
    from: CubeState,
    moves: List<Move>,
    reduceMotion: Boolean,
    onMove: (Move?) -> Unit = {},
    onShown: (CubeState?) -> Unit = {},
) {
    try {
        val held = host.state.value
        if (from != held.virtual) renderer.submit(listOf(PresentationItem.Snap(held.revision, from.toFacelets())))
        var shown = from
        onShown(shown)
        moves.forEach { move ->
            onMove(move)
            shown = shown.apply(move)
            onShown(shown)
            val target = shown.toFacelets()
            val item =
                if (reduceMotion) {
                    PresentationItem.Snap(held.revision, target)
                } else {
                    PresentationItem.Animate(move, held.revision, target)
                }
            renderer.submit(listOf(item))
            awaitFrameMillis(REORIENT_STEP_MILLIS)
        }
        awaitFrameMillis(REORIENT_HOLD_MILLIS)
        val now = host.state.value
        renderer.submit(listOf(PresentationItem.Snap(now.revision, now.virtual.toFacelets())))
    } finally {
        onMove(null)
        onShown(null)
    }
}

/** Suspends for [millis] on the Compose frame clock, timed by a tween as the renderer's own animations are. */
private suspend fun awaitFrameMillis(millis: Long) {
    animate(0f, 1f, animationSpec = tween(millis.toInt(), easing = LinearEasing)) { _, _ -> }
}

/** Reset changes something: a Move to take back or redo, or a later step to return from. */
private fun canReset(session: SessionState): Boolean =
    session.syncState == SyncState.VIRTUAL && (session.history.canUndo || session.history.canRedo || session.stepIndex > 0)

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

/** Each net demonstration Move's slot (*Show me how*, the Hint Ladder's top rung): the renderer's flip ([Cube3DRenderer.TURN_MILLIS]) plus a pause. */
internal const val REORIENT_STEP_MILLIS = 600L

/** How long the net holds a demonstration's last frame before returning to the session's state. */
internal const val REORIENT_HOLD_MILLIS = 1_200L
private const val COMPACT_STAGE_FRACTION = 0.45f
private const val WIDE_STAGE_FRACTION = 0.55f
private val COMPACT_STAGE_MIN: Dp = 280.dp
private val EXPANDED_PANEL_MAX: Dp = 560.dp
private val TOP_BAR_HEIGHT: Dp = 56.dp

/** The *Show me how* Recovery offer's label: learner-facing content text, resolved from `strings/en.json` (AD-6). */
internal val SHOW_ME_HOW_LABEL = TextKey("msg.recovery.offer.show-me-how")

/** The recovery prompt's *Undo* label (content text, AD-6). */
internal val UNDO_LABEL = TextKey("msg.recovery.offer.undo")

/** The recovery prompt's *Guide me back* label (content text, AD-6). */
internal val GUIDE_ME_BACK_LABEL = TextKey("msg.recovery.offer.guide-me-back")

/** The recovery prompt's *Try again* label (content text, AD-6). */
internal val TRY_AGAIN_LABEL = TextKey("msg.recovery.offer.try-again")

internal val DashedBorderKey = SemanticsPropertyKey<Boolean>("DashedBorder")

/** Certainty changes are announced by the frame's live region; initial insertion is silent. */
@Composable
internal fun FrameLabel(
    certainty: Certainty,
    syncState: SyncState = SyncState.VIRTUAL,
) {
    val isPhysical = syncState != SyncState.VIRTUAL
    val tokens = RubikTheme.tokens
    val text = stringResource(if (isPhysical) R.string.frame_expected else R.string.frame_virtual)
    val description =
        if (isPhysical) {
            null
        } else {
            stringResource(
                when (certainty) {
                    Certainty.VERIFIED -> R.string.certainty_verified
                    Certainty.LIKELY -> R.string.certainty_likely
                    Certainty.UNKNOWN -> R.string.certainty_unknown
                },
            )
        }
    val strokeWidth = 1.5.dp
    val strokePx = with(LocalDensity.current) { strokeWidth.toPx() }
    val dashPx = with(LocalDensity.current) { 4.dp.toPx() }
    val borderColor = tokens.inkSecondary
    val cornerRadiusPx = with(LocalDensity.current) { Radii.xs.toPx() }

    val baseModifier = Modifier.padding(start = Spacing.s2).testTag("stage:frame")
    val styledModifier =
        if (isPhysical) {
            baseModifier
                .drawBehind {
                    drawRoundRect(
                        color = borderColor,
                        topLeft = Offset(strokePx / 2, strokePx / 2),
                        size = Size(size.width - strokePx, size.height - strokePx),
                        cornerRadius = CornerRadius(cornerRadiusPx),
                        style =
                            Stroke(
                                width = strokePx,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashPx, dashPx)),
                            ),
                    )
                }.padding(horizontal = Spacing.s2, vertical = 2.dp)
        } else {
            baseModifier
        }

    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = tokens.inkSecondary,
        modifier =
            styledModifier.semantics {
                traversalIndex = 0f
                if (isPhysical) {
                    set(DashedBorderKey, true)
                }
                if (description != null) {
                    stateDescription = description
                    liveRegion = LiveRegionMode.Polite
                }
            },
    )
}
