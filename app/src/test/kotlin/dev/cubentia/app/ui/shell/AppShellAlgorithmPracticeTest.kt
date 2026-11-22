package dev.cubentia.app.ui.shell

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.lifecycle.viewModelScope
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.content.LessonCatalog
import dev.cubentia.app.preferences.PreferenceStore
import dev.cubentia.app.scan.QuickCheckCapture
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.session.TickingClock
import dev.cubentia.app.session.testFlags
import dev.cubentia.app.solver.MethodSolutionCache
import dev.cubentia.app.solver.SolverLoad
import dev.cubentia.app.solver.SolverSource
import dev.cubentia.app.ui.freecube.FreeCubeViewModel
import dev.cubentia.app.ui.recognize.RecognizeFlowViewModel
import dev.cubentia.app.ui.solve.SolutionStyle
import dev.cubentia.app.ui.solve.SolutionStyleViewModel
import dev.cubentia.app.ui.solve.SolveFlowViewModel
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.application.port.CubeSolver
import dev.cubentia.application.port.FeatureFlag
import dev.cubentia.application.port.FeatureFlags
import dev.cubentia.application.port.FlagLevel
import dev.cubentia.application.port.SolveResult
import dev.cubentia.application.session.Effect
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.application.session.SessionKind
import dev.cubentia.application.usecase.SolveByMethod
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.playback.AlgorithmPractice
import dev.cubentia.domain.playback.AlgorithmPracticeResult
import dev.cubentia.domain.playback.MoveNote
import dev.cubentia.domain.recognition.PhysicalObservation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Story 14.2: practicing an Algorithm on the real cube from its Library detail (FR-18, UX Flow 11). */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class AppShellAlgorithmPracticeTest {
    @Before fun mainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @get:Rule val rule = createComposeRule()
    private val loaded = ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded
    private val bundle = loaded.bundle
    private val strings = ContentStrings(bundle.strings)
    private val algorithm = bundle.algorithms.single { it.id.value == "alg-oll-45-a" }
    private val case = bundle.cases.single { it.id == algorithm.caseId }
    private val setUp = (AlgorithmPractice.of(algorithm, fromSetUp = true) as AlgorithmPracticeResult.Ready).practice
    private val alreadyCase = (AlgorithmPractice.of(algorithm, fromSetUp = false) as AlgorithmPracticeResult.Ready).practice
    private val host = SessionHost(TickingClock(), Dispatchers.Default.limitedParallelism(1))
    private var solver: CubeSolver = CubeSolver { SolveResult.Solved(emptyList()) }
    private val solveFlow =
        SolveFlowViewModel(
            object : SolverSource {
                override val isReady = true

                override suspend fun load(onGenerating: () -> Unit): SolverLoad = SolverLoad.Ready(solver)
            },
        )
    private val styles = SolutionStyleViewModel({ SolveByMethod(it) }, MethodSolutionCache())
    private val recognizeFlow = RecognizeFlowViewModel()
    private val started = mutableListOf<Effect.QuickCheck>()
    private lateinit var back: OnBackPressedDispatcher

    @After fun close() {
        try {
            runBlocking {
                solveFlow.viewModelScope.coroutineContext.job
                    .cancelAndJoin()
                styles.viewModelScope.coroutineContext.job
                    .cancelAndJoin()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun launch(
        cameraScan: FlagLevel = FlagLevel.OFF,
        recognize: Boolean = false,
    ) {
        val preferences = PreferenceStore()
        val flags =
            if (recognize) {
                object : FeatureFlags {
                    override fun level(flag: FeatureFlag) = if (flag == FeatureFlag.CAMERA_SCAN) cameraScan else FlagLevel.GA
                }
            } else {
                testFlags(cameraScan = cameraScan)
            }
        rule.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CubentiaTheme {
                AppShell(
                    host,
                    LessonCatalog.of(loaded),
                    FreeCubeViewModel(),
                    false,
                    flags,
                    preferenceStore = preferences,
                    // A capture is wired: only CAMERA_SCAN decides whether Quick Check shows.
                    quickCheck = QuickCheckCapture { started += it },
                    solveFlow = solveFlow,
                    solutionStyles = styles,
                    onRecognize = if (recognize) ({ _ -> }) else null,
                    recognizeFlow = recognizeFlow,
                )
            }
        }
        click("nav:library")
    }

    private fun click(tag: String) {
        // The shell reacts to host state set off the main thread: wait until the target is composed before clicking.
        rule.waitUntil(10000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    private fun openDetail() {
        rule.onNodeWithTag("library:grid").performScrollToKey(case.id.value)
        click("library:case:${case.id.value}")
        rule.onNodeWithTag("algorithmDetail:title").assertExists()
    }

    private fun practice(fromSetUp: Boolean) {
        rule.onNodeWithTag("algorithmDetail:practice").performScrollTo().performClick()
        rule.waitForIdle()
        click(if (fromSetUp) "algorithmDetail:practice:setUp" else "algorithmDetail:practice:alreadyCase")
        awaitPhysicalAt(0)
    }

    private fun awaitPhysicalAt(position: Int) {
        rule.waitUntil(10000) {
            val state = host.state.value
            state.kind == SessionKind.SOLVE_PLAYBACK && state.isPhysical && state.history.done.size == position
        }
        rule.waitForIdle()
    }

    private fun done(times: Int = 1) {
        repeat(times) {
            val before = host.state.value.revision
            click("playback:done")
            rule.waitUntil(10000) { host.state.value.revision > before }
            rule.waitForIdle()
        }
    }

    private fun counter(
        position: Int,
        size: Int,
    ) = rule.onNodeWithTag("playback:counter").assertTextEquals("$position / $size")

    @Test fun `set-up practice plays the inverse then the Algorithm in physical mode and Again restarts from the set-up`() {
        launch()
        openDetail()
        practice(fromSetUp = true)
        val session = host.state.value
        assertEquals(setUp.start, session.entry)
        assertEquals(setUp.moves, session.solution)
        assertEquals(2 * setUp.algorithmStart, setUp.moves.size)
        rule.onNodeWithTag("solvePlayback:title").assertTextEquals(strings.resolve(case.name))
        rule.onNodeWithTag("solvePlayback:length").assertTextEquals("${setUp.moves.size} moves")
        rule.onNodeWithTag("solutionStyle:chips").assertDoesNotExist()
        rule.onNodeWithTag("net").assertExists()
        rule.onNodeWithTag("playback:scrubber").assertDoesNotExist()
        counter(0, setUp.moves.size)
        rule.onNodeWithTag("playback:segment").assertTextEquals("Set-up")
        rule.onNodeWithTag("playback:note").assertTextEquals(strings.resolve(MoveNote.of(setUp.moves[0])))
        // CAMERA_SCAN OFF: no camera control, Done-only practice.
        rule.onNodeWithTag("playback:quickCheck").assertDoesNotExist()
        rule.onNodeWithTag("practice:again").assertDoesNotExist()

        done(setUp.algorithmStart)
        counter(setUp.algorithmStart, setUp.moves.size)
        rule.onNodeWithTag("playback:segment").assertTextEquals("Algorithm")
        rule.onNodeWithTag("playback:note").assertTextEquals(strings.resolve(MoveNote.of(setUp.moves[setUp.algorithmStart])))
        done(setUp.moves.size - setUp.algorithmStart)
        counter(setUp.moves.size, setUp.moves.size)
        rule.onNodeWithTag("playback:solvedNow").assertExists()
        rule.onNodeWithTag("solve:learnOffer").assertExists()
        rule.onNodeWithTag("practice:again").assertTextEquals("Again")

        click("practice:again")
        awaitPhysicalAt(0)
        rule.waitUntil(10000) { rule.onAllNodesWithTag("practice:again").fetchSemanticsNodes().isEmpty() }
        assertEquals(setUp.start, host.state.value.expected)
        assertEquals(setUp.moves, host.state.value.solution)
        counter(0, setUp.moves.size)
        rule.onNodeWithTag("playback:segment").assertTextEquals("Set-up")

        done(setUp.moves.size)
        click("solve:learnOffer")
        rule.waitUntil(10000) { host.state.value.kind != SessionKind.SOLVE_PLAYBACK }
        rule.waitForIdle()
        rule.onNodeWithTag("nav:learn").assertIsSelected()
        rule.onNodeWithTag("solvePlayback:screen").assertDoesNotExist()
    }

    @Test fun `already-case practice starts at the Algorithm with no Recognize step and closing returns to the detail`() {
        launch()
        openDetail()
        practice(fromSetUp = false)
        assertEquals(alreadyCase.start, host.state.value.entry)
        assertEquals(alreadyCase.moves, host.state.value.solution)
        rule.onNodeWithTag("recognize:match").assertDoesNotExist()
        counter(0, alreadyCase.moves.size)
        rule.onNodeWithTag("playback:segment").assertTextEquals("Algorithm")
        done(2)
        counter(2, alreadyCase.moves.size)
        click("solvePlayback:close")
        rule.waitUntil(10000) { !host.state.value.isActive }
        rule.waitForIdle()
        rule.onNodeWithTag("algorithmDetail:title").assertTextEquals(strings.resolve(case.name))
        rule.onNodeWithTag("nav:library").assertIsSelected()
        // A set-up practice started again from the detail also returns to it on system back.
        practice(fromSetUp = true)
        rule.runOnIdle { back.onBackPressed() }
        rule.waitUntil(10000) { !host.state.value.isActive }
        rule.waitForIdle()
        rule.onNodeWithTag("algorithmDetail:title").assertExists()
        rule.onNodeWithTag("nav:library").assertIsSelected()
    }

    @Test fun `Again after an already-case run replays from the set-up`() {
        launch()
        openDetail()
        practice(fromSetUp = false)
        done(alreadyCase.moves.size)
        click("practice:again")
        rule.waitUntil(10000) { host.state.value.solution == setUp.moves && host.state.value.isPhysical }
        rule.waitForIdle()
        assertEquals(setUp.start, host.state.value.entry)
        counter(0, setUp.moves.size)
        rule.onNodeWithTag("playback:segment").assertTextEquals("Set-up")
    }

    @Test fun `with CAMERA_SCAN on a Quick Check is available mid-Algorithm`() {
        launch(cameraScan = FlagLevel.GA)
        openDetail()
        practice(fromSetUp = true)
        rule.onNodeWithTag("playback:quickCheck").assertContentDescriptionEquals("Quick Check")
        done(setUp.algorithmStart + 2)
        click("playback:quickCheck")
        rule.waitUntil(10000) { started.isNotEmpty() }
        assertEquals(started.single(), host.state.value.pendingQuickCheck)
    }

    @Test fun `use my cube after a Full mismatch re-solves into a plain Solution Playback`() {
        launch(cameraScan = FlagLevel.GA)
        openDetail()
        practice(fromSetUp = true)
        done(2)
        val actual = CubeState.SOLVED.apply(FaceTurn(Face.R, Turn.CLOCKWISE))
        solver = CubeSolver { SolveResult.Solved(listOf(FaceTurn(Face.R, Turn.COUNTERCLOCKWISE))) }
        observeFull(actual)
        click("playback:useMyCube")
        rule.waitUntil(10000) { host.state.value.entry == actual && host.state.value.isPhysical }
        rule.waitForIdle()
        rule.onNodeWithTag("solvePlayback:title").assertDoesNotExist()
        rule.onNodeWithTag("solvePlayback:length").assertTextEquals("1 move")
        rule.onNodeWithTag("solutionStyle:${SolutionStyle.SHORTEST.name}").assertIsSelected()
        rule.onNodeWithTag("playback:segment").assertDoesNotExist()
        click("solvePlayback:close")
        rule.waitUntil(10000) { host.state.value.kind != SessionKind.SOLVE_PLAYBACK }
        rule.waitForIdle()
        rule.onNodeWithTag("nav:solve").assertIsSelected()
    }

    @Test fun `use my cube on a solved cube ends practice on the Solve tab's already-solved message`() {
        launch(cameraScan = FlagLevel.GA)
        openDetail()
        practice(fromSetUp = true)
        done(2)
        observeFull(CubeState.SOLVED)
        click("playback:useMyCube")
        rule.waitUntil(10000) { host.state.value.kind != SessionKind.SOLVE_PLAYBACK }
        rule.waitForIdle()
        rule.onNodeWithTag("nav:solve").assertIsSelected()
        rule.onNodeWithTag("solve:alreadySolved").assertExists()
    }

    @Test fun `closing a practice opened from Recognize plays its Demonstration again`() {
        launch(cameraScan = FlagLevel.GA, recognize = true)
        val state = alreadyCase.start
        rule.runOnIdle {
            recognizeFlow.scanDenied()
            recognizeFlow.editorAccepted(state)
        }
        click("recognize:match")
        rule.onNodeWithTag("algorithmDetail:title").assertExists()
        practice(fromSetUp = false)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("solvePlayback:close").performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.waitUntil(10000) {
            rule.mainClock.advanceTimeByFrame()
            rule.onAllNodesWithTag("algorithmDetail:title").fetchSemanticsNodes().size == 1
        }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("playback:playPause").assertContentDescriptionEquals("Pause")
        rule.mainClock.autoAdvance = true
        assertFalse(host.state.value.isActive)
    }

    @Test fun `closing a practice opened from the grid returns to a paused detail`() {
        launch()
        openDetail()
        practice(fromSetUp = true)
        click("solvePlayback:close")
        rule.waitUntil(10000) { !host.state.value.isActive }
        rule.waitForIdle()
        rule.onNodeWithTag("playback:playPause").assertContentDescriptionEquals("Play")
        assertNotEquals(SessionKind.SOLVE_PLAYBACK, host.state.value.kind)
        assertTrue(rule.onAllNodesWithTag("solvePlayback:screen").fetchSemanticsNodes().isEmpty())
    }

    private fun observeFull(cube: CubeState) {
        click("playback:quickCheck")
        rule.waitUntil(10000) { started.isNotEmpty() }
        val effect = started.last()
        runBlocking {
            host.dispatch { SessionEvent.QuickCheckObserved(effect.effectId, effect.revision, PhysicalObservation.Full(cube), it) }
        }
        rule.waitUntil(10000) { host.state.value.canResolveObserved }
        rule.waitForIdle()
    }
}
