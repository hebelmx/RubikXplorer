package dev.cubentia.app.ui.welcome

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.cubentia.app.preferences.PreferenceStore
import dev.cubentia.app.session.testFlags
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.application.port.FeatureFlag
import dev.cubentia.application.port.FeatureFlags
import dev.cubentia.application.port.FlagLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class WelcomeScreenTest {
    @get:Rule val rule = createComposeRule()

    private val preferences = PreferenceStore()

    @Test
    fun `welcome screen renders standard options and hides startFromCube when PLACEMENT flag is off`() {
        val flags = testFlags(placement = false)
        rule.setContent {
            CubentiaTheme {
                WelcomeScreen(
                    onImNew = {},
                    onCanAlreadySolve = {},
                    flags = flags,
                    preferenceStore = preferences,
                    abandoned = false,
                )
            }
        }

        rule.onNodeWithTag("welcome:title").assertIsDisplayed()
        rule.onNodeWithTag("welcome:imNew").assertIsDisplayed().assertTextContains("I'm new")
        rule.onNodeWithTag("welcome:canSolve").assertIsDisplayed().assertTextContains("I can already solve it")
        rule.onNodeWithTag("welcome:glyphs").assertIsDisplayed().assertTextContains("W Y R O G B")
        rule.onNodeWithTag("welcome:glyphOrder", useUnmergedTree = true).assertTextContains("W Y R O G B")
        rule.onNodeWithTag("welcome:startFromCube").assertDoesNotExist()
        rule.onNodeWithTag("welcome:abandoned").assertDoesNotExist()
    }

    @Test
    fun `welcome screen displays startFromCube when PLACEMENT flag is GA and clicking it triggers callback`() {
        val flags =
            object : FeatureFlags {
                override fun level(flag: FeatureFlag): FlagLevel = if (flag == FeatureFlag.PLACEMENT) FlagLevel.GA else FlagLevel.OFF
            }
        var startFromCubeClicked = false
        rule.setContent {
            CubentiaTheme {
                WelcomeScreen(
                    onImNew = {},
                    onCanAlreadySolve = {},
                    flags = flags,
                    preferenceStore = preferences,
                    onStartFromCube = { startFromCubeClicked = true },
                )
            }
        }

        rule
            .onNodeWithTag("welcome:startFromCube")
            .assertIsDisplayed()
            .assertTextContains("Start from my cube")
            .performClick()
        assertTrue(startFromCubeClicked)
    }

    @Test
    fun `clicking imNew triggers callback`() {
        var imNewClicked = false
        rule.setContent {
            CubentiaTheme {
                WelcomeScreen(
                    onImNew = { imNewClicked = true },
                    onCanAlreadySolve = {},
                    flags = testFlags(),
                    preferenceStore = preferences,
                )
            }
        }

        rule.onNodeWithTag("welcome:imNew").performClick()
        assertTrue(imNewClicked)
    }

    @Test
    fun `clicking canSolve triggers callback`() {
        var canSolveClicked = false
        rule.setContent {
            CubentiaTheme {
                WelcomeScreen(
                    onImNew = {},
                    onCanAlreadySolve = { canSolveClicked = true },
                    flags = testFlags(),
                    preferenceStore = preferences,
                )
            }
        }

        rule.onNodeWithTag("welcome:canSolve").performClick()
        assertTrue(canSolveClicked)
    }

    @Test
    fun `toggling color-blind glyphs switch updates preference store immediately`() {
        assertFalse(preferences.preferences.value.colorBlindGlyphs)
        rule.setContent {
            CubentiaTheme {
                WelcomeScreen(
                    onImNew = {},
                    onCanAlreadySolve = {},
                    flags = testFlags(),
                    preferenceStore = preferences,
                )
            }
        }

        rule.onNodeWithTag("welcome:glyphs").performClick()
        assertTrue(preferences.preferences.value.colorBlindGlyphs)

        rule.onNodeWithTag("welcome:glyphs").performClick()
        assertFalse(preferences.preferences.value.colorBlindGlyphs)
    }

    @Test
    fun `welcome screen displays abandoned banner when abandoned is true`() {
        rule.setContent {
            CubentiaTheme {
                WelcomeScreen(
                    onImNew = {},
                    onCanAlreadySolve = {},
                    flags = testFlags(),
                    preferenceStore = preferences,
                    abandoned = true,
                )
            }
        }

        rule.onNodeWithTag("welcome:abandoned").assertIsDisplayed().assertTextContains("Let's find where to start")
    }
}
