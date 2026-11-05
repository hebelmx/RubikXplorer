package dev.rubikxplorer.app.ui.lesson

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.rubikxplorer.domain.tutor.Certainty
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FrameLabelTest {
    @get:Rule val rule = createComposeRule()

    @Test fun certaintyChangesUpdateThePoliteFrameLabel() {
        val certainty = mutableStateOf(Certainty.VERIFIED)
        rule.setContent { FrameLabel(certainty.value) }
        listOf(
            Certainty.VERIFIED to "Verified",
            Certainty.LIKELY to "Likely",
            Certainty.UNKNOWN to "Unknown",
        ).forEach { (value, description) ->
            rule.runOnIdle { certainty.value = value }
            val config = rule.onNodeWithTag("stage:frame").fetchSemanticsNode().config
            assertEquals(description, config[SemanticsProperties.StateDescription])
            assertEquals(LiveRegionMode.Polite, config[SemanticsProperties.LiveRegion])
        }
    }
}
