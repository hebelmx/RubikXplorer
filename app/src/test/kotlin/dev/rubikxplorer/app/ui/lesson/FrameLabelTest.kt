package dev.rubikxplorer.app.ui.lesson

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityRecord
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import dev.rubikxplorer.application.session.SyncState
import dev.rubikxplorer.domain.tutor.Certainty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

    @Test fun physicalModeDisplaysExpectedBadgeWithDashedBorderAndSuppressedCertainty() {
        val syncState = mutableStateOf(SyncState.PHYSICAL_PREDICTED)
        rule.setContent { FrameLabel(Certainty.VERIFIED, syncState.value) }

        rule.onNodeWithTag("stage:frame").assertTextEquals("EXPECTED")
        var config = rule.onNodeWithTag("stage:frame").fetchSemanticsNode().config
        assertTrue(config[DashedBorderKey])
        assertNull(config.getOrNull(SemanticsProperties.StateDescription))
        assertNull(config.getOrNull(SemanticsProperties.LiveRegion))

        rule.runOnIdle { syncState.value = SyncState.PHYSICAL_AWAITING_CONFIRM }
        rule.onNodeWithTag("stage:frame").assertTextEquals("EXPECTED")
        config = rule.onNodeWithTag("stage:frame").fetchSemanticsNode().config
        assertTrue(config[DashedBorderKey])
        assertNull(config.getOrNull(SemanticsProperties.StateDescription))
        assertNull(config.getOrNull(SemanticsProperties.LiveRegion))
    }

    @Test fun modeSwitchTransitionsFrameLabelBetweenVirtualAndExpected() {
        val syncState = mutableStateOf(SyncState.VIRTUAL)
        rule.setContent { FrameLabel(Certainty.VERIFIED, syncState.value) }

        rule.onNodeWithTag("stage:frame").assertTextEquals("VIRTUAL")
        var config = rule.onNodeWithTag("stage:frame").fetchSemanticsNode().config
        assertEquals("Verified", config[SemanticsProperties.StateDescription])
        assertEquals(LiveRegionMode.Polite, config[SemanticsProperties.LiveRegion])
        assertNull(config.getOrNull(DashedBorderKey))

        rule.runOnIdle { syncState.value = SyncState.PHYSICAL_PREDICTED }
        rule.onNodeWithTag("stage:frame").assertTextEquals("EXPECTED")
        config = rule.onNodeWithTag("stage:frame").fetchSemanticsNode().config
        assertTrue(config[DashedBorderKey])
        assertNull(config.getOrNull(SemanticsProperties.StateDescription))
        assertNull(config.getOrNull(SemanticsProperties.LiveRegion))

        rule.runOnIdle { syncState.value = SyncState.VIRTUAL }
        rule.onNodeWithTag("stage:frame").assertTextEquals("VIRTUAL")
        config = rule.onNodeWithTag("stage:frame").fetchSemanticsNode().config
        assertEquals("Verified", config[SemanticsProperties.StateDescription])
        assertEquals(LiveRegionMode.Polite, config[SemanticsProperties.LiveRegion])
        assertNull(config.getOrNull(DashedBorderKey))
    }

    /**
     * Drives the real Compose View hierarchy with an accessibility service enabled and captures
     * what reaches the platform AccessibilityManager: each Certainty change must send a
     * TYPE_WINDOW_CONTENT_CHANGED (state-description) event whose source node is the Polite
     * live-region frame label carrying the new Certainty, which is what TalkBack announces.
     */
    @Test fun eachCertaintyChangeSendsAnAnnounceableAccessibilityEvent() {
        val manager =
            ApplicationProvider
                .getApplicationContext<Context>()
                .getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val shadow = shadowOf(manager)
        shadow.setEnabled(true)
        shadow.setTouchExplorationEnabled(true)
        shadow.setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo()))
        val certainty = mutableStateOf(Certainty.VERIFIED)
        lateinit var host: View
        rule.setContent {
            host = LocalView.current
            FrameLabel(certainty.value)
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        val labelId = rule.onNodeWithTag("stage:frame").fetchSemanticsNode().id
        listOf(Certainty.LIKELY to "Likely", Certainty.UNKNOWN to "Unknown").forEach { (value, description) ->
            val before = shadow.sentAccessibilityEvents.size
            rule.runOnIdle { certainty.value = value }
            rule.mainClock.advanceTimeBy(1_000)
            rule.waitForIdle()
            val sent = shadow.sentAccessibilityEvents.drop(before)
            val announced =
                sent.filter {
                    it.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                        it.contentChangeTypes and ANNOUNCED_CHANGES != 0 &&
                        it.sourceVirtualId() == labelId
                }
            assertTrue("no announceable frame-label event for $value: $sent", announced.isNotEmpty())
            val node = rule.runOnIdle { host.accessibilityNodeProvider.createAccessibilityNodeInfo(labelId) }!!
            assertEquals(description, node.stateDescription?.toString())
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, node.liveRegion)
        }
    }

    /** The virtual descendant id packed in the upper 32 bits of the record's hidden source node id. */
    private fun AccessibilityEvent.sourceVirtualId(): Int =
        ((AccessibilityRecord::class.java.getMethod("getSourceNodeId").invoke(this) as Long) shr 32).toInt()

    private companion object {
        const val ANNOUNCED_CHANGES =
            AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION or
                AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE or
                AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT
    }
}
