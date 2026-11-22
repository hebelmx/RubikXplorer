package dev.cubentia.app.ui.shell

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.cubentia.app.R
import dev.cubentia.app.content.CatalogEntry
import dev.cubentia.app.content.CatalogState
import dev.cubentia.app.content.LessonCatalog
import dev.cubentia.app.content.twoLookMoves
import dev.cubentia.app.preferences.PreferenceProvider
import dev.cubentia.app.preferences.PreferenceStore
import dev.cubentia.app.random.KotlinRandomSource
import dev.cubentia.app.scan.QuickCheckCapture
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.solver.MethodSolutionCache
import dev.cubentia.app.solver.SolverLoad
import dev.cubentia.app.solver.SolverSource
import dev.cubentia.app.ui.freecube.FreeCubeScreen
import dev.cubentia.app.ui.freecube.FreeCubeViewModel
import dev.cubentia.app.ui.lesson.LessonScreen
import dev.cubentia.app.ui.library.AlgorithmDetailScreen
import dev.cubentia.app.ui.library.LibraryFilterSaver
import dev.cubentia.app.ui.library.LibraryScreen
import dev.cubentia.app.ui.library.RequestedAlgorithmStatus
import dev.cubentia.app.ui.placement.PlacementAcceptance
import dev.cubentia.app.ui.placement.PlacementFlowViewModel
import dev.cubentia.app.ui.placement.PlacementPhase
import dev.cubentia.app.ui.placement.PlacementScreen
import dev.cubentia.app.ui.recognize.RecognizeFlowViewModel
import dev.cubentia.app.ui.recognize.RecognizePhase
import dev.cubentia.app.ui.recognize.RecognizeScreen
import dev.cubentia.app.ui.settings.SettingsScreen
import dev.cubentia.app.ui.solve.ResolveState
import dev.cubentia.app.ui.solve.SolutionStyleViewModel
import dev.cubentia.app.ui.solve.SolveFlowViewModel
import dev.cubentia.app.ui.solve.SolvePhase
import dev.cubentia.app.ui.solve.SolvePlaybackScreen
import dev.cubentia.app.ui.solve.SolveScreen
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.welcome.WelcomeScreen
import dev.cubentia.application.port.AlgorithmStatusLoad
import dev.cubentia.application.port.FeatureFlag
import dev.cubentia.application.port.FeatureFlags
import dev.cubentia.application.port.FlagLevel
import dev.cubentia.application.port.showsBeta
import dev.cubentia.application.preference.Preference
import dev.cubentia.application.session.CubeMode
import dev.cubentia.application.session.Effect
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.application.session.SessionKind
import dev.cubentia.application.session.SessionState
import dev.cubentia.application.usecase.EntryLevel
import dev.cubentia.application.usecase.RecognizeCase
import dev.cubentia.application.usecase.SolveByMethod
import dev.cubentia.application.usecase.StagePractice
import dev.cubentia.application.usecase.TwoLookPractice
import dev.cubentia.domain.content.AlgorithmId
import dev.cubentia.domain.content.AlgorithmStatus
import dev.cubentia.domain.content.CaseSet
import dev.cubentia.domain.content.ContentBundle
import dev.cubentia.domain.content.Lesson
import dev.cubentia.domain.content.LessonId
import dev.cubentia.domain.content.LibraryFilter
import dev.cubentia.domain.content.Stage
import dev.cubentia.domain.content.StageId
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.lastlayer.LastLayerMatcher
import dev.cubentia.domain.lastlayer.LastLayerMatcherResult
import dev.cubentia.domain.lastlayer.RecognitionDiagram
import dev.cubentia.domain.lastlayer.RecognitionDiagramResult
import dev.cubentia.domain.recognition.PhysicalObservation
import dev.cubentia.domain.tutor.Mastery
import dev.cubentia.domain.tutor.StageProgress
import dev.cubentia.domain.tutor.StageStatus
import dev.cubentia.domain.tutor.TeachingMode
import dev.cubentia.domain.tutor.TwoLookMastery
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** The top-level destinations of the navigation shell. */
enum class Tab(
    @param:DrawableRes val icon: Int,
    @param:StringRes val label: Int,
) {
    LEARN(R.drawable.ic_learn, R.string.nav_learn),
    SOLVE(R.drawable.ic_solve, R.string.nav_solve),
    LIBRARY(R.drawable.ic_library, R.string.nav_library),
    YOU(R.drawable.ic_you, R.string.nav_you),
}

/**
 * The navigation shell (UX-DR7): Learn, Solve (color entry and the free cube), Library and You behind a `NavigationBar` when
 * compact and a `NavigationRail` from medium width. An open Lesson or Solve Playback replaces the shell as a
 * full-screen task flow, with no bar. The tab and the open Lesson survive configuration changes; the
 * Lesson's progress lives in [host]'s session. [flags] decides which Teaching Modes the Lesson offers:
 * *Test me* only while `TEST_ME` is enabled; `ADAPTIVE_ASSISTANCE` decides whether help adapts in the Lesson,
 * and at BETA the Assistance line carries the beta label (AD-18).
 * [showWelcome] offers Welcome on a cold start with no profile, after restore completes.
 */
