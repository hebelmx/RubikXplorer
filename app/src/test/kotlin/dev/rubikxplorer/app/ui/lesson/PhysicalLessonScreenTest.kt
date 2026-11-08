package dev.rubikxplorer.app.ui.lesson

import android.app.Application
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.rubikxplorer.app.content.CatalogEntry
import dev.rubikxplorer.app.content.CatalogState
import dev.rubikxplorer.app.session.SessionHost
import dev.rubikxplorer.app.session.TickingClock
import dev.rubikxplorer.app.ui.theme.RubikXplorerTheme
import dev.rubikxplorer.application.session.SessionEvent
import dev.rubikxplorer.application.session.SyncState
import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.content.ContentLoader
import dev.rubikxplorer.domain.content.ContentSource
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.NotationResult
import dev.rubikxplorer.domain.cube.Turn
import dev.rubikxplorer.domain.recognition.PhysicalObservation
import dev.rubikxplorer.domain.tutor.Certainty
import dev.rubikxplorer.domain.tutor.TeachingMode
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
    ) {
        rule.setContent {
            RubikXplorerTheme {
                LessonScreen(
                    host = host,
                    entry = entry,
                    strings = ready.strings,
                    modes = TeachingMode.entries,
                    onClose = {},
                )
            }
        }
        rule.waitForIdle()
        rule.waitUntil(5_000) { host.state.value.isActive }
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
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
        rule.waitForIdle()

        // Editor dismisses, transitions to PHYSICAL_PREDICTED
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
        val milestoneEntry = ready.lessons.single { it.lesson.id.value == "lesson-06-03" }
        val host = SessionHost(TickingClock())
        setLessonContent(host, milestoneEntry)

        // Enter physical mode and confirm match
        node("lesson:physicalModeSwitch").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_AWAITING_CONFIRM }
        rule.waitForIdle()
        node("confirmCube:matches").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
        rule.waitForIdle()

        val lesson = milestoneEntry.lesson
        // Step 1 moves for lesson-06-03
        val step1Moves = (Notation.parse(lesson.steps[0].moves) as NotationResult.Parsed).moves
        step1Moves.forEach { move ->
            runBlocking { host.dispatch { SessionEvent.PhysicalConfirmed(move, it) } }
        }
        runBlocking { host.dispatch(SessionEvent::StepAdvanced) }

        // Step 2 moves for lesson-06-03
        val step2Moves = (Notation.parse(lesson.steps[1].moves) as NotationResult.Parsed).moves
        step2Moves.forEach { move ->
            runBlocking { host.dispatch { SessionEvent.PhysicalConfirmed(move, it) } }
        }
        rule.waitUntil(5_000) {
            host.state.value.decision
                ?.satisfied == true
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

        // Real milestone overlay and burst must NOT exist on milestone-eligible lesson in physical mode
        node("milestone:overlay").assertDoesNotExist()
        node("milestone:burst").assertDoesNotExist()
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
    fun story8_3_ac2_enteredMoveDiagnosedAsEvidence_certaintyUpdates() {
        val host = SessionHost(TickingClock())
        setLessonContent(host)

        // Enter physical mode and confirm match
        node("lesson:physicalModeSwitch").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_AWAITING_CONFIRM }
        rule.waitForIdle()
        node("confirmCube:matches").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
        rule.waitForIdle()

        // Open overflow menu and report "I'm not sure what I did"
        node("lesson:overflowMenu").performScrollTo().performClick()
        rule.waitForIdle()
        node("lesson:menuNotSure").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_MISMATCH }
        rule.waitForIdle()

        // Chip shows ? Unknown initially
        node("certainty:icon").assertTextEquals("?")
        node("certainty:label").assertTextEquals("Unknown")

        // In Rung 1, enter move key U' (counter-clockwise U)
        node("moveKey:U'").performClick()
        rule.waitUntil(5_000) { host.state.value.lastDiagnosis != null }
        rule.waitForIdle()

        // Diagnosis was evaluated using entered move as evidence
        val diagnosis = host.state.value.lastDiagnosis
        assertNotNull(diagnosis)
        // Certainty chip updates to Verified or Likely
        node("certainty:chip").assertExists()
        val certainty =
            host.state.value.decision
                ?.certainty
        assertTrue(certainty == Certainty.VERIFIED || certainty == Certainty.LIKELY)
        if (certainty == Certainty.VERIFIED) {
            node("certainty:icon").assertTextEquals("✓")
            node("certainty:label").assertTextEquals("Verified")
        } else {
            node("certainty:icon").assertTextEquals("≈")
            node("certainty:label").assertTextEquals("Likely")
            node("mismatch:candidate").assertExists()
        }
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
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
        rule.waitForIdle()

        // Becomes Full observation
        val observation = host.state.value.observed
        assertTrue(observation is PhysicalObservation.Full)
        assertEquals(disturbedState, (observation as PhysicalObservation.Full).state)

        // Guided recovery is offered
        assertTrue(host.state.value.canGuideBack)
        node("lesson:recovery:guideMeBack").assertExists()
    }
}
