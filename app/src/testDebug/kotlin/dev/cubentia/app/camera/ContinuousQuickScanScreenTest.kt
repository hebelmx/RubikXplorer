package dev.cubentia.app.camera

import android.app.Application
import android.os.Build
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.preferences.LocalHaptics
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.continuous.ContinuousScanCue
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp-port")
class ContinuousQuickScanScreenTest {
    @get:Rule val rule = createComposeRule()
    private val strings = ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)

    @Test fun onlyTheCueAndIncreasingCountArePoliteLiveRegions() {
        val state = mutableStateOf(ContinuousQuickScanFlow())
        rule.setContent {
            CubentiaTheme {
                GuidedScanScreen(
                    GuidedScanPhase.Continuous,
                    strings,
                    GuidedScanActions(),
                    continuous = state.value,
                )
            }
        }
        val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        rule
            .onNodeWithTag("quickScan:count")
            .assertTextEquals("Sides mapped: 0/6")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        rule.onNodeWithTag("quickScan:cue").assertTextEquals("Bring the cube into view.").assert(polite)
        rule.onNodeWithTag("quickScan:torch").assertDoesNotExist()
        rule.onNodeWithTag("quickScan:faceByFace").assertIsEnabled()
        val cues =
            listOf(
                ContinuousScanCue.TurnRemaining(3),
                ContinuousScanCue.TiltAdjacent,
                ContinuousScanCue.KeepTurningRealign,
                ContinuousScanCue.TiltAwayLight,
                ContinuousScanCue.TiltAwayLightRemaining(3),
                ContinuousScanCue.ResolveConflict(Face.F),
                ContinuousScanCue.LayerTwistDetected,
                ContinuousScanCue.TiltRemainingStickers(42),
                ContinuousScanCue.AllVerified,
            )
        for (cue in cues) {
            rule.runOnIdle {
                state.value =
                    state.value.next(ContinuousFrame(testSnapshot(testHandoff(Face.entries.take(3)), cue), emptyList()), 0)
            }
            rule.onNodeWithTag("quickScan:cue").assertTextEquals(strings.resolve(cue.toText())).assert(polite)
            rule
                .onNodeWithTag(
                    "quickScan:announcement",
                ).assert(polite)
                .assertContentDescriptionEquals("Sides mapped: ${state.value.count}/6")
        }
        rule.runOnIdle { state.value = state.value.next(ContinuousFrame(testSnapshot(testHandoff(Face.entries.take(2))), emptyList()), 0) }
        rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 2/6")
        // The count fell: nothing is announced, and the old number is no longer readable beside the visible one.
        rule
            .onNodeWithTag("quickScan:announcement")
            .assert(polite)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        rule.runOnIdle { state.value = state.value.next(ContinuousFrame(testSnapshot(testHandoff(Face.entries.take(4))), emptyList()), 0) }
        rule.onNodeWithTag("quickScan:announcement").assertContentDescriptionEquals("Sides mapped: 4/6")
        assertTrue(
            rule
                .onNodeWithTag("quickScan:net")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .children
                .isEmpty(),
        )
    }

    @Test fun confirmAndRejectFallBackToALongPressBelowApi30() {
        val pulses = ContinuousQuickScanFlow.Pulse.entries
        assertEquals(
            listOf(HapticFeedbackType.KeyboardTap, HapticFeedbackType.Confirm, HapticFeedbackType.Reject),
            pulses.map { hapticFor(it, Build.VERSION_CODES.R) },
        )
        assertEquals(
            listOf(HapticFeedbackType.KeyboardTap, HapticFeedbackType.LongPress, HapticFeedbackType.LongPress),
            pulses.map { hapticFor(it, Build.VERSION_CODES.Q) },
        )
    }

    /** Plays the pulses of a count increase, a conflict, a twist and a completion with the haptics setting [enabled]. */
    private fun played(
        enabled: Boolean,
        sdk: Int,
    ): List<HapticFeedbackType> {
        val played = mutableListOf<HapticFeedbackType>()
        val haptics =
            object : HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                    played += hapticFeedbackType
                }
            }
        val pulses = Channel<ContinuousQuickScanFlow.Pulse>(Channel.UNLIMITED)
        rule.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics, LocalHaptics provides enabled) {
                ContinuousQuickScanHaptics(pulses, sdk)
            }
        }
        val three = testHandoff(Face.entries.take(3))
        val full = testHandoff(Face.entries)
        var state = ContinuousQuickScanFlow()
        val snapshots =
            listOf(
                testSnapshot(three),
                // The same snapshot again: no transition, no pulse.
                testSnapshot(three),
                testSnapshot(three, ContinuousScanCue.ResolveConflict(Face.F)),
                testSnapshot(three, ContinuousScanCue.ResolveConflict(Face.F)),
                testSnapshot(three, ContinuousScanCue.LayerTwistDetected),
                testSnapshot(full, ContinuousScanCue.AllVerified),
                testSnapshot(full, ContinuousScanCue.AllVerified),
            )
        for (snapshot in snapshots) {
            val before = state
            state = before.next(ContinuousFrame(snapshot, emptyList()), 0)
            state.pulsesSince(before).forEach { pulses.trySend(it) }
        }
        rule.waitForIdle()
        return played
    }

    @Test fun eachTransitionPlaysItsHapticOnce() {
        assertEquals(
            listOf(
                // 0 → 3 sides.
                HapticFeedbackType.KeyboardTap,
                // The cue becomes a conflict face, then a layer twist.
                HapticFeedbackType.Reject,
                HapticFeedbackType.Reject,
                // 3 → 6 sides, and all 54 verified.
                HapticFeedbackType.KeyboardTap,
                HapticFeedbackType.Confirm,
            ),
            played(enabled = true, sdk = Build.VERSION_CODES.R),
        )
    }

    @Test fun belowApi30TheConfirmAndRejectPulsesAreLongPresses() {
        assertEquals(
            listOf(
                HapticFeedbackType.KeyboardTap,
                HapticFeedbackType.LongPress,
                HapticFeedbackType.LongPress,
                HapticFeedbackType.KeyboardTap,
                HapticFeedbackType.LongPress,
            ),
            played(enabled = true, sdk = Build.VERSION_CODES.Q),
        )
    }

    @Test fun noHapticPlaysWithTheHapticsSettingOff() {
        assertTrue(played(enabled = false, sdk = Build.VERSION_CODES.R).isEmpty())
    }
}