@Composable
fun AppShell(
    host: SessionHost,
    catalog: LessonCatalog,
    freeCube: FreeCubeViewModel,
    showDebugToggles: Boolean,
    flags: FeatureFlags,
    modifier: Modifier = Modifier,
    preferenceStore: PreferenceStore =
        remember {
            PreferenceStore()
        },
    stagePractice: StagePractice = remember { StagePractice(KotlinRandomSource()) },
    showWelcome: Boolean = false,
    calibration: dev.cubentia.app.ui.settings.CalibrationSettings? = null,
    onScan: (() -> Unit)? = null,
    onQuickScan: (() -> Unit)? = null,
    quickCheck: QuickCheckCapture? = null,
    solveFlow: SolveFlowViewModel =
        remember {
            SolveFlowViewModel(
                object : SolverSource {
                    override val isReady = false

                    override suspend fun load(onGenerating: () -> Unit): SolverLoad = SolverLoad.Failed
                },
            )
        },
    onPlacementScan: (() -> Unit)? = null,
    placementFlow: PlacementFlowViewModel = remember { PlacementFlowViewModel() },
    onRecognize: ((Int) -> Unit)? = null,
    recognizeFlow: RecognizeFlowViewModel = remember { RecognizeFlowViewModel() },
    solutionStyles: SolutionStyleViewModel = remember { SolutionStyleViewModel({ SolveByMethod(it) }, MethodSolutionCache()) },
) {
    PreferenceProvider(preferenceStore) {
        ShellContent(
            host,
            catalog,
            freeCube,
            showDebugToggles,
            flags,
            modifier,
            stagePractice,
            preferenceStore,
            showWelcome,
            solveFlow,
            calibration,
            onScan,
            onQuickScan,
            quickCheck,
            onPlacementScan,
            placementFlow,
            onRecognize,
            recognizeFlow,
            solutionStyles,
        )
    }
}

