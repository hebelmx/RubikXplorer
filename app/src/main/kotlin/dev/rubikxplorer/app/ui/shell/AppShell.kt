package dev.rubikxplorer.app.ui.shell

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.rubikxplorer.app.R
import dev.rubikxplorer.app.content.CatalogEntry
import dev.rubikxplorer.app.content.CatalogState
import dev.rubikxplorer.app.content.LessonCatalog
import dev.rubikxplorer.app.preferences.PreferenceProvider
import dev.rubikxplorer.app.preferences.PreferenceStore
import dev.rubikxplorer.app.random.KotlinRandomSource
import dev.rubikxplorer.app.session.SessionHost
import dev.rubikxplorer.app.ui.freecube.FreeCubeScreen
import dev.rubikxplorer.app.ui.freecube.FreeCubeViewModel
import dev.rubikxplorer.app.ui.lesson.LessonScreen
import dev.rubikxplorer.app.ui.settings.SettingsScreen
import dev.rubikxplorer.app.ui.theme.RubikTheme
import dev.rubikxplorer.app.ui.welcome.WelcomeScreen
import dev.rubikxplorer.application.port.FeatureFlag
import dev.rubikxplorer.application.port.FeatureFlags
import dev.rubikxplorer.application.port.FlagLevel
import dev.rubikxplorer.application.session.SessionKind
import dev.rubikxplorer.application.usecase.StagePractice
import dev.rubikxplorer.domain.content.LessonId
import dev.rubikxplorer.domain.content.StageId
import dev.rubikxplorer.domain.tutor.StageProgress
import dev.rubikxplorer.domain.tutor.StageStatus
import dev.rubikxplorer.domain.tutor.TeachingMode
import kotlinx.coroutines.launch

/** The top-level destinations of the navigation shell. */
enum class Tab(
    @param:DrawableRes val icon: Int,
    @param:StringRes val label: Int,
) {
    LEARN(R.drawable.ic_learn, R.string.nav_learn),
    SOLVE(R.drawable.ic_solve, R.string.nav_solve),
    YOU(R.drawable.ic_you, R.string.nav_you),
}

