package dev.cubentia.app.render

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.cubentia.app.ui.theme.CubentiaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w900dp-h891dp")
class Net2DRendererRtlTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `all sticker positions stay fixed under RTL`() {
        var direction by mutableStateOf(LayoutDirection.Ltr)
        val renderer = Net2DRenderer()
        rule.setContent {
            CubentiaTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    renderer.Content(RenderStyle(), Modifier.size(600.dp))
                }
            }
        }

        fun positions() =
            (0 until 54).map {
                rule
                    .onNodeWithTag("sticker:$it")
                    .fetchSemanticsNode()
                    .positionInRoot.x
            }
        val ltr = positions()

        rule.runOnIdle { direction = LayoutDirection.Rtl }
        assertEquals(ltr, positions())
    }
}
