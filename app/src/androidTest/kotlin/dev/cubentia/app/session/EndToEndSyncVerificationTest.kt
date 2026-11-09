package dev.cubentia.app.session

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented execution of Story 8.6 AC 2 (NFR-4) end-to-end sync scenario.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndSyncVerificationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun endToEndSyncVerificationAcrossModesAndLifecycle() =
        runBlocking {
            endToEndSyncScenario(InstrumentationRegistry.getInstrumentation().targetContext, composeRule)
        }
}
