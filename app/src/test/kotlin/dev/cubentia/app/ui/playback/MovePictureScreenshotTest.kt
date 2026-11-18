package dev.cubentia.app.ui.playback

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.preferences.PreferenceProvider
import dev.cubentia.app.preferences.PreferenceStore
import dev.cubentia.app.render.Net2DRenderer
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.application.preference.Preference
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.playback.MovePicture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MovePictureScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val samples = listOf("R", "R'", "R2", "F", "B", "M", "Rw", "x", "S", "U", "U2")
    private val decorative = SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription)

    @Test fun lightMovePictures() = checkSamples(dark = false)

    @Test fun darkMovePictures() = checkSamples(dark = true)

    private fun checkSamples(dark: Boolean) {
        var ink = Color.Unspecified
        var surface = Color.Unspecified
        rule.setContent {
            CubentiaTheme(darkTheme = dark) {
                ink = RubikTheme.tokens.inkPrimary
                surface = RubikTheme.tokens.surfaceBase
                Column(Modifier.background(surface)) {
                    samples.chunked(3).forEach { row ->
                        Row {
                            row.forEach { token ->
                                val move = parsed(if (token == "Rw") "r" else token).moves.single()
                                MovePictureView(MovePicture.of(move), Modifier.size(120.dp, 96.dp).testTag("sample:$token"))
                            }
                        }
                    }
                }
            }
        }

        val images =
            samples.associateWith { token ->
                val node = rule.onNodeWithTag("sample:$token").assert(decorative)
                node.captureToImage().toPixelMap().also { pixels ->
                    assertTrue("Ink in $token", pixels.inkCount(0..29, 0..23, ink) > 30)
                }
            }
        val r = images.getValue("R")
        val prime = images.getValue("R'")
        assertTrue(r.at(19f, 12f).nearColor(surface).not())
        assertTrue(r.at(7f, 12f).nearColor(surface))
        assertTrue(r.at(13f, 12f).nearColor(surface))
        assertTrue(r.inkCount(15..20, 5..8, ink) > prime.inkCount(15..20, 5..8, ink))
        assertTrue(prime.inkCount(15..20, 16..19, ink) > r.inkCount(15..20, 16..19, ink))
        assertEquals(0, r.inkCount(23..29, 0..9, ink))
        assertTrue(images.getValue("R2").inkCount(23..29, 0..9, ink) > 0)

        val front = images.getValue("F")
        val back = images.getValue("B")
        assertTrue(!front.at(12f, 12f).nearColor(surface))
        assertTrue(back.at(12f, 12f).nearColor(surface))
        assertTrue(back.at(9f, 12f).nearColor(ink))
        assertTrue(front.inkCount(4..20, 4..20, ink) > back.inkCount(4..20, 4..20, ink))
        assertTrue(!images.getValue("M").at(13f, 12f).nearColor(surface))
        assertTrue(!images.getValue("Rw").at(13f, 12f).nearColor(surface))
        assertTrue(images.getValue("x").at(1.5f, 12f).nearColor(ink))
        // S is drawn from above: the "top" tick sits on the far edge, above the grid.
        assertTrue(images.getValue("S").inkCount(10..13, 0..1, ink) > 0)
        assertEquals(0, images.getValue("R").inkCount(10..13, 0..1, ink))
        // A half turn's 2 sits in the clear margin right of the grid, never over it.
        val u = images.getValue("U")
        val u2 = images.getValue("U2")
        assertEquals(0, u.inkCount(23..29, 0..23, ink))
        assertTrue(u2.inkCount(23..29, 0..23, ink) > 0)
        assertEquals(u.inkCount(4..20, 4..20, ink), u2.inkCount(4..20, 4..20, ink))
    }

    @Test fun transportRendersPicturesAndUpdatesWhenPreferenceChanges() {
        val store = PreferenceStore()
        val moves = parsed("R U").moves
        val start = CubeState.SOLVED
        val state = NetPlayback(start, moves, true, Net2DRenderer(start.toFacelets()))
        val strings = ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)
        var ink = Color.Unspecified
        rule.setContent {
            CubentiaTheme {
                ink = RubikTheme.tokens.inkPrimary
                PreferenceProvider(store) {
                    PlaybackTransport(state, strings, onScrub = state::scrubTo, modifier = Modifier.testTag("transport"))
                }
            }
        }
        val picture = rule.onNodeWithTag("playback:picture").assert(decorative)
        val chipPicture = rule.onNodeWithTag("playback:chipPicture:0", useUnmergedTree = true).assert(decorative)
        val transport = rule.onNodeWithTag("transport")
        val before = transport.captureToImage()
        assertTrue(picture.captureToImage().toPixelMap().inkCount(0..29, 0..23, ink) > 0)
        chipPicture.assertExists()
        val strip = rule.onNodeWithTag("playback:strip").fetchSemanticsNode()
        val scrub = rule.onNodeWithTag("playback:scrub").fetchSemanticsNode()
        assertTrue(strip.boundsInRoot.bottom <= scrub.boundsInRoot.top)
        assertTrue(
            picture.fetchSemanticsNode().boundsInRoot.right <=
                rule
                    .onNodeWithTag("playback:move")
                    .fetchSemanticsNode()
                    .boundsInRoot.left,
        )
        val selected = rule.onNodeWithTag("playback:chip:0").captureToImage().toPixelMap()
        val unselected = rule.onNodeWithTag("playback:chip:1").captureToImage().toPixelMap()
        assertTrue(selected[selected.width / 2, 0].nearColor(ink))
        assertTrue(!unselected[unselected.width / 2, 0].nearColor(ink))
        rule.runOnIdle { store.update(Preference.MovePictures(false)) }
        picture.assertDoesNotExist()
        chipPicture.assertDoesNotExist()
        rule.onNodeWithTag("playback:strip").assertExists()
        assertTrue(transport.captureToImage().height < before.height)
        rule.runOnIdle { store.reset() }
        picture.assertExists()
        chipPicture.assertExists()
    }

    private fun parsed(notation: String) = Notation.parse(notation) as NotationResult.Parsed

    private fun PixelMap.at(
        x: Float,
        y: Float,
    ): Color = this[(x * width / 30f).toInt(), (y * height / 24f).toInt()]

    private fun PixelMap.inkCount(
        xs: IntRange,
        ys: IntRange,
        ink: Color,
    ): Int {
        val cols = (xs.first * width / 30f).roundToInt() until ((xs.last + 1) * width / 30f).roundToInt()
        val rows = (ys.first * height / 24f).roundToInt() until ((ys.last + 1) * height / 24f).roundToInt()
        return rows.sumOf { y -> cols.count { x -> this[x, y].nearColor(ink) } }
    }

    private fun Color.nearColor(other: Color): Boolean = abs(red - other.red) + abs(green - other.green) + abs(blue - other.blue) < 0.24f
}