@Composable
private fun ShellContent(
    host: SessionHost,
    catalog: LessonCatalog,
    freeCube: FreeCubeViewModel,
    showDebugToggles: Boolean,
    flags: FeatureFlags,
    modifier: Modifier,
    stagePractice: StagePractice,
    preferenceStore: PreferenceStore,
    showWelcome: Boolean,
    solveFlow: SolveFlowViewModel,
    calibration: dev.cubentia.app.ui.settings.CalibrationSettings?,
    onScan: (() -> Unit)?,
    onQuickScan: (() -> Unit)?,
    quickCheck: QuickCheckCapture?,
    onPlacementScan: (() -> Unit)?,
    placementFlow: PlacementFlowViewModel,
    onRecognize: ((Int) -> Unit)?,
    recognizeFlow: RecognizeFlowViewModel,
    solutionStyles: SolutionStyleViewModel,
) {
    // Quick Check is a camera capture: like Scan it and calibration, it stays hidden while CAMERA_SCAN is OFF.
    @Suppress("NAME_SHADOWING")
    val quickCheck = quickCheck.takeIf { flags.isEnabled(FeatureFlag.CAMERA_SCAN) }
    LaunchedEffect(catalog) { catalog.load() }
    val content by catalog.state.collectAsState()
    val session by host.state.collectAsState()
    val solvePhase by solveFlow.phase.collectAsState()
    val recognizePhase by recognizeFlow.phase.collectAsState()
    val recognitionPending by recognizeFlow.pending.collectAsState()
    val recognizeAction = onRecognize.takeIf { flags.isEnabled(FeatureFlag.CASE_RECOGNITION) }
    val placementPhase by placementFlow.phase.collectAsState()
    val pendingScan by solveFlow.pendingScan.collectAsState()
    val placementScanState by placementFlow.acceptedScan.collectAsState()
    val placementAcceptance by placementFlow.acceptance.collectAsState()
    val algorithmStatusLoad by host.algorithmStatusLoad.collectAsState(null)
    var algorithmStatuses by remember { mutableStateOf<Map<AlgorithmId, AlgorithmStatus>>(emptyMap()) }
    LaunchedEffect(algorithmStatusLoad) {
        (algorithmStatusLoad as? AlgorithmStatusLoad.Loaded)?.let { algorithmStatuses = it.statuses }
    }
    val progressEvents by host.progressEvents.collectAsState()
    val attemptRecords by host.attemptRecords.collectAsState()
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(Tab.LEARN) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var welcome by rememberSaveable { mutableStateOf<Boolean?>(if (showWelcome) null else false) }
    var welcomeAbandoned by rememberSaveable { mutableStateOf(false) }
    var stageAbandoned by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedStage by rememberSaveable { mutableStateOf<String?>(null) }
    var openAlgorithm by rememberSaveable { mutableStateOf<String?>(null) }
    var autoPlayAlgorithm by rememberSaveable { mutableStateOf(false) }
    var openLesson by rememberSaveable { mutableStateOf<String?>(null) }
    var openPractice by rememberSaveable { mutableStateOf<String?>(null) }
    var practiceEpoch by rememberSaveable { mutableIntStateOf(0) }

    // The LL practice's generated scramble, saved so recreation keeps the attempt; replaced only on an explicit new practice.
    var twoLookScramble by rememberSaveable { mutableStateOf<String?>(null) }
    var openSkipCheck by rememberSaveable { mutableStateOf<String?>(null) }
    var skipCheckEpoch by rememberSaveable { mutableIntStateOf(0) }

    // The Stage practice's and skip-ahead check's generated scrambles, saved so recreation keeps the attempt (and a
    // reached milestone); a new practice or check bumps its epoch, which resets the saved scramble and deals a new one.
    var practiceScramble by rememberSaveable(openPractice, practiceEpoch) { mutableStateOf<String?>(null) }
    var skipCheckScramble by rememberSaveable(openSkipCheck, skipCheckEpoch) { mutableStateOf<String?>(null) }
    // Held above the Lesson and Settings task flows so closing either returns to the same place in the list.
    val learnScroll = rememberScrollState()
    var libraryFilter by rememberSaveable(stateSaver = LibraryFilterSaver) { mutableStateOf(LibraryFilter()) }
    val libraryGrid = rememberLazyGridState()
    val libraryFamilyChips = rememberScrollState()
    var scrollToTwoLook by remember { mutableStateOf(false) }
    var twoLookLocked by rememberSaveable { mutableStateOf(false) }

    // The entry-level picker's caller ("welcome" or "learn"), the Learn *Find my place* sheet, and a Placement run
    // started from Learn (Story 14.1, UX Flow 8).
    var entryLevelFrom by rememberSaveable { mutableStateOf<String?>(null) }
    var findMyPlace by rememberSaveable { mutableStateOf(false) }
    var placementFromLearn by rememberSaveable { mutableStateOf(false) }
    var placementLeftIdle by rememberSaveable { mutableStateOf(false) }
    var entryLevelSaving by remember { mutableStateOf(false) }
    var entryLevelFailed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(tab, selectedStage, scrollToTwoLook) {
        if (tab == Tab.LEARN && selectedStage == null && scrollToTwoLook) {
            withFrameNanos { }
            learnScroll.scrollTo(learnScroll.maxValue)
            scrollToTwoLook = false
        }
    }
    if (settings) {
        SettingsScreen(
            // Calibration copy is content text: the rows wait for the content, and stay hidden while CAMERA_SCAN is OFF.
            calibration =
                calibration.takeIf {
                    content is CatalogState.Ready && flags.isEnabled(FeatureFlag.CAMERA_SCAN)
                },
            strings = (content as? CatalogState.Ready)?.strings,
            onClose = { settings = false },
            onResetProgress = {
                // Runs in the shell's scope so closing Settings does not cancel the reset; navigates only on success.
                scope
                    .async {
                        host.resetProgress().also { reset ->
                            if (reset) {
                                selectedStage = null
                                twoLookLocked = false
                                stageAbandoned = null
                                welcomeAbandoned = false
                                // A Placement phase left by a late scan outcome must not render over the fresh Welcome.
                                placementFlow.reset()
                                preferenceStore.update(Preference.Onboarded(false))
                                welcome = true
                            }
                        }
                    }.await()
            },
        )
        return
    }

    LaunchedEffect(content, host) { (content as? CatalogState.Ready)?.let { host.restore(it.bundle) } }
    LaunchedEffect(content, host, welcome) {
        if (welcome == null && content !is CatalogState.Loading) {
            if (content is CatalogState.Ready) host.awaitRestored()
            preferenceStore.awaitLoaded()
            welcome =
                !preferenceStore.preferences.value.onboarded &&
                host.progressEvents.value.isEmpty() &&
                host.attemptRecords.value.isEmpty()
        }
    }
    if (welcome == null) {
        Box(modifier.fillMaxSize().background(RubikTheme.tokens.surfaceBase))
        return
    }
    val ready = content as? CatalogState.Ready
    // One matcher per loaded catalog, not one per capture.
    val recognizeCase = remember(ready) { ready?.let { RecognizeCase.of(it.bundle.cases, it.bundle.algorithms) } }
    val placementMatcher =
        remember(ready) {
            ready?.let { (LastLayerMatcher.of(it.bundle.cases, it.bundle.algorithms) as? LastLayerMatcherResult.Built)?.matcher }
        }
    LaunchedEffect(ready, recognitionPending, recognizeFlow) {
        if (ready != null && recognitionPending) {
            recognizeFlow.evaluate(recognizeCase)
        }
    }
    LaunchedEffect(ready, placementScanState, placementFlow) {
        if (ready != null && placementScanState != null) {
            placementFlow.evaluateScan(ready.bundle.stages, placementMatcher)
        }
    }
    val solveOnSolved: suspend (CubeState, List<FaceTurn>) -> Unit = { state, moves ->
        if (ready != null) {
            val scanned = solveFlow.isAcceptedScan()
            val colors = if (scanned) solveFlow.acceptedScanColors() else null
            // A new playback opens on *Shortest* (Story 15.3).
            solutionStyles.opened(state, moves, scanned)
            host.dispatch(colors) { SessionEvent.SolvePlaybackStarted(state, moves, it, ready.bundle.tutorConfig, scanned) }
        }
    }
    // Cancel a running solve and discard its scan baseline when the Learner leaves the Solve tab.
    LaunchedEffect(tab) { if (tab != Tab.SOLVE) solveFlow.leaveSolveTab() }
    // The *I know 2-look* OLL filter lasts one Library visit; leaving Learn ends a Placement run and the sheet opened there.
    LaunchedEffect(tab) {
        if (tab != Tab.LIBRARY && libraryFilter.set != null) libraryFilter = libraryFilter.copy(set = null)
        if (tab != Tab.LEARN) {
            placementFromLearn = false
            findMyPlace = false
        }
    }
    LaunchedEffect(ready, pendingScan, tab) {
        if (ready != null && pendingScan != null && tab == Tab.SOLVE) {
            // A scan returned into a recreated process waits for the cold-start restore, so playback never races it.
            host.awaitRestored()
            solveFlow.takePendingScan()?.let { solveFlow.confirm(it, solveOnSolved) }
        }
    }
    if (ready != null && session.kind == SessionKind.SOLVE_PLAYBACK) {
        LaunchedEffect(session.revision) { solutionStyles.sessionMoved(session.revision) }
        val styles by solutionStyles.state.collectAsState()
        val suggested =
            remember(ready, progressEvents, attemptRecords) {
                dev.cubentia.app.ui.solve
                    .suggestedStyle(ready.bundle, progressEvents, attemptRecords)
            }
        val segmentLabels =
            remember(ready, styles.segments) {
                dev.cubentia.app.ui.solve
                    .segmentLabels(styles.segments, ready.bundle)
            }
        // Restarts the same SOLVE_PLAYBACK at step 1 on [moves] from the input, still following on the real cube (AD-3).
        val restartPlayback: suspend (List<Move>) -> Unit = { moves ->
            val input = solutionStyles.input
            val before = host.state.value
            if (input != null && before.kind == SessionKind.SOLVE_PLAYBACK) {
                val scanned = solutionStyles.fromAcceptedScan
                val colors = if (scanned) host.runScanColors() else null
                host.dispatch(colors) { SessionEvent.SolvePlaybackStarted(input, moves, it, ready.bundle.tutorConfig, scanned) }
                if (before.isPhysical) host.dispatch { SessionEvent.ModeSwitched(CubeMode.PHYSICAL, it) }
            }
        }
        SolvePlaybackScreen(
            styles = styles,
            suggestedStyle = suggested,
            // The real cube has left the start (after *Done*): the style changes only back at step 1.
            styleLocked = solutionStyles.input?.let { session.expected != it } ?: false,
            onChooseStyle = { style -> solutionStyles.choose(style, ready.bundle, { host.state.value.revision }, restartPlayback) },
            segments = segmentLabels,
            quickCheck = quickCheck,
            requestQuickCheck = { full ->
                if (host.state.value.pendingQuickCheck == null) {
                    host
                        .dispatch {
                            SessionEvent.QuickCheckRequested(at = it, fullScan = full)
                        }.effects
                        .filterIsInstance<Effect.QuickCheck>()
                        .forEach { quickCheck?.start(it) }
                }
            },
            session = session,
            strings = ready.strings,
            onEvent = { event -> scope.launch { host.dispatch { event } } },
            resolve = ResolveState(solvePhase is SolvePhase.Solving, (solvePhase as? SolvePhase.Editing)?.error),
            onUseMyCube = {
                // Re-solves the observed cube off the main thread (the Solve tab's path) and restarts playback from it, still
                // following on the real cube; a result is dropped when the session moved on meanwhile (AD-3 revision echo).
                val from = host.state.value
                val observed = (from.observed as? PhysicalObservation.Full)?.state
                if (from.canResolveObserved && observed != null) {
                    solveFlow.confirm(observed) { state, moves ->
                        val now = host.state.value
                        if (now.revision == from.revision && now.canResolveObserved) {
                            solutionStyles.opened(state, moves, true)
                            host.dispatch { SessionEvent.SolvePlaybackStarted(state, moves, it, ready.bundle.tutorConfig, true) }
                            host.dispatch { SessionEvent.ModeSwitched(CubeMode.PHYSICAL, it) }
                        }
                    }
                    if (observed.isSolved) {
                        solutionStyles.left()
                        scope.launch {
                            host.endSolve(ready.bundle)
                            tab = Tab.SOLVE
                        }
                    }
                }
            },
            onClose = {
                solutionStyles.left()
                scope.launch {
                    host.endSolve(ready.bundle)
                    tab = Tab.SOLVE
                }
            },
            onLearn = {
                solutionStyles.left()
                scope.launch {
                    host.endSolve(ready.bundle)
                    tab = Tab.LEARN
                }
            },
            modifier = modifier,
        )
        return
    }

    fun startCourse(stage: String?) {
        preferenceStore.update(Preference.Onboarded(true))
        welcome = false
        tab = Tab.LEARN
        selectedStage = stage
        placementFlow.reset()
        recognizeFlow.reset()
        entryLevelFrom = null
        entryLevelFailed = false
        placementFromLearn = false
    }

    // Records the chosen entry level (Story 14.1) in the shell's scope; navigates only once the write succeeded.
    fun chooseEntryLevel(level: EntryLevel) {
        val bundle = ready?.bundle ?: return
        if (entryLevelSaving) return
        entryLevelSaving = true
        entryLevelFailed = false
        scope.launch {
            // A MASTERED Stage is never skipped (AD-10): pass the current-epoch mastered Stages.
            val mastered =
                Mastery.masteredStages(
                    bundle.stages + bundle.twoLookStages,
                    attemptRecords,
                    progressEvents,
                    bundle.tutorConfig.mastery,
                )
            val saved = host.chooseEntryLevel(level, bundle.stages, bundle.twoLookStages, mastered)
            entryLevelSaving = false
            if (!saved) {
                entryLevelFailed = true
                return@launch
            }
            welcomeAbandoned = false
            startCourse(null)
            // Levels 1 and 2 land on the Learn Stage list (level 2 at *Faster last layer*); 2-look opens the OLL Library.
            scrollToTwoLook = level == EntryLevel.BEGINNER_METHOD
            if (level == EntryLevel.TWO_LOOK) {
                tab = Tab.LIBRARY
                openAlgorithm = null
                libraryFilter = LibraryFilter(set = CaseSet.OLL)
                libraryGrid.requestScrollToItem(0)
            }
        }
    }

    fun startStageOne() {
        startCourse(
            ready
                ?.bundle
                ?.stages
                ?.firstOrNull()
                ?.id
                ?.value ?: "stage-01-fundamentals",
        )
    }
    // An accept write finished, possibly in a composition lost to recreation: this one navigates and onboards.
    LaunchedEffect(placementAcceptance) {
        if (placementAcceptance is PlacementAcceptance.Saved) {
            placementFlow.consumeSaved()?.let { saved ->
                placementFlow.accepted(saved.stageId)
                startCourse(saved.stageId.value.takeUnless { saved.faster })
                scrollToTwoLook = saved.faster
            }
        }
    }
    // Placement is entered from Welcome or from a Recognize result's "Start from my cube" (Library tab): a phase or scan
    // still set once neither is the active screen is a late scan outcome (or a denial) for a flow the Learner already
    // left, so it is dropped instead of rendering.
    val recognizeOffersPlacement = tab == Tab.LIBRARY && openAlgorithm == null && recognizePhase != RecognizePhase.Idle
    val placementStale = openSkipCheck != null || (welcome == false && !recognizeOffersPlacement && !placementFromLearn)
    // A Placement run from Learn ends once its phase returns to Idle after having left it.
    LaunchedEffect(placementPhase) {
        if (placementPhase != PlacementPhase.Idle) {
            placementLeftIdle = true
        } else if (placementLeftIdle) {
            placementLeftIdle = false
            placementFromLearn = false
        }
    }
    LaunchedEffect(placementStale, placementPhase, placementScanState) {
        if (placementStale && (placementPhase != PlacementPhase.Idle || placementScanState != null)) placementFlow.reset()
    }
    val placementScan = onPlacementScan.takeIf { ready != null && flags.isEnabled(FeatureFlag.PLACEMENT) }
    if (ready != null && placementScan != null && placementPhase != PlacementPhase.Idle && !placementStale) {
        PlacementScreen(
            phase = placementPhase,
            flow = placementFlow,
            strings = ready.strings,
            stages = ready.bundle.stages,
            twoLookStages = ready.bundle.twoLookStages,
            matcher = placementMatcher,
            cases = ready.bundle.cases,
            onAccept = { target, faster ->
                if (placementAcceptance != PlacementAcceptance.Saving) {
                    if (target == ready.bundle.stages.first()) {
                        placementFlow.accepted(target.id)
                        startStageOne()
                    } else {
                        // Runs in the view model's scope so recreating the Activity mid-write keeps the outcome.
                        val course = ready.bundle.stages
                        placementFlow.accept(target.id, faster) {
                            host.placeFromScan(target.id, if (target in course) course else course + target)
                        }
                    }
                }
            },
            onScanAgain = {
                placementFlow.clearSaveFailure()
                placementScan()
            },
            modifier = modifier,
            accepting = placementAcceptance == PlacementAcceptance.Saving,
            saveFailed = placementAcceptance == PlacementAcceptance.Failed,
        )
        return
    }
    if (ready != null && entryLevelFrom != null && openSkipCheck == null) {
        val fromLearn = entryLevelFrom == "learn"
        EntryLevelScreen(
            strings = ready.strings,
            onChoose = ::chooseEntryLevel,
            onCheck = {
                entryLevelFailed = false
                openSkipCheck = "course"
                skipCheckEpoch++
            },
            onBack = {
                if (!entryLevelSaving) {
                    entryLevelFrom = null
                    entryLevelFailed = false
                }
            },
            onScan =
                placementScan?.let { scan ->
                    {
                        entryLevelFailed = false
                        placementFromLearn = fromLearn
                        scan()
                    }
                },
            scanBeta = flags.showsBeta(FeatureFlag.PLACEMENT),
            saving = entryLevelSaving,
            saveFailed = entryLevelFailed,
            modifier = modifier,
        )
        return
    }
    if (welcome == true && openSkipCheck == null) {
        WelcomeScreen(
            onImNew = ::startStageOne,
            onStartFromCube = placementScan,
            strings = ready?.strings,
            onCanAlreadySolve = {
                entryLevelFailed = false
                entryLevelFrom = "welcome"
            },
            flags = flags,
            preferenceStore = preferenceStore,
            abandoned = welcomeAbandoned,
            modifier = modifier,
        )
        return
    }
    val entry = openLesson?.let { ready?.find(LessonId(it)) }
    if (ready != null && entry != null) {
        val lessonAlgorithms = ready.bundle.lessonAlgorithms[entry.lesson.id].orEmpty()
        val notStarted = algorithmsToOffer(lessonAlgorithms, algorithmStatusLoad)
        val recognition =
            remember(entry.lesson.id) {
                recognitionDiagramFor(ready.bundle, entry.lesson.id)
            }
        LessonScreen(
            quickCheck = quickCheck,
            host = host,
            entry = entry,
            strings = ready.strings,
            modes = availableModes(flags),
            onClose = { openLesson = null },
            modifier = modifier,
            adaptiveAssistance = flags.level(FeatureFlag.ADAPTIVE_ASSISTANCE),
            testMe = flags.level(FeatureFlag.TEST_ME),
            isTwoLook = entry.stage in ready.bundle.twoLookStages,
            recognition = recognition,
            offerAlgorithms = notStarted,
            onAddToLearning = {
                // The host re-checks each persisted status in the write step; the collected map may be stale.
                notStarted.map { host.startLearning(it) }.all { it }
            },
            onPracticeNewScramble = {
                openLesson = null
                if (entry.stage in ready.bundle.twoLookStages) {
                    twoLookScramble = newTwoLookPractice(ready).entryState
                    openPractice = "two-look-practice"
                } else {
                    openPractice = if (entry.stage.id != StagePractice.STAGE_1_ID) entry.stage.id.value else "stage-07-independent-practice"
                }
                practiceEpoch++
            },
            twoLookStages = ready.bundle.twoLookStages,
            stages = ready.bundle.stages,
            onNextStage = { nextStageId ->
                openLesson = null
                openPractice = null
                selectedStage = nextStageId.value
                tab = Tab.LEARN
            },
        )
        return
    }

    val twoLookMoves = remember(ready) { ready?.bundle?.twoLookMoves().orEmpty() }
    val practiceEntry =
        remember(openPractice, practiceEpoch, ready, twoLookScramble) {
            val stageId = openPractice?.let(::StageId)
            val stage = stageId?.takeIf { it != StagePractice.STAGE_1_ID }?.let { id -> ready?.bundle?.stages?.firstOrNull { it.id == id } }
            if (openPractice == "two-look-practice" && ready != null) {
                val lesson =
                    twoLookScramble?.let(TwoLookPractice::lessonAt)
                        ?: newTwoLookPractice(ready).also { twoLookScramble = it.entryState }
                CatalogEntry(
                    lesson,
                    Stage(
                        lesson.stageId,
                        dev.cubentia.domain.content
                            .TextKey("stage.two-look-practice.title"),
                        listOf(lesson),
                    ),
                    ready.bundle.tutorConfig,
                )
            } else if (stageId != null && stage != null && ready != null) {
                val lesson = stagePractice(stageId).keepingScramble(practiceScramble) { practiceScramble = it }
                CatalogEntry(lesson, stage, ready.bundle.tutorConfig)
            } else {
                null
            }
        }
    if (ready != null && practiceEntry != null) {
        LessonScreen(
            quickCheck = quickCheck,
            host = host,
            entry = practiceEntry,
            strings = ready.strings,
            modes = emptyList(),
            onClose = {
                openPractice = null
                scope.launch { host.endSession() }
            },
            modifier = modifier,
            adaptiveAssistance = FlagLevel.OFF,
            onPracticeNewScramble = {
                if (openPractice == "two-look-practice") twoLookScramble = newTwoLookPractice(ready).entryState
                openPractice = practiceEntry.stage.id.value
                practiceEpoch++
            },
            twoLookStages = ready.bundle.twoLookStages,
            stages = ready.bundle.stages,
            onNextStage = { nextStageId ->
                scope.launch { host.endSession() }
                openLesson = null
                openPractice = null
                selectedStage = nextStageId.value
                tab = Tab.LEARN
            },
            isPractice = true,
            twoLookPractice = openPractice == "two-look-practice",
            twoLookAlgorithms = twoLookMoves,
        )
        return
    }

    val skipCheckEntry =
        remember(openSkipCheck, skipCheckEpoch, ready) {
            val key = openSkipCheck
            if (key == null || ready == null) {
                null
            } else if (key == "course") {
                val lesson = stagePractice.courseSkipCheckLesson().keepingScramble(skipCheckScramble) { skipCheckScramble = it }
                val stage = ready.bundle.stages.lastOrNull() ?: return@remember null
                CatalogEntry(lesson, stage, ready.bundle.tutorConfig)
            } else {
                val stageId = StageId(key)
                val stage = ready.bundle.stages.firstOrNull { it.id == stageId }
                if (stage != null) {
                    val lesson = stagePractice.skipCheckLesson(stageId).keepingScramble(skipCheckScramble) { skipCheckScramble = it }
                    CatalogEntry(lesson, stage, ready.bundle.tutorConfig)
                } else {
                    null
                }
            }
        }
    if (ready != null && skipCheckEntry != null) {
        LessonScreen(
            quickCheck = quickCheck,
            host = host,
            entry = skipCheckEntry,
            strings = ready.strings,
            modes = emptyList(),
            onClose = {
                val isCourse = openSkipCheck == "course"
                val stageId = openSkipCheck
                val wasSatisfied =
                    host.state.value.decision
                        ?.satisfied == true
                openSkipCheck = null
                scope.launch { host.endSession() }
                if (!wasSatisfied) {
                    if (isCourse) {
                        // Given up from Learn's picker: back to the picker, with no abandon line on it.
                        if (entryLevelFrom != "learn") {
                            entryLevelFrom = null
                            welcomeAbandoned = true
                        }
                    } else if (stageId != null) {
                        stageAbandoned = stageId
                    }
                }
            },
            modifier = modifier,
            adaptiveAssistance = FlagLevel.OFF,
            twoLookStages = ready.bundle.twoLookStages,
            stages = ready.bundle.stages,
            isSkipCheck = true,
            skipCheckInstruction = stringResource(R.string.skip_check_instruction),
            onSkipCheckPassed = {
                val isCourse = openSkipCheck == "course"
                val currentStageId = openSkipCheck?.let(::StageId)
                openSkipCheck = null
                if (isCourse) {
                    entryLevelFrom = null
                    welcome = false
                    welcomeAbandoned = false
                    tab = Tab.LEARN
                    selectedStage = null
                    scope.launch {
                        host.endSession()
                        host.passSkipCheck(ready.bundle.stages.map { it.id }, ready.bundle.stages)
                    }
                } else if (currentStageId != null) {
                    stageAbandoned = null
                    val stageIndex = ready.bundle.stages.indexOfFirst { it.id == currentStageId }
                    if (stageIndex >= 0) {
                        val nextStage = ready.bundle.stages.getOrNull(stageIndex + 1)
                        selectedStage = nextStage?.id?.value
                    }
                    scope.launch {
                        host.endSession()
                        host.passSkipCheck(listOf(currentStageId), ready.bundle.stages)
                    }
                }
            },
        )
        return
    }
    // Back from another tab returns to Learn first; back from Stage detail returns to the Stage list before leaving the app.
    BackHandler(enabled = tab != Tab.LEARN || selectedStage != null || twoLookLocked || openPractice != null || openSkipCheck != null) {
        if (openSkipCheck != null) {
            val isCourse = openSkipCheck == "course"
            val stageId = openSkipCheck
            openSkipCheck = null
            scope.launch { host.endSession() }
            if (isCourse) {
                if (entryLevelFrom != "learn") {
                    entryLevelFrom = null
                    welcomeAbandoned = true
                }
            } else if (stageId != null) {
                stageAbandoned = stageId
            }
        } else if (openPractice != null) {
            openPractice = null
            scope.launch { host.endSession() }
        } else if (tab == Tab.LIBRARY && openAlgorithm != null) {
            openAlgorithm = null
        } else if (tab == Tab.LIBRARY && recognizePhase != RecognizePhase.Idle) {
            recognizeFlow.reset()
        } else if (tab != Tab.LEARN) {
            tab = Tab.LEARN
        } else {
            selectedStage = null
            twoLookLocked = false
        }
    }

    val allStagesFinished =
        ready != null && ready.bundle.stages.isNotEmpty() &&
            ready.bundle.stages.all { stage ->
                val s = StageProgress.status(stage, ready.bundle.stages, progressEvents)
                s == StageStatus.SKIPPED || s == StageStatus.SKIPPED_BY_PLACEMENT || s == StageStatus.COMPLETED ||
                    s == StageStatus.MASTERED
            }

    val destination: @Composable (Modifier, WindowWidthClass) -> Unit = { destinationModifier, widthClass ->
        when (tab) {
            Tab.LIBRARY -> {
                val algorithm = ready?.bundle?.algorithms?.firstOrNull { it.id.value == openAlgorithm }
                val case = ready?.bundle?.cases?.firstOrNull { it.id == algorithm?.caseId }
                if (algorithm != null && case != null && ready != null) {
                    val requestedStatus = remember(algorithm.id) { RequestedAlgorithmStatus() }
                    AlgorithmDetailScreen(
                        case,
                        algorithm,
                        ready.strings,
                        {
                            openAlgorithm = null
                        },
                        destinationModifier.background(RubikTheme.tokens.surfaceBase).safeDrawingPadding(),
                        status =
                            requestedStatus.shown(
                                algorithmStatuses[algorithm.id] ?: AlgorithmStatus.NOT_STARTED,
                            ),
                        autoPlay = autoPlayAlgorithm,
                        onSetStatus = { status ->
                            requestedStatus.request(status)
                            scope.launch { requestedStatus.settled(host.setAlgorithmStatus(algorithm.id, status)) }
                        },
                    )
                } else if (ready != null && recognizeAction != null && recognizePhase != RecognizePhase.Idle) {
                    RecognizeScreen(
                        recognizePhase,
                        ready,
                        recognizeFlow,
                        recognizeAction,
                        placementScan,
                        placementBeta = flags.showsBeta(FeatureFlag.PLACEMENT),
                        onOpen = { id, autoPlay ->
                            autoPlayAlgorithm = autoPlay
                            openAlgorithm = id.value
                        },
                        modifier = destinationModifier.background(RubikTheme.tokens.surfaceBase).safeDrawingPadding(),
                    )
                } else {
                    LibraryScreen(
                        content,
                        widthClass,
                        libraryFilter,
                        { libraryFilter = it },
                        libraryGrid,
                        {
                            selectedStage = null
                            tab = Tab.LEARN
                            scrollToTwoLook = allStagesFinished
                            twoLookLocked = !allStagesFinished
                        },
                        destinationModifier
                            .background(
                                RubikTheme.tokens.surfaceBase,
                            ).safeDrawingPadding(),
                        statuses = algorithmStatuses,
                        familyChipsScroll = libraryFamilyChips,
                        onOpenAlgorithm = {
                            autoPlayAlgorithm = false
                            openAlgorithm = it.value
                        },
                        onRecognize = recognizeAction?.let { start -> { start(2) } },
                        recognizeBeta = flags.showsBeta(FeatureFlag.CASE_RECOGNITION),
                    )
                }
            }

            Tab.YOU -> {
                YouScreen(
                    catalog = content,
                    progressEvents = progressEvents,
                    attemptRecords = attemptRecords,
                    onSettings = { settings = true },
                    modifier = destinationModifier,
                )
            }

            Tab.LEARN -> {
                LearnScreen(
                    catalog = content,
                    continueEntry =
                        if (session.kind?.isTaught == true) {
                            session.lesson?.let { ready?.find(it.id) }
                        } else {
                            twoLookPracticeContinue(session, ready)
                        },
                    progressEvents = progressEvents,
                    attemptRecords = attemptRecords,
                    selectedStage = selectedStage?.let(::StageId),
                    onSelectStage = {
                        selectedStage = it?.value
                        stageAbandoned = null
                    },
                    onOpen = {
                        if (it == TwoLookMastery.PRACTICE_ID) {
                            // Resumes the live LL practice (also after a cold-start restore) on its own scramble.
                            twoLookScramble = session.lesson?.entryState
                            openPractice = "two-look-practice"
                            practiceEpoch++
                        } else {
                            openLesson = it.value
                        }
                    },
                    onPracticeStage = { stageId ->
                        openPractice = stageId.value
                        practiceEpoch++
                    },
                    skipCheckEnabled = true,
                    onSkipCheck = { stageId ->
                        openSkipCheck = stageId.value
                        skipCheckEpoch++
                        stageAbandoned = null
                    },
                    abandonedSkipCheckStage = stageAbandoned?.let(::StageId),
                    twoLookProgress =
                        ready?.let {
                            TwoLookMastery.evaluate(
                                it.bundle.twoLookStages,
                                attemptRecords,
                                progressEvents,
                                it.bundle.tutorConfig.twoLookSolvesRequired,
                            )
                        },
                    showFasterLastLayer = allStagesFinished,
                    onTwoLookPractice = {
                        twoLookScramble = ready?.let { newTwoLookPractice(it).entryState }
                        openPractice = "two-look-practice"
                        practiceEpoch++
                    },
                    showTwoLookLocked = twoLookLocked && !allStagesFinished,
                    onCloseTwoLookLocked = { twoLookLocked = false },
                    scrollState = learnScroll,
                    onFindMyPlace = { findMyPlace = true }.takeIf { ready != null },
                    // The inset area takes surfaceBase too, not the window background.
                    modifier = destinationModifier.background(RubikTheme.tokens.surfaceBase).safeDrawingPadding(),
                )
                if (findMyPlace && ready != null && selectedStage == null) {
                    FindMyPlaceSheet(
                        strings = ready.strings,
                        onChooseLevel = {
                            entryLevelFailed = false
                            entryLevelFrom = "learn"
                        },
                        onDismiss = { findMyPlace = false },
                        onScan =
                            placementScan?.let { scan ->
                                {
                                    placementFromLearn = true
                                    scan()
                                }
                            },
                        scanBeta = flags.showsBeta(FeatureFlag.PLACEMENT),
                    )
                }
            }

            Tab.SOLVE -> {
                if (ready != null) {
                    SolveScreen(
                        solveFlow,
                        ready.strings,
                        freeCube,
                        showDebugToggles,
                        session.kind?.takesMoves == true,
                        onScan = onScan.takeIf { flags.isEnabled(FeatureFlag.CAMERA_SCAN) },
                        onQuickScan = onQuickScan.takeIf { flags.isEnabled(FeatureFlag.QUICK_SCAN) },
                        quickScanBeta = flags.showsBeta(FeatureFlag.QUICK_SCAN),
                        scanBeta = flags.showsBeta(FeatureFlag.CAMERA_SCAN),
                        onLearn = {
                            solveFlow.closeEditor()
                            tab = Tab.LEARN
                        },
                        onSolved = solveOnSolved,
                        // Edge-to-edge: the themed surface fills the bar area while controls sit inside the safe area.
                        modifier = destinationModifier.background(RubikTheme.tokens.surfaceBase).safeDrawingPadding(),
                    )
                } else {
                    // The solve flow needs content copy; until it loads (or if it fails) the free cube stays usable.
                    FreeCubeScreen(
                        viewModel = freeCube,
                        showDebugToggles = showDebugToggles,
                        modifier = destinationModifier,
                        strings =
                            dev.cubentia.app.content
                                .ContentStrings(emptyMap()),
                    )
                }
            }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().testTag("shell")) {
        val widthClass = WindowWidthClass.of(maxWidth.value)
        if (widthClass == WindowWidthClass.COMPACT) {
            Scaffold(
                containerColor = RubikTheme.tokens.surfaceBase,
                bottomBar = {
                    NavigationBar(containerColor = RubikTheme.tokens.surfaceRaised, modifier = Modifier.testTag("nav:bar")) {
                        Tab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = { tab = item },
                                icon = { Icon(painterResource(item.icon), contentDescription = null) },
                                label = { Text(stringResource(item.label)) },
                                modifier = Modifier.testTag("nav:${item.name.lowercase()}"),
                            )
                        }
                    }
                },
            ) { padding ->
                Box(
                    Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                ) { destination(Modifier.fillMaxSize(), widthClass) }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(containerColor = RubikTheme.tokens.surfaceRaised, modifier = Modifier.fillMaxHeight().testTag("nav:rail")) {
                    Tab.entries.forEach { item ->
                        NavigationRailItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Icon(painterResource(item.icon), contentDescription = null) },
                            label = { Text(stringResource(item.label)) },
                            modifier = Modifier.testTag("nav:${item.name.lowercase()}"),
                        )
                    }
                }
                Surface(
                    Modifier.weight(1f).fillMaxHeight(),
                    color = RubikTheme.tokens.surfaceBase,
                    contentColor = RubikTheme.tokens.inkPrimary,
                ) {
                    destination(Modifier.fillMaxSize(), widthClass)
                }
            }
        }
    }
}

