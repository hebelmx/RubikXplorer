package dev.rubikxplorer.app.ui.lesson

import android.app.Application
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.rubikxplorer.app.render.RenderedArrow
import dev.rubikxplorer.app.render.RenderedArrowKey
import dev.rubikxplorer.app.render.RenderedStickersKey
import dev.rubikxplorer.app.render.StickerDimmedKey
import dev.rubikxplorer.app.render.StickerHighlightedKey
import dev.rubikxplorer.app.session.whiteCrossLesson
import dev.rubikxplorer.app.ui.theme.RubikXplorerTheme
import dev.rubikxplorer.domain.content.FaceletString
import dev.rubikxplorer.domain.cube.Color
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Facelets
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.NotationResult
import dev.rubikxplorer.domain.cube.Turn
import dev.rubikxplorer.domain.playback.Demonstration
import dev.rubikxplorer.domain.playback.DemonstrationResult
import dev.rubikxplorer.domain.playback.PlaybackSpeed
import dev.rubikxplorer.domain.predicate.Piece
import dev.rubikxplorer.domain.predicate.WhiteCrossSolved
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** AC 2–3: the white-cross Demonstration on the net, its transport, and reduce motion. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class DemonstrationPlayerTest {
    @get:Rule
    val rule = createComposeRule()

    // Preserving the cross, as a later Lesson would (AD-6), so the Keep emphasis has pieces to light.
    private val demonstration =
        (Demonstration.of(whiteCrossLesson().copy(preserve = WhiteCrossSolved)) as DemonstrationResult.Ready).demonstration
    private val entry = CubeState.SOLVED.apply(FaceTurn(Face.F, Turn.CLOCKWISE))
    private val whiteGreen = Piece(setOf(Color.WHITE, Color.GREEN))
    private val centers = Face.entries.map { it.ordinal * 9 + 4 }

    private lateinit var state: DemonstrationPlayerState

    private val resolver get() = RuntimeEnvironment.getApplication().contentResolver

    @After
    fun resetAnimatorScale() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    private fun show(
        reduceMotion: Boolean = false,
        demo: Demonstration = demonstration,
    ) {
        rule.setContent {
            RubikXplorerTheme {
                state = rememberDemonstrationPlayerState(demo, reduceMotion)
                DemonstrationPlayer(state, Modifier.fillMaxSize())
            }
        }
    }

    private fun moves(text: String) = (Notation.parse(text) as NotationResult.Parsed).moves

    /** The white-cross Lesson from SOLVED + [entry], with one step per entry of [steps]. */
    private fun demo(
        entry: String,
        vararg steps: String,
    ): Demonstration {
        val base = whiteCrossLesson()
        val lesson =
            base.copy(
                entryState = FaceletString.encode(CubeState.SOLVED.apply(moves(entry)).toFacelets()),
                steps = steps.map { base.steps.single().copy(moves = it) },
            )
        return (Demonstration.of(lesson) as DemonstrationResult.Ready).demonstration
    }

    private fun assertCounter(text: String) = rule.onNodeWithTag("playback:counter").assertTextEquals(text)

    /** The stickers whose highlight semantics are set, read against the drawn facelets' [pieces]. */
    private fun assertHighlighted(pieces: Set<Piece>) {
        val drawn = state.renderer.renderedFacelets()
        for (index in 0 until Facelets.SIZE) {
            val piece = Piece.at(drawn, index)
            assertEquals("highlighted $index", piece != null && piece in pieces, flag(StickerHighlightedKey, index))
        }
    }

    private fun flag(
        key: SemanticsPropertyKey<Boolean>,
        index: Int,
    ): Boolean {
        val draw = rule.onNodeWithTag("cube3d").fetchSemanticsNode().config[RenderedStickersKey][index]
        return when (key) {
            StickerDimmedKey -> draw.dimmed
            StickerHighlightedKey -> draw.highlighted
            else -> error("Unknown sticker flag")
        }
    }

    /** Every sticker is dimmed exactly when its piece is outside the step, and outlined exactly when it is the white-green edge. */
    private fun assertEmphasis(drawn: Facelets) {
        val lit =
            demonstration.steps
                .single()
                .focus.lit
        for (index in 0 until Facelets.SIZE) {
            val piece = Piece.at(drawn, index)
            assertEquals("dimmed $index", piece != null && piece !in lit, flag(StickerDimmedKey, index))
            assertEquals("highlighted $index", piece == whiteGreen, flag(StickerHighlightedKey, index))
        }
        centers.forEach { assertEquals(false, flag(StickerDimmedKey, it)) }
    }

    @Test
    fun `the step dims off-step pieces, keeps the cross lit and outlines the white-green edge`() {
        show()
        rule.waitForIdle()
        assertEquals(entry.toFacelets(), state.renderer.renderedFacelets())
        assertEmphasis(entry.toFacelets())
        assertEquals(RenderedArrow("F'", 1f), rule.onNodeWithTag("cube3d").fetchSemanticsNode().config[RenderedArrowKey])
        // Off the F layer and not a cross edge: the DB edge (34, 52) and the DBL corner (33, 42, 53).
        listOf(34, 52, 33, 42, 53).forEach { assertEquals(true, flag(StickerDimmedKey, it)) }
        // Keep: the white-red edge (5, 10) stays lit, without the outline.
        listOf(5, 10).forEach {
            assertEquals(false, flag(StickerDimmedKey, it))
            assertEquals(false, flag(StickerHighlightedKey, it))
        }
        rule.onNodeWithTag("playback:counter").assertTextEquals("0 / 1")
        rule.onNodeWithTag("playback:move").assertTextEquals("F'")
        rule.onNodeWithTag("playback:back").assertIsNotEnabled()

        rule.onNodeWithTag("playback:playPause").performClick()
        rule.waitForIdle()
        assertEquals(CubeState.SOLVED.toFacelets(), state.renderer.renderedFacelets())
        rule.onNodeWithTag("playback:counter").assertTextEquals("1 / 1")
        rule.onNodeWithTag("playback:move").assertTextEquals("F'")
        // The outline followed the white-green edge home to UF (7, 19).
        assertEmphasis(CubeState.SOLVED.toFacelets())
        listOf(7, 19).forEach { assertEquals(true, flag(StickerHighlightedKey, it)) }
        rule.onNodeWithTag("playback:forward").assertIsNotEnabled()
        rule.onNodeWithTag("playback:end").assertIsNotEnabled()
        assertFalse(state.isPlaying)
    }

    @Test
    fun `step back, step forward, end and restart move the counter`() {
        show()
        rule.onNodeWithTag("playback:forward").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("playback:counter").assertTextEquals("1 / 1")
        rule.onNodeWithTag("playback:back").assertIsEnabled().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("playback:counter").assertTextEquals("0 / 1")
        assertEquals(entry.toFacelets(), state.renderer.renderedFacelets())
        rule.onNodeWithTag("playback:end").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("playback:counter").assertTextEquals("1 / 1")
        assertEquals(CubeState.SOLVED.toFacelets(), state.renderer.renderedFacelets())
        rule.onNodeWithTag("playback:restart").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("playback:counter").assertTextEquals("1 / 1")
        assertEquals(CubeState.SOLVED.toFacelets(), state.renderer.renderedFacelets())
    }

    @Test
    fun `speed buttons select the speed`() {
        show()
        rule.onNodeWithTag("playback:speed:NORMAL").assertIsSelected()
        rule.onNodeWithTag("playback:speed:DOUBLE").performClick()
        rule.onNodeWithTag("playback:speed:DOUBLE").assertIsSelected()
        rule.onNodeWithTag("playback:speed:NORMAL").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        assertEquals(PlaybackSpeed.DOUBLE, state.speed)
    }

    @Test
    fun `with reduce motion the net snaps to the target within one frame`() {
        show(reduceMotion = true)
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("playback:playPause").performClick()
        rule.mainClock.advanceTimeByFrame()
        assertEquals(CubeState.SOLVED.toFacelets(), state.renderer.renderedFacelets())
    }

    @Test
    fun `without reduce motion the Move animates over the flip`() {
        show(reduceMotion = false)
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("playback:playPause").performClick()
        rule.mainClock.advanceTimeByFrame()
        assertNotEquals(CubeState.SOLVED.toFacelets(), state.renderer.renderedFacelets())
        rule.mainClock.advanceTimeBy(300)
        assertEquals(CubeState.SOLVED.toFacelets(), state.renderer.renderedFacelets())
    }

    @Test
    fun `reduce motion is on at animator scale 0`() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        var reduce = false
        rule.setContent { reduce = rememberReduceMotion() }
        rule.waitForIdle()
        assertTrue(reduce)
    }

    @Test
    fun `reduce motion is off at animator scale 1`() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        var reduce = true
        rule.setContent { reduce = rememberReduceMotion() }
        rule.waitForIdle()
        assertFalse(reduce)
    }

    @Test
    fun `the player reads reduce motion from the system by default`() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        rule.setContent {
            RubikXplorerTheme {
                state = rememberDemonstrationPlayerState(demonstration)
                DemonstrationPlayer(state, Modifier.fillMaxSize())
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("playback:playPause").performClick()
        rule.mainClock.advanceTimeByFrame()
        assertEquals(CubeState.SOLVED.toFacelets(), state.renderer.renderedFacelets())
    }

    @Test
    fun `the frame loop plays one Move per step at the chosen speed`() {
        val three = demo("F U R", "R'", "U'", "F'")
        show(demo = three)
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("playback:playPause").performClick()
        rule.mainClock.advanceTimeByFrame()
        assertCounter("1 / 3")
        rule.mainClock.advanceTimeBy(650)
        assertCounter("2 / 3")
        rule.onNodeWithTag("playback:speed:DOUBLE").performClick()
        rule.mainClock.advanceTimeBy(350)
        assertCounter("3 / 3")
        assertFalse(state.isPlaying)
    }

    @Test
    fun `at double speed the next Move comes after 300 ms`() {
        val three = demo("F U R", "R'", "U'", "F'")
        show(demo = three)
        rule.waitForIdle()
        rule.onNodeWithTag("playback:speed:DOUBLE").performClick()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("playback:playPause").performClick()
        rule.mainClock.advanceTimeByFrame()
        assertCounter("1 / 3")
        rule.mainClock.advanceTimeBy(350)
        assertCounter("2 / 3")
    }

    @Test
    fun `restart from the end snaps back to the start and plays on`() {
        val three = demo("F U R", "R'", "U'", "F'")
        show(demo = three)
        rule.onNodeWithTag("playback:end").performClick()
        rule.waitForIdle()
        assertCounter("3 / 3")
        assertEquals(CubeState.SOLVED.toFacelets(), state.renderer.renderedFacelets())
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("playback:restart").performClick()
        rule.mainClock.advanceTimeByFrame()
        // Snap(0) then Animate(first Move): the counter shows 1 / 3 and the net is back at the start mid-flip.
        assertCounter("1 / 3")
        assertTrue(state.isPlaying)
        assertEquals(three.start.toFacelets(), state.renderer.renderedFacelets())
    }

    @Test
    fun `each step of a two-step lesson outlines its own target`() {
        val two = demo("F U", "U'", "F'")
        show(reduceMotion = true, demo = two)
        rule.onNodeWithTag("playback:forward").performClick()
        rule.waitForIdle()
        assertCounter("1 / 2")
        assertHighlighted(two.steps[0].focus.target)
        assertEquals(
            3,
            two.steps[0]
                .focus.target.size,
        )
        rule.onNodeWithTag("playback:forward").performClick()
        rule.waitForIdle()
        assertCounter("2 / 2")
        assertHighlighted(setOf(whiteGreen))
    }

    @Test
    fun `steps without Moves draw no emphasis`() {
        show(demo = demo("F", "", ""))
        rule.waitForIdle()
        assertCounter("0 / 0")
        assertEquals(false, flag(StickerDimmedKey, 0))
    }

    @Test
    fun `speed buttons are announced as a single-choice group`() {
        show()
        rule
            .onNodeWithTag("playback:speed:HALF")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
    }

    @Test
    fun `an empty Demonstration disables the transport`() {
        val empty = (Demonstration.of(whiteCrossLesson().copy(steps = emptyList())) as DemonstrationResult.Ready).demonstration
        rule.setContent {
            RubikXplorerTheme {
                state = rememberDemonstrationPlayerState(empty, reduceMotion = false)
                DemonstrationPlayer(state, Modifier.fillMaxSize())
            }
        }
        listOf("restart", "back", "playPause", "forward", "end").forEach { rule.onNodeWithTag("playback:$it").assertIsNotEnabled() }
        rule.onNodeWithTag("playback:counter").assertTextEquals("0 / 0")
        rule.onNodeWithTag("playback:move").assertTextEquals("")
        // No step, no emphasis: stickers carry no dim or highlight semantics.
        assertEquals(false, flag(StickerDimmedKey, 0))
    }

    @Test
    fun `a stalled frame lets at most one Move's step pass`() {
        assertEquals(PlaybackSpeed.NORMAL.stepMillis, frameDelta(now = 6_000, last = 1_000, speed = PlaybackSpeed.NORMAL))
        assertEquals(PlaybackSpeed.DOUBLE.stepMillis, frameDelta(now = 6_000, last = 1_000, speed = PlaybackSpeed.DOUBLE))
        assertEquals(16L, frameDelta(now = 1_016, last = 1_000, speed = PlaybackSpeed.NORMAL))
    }
}
