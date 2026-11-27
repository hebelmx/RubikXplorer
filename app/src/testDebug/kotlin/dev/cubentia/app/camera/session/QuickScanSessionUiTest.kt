package dev.cubentia.app.camera.session

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import dev.cubentia.app.camera.ContinuousFrameTap
import dev.cubentia.app.camera.ContinuousModelIdentity
import dev.cubentia.app.camera.QuickScanSessionHost
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.domain.recognition.continuous.ContinuousScanExport
import dev.cubentia.domain.recognition.continuous.ContinuousScanHandoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.util.concurrent.Executor

/** Story 10.34: the session overlay's Rec control, indicator, sheet and notice, as the operator sees them (W1, W4, W9). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class QuickScanSessionUiTest {
    @get:Rule val rule = createComposeRule()

    @get:Rule val temp = TemporaryFolder()

    private class Host : QuickScanSessionHost {
        var tap: ContinuousFrameTap? = null
        override val liveScanShown = true
        override val hostDialogShown = false
        override val modelIdentity: ContinuousModelIdentity? = null

        override fun startCapture(
            tap: ContinuousFrameTap,
            started: () -> Unit,
        ): Boolean {
            this.tap = tap
            started()
            return true
        }

        override fun export(consume: (ContinuousScanExport) -> Unit): Boolean {
            consume(ContinuousScanExport.Partial(ContinuousScanHandoff(emptyMap())))
            return true
        }

        override fun resetMap() = Unit

        override fun startBaseline(
            tap: ContinuousFrameTap,
            started: () -> Unit,
        ) = false

        override fun endBaseline(ended: () -> Unit) = ended()

        override fun releaseHandOff() = Unit
    }

    @Test
    fun recThenStopOpensTheSheetAndSaveShowsTheNotice() {
        val inline = Executor { it.run() }
        val controller =
            QuickScanSessionController(
                host = Host(),
                writers = DebugContinuousSessionWriterFactory({ temp.root }, "c".repeat(40), "2.3.2"),
                deviceModel = "TB128XU",
                deviceManufacturer = "LENOVO",
                io = inline,
                main = inline,
                clockMs = { 0L },
                now = { LocalDateTime.of(2026, 11, 26, 9, 15, 2) },
            )
        rule.setContent { CubentiaTheme { Box(Modifier.fillMaxSize()) { controller.Overlay(this) } } }
        rule.onNodeWithTag("quickScan:recIndicator").assertDoesNotExist()
        rule.onNodeWithTag("quickScan:rec").assertTextEquals("Rec").performClick()
        rule.onNodeWithText("REC 0 · 0 skips").assertExists()
        rule.onNodeWithTag("quickScan:rec").assertTextEquals("Stop").performClick()
        // The sheet: Rec is hidden, the stop line, numbers and fields show.
        rule.onNodeWithTag("quickScan:rec").assertDoesNotExist()
        rule.onNodeWithText("Stopped: operator").assertExists()
        rule.onNodeWithText("No baseline yet").assertExists()
        // Answer 10: Back, an outside tap and a swipe down leave the sheet open and the session stopped.
        // Back reaches the sheet's own dialog window, which keeps it (shouldDismissOnBackPress = false).
        rule.runOnIdle {
            @Suppress("DEPRECATION")
            org.robolectric.shadows.ShadowDialog
                .getLatestDialog()
                .onBackPressed()
        }
        rule.waitForIdle()
        rule.onNodeWithTag("quickScan:sessionSheet").assertExists()
        rule
            .onNode(
                androidx.compose.ui.test
                    .isRoot() and
                    androidx.compose.ui.test
                        .hasAnyDescendant(
                            androidx.compose.ui.test
                                .hasTestTag("quickScan:sessionSheet"),
                        ),
            ).performTouchInput {
                click(
                    androidx.compose.ui.geometry
                        .Offset(5f, 5f),
                )
            }
        rule.onNodeWithTag("quickScan:sessionSheet").assertExists()
        rule.onNodeWithTag("quickScan:sessionSheet").performTouchInput { swipeDown() }
        rule.onNodeWithTag("quickScan:sessionSheet").assertExists()
        assertTrue(controller.phase is SessionPhase.Stopped)
        rule.onNodeWithText("stla").performClick()
        assertEquals("stla", controller.fields.cube)
        rule.onNodeWithTag("session:discard").performClick()
        rule.onNodeWithText("Discard this recording?").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithTag("session:save").performClick()
        rule.onNodeWithText("Saved 20261126-091502-tb128xu").assertExists()
        rule.onNodeWithTag("quickScan:rec").assertTextEquals("Rec")
        rule.onNodeWithTag("session:dismiss").performClick()
        rule.onNodeWithTag("quickScan:sessionNotice").assertDoesNotExist()
        assertTrue(temp.root.resolve("20261126-091502-tb128xu").isDirectory)
    }
}