/** The Teaching Modes a Lesson offers under [flags]: every mode, less *Test me* while `TEST_ME` is off (AD-18). */
internal fun availableModes(flags: FeatureFlags): List<TeachingMode> =
    TeachingMode.entries.filter { it != TeachingMode.TEST_ME || flags.isEnabled(FeatureFlag.TEST_ME) }

/**
 * This generated practice or skip-check Lesson at the [saved] scramble when there is one, so recreation rebuilds the
 * same attempt; otherwise this Lesson as dealt, its scramble handed to [save] (Story 12.2 AC3).
 */
private fun Lesson.keepingScramble(
    saved: String?,
    save: (String) -> Unit,
): Lesson = if (saved != null) copy(entryState = saved) else also { save(it.entryState) }

/** A fresh LL practice scramble over every bundled Algorithm (Story 9.8). */
private fun newTwoLookPractice(ready: CatalogState.Ready): Lesson =
    TwoLookPractice(KotlinRandomSource()).lesson(
        ready.bundle.algorithms.map {
            (
                Notation
                    .parse(it.notation) as NotationResult.Parsed
            ).moves
        },
    )

/** The *Continue* entry for an unfinished LL practice, live or restored on cold start (AD-10). */
private fun twoLookPracticeContinue(
    session: SessionState,
    ready: CatalogState.Ready?,
): CatalogEntry? {
    val lesson = session.lesson?.takeIf { it.id == TwoLookMastery.PRACTICE_ID } ?: return null
    if (session.kind != SessionKind.PRACTICE || session.objectiveMet || ready == null) return null
    return CatalogEntry(
        lesson,
        Stage(
            lesson.stageId,
            dev.cubentia.domain.content
                .TextKey("stage.two-look-practice.title"),
            listOf(lesson),
        ),
        ready.bundle.tutorConfig,
    )
}

internal fun algorithmsToOffer(
    ids: List<AlgorithmId>,
    load: AlgorithmStatusLoad?,
): List<AlgorithmId> {
    val statuses = (load as? AlgorithmStatusLoad.Loaded)?.statuses ?: return emptyList()
    return ids.filter { (statuses[it] ?: AlgorithmStatus.NOT_STARTED) == AlgorithmStatus.NOT_STARTED }
}

internal fun recognitionDiagramFor(
    bundle: ContentBundle,
    lessonId: LessonId,
): RecognitionDiagram? =
    bundle.lessonAlgorithms[lessonId]?.firstOrNull()?.let { id ->
        bundle.algorithms.firstOrNull { it.id == id }?.let { algorithm ->
            (RecognitionDiagram.derive(algorithm) as? RecognitionDiagramResult.Derived)?.diagram
        }
    }
