package dev.cubentia.app.ui.shell

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.cubentia.app.content.LessonCatalog
import dev.cubentia.app.preferences.PreferenceStore
import dev.cubentia.app.scan.CaseCaptureOutcome
import dev.cubentia.app.scan.ScanOutcome
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.session.TickingClock
import dev.cubentia.app.ui.freecube.FreeCubeViewModel
import dev.cubentia.app.ui.placement.PlacementFlowViewModel
import dev.cubentia.app.ui.placement.PlacementPhase
import dev.cubentia.app.ui.recognize.RecognizeFlowViewModel
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.application.port.FeatureFlag
import dev.cubentia.application.port.FeatureFlags
import dev.cubentia.application.port.FlagLevel
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.lastlayer.CaseStateResult
import dev.cubentia.domain.lastlayer.LastLayerMatcher
import dev.cubentia.domain.recognition.CornerView
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class AppShellRecognizeTest {
    @get:Rule val rule = createComposeRule()

    private val content = ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded
    private val flow = RecognizeFlowViewModel()
    private val placementFlow = PlacementFlowViewModel()
    private val requests = mutableListOf<Int>()
    private var placementRequests = 0
    private lateinit var back: OnBackPressedDispatcher

    @Test
    fun recognitionIsHiddenWhenOff() {
        launch(FlagLevel.OFF)
        rule.onNodeWithTag("library:recognize").assertDoesNotExist()
    }

    @Test
    fun betaUsesSharedNoteAndAccessibleLabelAndRequestsTwoHolds() {
        launch(FlagLevel.BETA)
        rule.onNodeWithTag("library:recognize:beta", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("library:recognize:betaNote").assertExists()
        rule.onNodeWithTag("library:recognize").assertContentDescriptionEquals("Recognize case, BETA").performClick()
        assertEquals(listOf(2), requests)
    }

    @Test
    fun uniqueCardStartsPlaybackAndBackKeepsTheResult() {
        launch()
        val algorithm = content.bundle.algorithms.single { it.id.value == "alg-oll-45-a" }
        val state = (LastLayerMatcher.caseState(algorithm, Color.YELLOW) as CaseStateResult.Derived).state
        rule.runOnIdle {
            flow.scanDenied()
            flow.editorAccepted(state)
        }
        rule.onNodeWithText("OLL 45 · T-shape").assertIsDisplayed()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("recognize:match").performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("playback:playPause").assertContentDescriptionEquals("Pause")
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("algorithmDetail:status:LEARNING").performScrollTo().assertIsNotSelected()
        rule.runOnIdle { back.onBackPressed() }
        rule.onNodeWithTag("recognize:match").assertIsDisplayed()
    }

    @Test
    fun ambiguousAndNoFitOfferDifferentRescansAndCancellationKeepsTheResult() {
        launch()
        val first = corner(Color.GREEN, Color.ORANGE)
        val second = corner(Color.BLUE, Color.RED)
        rule.runOnIdle { flow.onCapture(CaseCaptureOutcome.Captured(listOf(first, second))) }
        val count = content.bundle.cases.size
        rule.onNodeWithText("This could be one of $count cases").assertIsDisplayed()
        rule.onNodeWithText("OLL 1").performClick()
        rule.onNodeWithTag("algorithmDetail:title").assertTextContains("OLL 1")
        rule.onNodeWithTag("playback:playPause").assertContentDescriptionEquals("Play")
        rule.runOnIdle { back.onBackPressed() }
        rule.onNodeWithText("This could be one of $count cases").assertIsDisplayed()
        rule.onNodeWithText("Scan another angle").performClick()
        assertEquals(listOf(1), requests)
        rule.runOnIdle { flow.onCapture(CaseCaptureOutcome.Cancelled) }
        rule.onNodeWithText("This could be one of $count cases").assertIsDisplayed()

        rule.runOnIdle { flow.onCapture(CaseCaptureOutcome.Captured(listOf(first, first))) }
        rule.onNodeWithText("I couldn't match this to a case. Try another angle or better light.").assertIsDisplayed()
        rule.onNodeWithText("Scan another angle").performClick()
        assertEquals(listOf(1, 2), requests)
        rule.runOnIdle { flow.onCapture(CaseCaptureOutcome.Cancelled) }
        rule.onNodeWithText("Browse the Library").performClick()
        rule.onNodeWithTag("library:recognize").assertIsDisplayed()
    }

    @Test
    fun solvedAndUnsolvedFirstTwoLayersShowTheirMessagesWithPlacementOff() {
        launch()
        rule.runOnIdle { flow.editorAccepted(CubeState.SOLVED) }
        rule.onNodeWithText("Your last layer is already solved.").assertIsDisplayed()
        rule.onNodeWithText("Back to Library").performClick()
        rule.onNodeWithTag("library:recognize").assertIsDisplayed()
        showUnsolved()
        rule.onNodeWithText("Start from my cube").assertDoesNotExist()
    }

    @Test
    fun unsolvedFirstTwoLayersCanStartPlacementFromLibrary() {
        launch(placement = FlagLevel.GA)
        showUnsolved()
        rule.onNodeWithText("BETA", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText(content.bundle.strings.getValue("msg.feature.camera.beta-note")).assertDoesNotExist()
        rule.onNodeWithTag("recognize:startFromCube").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        rule.onNodeWithText("Start from my cube").performClick()
        assertEquals(1, placementRequests)
    }

    @Test
    fun aLateScanOutcomeAfterLeavingTheRecognizeResultDoesNotShowPlacement() {
        launch(placement = FlagLevel.GA)
        showUnsolved()
        rule.onNodeWithText("Start from my cube").performClick()
        rule.runOnIdle { back.onBackPressed() }
        rule.onNodeWithTag("library:recognize").assertIsDisplayed()
        val moves = (Notation.parse("F") as NotationResult.Parsed).moves
        rule.runOnIdle { placementFlow.onScanOutcome(ScanOutcome.Accepted(CubeState.SOLVED.apply(moves))) }
        rule.waitForIdle()
        rule.onNodeWithTag("placement:prompt").assertDoesNotExist()
        rule.onNodeWithTag("placement:screen").assertDoesNotExist()
        assertEquals(PlacementPhase.Idle, placementFlow.phase.value)
    }

    @Test
    fun placementBetaShowsSharedNoteAndAccessibleLabel() {
        launch(placement = FlagLevel.BETA)
        showUnsolved()
        rule.onNodeWithText("BETA", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(content.bundle.strings.getValue("msg.feature.camera.beta-note")).assertIsDisplayed()
        rule.onNodeWithTag("recognize:startFromCube").assertContentDescriptionEquals("Start from my cube, BETA")
    }

    @Test
    fun recognizeGaShowsTheBetaLabelWhileCameraScanIsBeta() {
        launch(FlagLevel.GA, cameraScan = FlagLevel.BETA)
        rule.onNodeWithTag("library:recognize:beta", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("library:recognize").assertContentDescriptionEquals("Recognize case, BETA")
    }

    @Test
    fun recognizeGaWithCameraScanGaShowsNoBetaLabel() {
        launch(FlagLevel.GA)
        rule.onNodeWithTag("library:recognize").assertExists()
        rule.onNodeWithTag("library:recognize:beta", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun placementGaShowsTheBetaLabelWhileCameraScanIsBeta() {
        launch(placement = FlagLevel.GA, cameraScan = FlagLevel.BETA)
        showUnsolved()
        rule.onNodeWithTag("recognize:startFromCube").assertContentDescriptionEquals("Start from my cube, BETA")
        rule.onNodeWithText(content.bundle.strings.getValue("msg.feature.camera.beta-note")).assertIsDisplayed()
    }

    @Test
    fun placementStartedFromRecognitionShowsItsSuggestionAfterOnboarding() {
        launch(placement = FlagLevel.GA)
        showUnsolved()
        rule.onNodeWithText("Start from my cube").performClick()
        val moves = (Notation.parse("F") as NotationResult.Parsed).moves
        rule.runOnIdle { placementFlow.onScanOutcome(ScanOutcome.Accepted(CubeState.SOLVED.apply(moves))) }
        rule.onNodeWithTag("placement:prompt").assertIsDisplayed()
        rule.onNodeWithTag("placement:start").performClick()
        rule.onNodeWithTag("placement:prompt").assertDoesNotExist()
    }

    @Test
    fun enterColorsInsteadOpensTheEditorAndItsAcceptedCubeIsRecognized() {
        launch()
        rule.runOnIdle { flow.onCapture(CaseCaptureOutcome.FellBackToEditor) }
        rule.onNodeWithText("No scan this time. Enter your cube's colors here.").assertIsDisplayed()
        rule.onNodeWithTag("editor:accept").performScrollTo().performClick()
        rule.onNodeWithText("Your last layer is already solved.").assertIsDisplayed()
    }

    private fun launch(
        level: FlagLevel = FlagLevel.GA,
        placement: FlagLevel = FlagLevel.OFF,
        cameraScan: FlagLevel = FlagLevel.GA,
    ) {
        val preferences = PreferenceStore()
        val host = SessionHost(TickingClock(), Dispatchers.Default.limitedParallelism(1), preferences = preferences)
        val catalog = LessonCatalog.of(content)
        val freeCube = FreeCubeViewModel()
        val flags =
            object : FeatureFlags {
                override fun level(flag: FeatureFlag) =
                    when (flag) {
                        FeatureFlag.CASE_RECOGNITION -> level
                        FeatureFlag.PLACEMENT -> placement
                        FeatureFlag.CAMERA_SCAN -> cameraScan
                        else -> FlagLevel.GA
                    }
            }
        rule.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CubentiaTheme {
                AppShell(
                    host,
                    catalog,
                    freeCube,
                    false,
                    flags,
                    preferenceStore = preferences,
                    onRecognize = { requests += it },
                    recognizeFlow = flow,
                    onPlacementScan = { placementRequests++ },
                    placementFlow = placementFlow,
                )
            }
        }
        rule.onNodeWithTag("nav:library").performClick()
    }

    private fun showUnsolved() {
        val moves = (Notation.parse("F") as NotationResult.Parsed).moves
        rule.runOnIdle { flow.editorAccepted(CubeState.SOLVED.apply(moves)) }
        rule.onNodeWithText("The first two layers aren't solved yet.").assertIsDisplayed()
    }

    private fun corner(
        left: Color,
        right: Color,
    ): CornerView {
        fun face(center: Color) = List(9) { center.takeIf { _ -> it == 4 } }
        return CornerView(face(Color.YELLOW), face(left), face(right))
    }
}
