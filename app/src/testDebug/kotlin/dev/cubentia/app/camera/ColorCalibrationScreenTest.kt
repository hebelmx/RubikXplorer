package dev.cubentia.app.camera

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.recognition.CalibrationSession
import dev.cubentia.domain.recognition.CellReading
import dev.cubentia.domain.recognition.Lab
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp-port")
class ColorCalibrationScreenTest {
    @get:Rule val rule = createComposeRule()
    private val strings = ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)

    @Test fun promptsSwatchKeepRetakeAndSaveFailure() {
        var phase by mutableStateOf(GuidedScanPhase.Calibration())
        rule.setContent {
            CubentiaTheme {
                GuidedScanScreen(
                    phase,
                    strings,
                    GuidedScanActions(
                        onKeep = { phase = phase.copy(session = phase.session.keep()) },
                        onRetake = { phase = phase.copy(session = phase.session.retake()) },
                    ),
                )
            }
        }
        rule.onNodeWithText("Show the green center.").assertIsDisplayed()
        assertEquals(
            LiveRegionMode.Polite,
            rule.onNodeWithTag("calibration:prompt").fetchSemanticsNode().config[SemanticsProperties.LiveRegion],
        )
        rule.onNodeWithTag("calibration:save").assertIsNotEnabled()
        rule.runOnIdle { phase = phase.copy(session = phase.session.offer(null)) }
        rule.onNodeWithText("Put a plain green sticker in the middle square.").assertIsDisplayed()
        rule.runOnIdle { phase = phase.copy(session = phase.session.offer(CellReading(Lab(55.0, -60.0, 40.0), true))) }
        rule.onNodeWithTag("calibration:swatch").assertIsDisplayed()
        rule.onNodeWithTag("calibration:retake").performClick()
        rule.onNodeWithTag("calibration:swatch").assertDoesNotExist()
        rule.onNodeWithText("Put a plain green sticker in the middle square.").assertIsDisplayed()
        rule.runOnIdle { phase = phase.copy(session = phase.session.offer(CellReading(Lab(55.0, -60.0, 40.0), true))) }
        rule.onNodeWithTag("calibration:keep").performClick()
        rule.onNodeWithText("Show the red center.").assertIsDisplayed()
        val labs =
            mapOf(
                Color.GREEN to Lab(55.0, -60.0, 40.0),
                Color.RED to Lab(45.0, 65.0, 45.0),
                Color.BLUE to Lab(40.0, 10.0, -50.0),
                Color.ORANGE to Lab(65.0, 45.0, 65.0),
                Color.WHITE to Lab(90.0, 0.0, 0.0),
                Color.YELLOW to Lab(85.0, -5.0, 80.0),
            )
        rule.runOnIdle {
            var session = phase.session
            while (session.current != null) session = session.offer(CellReading(labs.getValue(session.current!!), true)).keep()
            phase = phase.copy(session = session, saveFailed = true)
        }
        rule.onNodeWithTag("calibration:save").assertIsEnabled()
        rule.onNodeWithTag("calibration:save-failed").assertIsDisplayed()
    }

    @Test fun whitePromptResolvesUnderATurkishDefaultLocale() {
        // GH #158: the prompt key lowercases the color name; under tr, a locale-sensitive "WHITE" would become "whıte".
        val before = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        try {
            val labs =
                mapOf(
                    Color.GREEN to Lab(55.0, -60.0, 40.0),
                    Color.RED to Lab(45.0, 65.0, 45.0),
                    Color.BLUE to Lab(40.0, 10.0, -50.0),
                    Color.ORANGE to Lab(65.0, 45.0, 65.0),
                    Color.WHITE to Lab(90.0, 0.0, 0.0),
                    Color.YELLOW to Lab(85.0, -5.0, 80.0),
                )
            var session = CalibrationSession()
            repeat(labs.size) {
                if (session.current != Color.WHITE) session = session.offer(CellReading(labs.getValue(session.current!!), true)).keep()
            }
            assertEquals(Color.WHITE, session.current)
            var phase by mutableStateOf(GuidedScanPhase.Calibration(session = session))
            rule.setContent { CubentiaTheme { GuidedScanScreen(phase, strings, GuidedScanActions()) } }
            rule.onNodeWithTag("calibration:prompt").assertTextEquals("Show the white center.")
            rule.runOnIdle { phase = phase.copy(session = phase.session.offer(null)) }
            rule.onNodeWithTag("calibration:prompt").assertTextEquals("Put a plain white sticker in the middle square.")
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test fun calibrationShowsTheTorchToggleAndLocksItFromTheFirstKeptSwatch() {
        // Story 10.21: the corner view's toggle, top-end of the viewfinder; disabled (still shown) once a swatch is kept.
        var phase by mutableStateOf(GuidedScanPhase.Calibration())
        var torch by mutableStateOf<Boolean?>(true)
        var toggles = 0
        rule.setContent {
            CubentiaTheme {
                GuidedScanScreen(
                    phase,
                    strings,
                    GuidedScanActions(
                        onKeep = { phase = phase.copy(session = phase.session.keep()) },
                        onToggleTorch = { toggles++ },
                    ),
                    cornerTorch = torch,
                )
            }
        }
        rule.onNodeWithTag("quickScan:torch").assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithText("Torch on").assertIsDisplayed()
        rule.onNodeWithTag("quickScan:torch").performClick()
        assertEquals(1, toggles)
        rule.runOnIdle { phase = phase.copy(session = phase.session.offer(CellReading(Lab(55.0, -60.0, 40.0), true))) }
        rule.onNodeWithTag("quickScan:torch").assertIsEnabled()
        rule.onNodeWithTag("calibration:keep").performClick()
        rule.onNodeWithTag("quickScan:torch").assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithTag("quickScan:torch").performClick()
        assertEquals(1, toggles)
        // No flash unit: no control.
        rule.runOnIdle { torch = null }
        rule.onNodeWithTag("quickScan:torch").assertDoesNotExist()
    }
}
