package dev.cubentia.app.ui.scan

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.render.StickerColorKey
import dev.cubentia.app.ui.a11y.assertClickablesAtLeast48dp
import dev.cubentia.app.ui.a11y.assertNoMidWordBreaks
import dev.cubentia.app.ui.a11y.assertNoTextOverflow
import dev.cubentia.app.ui.a11y.fontScale200
import dev.cubentia.app.ui.editor.StickerInvalidKey
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.recognition.ScanResult
import dev.cubentia.domain.recognition.ScanReview
import dev.cubentia.domain.recognition.StickerConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w640dp-h960dp")
class ScanReviewScreenTest {
    @get:Rule val rule = createComposeRule()
    private val strings = ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)
    private var review by mutableStateOf(
        ScanReview.from(
            ScanResult.Full(
                CubeState.SOLVED.toFacelets(),
                List(54) {
                    if (it == 7 || it == 20) StickerConfidence.UNCERTAIN else StickerConfidence.CONFIDENT
                },
            ),
        ),
    )
    private var accepted: CubeState? = null
    private var closed = false

    private fun launch(large: Boolean = false) {
        rule.setContent {
            CubentiaTheme {
                if (large) fontScale200 { screen() } else screen()
            }
        }
    }

    @androidx.compose.runtime.Composable private fun screen() {
        ScanReviewScreen(review, strings, { review = it }, { accepted = it }, { closed = true })
    }

    @Test fun `blank selected stickers paint with auto advance and accept only after validation`() {
        launch()
        rule.onNodeWithTag("sticker:7").assertIsSelected()
        assertNotEquals(
            rule.onNodeWithTag("sticker:0").fetchSemanticsNode().config[StickerColorKey],
            rule.onNodeWithTag("sticker:7").fetchSemanticsNode().config[StickerColorKey],
        )
        assertEquals("Color needed", rule.onNodeWithTag("sticker:7").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        rule.onNodeWithTag("review:accept").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("swatch:WHITE").performScrollTo().performClick()
        rule.onNodeWithTag("sticker:20").assertIsSelected()
        rule.onNodeWithTag("swatch:GREEN").performClick()
        rule
            .onNodeWithTag("review:accept")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(CubeState.SOLVED, accepted)
        rule.onNodeWithTag("review:close").performScrollTo().performClick()
        assertEquals(true, closed)
    }

    @Test fun `two touches correct a selected sticker and two whites identify their positions`() {
        review = ScanReview(CubeState.SOLVED.toFacelets().colors, null)
        launch()
        rule.onNodeWithTag("sticker:9").performClick()
        rule.onNodeWithTag("swatch:WHITE").performScrollTo().performClick()
        rule.onNodeWithTag("review:problem:0").performScrollTo().assertTextEquals("This corner shows two whites.")
        assertEquals(true, rule.onNodeWithTag("sticker:8").fetchSemanticsNode().config[StickerInvalidKey])
        assertEquals(true, rule.onNodeWithTag("sticker:9").fetchSemanticsNode().config[StickerInvalidKey])
        assertEquals(false, rule.onNodeWithTag("sticker:20").fetchSemanticsNode().config[StickerInvalidKey])
        rule.onNodeWithTag("review:accept").performScrollTo().assertIsNotEnabled()
        assertNull(accepted)
        rule.onNodeWithTag("swatch:RED").performScrollTo().performClick()
        rule.onNodeWithTag("review:accept").performScrollTo().assertIsEnabled()
    }

    @Test
    @Config(qualifiers = "w600dp-h960dp")
    fun `review at 200 percent has no broken words overflow or undersized controls`() {
        val colors =
            CubeState.SOLVED
                .toFacelets()
                .colors
                .toMutableList<Color?>()
                .also { it[9] = Color.WHITE }
        // Blanks, the selected-sticker line and a problem line all render at 200%.
        colors[7] = null
        colors[30] = null
        review = ScanReview(colors, 7)
        launch(large = true)
        rule.onNodeWithTag("review:problem:0").performScrollTo().assertTextEquals("This corner shows two whites.")
        rule.onNodeWithTag("review:selected").assertTextEquals("Selected: Blank sticker, up face, position 8")
        rule.assertNoMidWordBreaks()
        rule.assertNoTextOverflow("scan review at 200 percent")
        rule.assertClickablesAtLeast48dp("scan review")
        rule.onNodeWithTag("review:close").performScrollTo().performClick()
        assertEquals(true, closed)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `compact review shows one face and auto advance reveals the next blank face`() {
        launch()
        rule.onAllNodesWithTag("sticker:7").assertCountEquals(1)
        rule.onAllNodesWithTag("sticker:20").assertCountEquals(0)
        rule.onNodeWithTag("swatch:WHITE").performScrollTo().performClick()
        rule.onNodeWithTag("sticker:20").assertIsSelected()
        rule.onAllNodesWithTag("sticker:7").assertCountEquals(0)
        rule.onNodeWithTag("swatch:GREEN").performClick()
        rule.onNodeWithTag("review:accept").performScrollTo().assertIsEnabled()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `switching faces never lets the palette paint a hidden sticker`() {
        launch()
        rule.onNodeWithTag("review:selected").assertTextEquals("Selected: Blank sticker, up face, position 8")
        // A face with a blank moves the selection to that blank, and the change is announced.
        rule.onNodeWithTag("editor:face:2").performClick()
        rule.onNodeWithTag("sticker:20").assertIsSelected()
        rule.onNodeWithTag("review:selected").assertTextEquals("Selected: Blank sticker, front face, position 3")
        // A face with no blank keeps the selection on its hidden face; the palette then does nothing.
        rule.onNodeWithTag("editor:face:3").performClick()
        val before = review
        rule.onNodeWithTag("swatch:RED").performScrollTo().performClick()
        assertEquals(before, review)
    }
}
