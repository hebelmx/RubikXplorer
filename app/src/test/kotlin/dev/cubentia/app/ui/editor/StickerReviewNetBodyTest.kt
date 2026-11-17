package dev.cubentia.app.ui.editor

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.cubentia.app.ui.theme.CubeTokens
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.app.ui.theme.DarkTokens
import dev.cubentia.app.ui.theme.LightTokens
import dev.cubentia.domain.cube.CubeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w900dp-h891dp")
class StickerReviewNetBodyTest {
    @get:Rule val rule = createComposeRule()

    private fun show(
        dark: Boolean,
        face: Int?,
    ) = rule.setContent {
        CubentiaTheme(darkTheme = dark) {
            StickerReviewNet(
                facelets = CubeState.SOLVED.toFacelets(),
                selected = null,
                invalid = emptySet(),
                onStickerClick = {},
                modifier = Modifier.size(if (face == null) 600.dp else 300.dp),
                face = face,
            )
        }
    }

    private fun net() = rule.onNodeWithTag("reviewNet").fetchSemanticsNode().config

    @Test fun `the net is drawn on the cubie-body with a hairline outline in light theme`() {
        show(dark = false, face = null)
        assertEquals(CubeTokens.cubieBody, net()[CubeBodyColorKey])
        assertEquals(LightTokens.hairline, net()[CubeBodyOutlineKey])
    }

    @Test fun `a single face is drawn on the cubie-body`() {
        show(dark = false, face = 0)
        assertEquals(CubeTokens.cubieBody, net()[CubeBodyColorKey])
    }

    @Test fun `the body outline separates it from the dark page background`() {
        show(dark = true, face = 0)
        assertEquals(CubeTokens.cubieBody, net()[CubeBodyColorKey])
        assertEquals(DarkTokens.hairline, net()[CubeBodyOutlineKey])
        assertNotEquals(DarkTokens.surfaceBase, net()[CubeBodyOutlineKey])
    }
}
