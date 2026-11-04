package dev.rubikxplorer.app.ui.lesson

import android.app.Application
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.rubikxplorer.app.content.CatalogState
import dev.rubikxplorer.app.content.LessonCatalog
import dev.rubikxplorer.app.render.StickerColorKey
import dev.rubikxplorer.app.render.StickerDimmedKey
import dev.rubikxplorer.app.render.StickerHighlightedKey
import dev.rubikxplorer.app.render.StickerPulsingKey
import dev.rubikxplorer.app.session.SessionHost
import dev.rubikxplorer.app.session.TickingClock
import dev.rubikxplorer.app.session.oneLongStepLesson
import dev.rubikxplorer.app.session.testBundle
import dev.rubikxplorer.app.session.testFlags
import dev.rubikxplorer.app.session.twoStepLesson
import dev.rubikxplorer.app.session.whiteCrossLesson
import dev.rubikxplorer.app.ui.freecube.FreeCubeViewModel
import dev.rubikxplorer.app.ui.shell.AppShell
import dev.rubikxplorer.app.ui.theme.RubikXplorerTheme
import dev.rubikxplorer.application.session.SessionEvent
import dev.rubikxplorer.domain.appearance.StickerAppearance
import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Facelets
import dev.rubikxplorer.domain.cube.Turn
import dev.rubikxplorer.domain.playback.Demonstration
import dev.rubikxplorer.domain.playback.DemonstrationResult
import dev.rubikxplorer.domain.playback.PlaybackSpeed
import dev.rubikxplorer.domain.predicate.Piece
import dev.rubikxplorer.domain.tutor.TeachingMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** AC 2–4: Show me follows the Demonstration, Guide me reveals on request, and the Objective-met state. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class LessonScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val host = SessionHost(TickingClock())
    private val catalog = LessonCatalog.of(ContentLoadResult.Loaded(testBundle(whiteCrossLesson(), twoStepLesson(), oneLongStepLesson())))
    private var closed = 0

    /** The Lesson screen alone for [lessonId]. */
    private fun open(
        lessonId: String,
        modes: List<TeachingMode> = TeachingMode.entries,
    ) {
        runBlocking { catalog.load() }
        val ready = catalog.state.value as CatalogState.Ready
        val entry = ready.lessons.single { it.lesson.id.value == lessonId }
        rule.setContent {
            RubikXplorerTheme {
                LessonScreen(host = host, entry = entry, strings = ready.strings, modes = modes, onClose = { closed++ })
            }
        }
        rule.waitUntil(5_000) {
            host.state.value.lesson
                ?.id
                ?.value == lessonId
        }
        rule.waitForIdle()
    }

    private fun node(tag: String) = rule.onNodeWithTag(tag)

    private fun stickersWith(key: SemanticsPropertyKey<Boolean>): Set<String> =
        rule
            .onAllNodes(SemanticsMatcher.expectValue(key, true))
            .fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
            .toSet()

    private val resolver get() = RuntimeEnvironment.getApplication().contentResolver

    @After
    fun resetAnimatorScale() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    private fun drawnColors(): List<Color> =
        (0 until Facelets.SIZE).map { node("sticker:$it").fetchSemanticsNode().config[StickerColorKey] }

    private fun assertNetMatchesSession(step: String) {
        rule.waitForIdle()
        val expected =
            host.state.value.virtual
                .toFacelets()
                .colors
                .map { Color(StickerAppearance.of(it).fill) }
        assertEquals(step, expected, drawnColors())
    }

    private fun anyPulsing(): Boolean = stickersWith(StickerPulsingKey).isNotEmpty()

    private fun assertStepTexts(
        n: Int,
        id: String,
    ) {
        node("lesson:goal").assertTextContains("Goal $n of $id")
        node("lesson:keep").assertTextContains("Keep $n of $id")
        node("lesson:watch").assertTextContains("Watch $n of $id")
    }

    @Test
    fun `show me follows the playing Demonstration, Notice after each step's Moves`() {
        val id = twoStepLesson().id.value
        open(id)
        node("mode:SHOW_ME").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.teachingMode == TeachingMode.SHOW_ME }
        rule.waitForIdle()
        node("mode:SHOW_ME").assertIsSelected()
        node("lesson:hint").performScrollTo().assertIsNotEnabled()
        node("demonstrationPlayer").assertExists()
        assertStepTexts(1, id)
        node("lesson:notice").assertDoesNotExist()
        node("moveChip:U").assertExists()
        node("lesson:showMove").assertDoesNotExist()
        // Show me already reveals, so there is nothing for Hint to ask.
        node("lesson:hint").assertIsNotEnabled()

        rule.mainClock.autoAdvance = false
        node("playback:playPause").performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        // Play emits step 1's only Move at once.
        assertStepTexts(1, id)
        node("lesson:notice").assertTextContains("Notice 1 of $id")

        rule.mainClock.advanceTimeBy(PlaybackSpeed.NORMAL.stepMillis + 100)
        assertStepTexts(2, id)
        node("moveChip:F'").assertExists()
        node("lesson:notice").assertTextContains("Notice 2 of $id")
        node("playback:counter").assertTextContains("2 / 2")
        // Playback never touches the session.
        assertEquals(host.state.value.entry, host.state.value.virtual)
    }

    @Test
    fun `guide me states the step first and shows the Move only once the session reveals it`() {
        val id = whiteCrossLesson().id.value
        open(id)
        node("mode:GUIDE_ME").assertIsSelected()
        assertStepTexts(1, id)
        node("lesson:notice").assertDoesNotExist()
        node("moveChip:F'").assertDoesNotExist()
        node("lesson:hint").assertIsEnabled()
        node("lesson:next").assertIsNotEnabled()
        assertFalse(
            host.state.value.decision!!
                .reveal,
        )

        node("lesson:showMove").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            host.state.value.decision!!
                .reveal
        }
        rule.waitForIdle()
        node("moveChip:F'").assertExists()
        node("lesson:showMove").assertDoesNotExist()
        node("lesson:hint").assertIsNotEnabled()
        assertEquals(host.state.value.entry, host.state.value.virtual)
    }

    @Test
    fun `hint reveals the Move through the session too`() {
        open(whiteCrossLesson().id.value)
        node("lesson:hint").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.revealRequested }
        rule.waitForIdle()
        node("moveChip:F'").assertExists()
    }

    @Test
    fun `guide me through two steps, the path state enables Next, which moves on and hides the Move again`() {
        val id = twoStepLesson().id.value
        open(id)
        node("lesson:showMove").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.revealRequested }
        node("moveKey:U").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            host.state.value.decision!!
                .advance
        }
        rule.waitForIdle()
        node("lesson:notice").assertTextContains("Notice 1 of $id")
        node("lesson:objectiveMet").assertDoesNotExist()
        node("lesson:next").performScrollTo().assertIsEnabled().performClick()
        rule.waitUntil(5_000) { host.state.value.stepIndex == 1 }
        rule.waitForIdle()
        assertStepTexts(2, id)
        node("lesson:notice").assertDoesNotExist()
        node("lesson:showMove").assertExists()
        assertEquals(0, closed)
    }

    /** The sticker tags of the current net whose piece is in the target of [lesson]'s step [index]. */
    private fun targetStickers(
        demonstration: Demonstration,
        index: Int,
    ): Set<String> {
        val facelets =
            host.state.value.virtual
                .toFacelets()
        val target = demonstration.steps[index].focus.target
        return (0 until Facelets.SIZE).filter { Piece.at(facelets, it) in target }.map { "sticker:$it" }.toSet()
    }

    @Test
    fun `guide me emphasis follows the current step, so step 2 outlines step 2's target`() {
        val lesson = twoStepLesson()
        val demonstration = (Demonstration.of(lesson) as DemonstrationResult.Ready).demonstration
        open(lesson.id.value)
        assertEquals("step 1", targetStickers(demonstration, 0), stickersWith(StickerHighlightedKey))
        node("moveKey:U").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            host.state.value.decision!!
                .advance
        }
        node("lesson:next").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.stepIndex == 1 }
        rule.waitForIdle()
        val step2 = targetStickers(demonstration, 1)
        assertTrue("step 2 has a target", step2.isNotEmpty())
        assertEquals("step 2", step2, stickersWith(StickerHighlightedKey))
    }

    @Test
    fun `committing F prime meets the Objective with a brief pulse and no auto-advance`() {
        open(whiteCrossLesson().id.value)
        node("lesson:objectiveMet").assertDoesNotExist()
        // The target (white-green edge) is outlined but not pulsing before the Objective is met.
        assertTrue(stickersWith(StickerHighlightedKey).isNotEmpty())
        assertFalse(anyPulsing())

        node("moveKey:F'").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            host.state.value.decision!!
                .satisfied
        }
        // The clock steps by hand from here, so the ~600 ms pulse is seen while it runs.
        rule.mainClock.autoAdvance = false
        var pulsed = false
        repeat(PULSE_CHECKS) {
            if (!pulsed) {
                rule.mainClock.advanceTimeBy(PULSE_CHECK_MILLIS)
                pulsed = anyPulsing()
            }
        }
        assertTrue("satisfied=${host.state.value.decision} but no sticker pulsed", pulsed)
        // Only the target's stickers pulse.
        assertTrue(stickersWith(StickerHighlightedKey).containsAll(stickersWith(StickerPulsingKey)))

        rule.mainClock.advanceTimeBy(2_000)
        assertFalse(anyPulsing())
        rule.mainClock.autoAdvance = true
        node("lesson").assertExists()
        node("lesson:objectiveMet").assertTextContains("Objective met")
        node("lesson:notice").assertTextContains("Notice 1 of lesson-01-01")
        node("lesson:next").assertIsEnabled()
        assertEquals(0, closed)
        node("lesson:next").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, closed)
    }

    @Test
    fun `next on a satisfied Lesson returns to Learn`() {
        rule.setContent {
            RubikXplorerTheme {
                AppShell(host = host, catalog = catalog, freeCube = FreeCubeViewModel(), showDebugToggles = false, flags = testFlags())
            }
        }
        node("learn:lesson:lesson-01-01").performClick()
        rule.waitUntil(5_000) {
            host.state.value.lesson
                ?.id == whiteCrossLesson().id
        }
        rule.waitForIdle()
        runBlocking { host.dispatch { SessionEvent.MoveCommitted(whiteCrossSolvingMove, it) } }
        rule.waitForIdle()
        node("lesson:next").performScrollTo().assertIsEnabled().performClick()
        rule.waitForIdle()
        node("learn").assertExists()
        node("lesson").assertDoesNotExist()
    }

    @Test
    fun `the guide me net follows the session through a Move, undo, redo and a confirmed reset`() {
        open(whiteCrossLesson().id.value)
        assertNetMatchesSession("start")
        node("moveKey:R").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.history.done.size == 1 }
        assertNetMatchesSession("R")
        node("toolbar:undo").performClick()
        rule.waitUntil(5_000) { host.state.value.history.canRedo }
        assertNetMatchesSession("undo")
        node("toolbar:redo").performClick()
        rule.waitUntil(5_000) { host.state.value.history.canUndo }
        assertNetMatchesSession("redo")
        node("toolbar:reset").performClick()
        rule.waitUntil(5_000) { host.state.value.pendingReset != null }
        node("resetConfirmation:confirm").performClick()
        rule.waitUntil(5_000) { host.state.value.pendingReset == null && !host.state.value.hasUnsavedSteps }
        assertEquals(host.state.value.entry, host.state.value.virtual)
        assertNetMatchesSession("reset")
    }

    @Test
    fun `on step 2 with nothing unsaved, reset is enabled and returns to step 1`() {
        open(twoStepLesson().id.value)
        node("moveKey:U").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            host.state.value.decision!!
                .advance
        }
        rule.waitForIdle()
        node("lesson:next").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.stepIndex == 1 }
        // Undo leaves nothing unsaved (only a redo), so Reset applies without asking.
        node("toolbar:undo").performClick()
        rule.waitUntil(5_000) { !host.state.value.hasUnsavedSteps }
        rule.waitForIdle()
        node("toolbar:reset").assertIsEnabled().performClick()
        rule.waitUntil(5_000) { host.state.value.stepIndex == 0 }
        rule.waitForIdle()
        node("resetConfirmation").assertDoesNotExist()
        assertStepTexts(1, twoStepLesson().id.value)
        assertNetMatchesSession("reset")
    }

    @Test
    fun `under reduce motion the Objective is met without a pulse`() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        open(whiteCrossLesson().id.value)
        node("moveKey:F'").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            host.state.value.decision!!
                .satisfied
        }
        rule.mainClock.autoAdvance = false
        repeat(PULSE_CHECKS) {
            rule.mainClock.advanceTimeBy(PULSE_CHECK_MILLIS)
            assertFalse("pulsing under reduce motion", anyPulsing())
        }
        rule.mainClock.autoAdvance = true
        node("lesson:objectiveMet").assertTextContains("Objective met")
    }

    private fun switchTo(mode: TeachingMode) {
        node("mode:${mode.name}").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.teachingMode == mode }
        rule.waitForIdle()
    }

    private fun press(
        key: String,
        moves: Int,
    ) {
        node("moveKey:$key").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.history.done.size == moves }
        rule.waitForIdle()
    }

    /** The test tags of every node now shown whose tag starts with [prefix]. */
    private fun tagsStartingWith(prefix: String): List<String> =
        rule
            .onAllNodes(
                SemanticsMatcher("tag starts with $prefix") {
                    it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) ==
                        true
                },
            ).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }

    @Test
    fun `test me tells how each Move did and keeps the Moves hidden until Hint`() {
        open(whiteCrossLesson().id.value)
        switchTo(TeachingMode.TEST_ME)
        node("mode:TEST_ME").assertIsSelected()
        node("lesson:feedback").assertDoesNotExist()
        node("lesson:movesHidden").assertTextContains("Hidden until you ask for a hint.")

        press("D", 1)
        node("lesson:feedback").assertTextContains("That didn't change much. Try another turn.")
        node("lesson:diagnosis").assertDoesNotExist()
        // U lifts three cross edges, but no step has kept them yet: neutral, never harmful.
        press("U", 2)
        node("lesson:feedback").assertTextContains("That didn't change much. Try another turn.")
        assertEquals(emptyList<String>(), tagsStartingWith("moveChip:"))
        node("lesson:showMove").assertDoesNotExist()
        assertFalse(
            host.state.value.decision!!
                .reveal,
        )

        node("lesson:hint").performScrollTo().assertIsEnabled().performClick()
        rule.waitUntil(5_000) {
            host.state.value.decision!!
                .reveal
        }
        rule.waitForIdle()
        node("moveChip:F'").assertExists()
        node("lesson:movesHidden").assertDoesNotExist()
        node("lesson:hint").assertIsNotEnabled()
    }

    @Test
    fun `let me try shows Goal, Keep and Watch, hides the Moves, never gives feedback and meets the Objective as Guide me does`() {
        val id = whiteCrossLesson().id.value
        open(id)
        switchTo(TeachingMode.LET_ME_TRY)
        assertStepTexts(1, id)
        assertEquals(emptyList<String>(), tagsStartingWith("moveChip:"))
        node("lesson:showMove").assertDoesNotExist()
        node("lesson:movesHidden").assertExists()
        node("lesson:hint").assertIsEnabled()
        // No dimming or target outline outside Guide me.
        assertTrue(stickersWith(StickerHighlightedKey).isEmpty())

        press("D", 1)
        node("lesson:feedback").assertDoesNotExist()
        press("D'", 2)
        node("lesson:feedback").assertDoesNotExist()
        press("F'", 3)
        rule.waitUntil(5_000) {
            host.state.value.decision!!
                .satisfied
        }
        rule.waitForIdle()
        node("lesson:objectiveMet").assertTextContains("Objective met")
        node("lesson:notice").assertTextContains("Notice 1 of $id")
        node("lesson:next").assertIsEnabled()
        node("lesson:feedback").assertDoesNotExist()
        assertEquals(emptyList<String>(), tagsStartingWith("moveChip:"))
    }

    @Test
    fun `test me says the taught Move is on path`() {
        open(oneLongStepLesson().id.value)
        switchTo(TeachingMode.TEST_ME)
        press("U", 1)
        node("lesson:feedback").assertTextContains("That's the move — keep going.")
        assertFalse(
            host.state.value.decision!!
                .advance,
        )
    }

    @Test
    fun `test me names a turned cube in a polite live region, with the reorient prompt, Show me how and no undo`() {
        open(whiteCrossLesson().id.value)
        switchTo(TeachingMode.TEST_ME)
        press("y", 1)
        rule.waitUntil(5_000) {
            host.state.value.decision
                ?.diagnosis != null
        }
        rule.waitForIdle()
        node("lesson:diagnosis")
            .assertTextContains("Your cube is turned — hold it with yellow on the bottom, green in front.", substring = true)
        node("lesson:diagnosis").assertTextContains("Turn it back with the rotation keys.", substring = true)
        node("lesson:diagnosis").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        node("lesson:feedback").assertDoesNotExist()

        // A turned cube offers Show me how and no Undo; tapping it shows the rotation back, never turns the cube.
        rule.onNode(hasText("Undo") and hasAnyAncestor(hasTestTag("lesson:stepCard"))).assertDoesNotExist()
        node("lesson:reorientMoves").assertDoesNotExist()
        val turned = host.state.value.virtual
        node("lesson:showMeHow").performScrollTo().assertTextContains("Show me how").performClick()
        rule.waitForIdle()
        node("lesson:reorientMoves").assertExists()
        node("moveChip:y'").assertExists()
        node("lesson:showMeHow").assertDoesNotExist()
        assertEquals(turned, host.state.value.virtual)
    }

    @Test
    fun `test me says an off-path Move that helps brings the Learner closer`() {
        open(whiteCrossLesson().id.value)
        switchTo(TeachingMode.TEST_ME)
        // U lifts the three placed cross edges (nothing kept yet); U' off the path brings them back.
        press("U", 1)
        press("U'", 2)
        node("lesson:feedback").assertTextContains("That works too — you're closer.")
        node("lesson:diagnosis").assertDoesNotExist()
    }

    @Test
    fun `test me names a harmful Move calmly with its recovery prompt, and keeps the Learner's cube`() {
        val id = twoStepLesson().id.value
        open(id)
        switchTo(TeachingMode.TEST_ME)
        press("U", 1)
        node("lesson:next").performScrollTo().assertIsEnabled().performClick()
        rule.waitUntil(5_000) { host.state.value.stepIndex == 1 }
        val before = host.state.value.virtual
        press("D", 2)
        rule.waitUntil(5_000) {
            host.state.value.decision
                ?.diagnosis != null
        }
        rule.waitForIdle()
        node("lesson:diagnosis").assertTextContains("That turn moved a piece you'd placed.", substring = true)
        node("lesson:diagnosis").assertTextContains("Undo, or try another turn.", substring = true)
        node("lesson:feedback").assertDoesNotExist()
        assertEquals(before.apply(FaceTurn(Face.D, Turn.CLOCKWISE)), host.state.value.virtual)
        assertEquals(emptyList<String>(), tagsStartingWith("moveChip:"))
    }

    @Test
    fun `outside Guide me the net is plain until the Objective-met pulse lights the target`() {
        open(whiteCrossLesson().id.value)
        switchTo(TeachingMode.LET_ME_TRY)
        assertTrue(stickersWith(StickerDimmedKey).isEmpty())
        assertTrue(stickersWith(StickerHighlightedKey).isEmpty())

        node("moveKey:F'").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            host.state.value.decision!!
                .satisfied
        }
        rule.mainClock.autoAdvance = false
        var pulsed = false
        repeat(PULSE_CHECKS) {
            if (!pulsed) {
                rule.mainClock.advanceTimeBy(PULSE_CHECK_MILLIS)
                pulsed = anyPulsing()
                if (pulsed) {
                    assertTrue(stickersWith(StickerHighlightedKey).containsAll(stickersWith(StickerPulsingKey)))
                    assertTrue("dimmed during the pulse", stickersWith(StickerDimmedKey).isEmpty())
                }
            }
        }
        assertTrue("satisfied=${host.state.value.decision} but no sticker pulsed", pulsed)
        rule.mainClock.advanceTimeBy(2_000)
        assertFalse(anyPulsing())
        assertTrue(stickersWith(StickerHighlightedKey).isEmpty())
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun `the switcher shows only the modes it is given`() {
        open(whiteCrossLesson().id.value, modes = TeachingMode.entries - TeachingMode.TEST_ME)
        node("mode:TEST_ME").assertDoesNotExist()
        node("mode:LET_ME_TRY").assertExists()
    }

    private companion object {
        const val PULSE_CHECKS = 6
        const val PULSE_CHECK_MILLIS = 80L
    }

    private val whiteCrossSolvingMove =
        dev.rubikxplorer.domain.cube
            .FaceTurn(dev.rubikxplorer.domain.cube.Face.F, dev.rubikxplorer.domain.cube.Turn.COUNTERCLOCKWISE)
}
