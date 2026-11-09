package dev.cubentia.app.ui.lesson

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.cubentia.app.content.CatalogEntry
import dev.cubentia.app.content.CatalogState
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.session.TickingClock
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.application.port.FlagLevel
import dev.cubentia.application.port.ProgressLoad
import dev.cubentia.application.port.ProgressRecord
import dev.cubentia.application.port.ProgressRepository
import dev.cubentia.application.port.ProgressSave
import dev.cubentia.application.session.Checkpoint
import dev.cubentia.application.session.CubeMode
import dev.cubentia.application.session.GuidedRecovery
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.application.session.SessionKind
import dev.cubentia.application.session.SessionSnapshot
import dev.cubentia.application.session.SyncState
import dev.cubentia.application.usecase.CompleteLesson
import dev.cubentia.application.usecase.PlanRecovery
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.content.LessonId
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.recognition.PhysicalObservation
import dev.cubentia.domain.tutor.Certainty
import dev.cubentia.domain.tutor.ProgressEvent
import dev.cubentia.domain.tutor.RecoveryPlan
import dev.cubentia.domain.tutor.TeachingMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Acceptance criteria tests for Story 8.2: Follow a Lesson on my real cube.
 *
 * AC 1: Given the Physical mode toggle in a Lesson, when switched on, then a Confirm-your-cube card
 *       offers It matches / Enter it (Scan it hidden until Epic 10), and the frame turns to dashed EXPECTED
 *       with no Certainty chip.
 * AC 2: Given a step, when the Learner taps Done (always primary) or I did something else then a Move key,
 *       then the Expected State advances by the confirmed or entered Move.
 * AC 3: Given the Objective is met on the Expected State, when the panel updates, then it reads
 *       "If your cube matches the picture, this step is done.", keeps EXPECTED, enables Next, and shows
 *       no celebration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class PhysicalLessonScreenTest {
    @get:Rule val rule = createComposeRule()

    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
    private val ready = CatalogState.Ready(bundle)
    private val lessonEntry = ready.lessons.first { it.lesson.id.value == "lesson-01-01" }

    private fun node(tag: String) = rule.onNodeWithTag(tag)

    private fun setLessonContent(
        host: SessionHost,
        entry: CatalogEntry = lessonEntry,
        cameraScan: FlagLevel = FlagLevel.OFF,
        onClose: () -> Unit = {},
        shown: () -> Boolean = { true },
        planRecovery: PlanRecovery = PlanRecovery(),
    ) {
        rule.setContent {
            CubentiaTheme {
                if (shown()) {
                    LessonScreen(
                        host = host,
                        entry = entry,
                        strings = ready.strings,
                        modes = TeachingMode.entries,
                        onClose = onClose,
                        cameraScan = cameraScan,
                        planRecovery = planRecovery,
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.waitUntil(5_000) { host.state.value.isActive }
    }

    /** Switches to Physical-cube Mode and confirms the cube matches. */
    private fun enterPhysicalMode(host: SessionHost) {
        node("lesson:physicalModeSwitch").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_AWAITING_CONFIRM }
        rule.waitForIdle()
        node("confirmCube:matches").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
        rule.waitForIdle()
    }

    /** Reports a mismatch through the overflow menu's [item]. */
    private fun reportMismatch(
        host: SessionHost,
        item: String = "lesson:menuMismatch",
    ) {
        node("lesson:overflowMenu").performScrollTo().performClick()
        rule.waitForIdle()
        node(item).performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_MISMATCH }
        rule.waitForIdle()
    }

    /** Opens `lesson-06-03` in Physical-cube Mode at [cameraScan] and confirms its moves until the Objective is met. */
    private fun meetStage6ObjectiveInPhysicalMode(
        cameraScan: FlagLevel = FlagLevel.OFF,
        host: SessionHost = SessionHost(TickingClock()),
        shown: () -> Boolean = { true },
        onClose: () -> Unit = {},
    ): SessionHost {
        val milestoneEntry = ready.lessons.single { it.lesson.id.value == "lesson-06-03" }
        setLessonContent(host, milestoneEntry, cameraScan, onClose = onClose, shown = shown)

        node("lesson:physicalModeSwitch").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_AWAITING_CONFIRM }
        rule.waitForIdle()
        node("confirmCube:matches").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
        rule.waitForIdle()

        val lesson = milestoneEntry.lesson
        val step1Moves = (Notation.parse(lesson.steps[0].moves) as NotationResult.Parsed).moves
        step1Moves.forEach { move ->
            runBlocking { host.dispatch { SessionEvent.PhysicalConfirmed(move, it) } }
        }
        runBlocking { host.dispatch(SessionEvent::StepAdvanced) }
        val step2Moves = (Notation.parse(lesson.steps[1].moves) as NotationResult.Parsed).moves
        step2Moves.forEach { move ->
            runBlocking { host.dispatch { SessionEvent.PhysicalConfirmed(move, it) } }
        }
        rule.waitUntil(5_000) {
            host.state.value.decision
                ?.satisfied == true || host.state.value.objectiveMet
        }
        rule.waitForIdle()
        return host
    }

    @Test
    fun ac1_togglePhysicalModeOn_showsConfirmCard_andDashedExpectedBadge_suppressesCertainty_scanHidden_andDisablesReset() {
        val host = SessionHost(TickingClock())
        setLessonContent(host)

        // Initially in virtual mode: frame is VIRTUAL with Certainty description
        node("stage:frame").assertTextEquals("VIRTUAL")
        val initialConfig = node("stage:frame").fetchSemanticsNode().config
        assertEquals(LiveRegionMode.Polite, initialConfig[SemanticsProperties.LiveRegion])
        assertNull(initialConfig.getOrNull(DashedBorderKey))
        node("confirmCube:card").assertDoesNotExist()

        // Turn Physical mode toggle ON
        node("lesson:physicalModeSwitch").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_AWAITING_CONFIRM }
        rule.waitForIdle()

        // Frame turns to dashed EXPECTED with Certainty chip / live region suppressed
        node("stage:frame").assertTextEquals("EXPECTED")
        val physicalConfig = node("stage:frame").fetchSemanticsNode().config
        assertTrue(physicalConfig[DashedBorderKey])
        assertNull(physicalConfig.getOrNull(SemanticsProperties.StateDescription))
        assertNull(physicalConfig.getOrNull(SemanticsProperties.LiveRegion))

        // Confirm-your-cube card offers "It matches" and "Enter it"
        node("confirmCube:card").assertExists()
        node("confirmCube:matches").assertExists()
        node("confirmCube:enter").assertExists()

        // Scan it stays hidden
        rule.onNodeWithText("Scan it").assertDoesNotExist()
        node("confirmCube:scan").assertDoesNotExist()

        // toolbar:reset is disabled in physical mode
        node("toolbar:reset").assertIsNotEnabled()
    }

    @Test
    fun ac1_tapItMatches_transitionsToPredicted_andDismissesConfirmCard_revealingStepControls() {
        val host = SessionHost(TickingClock())
        setLessonContent(host)

        // Switch to physical
        node("lesson:physicalModeSwitch").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_AWAITING_CONFIRM }
        rule.waitForIdle()

        // Tap "It matches"
        node("confirmCube:matches").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
        rule.waitForIdle()

        // Confirm card dismisses
        node("confirmCube:card").assertDoesNotExist()

        // Step controls appear: Done (primary) and I did something else (secondary)
        node("lesson:done").assertExists()
        node("lesson:didSomethingElse").assertExists()

        // MoveKeyBar is hidden by default in physical mode
        node("moveKey:U").assertDoesNotExist()

        // toolbar:reset remains disabled in physical mode
        node("toolbar:reset").assertIsNotEnabled()
    }

    @Test
    fun ac1_tapEnterIt_opensManualEditor_andAcceptSetsExpectedState() {
        val host = SessionHost(TickingClock())
        setLessonContent(host)

        // Switch to physical
        node("lesson:physicalModeSwitch").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_AWAITING_CONFIRM }
        rule.waitForIdle()

        // Tap "Enter it"
        node("confirmCube:enter").performScrollTo().performClick()
        rule.waitForIdle()

        // ManualCubeEditor appears
        node("lesson:manualEditor").assertExists()
        node("editor:accept").assertExists()

        // Tap Accept in editor
        node("editor:accept").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_VERIFIED }
        rule.waitForIdle()

        // Editor dismisses, transitions to PHYSICAL_VERIFIED
        node("lesson:manualEditor").assertDoesNotExist()
        node("confirmCube:card").assertDoesNotExist()
    }

    @Test
    fun ac2_tapDone_advancesExpectedStateByTaughtMove() {
        val host = SessionHost(TickingClock())
        setLessonContent(host)

        // Enter physical mode and confirm match
        node("lesson:physicalModeSwitch").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_AWAITING_CONFIRM }
        rule.waitForIdle()
        node("confirmCube:matches").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
        rule.waitForIdle()

        val initialExpected = host.state.value.expected

        // Step 1 taught move in lesson-01-01 is U
        // Tap Done
        node("lesson:done").performScrollTo().performClick()
        val expectedAfterMove = initialExpected.apply(FaceTurn(Face.U, Turn.CLOCKWISE))
        rule.waitUntil(5_000) { host.state.value.expected == expectedAfterMove }
        rule.waitForIdle()

        assertEquals(expectedAfterMove, host.state.value.expected)
        assertEquals(expectedAfterMove, host.state.value.virtual)
    }

    @Test
    fun ac2_tapDidSomethingElse_revealsMoveKeyBar_andMoveAdvancesExpectedStateAndHidesBar() {
        val host = SessionHost(TickingClock())
        setLessonContent(host)

        // Enter physical mode and confirm match
        node("lesson:physicalModeSwitch").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_AWAITING_CONFIRM }
        rule.waitForIdle()
        node("confirmCube:matches").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
        rule.waitForIdle()

        val initialExpected = host.state.value.expected

        // MoveKeyBar is hidden
        node("moveKey:R").assertDoesNotExist()

        // Tap "I did something else"
        node("lesson:didSomethingElse").performScrollTo().performClick()
        rule.waitForIdle()

        // MoveKeyBar is now revealed
        node("moveKey:R").assertExists()

        // Tap move key R
        node("moveKey:R").performClick()
        val expectedAfterR = initialExpected.apply(FaceTurn(Face.R, Turn.CLOCKWISE))
        rule.waitUntil(5_000) { host.state.value.expected == expectedAfterR }
        rule.waitForIdle()

        // Expected state advanced by R
        assertEquals(expectedAfterR, host.state.value.expected)
        assertEquals(expectedAfterR, host.state.value.virtual)

        // MoveKeyBar hides again after entry
        node("moveKey:R").assertDoesNotExist()
    }

    @Test
    fun ac2_tappingDoneOrNextDismissesMoveKeyBarIfRevealedEarlier() {
        val host = SessionHost(TickingClock())
        setLessonContent(host)

        // Enter physical mode and confirm match
        node("lesson:physicalModeSwitch").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_AWAITING_CONFIRM }
        rule.waitForIdle()
        node("confirmCube:matches").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
        rule.waitForIdle()

        // Tap "I did something else" to reveal MoveKeyBar
        node("lesson:didSomethingElse").performScrollTo().performClick()
        rule.waitForIdle()
        node("moveKey:U").assertExists()

        // Tapping Done dismisses MoveKeyBar
        node("lesson:done").performScrollTo().performClick()
        rule.waitForIdle()
        node("moveKey:U").assertDoesNotExist()
    }

    @Test
    fun multiStepLessonProgressionAdvancesIntermediateStepWithNextInPhysicalMode() {
        val multiStepEntry = ready.lessons.first { it.lesson.id.value == "lesson-02-05" }
        val host = SessionHost(TickingClock())
        setLessonContent(host, multiStepEntry)

        // Enter physical mode and confirm match
        node("lesson:physicalModeSwitch").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_AWAITING_CONFIRM }
        rule.waitForIdle()
        node("confirmCube:matches").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
        rule.waitForIdle()

        assertEquals(0, host.state.value.stepIndex)
        node("lesson:done").assertExists()

        // Tap Done for Step 1
        node("lesson:done").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            host.state.value.decision
                ?.advance == true
        }
        rule.waitForIdle()

        // In physical mode, when decision.advance is true on an intermediate step, Next is enabled
        node("lesson:next").assertExists().assertIsEnabled()

        // Tap Next to advance to step 2
        node("lesson:next").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.stepIndex == 1 }
        rule.waitForIdle()

        // Step 2 is active, Done is rendered again
        assertEquals(1, host.state.value.stepIndex)
        node("lesson:done").assertExists()
    }

    @Test
    fun ac3_objectiveMetInPhysicalMode_showsHonestCopy_keepsExpected_enablesNext_andSuppressesRealMilestones() {
        val host = SessionHost(TickingClock())
        setLessonContent(host, lessonEntry)

        // Enter physical mode and confirm match
        node("lesson:physicalModeSwitch").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_AWAITING_CONFIRM }
        rule.waitForIdle()
        node("confirmCube:matches").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
        rule.waitForIdle()

        // Tap Done for taught move U in lesson-01-01
        node("lesson:done").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            host.state.value.decision
                ?.satisfied == true || host.state.value.objectiveMet
        }
        rule.waitForIdle()

        // Panel reads honest physical completion copy from domain strings
        rule.onNodeWithText("If your cube matches the picture, this step is done.").assertExists()

        // Frame badge remains dashed EXPECTED
        node("stage:frame").assertTextEquals("EXPECTED")
        val physicalConfig = node("stage:frame").fetchSemanticsNode().config
        assertTrue(physicalConfig[DashedBorderKey])

        // Next button is enabled
        node("lesson:next").assertExists()
        node("lesson:next").assertIsEnabled()

        // Real milestone overlay and burst must NOT exist on standard lesson in physical mode
        node("milestone:overlay").assertDoesNotExist()
        node("milestone:burst").assertDoesNotExist()
    }

    @Test
    fun story8_6_ac1_story8_7_ac1_cameraOff_stage6PhysicalObjectiveMet_showsCameraFreePrompt_milestoneOnlyAfterYesItsSolved() {
        meetStage6ObjectiveInPhysicalMode(FlagLevel.OFF)

        // Story 8.7: v1.0 is camera-free, so the prompt compares with the picture and never mentions a camera
        rule.onNodeWithText("Your cube should be solved now. Compare it with the picture, then tell me if it is.").assertExists()
        rule.onAllNodes(hasText("camera", substring = true, ignoreCase = true), useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodes(hasText("Quick Check", substring = true, ignoreCase = true), useUnmergedTree = true).assertCountEquals(0)

        // Frame badge remains dashed EXPECTED
        node("stage:frame").assertTextEquals("EXPECTED")
        val physicalConfig = node("stage:frame").fetchSemanticsNode().config
        assertTrue(physicalConfig[DashedBorderKey])

        // Milestone celebration is initially suppressed
        node("milestone:overlay").assertDoesNotExist()
        node("milestone:burst").assertDoesNotExist()

        // "Yes, it's solved" button is present and primary
        node("lesson:yesItsSolved").assertExists()
        node("lesson:yesItsSolved").assertIsEnabled()
        rule.onNodeWithText("Yes, it's solved").assertExists()

        // "I did something else" button remains present alongside
        node("lesson:didSomethingElse").assertExists()
        node("lesson:didSomethingElse").assertIsEnabled()

        // Tapping "Yes, it's solved" triggers the celebratory first-solve milestone
        node("lesson:yesItsSolved").performScrollTo().performClick()
        // The confirmation goes through SessionHost off the main thread, which waitForIdle does not track.
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("milestone:overlay").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()

        node("milestone:overlay").assertExists()
        node("milestone:burst").assertExists()
        rule.onNodeWithText("You solved the cube!").assertExists()
        node("milestone:practice").assertExists()
        node("milestone:continue").assertExists()

        // Tapping continue dismisses milestone overlay
        node("milestone:continue").performClick()
        rule.waitForIdle()
        node("milestone:overlay").assertDoesNotExist()
    }

    @Test
    fun story8_6_ac1_stage6ObjectiveMet_didSomethingElseRevealsMoveKeys_milestoneSuppressed() {
        val milestoneEntry = ready.lessons.single { it.lesson.id.value == "lesson-06-03" }
        val host = SessionHost(TickingClock())
        setLessonContent(host, milestoneEntry)

        node("lesson:physicalModeSwitch").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_AWAITING_CONFIRM }
        rule.waitForIdle()
        node("confirmCube:matches").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
        rule.waitForIdle()

        val lesson = milestoneEntry.lesson
        val step1Moves = (Notation.parse(lesson.steps[0].moves) as NotationResult.Parsed).moves
        step1Moves.forEach { move ->
            runBlocking { host.dispatch { SessionEvent.PhysicalConfirmed(move, it) } }
        }
        runBlocking { host.dispatch(SessionEvent::StepAdvanced) }

        val step2Moves = (Notation.parse(lesson.steps[1].moves) as NotationResult.Parsed).moves
        step2Moves.forEach { move ->
            runBlocking { host.dispatch { SessionEvent.PhysicalConfirmed(move, it) } }
        }
        rule.waitUntil(5_000) {
            host.state.value.decision
                ?.satisfied == true || host.state.value.objectiveMet
        }
        rule.waitForIdle()

        // Tapping "I did something else"
        node("moveKey:U").assertDoesNotExist()
        node("lesson:didSomethingElse").performScrollTo().performClick()
        rule.waitForIdle()

        // Move keys are revealed, milestone stays suppressed
        node("moveKey:U").assertExists()
        node("milestone:overlay").assertDoesNotExist()
    }

    @Test
    fun story8_6_review_stage6ObjectiveMet_switchToVirtualWithoutConfirming_milestoneSuppressed() {
        val milestoneEntry = ready.lessons.single { it.lesson.id.value == "lesson-06-03" }
        val host = SessionHost(TickingClock())
        setLessonContent(host, milestoneEntry)

        node("lesson:physicalModeSwitch").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_AWAITING_CONFIRM }
        rule.waitForIdle()
        node("confirmCube:matches").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
        rule.waitForIdle()

        val lesson = milestoneEntry.lesson
        lesson.steps.take(2).forEachIndexed { index, step ->
            (Notation.parse(step.moves) as NotationResult.Parsed).moves.forEach { move ->
                runBlocking { host.dispatch { SessionEvent.PhysicalConfirmed(move, it) } }
            }
            if (index == 0) runBlocking { host.dispatch(SessionEvent::StepAdvanced) }
        }
        rule.waitUntil(5_000) {
            host.state.value.decision
                ?.satisfied == true || host.state.value.objectiveMet
        }
        rule.waitForIdle()
        node("lesson:yesItsSolved").assertExists()

        // Switching to virtual must not skip the honest *Yes, it's solved* confirmation
        node("lesson:physicalModeSwitch").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.VIRTUAL }
        rule.waitForIdle()
        node("milestone:overlay").assertDoesNotExist()
    }

    @Test
    fun togglePhysicalModeOff_returnsToVirtual_withCertainty_andDefaultMoveKeyBar() {
        val host = SessionHost(TickingClock())
        setLessonContent(host)

        // Switch to physical
        node("lesson:physicalModeSwitch").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_AWAITING_CONFIRM }
        rule.waitForIdle()
        node("confirmCube:matches").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
        rule.waitForIdle()

        // Switch back to virtual
        node("lesson:physicalModeSwitch").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.VIRTUAL }
        rule.waitForIdle()

        // Mode returns to VIRTUAL
        node("stage:frame").assertTextEquals("VIRTUAL")

        // Certainty chip and live region are active
        val config = node("stage:frame").fetchSemanticsNode().config
        assertEquals(LiveRegionMode.Polite, config[SemanticsProperties.LiveRegion])
        assertNull(config.getOrNull(DashedBorderKey))

        // MoveKeyBar is visible by default in virtual mode
        node("moveKey:U").assertExists()
    }

    @Test
    fun story8_3_ac1_reportMismatch_showsUnknownChip_andAsksInFR9Order() {
        val host = SessionHost(TickingClock())
        setLessonContent(host)

        // Enter physical mode and confirm match
        node("lesson:physicalModeSwitch").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_AWAITING_CONFIRM }
        rule.waitForIdle()
        node("confirmCube:matches").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
        rule.waitForIdle()

        // Certainty chip does not exist yet in predicted
        node("certainty:chip").assertDoesNotExist()

        // Open overflow menu
        node("lesson:overflowMenu").performScrollTo().performClick()
        rule.waitForIdle()

        // Choose "My cube doesn't match"
        node("lesson:menuMismatch").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_MISMATCH }
        rule.waitForIdle()

        // Chip shows ? Unknown
        node("certainty:chip").assertExists()
        node("certainty:icon").assertTextEquals("?")
        node("certainty:label").assertTextEquals("Unknown")

        // Rung 1: "Which turn did you make?" with Move keys
        node("mismatch:ladder").assertExists()
        rule.onNodeWithText("Which turn did you make?").assertExists()
        node("moveKey:U").assertExists()

        // Advance to Rung 2: one-face picker
        node("mismatch:nextQuestion").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Look at the front face — which corner is white?").assertExists()
        node("mismatch:corner:top_left").assertExists()
        node("mismatch:corner:top_right").assertExists()
        node("mismatch:corner:bottom_left").assertExists()
        node("mismatch:corner:bottom_right").assertExists()

        // Advance to Rung 3: "Let's re-enter your cube"
        node("mismatch:nextQuestion").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Let's re-enter your cube").assertExists()
        node("mismatch:reenter").assertExists()

        // Tapping Enter it opens ManualCubeEditor
        node("mismatch:reenter").performScrollTo().performClick()
        rule.waitForIdle()
        node("lesson:manualEditor").assertExists()
    }

    @Test
    fun story12_8_rung1MoveIsCommitted_withItsDiagnosedCertainty_andTheKeyBarCloses() {
        val host = SessionHost(TickingClock())
        setLessonContent(host)
        enterPhysicalMode(host)
        reportMismatch(host, "lesson:menuNotSure")

        // Chip shows ? Unknown initially
        node("certainty:icon").assertTextEquals("?")
        node("certainty:label").assertTextEquals("Unknown")

        val before = host.state.value.expected
        node("moveKey:U'").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
        rule.waitForIdle()

        assertEquals(before.apply(FaceTurn(Face.U, Turn.COUNTERCLOCKWISE)), host.state.value.expected)
        assertNotNull(host.state.value.lastDiagnosis)
        node("certainty:icon").assertTextEquals("✓")
        node("certainty:label").assertTextEquals("Verified")
        node("mismatch:candidate").assertDoesNotExist()
        node("moveKey:U").assertDoesNotExist()
    }

    @Test
    fun story12_8_moveKeysShowOnlyOnRung1_andReturnOnAFreshReport() {
        val host = SessionHost(TickingClock())
        setLessonContent(host)
        enterPhysicalMode(host)
        reportMismatch(host)
        node("moveKey:U").assertExists()

        repeat(2) {
            node("mismatch:nextQuestion").performScrollTo().performClick()
            rule.waitForIdle()
            node("moveKey:U").assertDoesNotExist()
        }
        rule.onNodeWithText("Let's re-enter your cube").assertExists()

        node("mismatch:matchesAfterAll").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
        rule.waitForIdle()

        reportMismatch(host)
        rule.onNodeWithText("Which turn did you make?").assertExists()
        node("moveKey:U").assertExists()
    }

    @Test
    fun story12_8_keyBarOpenedBeforeTheReport_closesAfterARung1Move() {
        val host = SessionHost(TickingClock())
        setLessonContent(host)
        enterPhysicalMode(host)
        node("lesson:didSomethingElse").performScrollTo().performClick()
        rule.waitForIdle()
        node("moveKey:U").assertExists()
        reportMismatch(host)

        node("moveKey:U'").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
        rule.waitForIdle()

        node("moveKey:U").assertDoesNotExist()
    }

    @Test
    fun story12_8_keyBarOpenedBeforeTheReport_closesAfterItMatchesAfterAll() {
        val host = SessionHost(TickingClock())
        setLessonContent(host)
        enterPhysicalMode(host)
        node("lesson:didSomethingElse").performScrollTo().performClick()
        rule.waitForIdle()
        node("moveKey:U").assertExists()
        reportMismatch(host)

        node("mismatch:matchesAfterAll").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
        rule.waitForIdle()

        node("moveKey:U").assertDoesNotExist()
    }

    @Test
    fun story12_8_keyBarOpenedBeforeTheReport_closesAfterAnEditorEntry() {
        val host = SessionHost(TickingClock())
        setLessonContent(host)
        enterPhysicalMode(host)
        node("lesson:didSomethingElse").performScrollTo().performClick()
        rule.waitForIdle()
        node("moveKey:U").assertExists()
        reportMismatch(host)

        val entered = host.state.value.expected
        runBlocking { host.dispatch { SessionEvent.EditorEntered(entered, it) } }
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_VERIFIED }
        rule.waitForIdle()

        node("moveKey:U").assertDoesNotExist()
    }

    @Test
    fun story12_8_mismatchRungSurvivesRecreation_withTheKeysStillHidden() {
        val host = SessionHost(TickingClock())
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            CubentiaTheme {
                LessonScreen(
                    host = host,
                    entry = lessonEntry,
                    strings = ready.strings,
                    modes = TeachingMode.entries,
                    onClose = {},
                    cameraScan = FlagLevel.OFF,
                )
            }
        }
        rule.waitForIdle()
        rule.waitUntil(5_000) { host.state.value.isActive }
        enterPhysicalMode(host)
        reportMismatch(host)
        repeat(2) {
            node("mismatch:nextQuestion").performScrollTo().performClick()
            rule.waitForIdle()
        }

        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()

        rule.onNodeWithText("Let's re-enter your cube").assertExists()
        node("moveKey:U").assertDoesNotExist()
    }

    @Test
    fun story12_8_verifiedWithNoDiagnosis_showsTheVerifiedChip() {
        val host = SessionHost(TickingClock())
        setLessonContent(host)
        enterPhysicalMode(host)

        val moves = (Notation.parse(lessonEntry.lesson.demonstration) as NotationResult.Parsed).moves
        val met =
            host.state.value.expected
                .apply(moves)
        runBlocking { host.dispatch { SessionEvent.EditorEntered(met, it) } }
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_VERIFIED }
        rule.waitForIdle()

        assertTrue(host.state.value.objectiveMet)
        assertNull(host.state.value.lastDiagnosis)
        node("certainty:icon").assertTextEquals("✓")
        node("certainty:label").assertTextEquals("Verified")
    }

    @Test
    fun story12_8_rung1LikelyMove_showsItsCandidateAfterTheCommit() {
        val host = SessionHost(TickingClock())
        setLessonContent(host)
        enterPhysicalMode(host)
        reportMismatch(host)

        node("moveKey:more").performClick()
        rule.waitForIdle()
        node("moveKey:M").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
        rule.waitForIdle()

        assertEquals(
            Certainty.LIKELY,
            host.state.value.decision
                ?.certainty,
        )
        node("certainty:icon").assertTextEquals("≈")
        node("certainty:label").assertTextEquals("Likely")
        node("mismatch:candidate").performScrollTo().assertExists()
    }

    @Test
    fun story12_8_itMatchesAfterAll_dismissesFromEveryRung_withExpectedUnchanged() {
        val host = SessionHost(TickingClock())
        setLessonContent(host)
        enterPhysicalMode(host)
        val expected = host.state.value.expected
        val revision = host.state.value.revision

        listOf(0, 1, 2).forEach { rung ->
            reportMismatch(host)
            repeat(rung) {
                node("mismatch:nextQuestion").performScrollTo().performClick()
                rule.waitForIdle()
            }
            node("mismatch:matchesAfterAll").performScrollTo().assertTextEquals("It matches after all").performClick()
            rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
            rule.waitForIdle()
            assertEquals(expected, host.state.value.expected)
            assertEquals(revision, host.state.value.revision)
            node("mismatch:ladder").assertDoesNotExist()
            node("moveKey:U").assertDoesNotExist()
        }
    }

    @Test
    fun story12_8_everyRung2Answer_leadsToRung3_andLeavesTheSessionUnchanged() {
        val host = SessionHost(TickingClock())
        setLessonContent(host)
        enterPhysicalMode(host)

        listOf(
            "mismatch:corner:top_left",
            "mismatch:corner:top_right",
            "mismatch:corner:bottom_left",
            "mismatch:corner:bottom_right",
            "mismatch:corner:none",
            "mismatch:nextQuestion",
        ).forEach { answer ->
            reportMismatch(host)
            node("mismatch:nextQuestion").performScrollTo().performClick()
            rule.waitForIdle()
            val before = host.state.value

            node(answer).performScrollTo().performClick()
            rule.waitForIdle()

            rule.onNodeWithText("Let's re-enter your cube").assertExists()
            node("mismatch:reenter").assertExists()
            assertEquals(before, host.state.value)
            assertNull(host.state.value.observed)

            node("mismatch:matchesAfterAll").performScrollTo().performClick()
            rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
            rule.waitForIdle()
        }
    }

    @Test
    fun story12_8_doneFollowsAPlannedRecovery_thenTheStepMoveAgain() {
        val entry0301 = ready.lessons.first { it.lesson.id.value == "lesson-03-01" }
        val host = SessionHost(TickingClock())
        val plan = listOf(FaceTurn(Face.U, Turn.COUNTERCLOCKWISE), FaceTurn(Face.R, Turn.COUNTERCLOCKWISE))
        setLessonContent(host, entry0301, planRecovery = PlanRecovery({ _, _, _, _, _ -> RecoveryPlan.Found(plan) }))
        enterPhysicalMode(host)

        val entered =
            host.state.value.expected
                .apply(FaceTurn(Face.R, Turn.CLOCKWISE), FaceTurn(Face.U, Turn.CLOCKWISE))
        runBlocking { host.dispatch { SessionEvent.EditorEntered(entered, it) } }
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_VERIFIED }
        rule.waitForIdle()

        node("lesson:recovery:guideMeBack").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.guidedRecovery is GuidedRecovery.Planned }
        rule.waitForIdle()

        node("lesson:done").performScrollTo().performClick()
        val afterFirst = entered.apply(plan.first())
        rule.waitUntil(5_000) { host.state.value.expected == afterFirst }
        rule.waitForIdle()
        assertEquals(plan.drop(1), (host.state.value.guidedRecovery as GuidedRecovery.Planned).moves)
        assertEquals(SyncState.PHYSICAL_PREDICTED, host.state.value.syncState)

        node("lesson:done").performScrollTo().performClick()
        val afterPlan = afterFirst.apply(plan.last())
        rule.waitUntil(5_000) { host.state.value.expected == afterPlan }
        rule.waitForIdle()
        assertEquals(GuidedRecovery.None, host.state.value.guidedRecovery)
    }

    @Test
    fun story8_3_ac3_reenteredCubeBecomesFullObservation_andGuidedRecoveryOffered() {
        val entry0301 = ready.lessons.first { it.lesson.id.value == "lesson-03-01" }
        val host = SessionHost(TickingClock())
        setLessonContent(host, entry0301)

        // Enter physical mode and confirm match
        node("lesson:physicalModeSwitch").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_AWAITING_CONFIRM }
        rule.waitForIdle()
        node("confirmCube:matches").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
        rule.waitForIdle()

        // Report mismatch
        node("lesson:overflowMenu").performScrollTo().performClick()
        rule.waitForIdle()
        node("lesson:menuMismatch").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_MISMATCH }
        rule.waitForIdle()

        // Move down ladder to re-enter
        node("mismatch:nextQuestion").performScrollTo().performClick()
        rule.waitForIdle()
        node("mismatch:nextQuestion").performScrollTo().performClick()
        rule.waitForIdle()
        node("mismatch:reenter").performScrollTo().performClick()
        rule.waitForIdle()
        node("lesson:manualEditor").assertExists()

        // Simulate editor entry that disturbs preserved white cross pieces
        val disturbedState =
            host.state.value.expected
                .apply(FaceTurn(Face.R, Turn.CLOCKWISE))
        runBlocking {
            host.dispatch { SessionEvent.EditorEntered(disturbedState, it) }
        }
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_VERIFIED }
        rule.waitForIdle()

        // Becomes Full observation
        val observation = host.state.value.observed
        assertTrue(observation is PhysicalObservation.Full)
        assertEquals(disturbedState, (observation as PhysicalObservation.Full).state)

        // Guided recovery is offered
        assertTrue(host.state.value.canGuideBack)
        node("lesson:recovery:guideMeBack").assertExists()
    }

    @Test
    fun story8_4_ac1_switchFromPhysicalToVirtual_alignsVirtualToExpected_showsNotice_andStopsUndo() {
        val host = SessionHost(TickingClock())
        setLessonContent(host)

        // Switch to physical mode
        node("lesson:physicalModeSwitch").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_AWAITING_CONFIRM }
        rule.waitForIdle()

        // Confirm match
        node("confirmCube:matches").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
        rule.waitForIdle()

        // Advance a step / move
        val initialExpected = host.state.value.expected
        val expectedAfterMove = initialExpected.apply(FaceTurn(Face.U, Turn.CLOCKWISE))
        node("lesson:done").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.expected == expectedAfterMove }
        rule.waitForIdle()

        val expectedBeforeSwitch = host.state.value.expected

        // Switch toggle back to virtual mode
        node("lesson:physicalModeSwitch").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.VIRTUAL }
        rule.waitForIdle()

        // 1. virtual := expected
        assertEquals(expectedBeforeSwitch, host.state.value.virtual)
        // 2. "The on-screen cube now matches what we expected on yours." shows
        node("lesson:restoreNote").assertTextEquals("The on-screen cube now matches what we expected on yours.")
        // 3. Undo stops there
        node("toolbar:undo").assertIsNotEnabled()
        node("toolbar:redo").assertIsNotEnabled()

        // Committing a new move in virtual mode allows undoing up to the barrier
        val virtualMove = FaceTurn(Face.R, Turn.CLOCKWISE)
        runBlocking {
            host.dispatch { SessionEvent.MoveCommitted(virtualMove, it) }
        }
        rule.waitForIdle()
        node("toolbar:undo").assertIsEnabled()
        node("toolbar:undo").performClick()
        rule.waitUntil(5_000) { !host.state.value.history.canUndo }
        rule.waitForIdle()
        assertEquals(expectedBeforeSwitch, host.state.value.virtual)
        node("toolbar:undo").assertIsNotEnabled()
    }

    @Test
    fun story8_4_ac2_physicalCheckpointRestored_landsInAwaitingConfirm_clearsObserved_andShowsConfirmCard() {
        val checkpoint =
            Checkpoint(
                kind = SessionKind.LESSON,
                lessonId = lessonEntry.lesson.id.value,
                stageId = lessonEntry.stage.id.value,
                contentRevision = bundle.contentRevision,
                start = lessonEntry.lesson.entryState,
                cube = lessonEntry.lesson.entryState,
                startOrientation = "UF",
                cubeOrientation = "UF",
                history = "",
                mode = CubeMode.PHYSICAL,
                teachingMode = TeachingMode.GUIDE_ME,
                adaptiveAssistance = false,
                savedAt = 0L,
            )
        val host = SessionHost(TickingClock())
        runBlocking {
            host.dispatch {
                SessionEvent.CheckpointRestored(
                    checkpoint = checkpoint,
                    lesson = lessonEntry.lesson,
                    tutorConfig = bundle.tutorConfig,
                    contentRevision = bundle.contentRevision,
                    at = it,
                )
            }
        }
        setLessonContent(host)

        // Lands in PHYSICAL_AWAITING_CONFIRM with observed cleared
        assertEquals(SyncState.PHYSICAL_AWAITING_CONFIRM, host.state.value.syncState)
        assertNull(host.state.value.observed)

        // Confirm-your-cube card shows
        node("confirmCube:card").assertExists()
        node("confirmCube:matches").assertExists()
        node("confirmCube:enter").assertExists()
        node("stage:frame").assertTextEquals("EXPECTED")
    }

    @Test
    fun story8_4_ac2_physicalSnapshotRestored_landsInAwaitingConfirm_clearsObserved_andShowsConfirmCard() {
        val host = SessionHost(TickingClock())
        runBlocking {
            host.dispatch {
                SessionEvent.SessionRestored(
                    snapshot = physicalSnapshot(),
                    lesson = lessonEntry.lesson,
                    tutorConfig = bundle.tutorConfig,
                    at = it,
                )
            }
        }
        setLessonContent(host)

        // Lands in PHYSICAL_AWAITING_CONFIRM with observed cleared
        assertEquals(SyncState.PHYSICAL_AWAITING_CONFIRM, host.state.value.syncState)
        assertNull(host.state.value.observed)

        // Confirm-your-cube card shows
        node("confirmCube:card").assertExists()
        node("confirmCube:matches").assertExists()
        node("confirmCube:enter").assertExists()
        node("stage:frame").assertTextEquals("EXPECTED")
    }

    @Test
    fun story8_7_ac2_cameraScanBeta_stage6ObjectiveMetInPhysicalMode_showsCameraPrompt() {
        assertCameraPrompt(FlagLevel.BETA)
    }

    @Test
    fun story8_7_ac2_cameraScanGa_stage6ObjectiveMetInPhysicalMode_showsCameraPrompt() {
        assertCameraPrompt(FlagLevel.GA)
    }

    private fun assertCameraPrompt(cameraScan: FlagLevel) {
        meetStage6ObjectiveInPhysicalMode(cameraScan)

        rule.onNodeWithText("Your cube should be solved now. Check it with the camera, or tell me if it is.").assertExists()
        rule.onNodeWithText("Your cube should be solved now. Compare it with the picture, then tell me if it is.").assertDoesNotExist()
        node("lesson:yesItsSolved").assertExists()
        node("lesson:didSomethingElse").assertExists()
        node("milestone:overlay").assertDoesNotExist()
    }

    /** A host recording completions in memory; [written] holds what it appended. */
    private fun physicalSnapshot(): SessionSnapshot =
        SessionSnapshot(
            kind = SessionKind.LESSON,
            sessionId = "test-session",
            revision = 1L,
            lessonId = lessonEntry.lesson.id.value,
            contentRevision = bundle.contentRevision,
            start = lessonEntry.lesson.entryState,
            done = "",
            undone = "",
            solution = "",
            checkpoint = null,
            teachingMode = TeachingMode.GUIDE_ME,
            adaptiveAssistance = false,
            hintsUsed = 0,
            hintRung = dev.cubentia.domain.tutor.HintRung.NONE,
            revealRequested = false,
            noticeStep = null,
            stepMistakes = emptyMap(),
            cleanSteps = 0,
            cleanCredited = emptySet(),
            stepHelped = emptySet(),
            assistanceDismissed = emptyMap(),
            mode = CubeMode.PHYSICAL,
        )

    /** [writeDelayMillis] and a background [confined] keep a write in flight long enough for a close to cancel it. */
    private fun recordingHost(
        written: MutableList<ProgressEvent>,
        confined: CoroutineDispatcher = Dispatchers.Main.immediate,
        writeDelayMillis: Long = 0,
    ): SessionHost {
        val repository =
            object : ProgressRepository {
                override suspend fun all(): ProgressLoad = ProgressLoad.Found(written.toList())

                override suspend fun append(records: List<ProgressRecord>): ProgressSave {
                    delay(writeDelayMillis)
                    written += records.map { it.event }
                    return ProgressSave.Saved
                }
            }
        val host =
            SessionHost(
                clock = TickingClock(),
                confined = confined,
                progress = repository,
                completeLesson = CompleteLesson(repository),
            )
        runBlocking { host.restore(bundle) }
        return host
    }

    private fun physicalLessonObjectiveMet(host: SessionHost) {
        node("lesson:physicalModeSwitch").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_AWAITING_CONFIRM }
        rule.waitForIdle()
        node("confirmCube:matches").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
        rule.waitForIdle()
        node("lesson:done").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.objectiveMet }
        rule.waitForIdle()
    }

    @Test
    fun story12_7_ac1_stage6IsRecordedAndCelebratedOnlyAfterYesItsSolved() {
        val written = mutableListOf<ProgressEvent>()
        val host = recordingHost(written)
        var closed = 0
        meetStage6ObjectiveInPhysicalMode(host = host, onClose = { closed++ })

        assertTrue(host.state.value.physicalSolveUnconfirmed)
        assertTrue(written.none { it is ProgressEvent.LessonCompleted })
        node("milestone:overlay").assertDoesNotExist()

        node("lesson:yesItsSolved").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.physicalSolveConfirmed }
        rule.waitForIdle()

        assertEquals(1, written.count { it is ProgressEvent.LessonCompleted })
        node("milestone:overlay").assertExists()
        assertEquals(0, closed)
    }

    @Test
    fun story12_7_ac1_yesOnAnAlreadyCompletedFinaleClosesOnce() {
        val written = mutableListOf<ProgressEvent>(ProgressEvent.LessonCompleted(LessonId("lesson-06-03")))
        val host = recordingHost(written)
        var closed = 0
        meetStage6ObjectiveInPhysicalMode(host = host, onClose = { closed++ })

        node("lesson:yesItsSolved").performScrollTo().performClick()
        rule.waitUntil(5_000) { closed > 0 }
        rule.waitForIdle()

        assertEquals(1, closed)
        assertTrue(host.state.value.physicalSolveConfirmed)
        node("milestone:overlay").assertDoesNotExist()
    }

    @Test
    fun story12_7_ac1_nextRecordsTheSolveWhenClosingUnmountsTheScreen() {
        val written = mutableListOf<ProgressEvent>()
        val host = recordingHost(written, Dispatchers.Default.limitedParallelism(1), writeDelayMillis = 200)
        var shown by mutableStateOf(true)
        setLessonContent(host, lessonEntry, onClose = { shown = false }, shown = { shown })
        physicalLessonObjectiveMet(host)

        node("lesson:next").performScrollTo().performClick()
        rule.waitUntil(5_000) { !shown }
        rule.waitForIdle()

        assertTrue(host.state.value.physicalSolveConfirmed)
        assertEquals(1, written.count { it is ProgressEvent.LessonCompleted })
    }

    @Test
    fun story12_7_ac1_yesConfirmsWhenClosingUnmountsTheScreen() {
        val written = mutableListOf<ProgressEvent>(ProgressEvent.LessonCompleted(LessonId("lesson-06-03")))
        val host = recordingHost(written, Dispatchers.Default.limitedParallelism(1), writeDelayMillis = 200)
        var shown by mutableStateOf(true)
        meetStage6ObjectiveInPhysicalMode(host = host, shown = { shown }, onClose = { shown = false })

        node("lesson:yesItsSolved").performScrollTo().performClick()
        rule.waitUntil(5_000) { !shown }
        rule.waitForIdle()

        assertTrue(host.state.value.physicalSolveConfirmed)
    }

    @Test
    fun story12_7_ac2_savedInstructionIsClearedWhenRestoredToConfirmYourCube() {
        var host = SessionHost(TickingClock())
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            CubentiaTheme {
                LessonScreen(
                    host = host,
                    entry = lessonEntry,
                    strings = ready.strings,
                    modes = TeachingMode.entries,
                    onClose = {},
                    cameraScan = FlagLevel.OFF,
                )
            }
        }
        rule.waitForIdle()
        rule.waitUntil(5_000) { host.state.value.isActive }
        physicalLessonObjectiveMet(host)
        node("toolbar:undo").performClick()
        rule.waitUntil(5_000) { !host.state.value.objectiveMet }
        rule.waitForIdle()
        node("lesson:physicalInstruction").assertTextEquals("Turn U' on your cube to undo.")

        // Process death: the saved instruction comes back over a host restored from its snapshot.
        host =
            SessionHost(TickingClock()).also { restored ->
                runBlocking {
                    restored.dispatch {
                        SessionEvent.SessionRestored(
                            snapshot = physicalSnapshot(),
                            lesson = lessonEntry.lesson,
                            tutorConfig = bundle.tutorConfig,
                            at = it,
                        )
                    }
                }
            }
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()

        assertEquals(SyncState.PHYSICAL_AWAITING_CONFIRM, host.state.value.syncState)
        node("lesson:physicalInstruction").assertTextEquals("")
    }

    @Test
    fun story12_7_ac1_reopeningBeforeConfirmingStillAsksAndRecordsOnce() {
        val written = mutableListOf<ProgressEvent>()
        val host = recordingHost(written)
        var shown by mutableStateOf(true)
        var closed = 0
        meetStage6ObjectiveInPhysicalMode(host = host, shown = { shown }, onClose = { closed++ })

        shown = false
        rule.waitForIdle()
        shown = true
        rule.waitForIdle()

        assertTrue(written.none { it is ProgressEvent.LessonCompleted })
        node("lesson:yesItsSolved").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.physicalSolveConfirmed }
        rule.waitForIdle()

        assertEquals(1, written.count { it is ProgressEvent.LessonCompleted })
        node("milestone:overlay").assertExists()
        assertEquals(0, closed)
    }

    @Test
    fun story12_7_ac1_reopeningAfterConfirmingWritesNothingFurther() {
        val written = mutableListOf<ProgressEvent>()
        val host = recordingHost(written)
        var shown by mutableStateOf(true)
        meetStage6ObjectiveInPhysicalMode(host = host, shown = { shown })
        node("lesson:yesItsSolved").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.physicalSolveConfirmed }
        rule.waitForIdle()
        assertEquals(1, written.count { it is ProgressEvent.LessonCompleted })

        shown = false
        rule.waitForIdle()
        shown = true
        rule.waitForIdle()

        assertTrue(host.state.value.physicalSolveConfirmed)
        assertEquals(1, written.count { it is ProgressEvent.LessonCompleted })
        node("milestone:overlay").assertDoesNotExist()
    }

    @Test
    fun story12_7_ac2_instructionIsEmptyAfterLeavingAndReturningToPhysicalMode() {
        val host = SessionHost(TickingClock())
        setLessonContent(host, lessonEntry)
        physicalLessonObjectiveMet(host)
        node("toolbar:undo").performClick()
        rule.waitUntil(5_000) { !host.state.value.objectiveMet }
        rule.waitForIdle()
        node("lesson:physicalInstruction").assertTextEquals("Turn U' on your cube to undo.")

        node("lesson:physicalModeSwitch").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.VIRTUAL }
        rule.waitForIdle()
        node("lesson:physicalModeSwitch").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_AWAITING_CONFIRM }
        rule.waitForIdle()

        node("lesson:physicalInstruction").assertTextEquals("")
    }

    @Test
    fun story12_7_ac1_reopenedLessonKeepsTheSolveUnconfirmedInVirtualMode() {
        val written = mutableListOf<ProgressEvent>()
        val host = recordingHost(written)
        var shown by mutableStateOf(true)
        meetStage6ObjectiveInPhysicalMode(host = host, shown = { shown })

        node("lesson:physicalModeSwitch").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.VIRTUAL }
        rule.waitForIdle()
        node("milestone:overlay").assertDoesNotExist()

        shown = false
        rule.waitForIdle()
        shown = true
        rule.waitForIdle()

        assertTrue(host.state.value.physicalSolveUnconfirmed)
        node("milestone:overlay").assertDoesNotExist()
        assertTrue(written.none { it is ProgressEvent.LessonCompleted })
    }

    @Test
    fun story12_7_ac1_nextOnOtherLessonsConfirmsThenCloses() {
        val written = mutableListOf<ProgressEvent>()
        val host = recordingHost(written)
        var closed = 0
        setLessonContent(host, lessonEntry, onClose = { closed++ })
        physicalLessonObjectiveMet(host)

        assertTrue(written.none { it is ProgressEvent.LessonCompleted })
        assertEquals(0, closed)

        node("lesson:next").performScrollTo().performClick()
        rule.waitUntil(5_000) { closed == 1 }

        assertTrue(host.state.value.physicalSolveConfirmed)
        assertEquals(1, written.count { it is ProgressEvent.LessonCompleted })
    }

    @Test
    fun story12_7_ac2_undoShowsThePoliteInstruction_andRedoStaysDisabled() {
        val host = SessionHost(TickingClock())
        setLessonContent(host, lessonEntry)
        physicalLessonObjectiveMet(host)

        node("lesson:physicalInstruction").assertTextEquals("")
        node("toolbar:undo").assertIsEnabled().performClick()
        rule.waitUntil(5_000) { !host.state.value.objectiveMet }
        rule.waitForIdle()

        node("lesson:physicalInstruction").assertTextEquals("Turn U' on your cube to undo.")
        assertEquals(
            LiveRegionMode.Polite,
            node("lesson:physicalInstruction").fetchSemanticsNode().config[SemanticsProperties.LiveRegion],
        )
        assertTrue(host.state.value.history.canRedo)
        node("toolbar:redo").assertIsNotEnabled()

        // The next action takes the instruction away.
        node("lesson:done").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.objectiveMet }
        rule.waitForIdle()
        node("lesson:physicalInstruction").assertTextEquals("")
    }
}
