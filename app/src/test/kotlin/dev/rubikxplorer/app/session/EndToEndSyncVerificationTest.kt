package dev.rubikxplorer.app.session

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Robolectric execution of Story 8.6 AC 2 (NFR-4) end-to-end sync scenario.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class EndToEndSyncVerificationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun endToEndSyncVerificationAcrossModesAndLifecycle() =
        runBlocking {
            endToEndSyncScenario(RuntimeEnvironment.getApplication(), composeRule)
        }
}
