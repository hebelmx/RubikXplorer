package dev.rubikxplorer.app.ui.settings
import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.rubikxplorer.app.content.LessonCatalog
import dev.rubikxplorer.app.preferences.PreferenceStore
import dev.rubikxplorer.app.preferences.PreferredTheme
import dev.rubikxplorer.app.session.SessionHost
import dev.rubikxplorer.app.session.TickingClock
import dev.rubikxplorer.app.session.testBundle
import dev.rubikxplorer.app.session.testFlags
import dev.rubikxplorer.app.ui.freecube.FreeCubeViewModel
import dev.rubikxplorer.app.ui.shell.AppShell
import dev.rubikxplorer.app.ui.theme.DarkTokens
import dev.rubikxplorer.app.ui.theme.RubikTheme
import dev.rubikxplorer.app.ui.theme.isDarkTheme
import dev.rubikxplorer.application.preference.AnimationSpeed
import dev.rubikxplorer.application.preference.Preferences
import dev.rubikxplorer.application.preference.ThemeChoice
import dev.rubikxplorer.domain.content.ContentLoadResult
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
class SettingsScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun themeChoiceFollowsTheSystemOnlyWhenSystem() {
        assertTrue(isDarkTheme(ThemeChoice.SYSTEM, systemDark = true))
        assertFalse(isDarkTheme(ThemeChoice.SYSTEM, systemDark = false))
        assertTrue(isDarkTheme(ThemeChoice.DARK, systemDark = false))
        assertFalse(isDarkTheme(ThemeChoice.LIGHT, systemDark = true))
    }

    @Test fun controlsThemeResetCancelAndBack() {
        val store = PreferenceStore()
        val host = SessionHost(TickingClock())
        val catalog = LessonCatalog.of(ContentLoadResult.Loaded(testBundle()))
        val cube = FreeCubeViewModel()
        lateinit var back: OnBackPressedDispatcher
        var dark = false
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            PreferredTheme(store) {
                dark = RubikTheme.tokens == DarkTokens
                AppShell(host, catalog, cube, false, testFlags(true), preferenceStore = store)
            }
        }
        rule.onNodeWithTag("nav:you").performClick()
        rule.onNodeWithTag("you:settings").performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("settings:screen").assertExists()
        rule.onNodeWithTag("settings:theme:dark").performScrollTo().performClick()
        rule.runOnIdle {
            assertTrue(dark)
            assertEquals(ThemeChoice.DARK, store.preferences.value.theme)
        }
        rule.onNodeWithTag("settings:glyphs").performScrollTo().performClick()
        rule.onNodeWithTag("settings:speed:fast").performScrollTo().performClick()
        rule.onNodeWithTag("settings:haptics").performScrollTo().performClick()
        rule.onNodeWithTag("settings:reduceMotion").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(Preferences(ThemeChoice.DARK, true, AnimationSpeed.FAST, false, true), store.preferences.value) }
        rule.onNodeWithTag("settings:reset").performScrollTo().performClick()
        rule.onNodeWithTag("settings:cancel").performClick()
        rule.runOnIdle { assertEquals(ThemeChoice.DARK, store.preferences.value.theme) }
        rule.onNodeWithTag("settings:reset").performScrollTo().performClick()
        rule.onNodeWithTag("settings:confirm").performClick()
        rule.runOnIdle {
            assertEquals(Preferences.DEFAULT, store.preferences.value)
            back.onBackPressed()
        }
        rule.onNodeWithTag("you:settings").assertExists()
        rule.onNodeWithTag("settings:screen").assertDoesNotExist()
    }
}
