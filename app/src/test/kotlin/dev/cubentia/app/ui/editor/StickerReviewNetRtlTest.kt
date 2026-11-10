package dev.cubentia.app.ui.editor

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.domain.cube.CubeState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w900dp-h891dp")
class StickerReviewNetRtlTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `all sticker positions stay fixed under RTL`() {
        var direction by mutableStateOf(LayoutDirection.Ltr)
        rule.setContent {
            CubentiaTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    StickerReviewNet(
                        facelets = CubeState.SOLVED.toFacelets(),
                        selected = null,
                        invalid = emptySet(),
                        onStickerClick = {},
                        modifier = Modifier.size(600.dp),
                    )
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
        assertEquals(Role.Button, rule.onNodeWithTag("sticker:0").fetchSemanticsNode().config[SemanticsProperties.Role])
        rule.runOnIdle { direction = LayoutDirection.Rtl }
        assertEquals(ltr, positions())
    }

    @Test fun `single-face sticker positions stay fixed under RTL`() {
        var direction by mutableStateOf(LayoutDirection.Ltr)
        var face by mutableStateOf(0)
        rule.setContent {
            CubentiaTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    StickerReviewNet(
                        facelets = CubeState.SOLVED.toFacelets(),
                        selected = null,
                        invalid = emptySet(),
                        onStickerClick = {},
                        modifier = Modifier.size(300.dp),
                        face = face,
                    )
                }
            }
        }

        fun positions(f: Int) =
            (f * 9 until (f + 1) * 9).map {
                rule
                    .onNodeWithTag("sticker:$it")
                    .fetchSemanticsNode()
                    .positionInRoot.x
            }
        for (f in 0 until 6) {
            rule.runOnIdle {
                face = f
                direction = LayoutDirection.Ltr
            }
            val ltr = positions(f)
            rule.runOnIdle { direction = LayoutDirection.Rtl }
            assertEquals("face $f", ltr, positions(f))
        }
    }
}
