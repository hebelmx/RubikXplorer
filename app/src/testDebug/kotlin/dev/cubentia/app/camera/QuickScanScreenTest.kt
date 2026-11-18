package dev.cubentia.app.camera

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.GuidedScanPlan
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp-port")
class QuickScanScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun cornerCounterAndGuidedFallbackFitInPortrait() {
        val strings = ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)
        val phase = mutableStateOf<GuidedScanPhase>(GuidedScanPhase.CornerViewfinder(hold = 1))
        rule.setContent { CubentiaTheme { GuidedScanScreen(phase.value, strings, GuidedScanActions(), offerEnterColors = true) } }
        rule.onNodeWithTag("quickScan:outline").assertIsDisplayed()
        rule
            .onNodeWithTag("quickScan:counter")
            .assertTextEquals("1 / 2")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        rule.onNodeWithText("Hold white on top, green and red facing you").assertIsDisplayed()
        rule.onNodeWithTag("scanEnterColors").assertIsDisplayed()
        rule.runOnIdle { phase.value = GuidedScanPhase.CornerViewfinder(hold = 2) }
        rule.onNodeWithTag("quickScan:counter").assertTextEquals("2 / 2")
        rule.onNodeWithText("Now yellow on top, blue and orange facing you").assertIsDisplayed()
        rule.runOnIdle {
            phase.value = GuidedScanPhase.Viewfinder(step = GuidedScanPlan.reprompt(Face.F), again = RepromptProgress(1, 2), retake = true)
        }
        rule.onNodeWithTag("quickScan:counter").assertDoesNotExist()
        rule.onNodeWithTag("scanGrid").assertIsDisplayed()
        rule.onNodeWithTag("scanProgress").assertTextEquals("Again: face 1 of 2")
        rule.onNodeWithText("Show the green face again, with white on top.").assertIsDisplayed()
        rule.onNodeWithTag("scanNet").assertIsDisplayed()
        rule.onNodeWithTag("scanEnterColors").assertIsDisplayed()
    }
}