/**
 * The navigation shell (UX-DR7): Learn and Solve (the free cube) behind a `NavigationBar` when
 * compact and a `NavigationRail` from medium width. An open Lesson replaces the shell as a
 * full-screen task flow, with no bar. The tab and the open Lesson survive configuration changes; the
 * Lesson's progress lives in [host]'s session. [flags] decides which Teaching Modes the Lesson offers:
 * *Test me* only while `TEST_ME` is enabled; `ADAPTIVE_ASSISTANCE` decides whether help adapts in the Lesson,
 * and at BETA the Assistance line carries the beta label (AD-18).
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
) {
    PreferenceProvider(preferenceStore) {
        ShellContent(host, catalog, freeCube, showDebugToggles, flags, modifier, stagePractice, preferenceStore, showWelcome)
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
) {
    LaunchedEffect(catalog) { catalog.load() }
    val content by catalog.state.collectAsState()
    val session by host.state.collectAsState()
    val progressEvents by host.progressEvents.collectAsState()
    val attemptRecords by host.attemptRecords.collectAsState()
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(Tab.LEARN) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var welcome by rememberSaveable { mutableStateOf(showWelcome) }
    var welcomeAbandoned by rememberSaveable { mutableStateOf(false) }
    var stageAbandoned by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedStage by rememberSaveable { mutableStateOf<String?>(null) }
    var openLesson by rememberSaveable { mutableStateOf<String?>(null) }
    var openPractice by rememberSaveable { mutableStateOf<String?>(null) }
    var practiceEpoch by rememberSaveable { mutableIntStateOf(0) }
    var openSkipCheck by rememberSaveable { mutableStateOf<String?>(null) }
    var skipCheckEpoch by rememberSaveable { mutableIntStateOf(0) }
    // Held above the Lesson and Settings task flows so closing either returns to the same place in the list.
    val learnScroll = rememberScrollState()
    if (settings) {
        SettingsScreen(
            onClose = { settings = false },
            onResetProgress = {
                selectedStage = null
                stageAbandoned = null
                welcomeAbandoned = false
                welcome = true
                scope.launch { host.resetProgress() }
            },
        )
        return
    }

    LaunchedEffect(content, host) { (content as? CatalogState.Ready)?.let { host.restore(it.bundle) } }
    val ready = content as? CatalogState.Ready
    if (welcome && openSkipCheck == null) {
        val readyBundle = ready?.bundle
        WelcomeScreen(
            onImNew = {
                welcome = false
                selectedStage = readyBundle
                    ?.stages
                    ?.firstOrNull()
                    ?.id
                    ?.value ?: "stage-01-fundamentals"
                tab = Tab.LEARN
            },
            onCanAlreadySolve = {
                openSkipCheck = "course"
                skipCheckEpoch++
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
        LessonScreen(
            host = host,
            entry = entry,
            strings = ready.strings,
            modes = availableModes(flags),
            onClose = { openLesson = null },
            modifier = modifier,
            adaptiveAssistance = flags.level(FeatureFlag.ADAPTIVE_ASSISTANCE),
            onPracticeNewScramble = {
                openLesson = null
                openPractice =
                    if (entry.stage.id != StagePractice.STAGE_1_ID) entry.stage.id.value else "stage-07-independent-practice"
                practiceEpoch++
            },
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

    val practiceEntry =
        remember(openPractice, practiceEpoch, ready) {
            val stageId = openPractice?.let(::StageId)
            val stage = stageId?.takeIf { it != StagePractice.STAGE_1_ID }?.let { id -> ready?.bundle?.stages?.firstOrNull { it.id == id } }
            if (stageId != null && stage != null && ready != null) {
                val lesson = stagePractice(stageId)
                CatalogEntry(lesson, stage, ready.bundle.tutorConfig)
            } else {
                null
            }
        }
    if (ready != null && practiceEntry != null) {
        LessonScreen(
            host = host,
            entry = practiceEntry,
            strings = ready.strings,
            modes = emptyList(),
            onClose = { openPractice = null },
            modifier = modifier,
            adaptiveAssistance = FlagLevel.OFF,
            onPracticeNewScramble = {
                openPractice = practiceEntry.stage.id.value
                practiceEpoch++
            },
            stages = ready.bundle.stages,
            onNextStage = { nextStageId ->
                openLesson = null
                openPractice = null
                selectedStage = nextStageId.value
                tab = Tab.LEARN
            },
            isPractice = true,
        )
        return
    }

    val skipCheckEntry =
        remember(openSkipCheck, skipCheckEpoch, ready) {
            val key = openSkipCheck
            if (key == null || ready == null) {
                null
            } else if (key == "course") {
                val lesson = stagePractice.courseSkipCheckLesson()
                val stage = ready.bundle.stages.lastOrNull() ?: return@remember null
                CatalogEntry(lesson, stage, ready.bundle.tutorConfig)
            } else {
                val stageId = StageId(key)
                val stage = ready.bundle.stages.firstOrNull { it.id == stageId }
                if (stage != null) {
                    val lesson = stagePractice.skipCheckLesson(stageId)
                    CatalogEntry(lesson, stage, ready.bundle.tutorConfig)
                } else {
                    null
                }
            }
        }
    if (ready != null && skipCheckEntry != null) {
        LessonScreen(
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
                if (!wasSatisfied) {
                    if (isCourse) {
                        welcomeAbandoned = true
                    } else if (stageId != null) {
                        stageAbandoned = stageId
                    }
                }
            },
            modifier = modifier,
            adaptiveAssistance = FlagLevel.OFF,
            stages = ready.bundle.stages,
            isSkipCheck = true,
            skipCheckInstruction = stringResource(R.string.skip_check_instruction),
            onSkipCheckPassed = {
                val isCourse = openSkipCheck == "course"
                val currentStageId = openSkipCheck?.let(::StageId)
                openSkipCheck = null
                if (isCourse) {
                    welcome = false
                    welcomeAbandoned = false
                    tab = Tab.LEARN
                    selectedStage = null
                    scope.launch {
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
                        host.passSkipCheck(listOf(currentStageId), ready.bundle.stages)
                    }
                }
            },
        )
        return
    }
    // Back from another tab returns to Learn first; back from Stage detail returns to the Stage list before leaving the app.
    BackHandler(enabled = tab != Tab.LEARN || selectedStage != null || openPractice != null || openSkipCheck != null) {
        if (openSkipCheck != null) {
            val isCourse = openSkipCheck == "course"
            val stageId = openSkipCheck
            openSkipCheck = null
            if (isCourse) {
                welcomeAbandoned = true
            } else if (stageId != null) {
                stageAbandoned = stageId
            }
        } else if (openPractice != null) {
            openPractice = null
        } else if (tab != Tab.LEARN) {
            tab = Tab.LEARN
        } else {
            selectedStage = null
        }
    }

    val destination: @Composable (Modifier) -> Unit = { destinationModifier ->
        when (tab) {
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
                val allStagesFinished =
                    ready != null && ready.bundle.stages.isNotEmpty() &&
                        ready.bundle.stages.all { stage ->
                            val s = StageProgress.status(stage, ready.bundle.stages, progressEvents)
                            s == StageStatus.SKIPPED || s == StageStatus.SKIPPED_BY_PLACEMENT || s == StageStatus.COMPLETED ||
                                s == StageStatus.MASTERED
                        }
                LearnScreen(
                    catalog = content,
                    continueEntry =
                        if (session.kind == SessionKind.LESSON) {
                            session.lesson?.let { ready?.find(it.id) }
                        } else {
                            null
                        },
                    progressEvents = progressEvents,
                    attemptRecords = attemptRecords,
                    selectedStage = selectedStage?.let(::StageId),
                    onSelectStage = {
                        selectedStage = it?.value
                        stageAbandoned = null
                    },
                    onOpen = { openLesson = it.value },
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
                    showFasterLastLayer = allStagesFinished,
                    scrollState = learnScroll,
                    // The inset area takes surfaceBase too, not the window background.
                    modifier = destinationModifier.background(RubikTheme.tokens.surfaceBase).safeDrawingPadding(),
                )
            }

            Tab.SOLVE -> {
                FreeCubeScreen(viewModel = freeCube, showDebugToggles = showDebugToggles, modifier = destinationModifier)
            }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().testTag("shell")) {
        if (WindowWidthClass.of(maxWidth.value) == WindowWidthClass.COMPACT) {
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
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { destination(Modifier.fillMaxSize()) }
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
                Box(Modifier.weight(1f).fillMaxHeight()) { destination(Modifier.fillMaxSize()) }
            }
        }
    }
}

/** The Teaching Modes a Lesson offers under [flags]: every mode, less *Test me* while `TEST_ME` is off (AD-18). */
internal fun availableModes(flags: FeatureFlags): List<TeachingMode> =
    TeachingMode.entries.filter { it != TeachingMode.TEST_ME || flags.isEnabled(FeatureFlag.TEST_ME) }
