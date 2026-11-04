package dev.rubikxplorer.application.session

import dev.rubikxplorer.domain.content.AdaptiveThresholds
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Turn
import dev.rubikxplorer.domain.text.Text
import dev.rubikxplorer.domain.tutor.AdaptivePolicy
import dev.rubikxplorer.domain.tutor.AssistanceLevel
import dev.rubikxplorer.domain.tutor.HintLadder
import dev.rubikxplorer.domain.tutor.HintRung
import dev.rubikxplorer.domain.tutor.TeachingMode
import dev.rubikxplorer.domain.tutor.Tutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Story 4.6 I/O matrix through [reduce]: adaptive assistance counts mistakes and clean steps and acts only on the Hint Ladder. */
class AdaptiveAssistanceSessionTest {
    private val f2 = FaceTurn(Face.F, Turn.HALF)
    private val r2 = FaceTurn(Face.R, Turn.HALF)

    private fun start(
        id: String = "lesson-03-01",
        mode: TeachingMode = TeachingMode.TEST_ME,
        adaptive: Boolean = true,
        thresholds: AdaptiveThresholds = TUTOR_CONFIG.adaptive,
    ): SessionState =
        SessionState.IDLE.after(
            lessonStarted(shippedLesson(id), adaptive, TUTOR_CONFIG.copy(adaptive = thresholds)),
            modeChanged(mode),
        )

    /** [times] mistakes on 03-01's only step: R disturbs the cross, Undo takes it back. */
    private fun SessionState.trippedUp(times: Int): SessionState = (1..times).fold(this) { s, _ -> s.after(commit(R), undo) }

    private val SessionState.line: Text? get() = AdaptivePolicy.line(assistance, stepMistakes[stepIndex] ?: 0)

    private fun assertModeKept(
        mode: TeachingMode,
        vararg states: SessionState,
    ) = states.forEach { assertEquals(mode, it.teachingMode, "adaptive assistance never switches the Teaching Mode") }

    @Test
    fun `a first mistake explains - the clue shows untapped and nothing is revealed or counted`() {
        val fresh = start()
        val once = fresh.trippedUp(1)
        assertEquals(mapOf(0 to 1), once.stepMistakes)
        assertEquals(AssistanceLevel.EXPLAIN, once.assistance)
        assertEquals(HintRung.NONE, once.hintRung)
        assertEquals(HintRung.CLUE, once.shownRung)
        assertFalse(checkNotNull(once.decision).reveal)
        assertEquals(0, once.hintsUsed)
        assertEquals(Text("msg.assistance.explain", mapOf("count" to 1)), once.line)
        assertModeKept(TeachingMode.TEST_ME, once)
        assertEquals(fresh.revision + 2, once.revision, "only the commit and the Undo advance the revision")
    }

    @Test
    fun `a repeated mistake demonstrates - the Moves are revealed and the line counts twice`() {
        val twice = start().trippedUp(2)
        assertEquals(AssistanceLevel.DEMONSTRATE, twice.assistance)
        assertEquals(HintRung.TARGET, twice.shownRung)
        assertTrue(checkNotNull(twice.decision).reveal)
        assertEquals(Text("msg.assistance.demonstrate", mapOf("count" to 2)), twice.line)
        assertModeKept(TeachingMode.TEST_ME, twice)
    }

    @Test
    fun `continued difficulty guides one Move at a time`() {
        val thrice = start().trippedUp(3)
        assertEquals(AssistanceLevel.GUIDE, thrice.assistance)
        assertTrue(checkNotNull(thrice.decision).reveal)
        assertEquals(FaceTurn(Face.R, Turn.COUNTERCLOCKWISE), Tutor.nextMove(checkNotNull(thrice.lesson), thrice.entry, thrice.virtual))
        assertEquals(Text("msg.assistance.guide", mapOf("count" to 3)), thrice.line)
        assertModeKept(TeachingMode.TEST_ME, thrice)
    }

    @Test
    fun `a Hint climbs from the shown rung and counts once`() {
        val once = start().trippedUp(1)
        val hinted = once.after(hint)
        assertEquals(HintRung.TARGET, hinted.hintRung)
        assertEquals(1, hinted.hintsUsed)
        assertTrue(checkNotNull(hinted.decision).reveal)
    }

    @Test
    fun `repeated success reduces help - the ladder stops at the target`() {
        val fresh = start("lesson-02-05", TeachingMode.GUIDE_ME)
        val first = fresh.after(commit(f2))
        assertEquals(1, first.cleanSteps)
        val second = first.after(stepAdvanced, commit(r2))
        assertEquals(2, second.cleanSteps)
        val third = second.after(stepAdvanced)
        assertEquals(2, third.stepIndex)
        assertEquals(AssistanceLevel.REDUCED, third.assistance)
        assertEquals(HintRung.TARGET, third.assistance.top)
        assertEquals(Text("msg.assistance.reduced", mapOf("count" to 0)), third.line)
        val climbs = run(third, hint, hint, hint).map { it.state }
        assertEquals(listOf(HintRung.CLUE, HintRung.TARGET, HintRung.TARGET), climbs.map { it.shownRung })
        assertEquals(listOf(1, 2, 2), climbs.map { it.hintsUsed })
        assertFalse(HintLadder.canClimb(climbs.last().teachingMode, climbs.last().shownRung, false, climbs.last().assistance.top))
        assertModeKept(TeachingMode.GUIDE_ME, first, second, third, *climbs.toTypedArray())
    }

    @Test
    fun `a step completed after a Hint is not clean`() {
        val unclean = start("lesson-02-05", TeachingMode.GUIDE_ME).after(hint, commit(f2))
        assertEquals(0, unclean.cleanSteps)
        val revealed = start("lesson-02-05", TeachingMode.GUIDE_ME).after(reveal, commit(f2))
        assertEquals(0, revealed.cleanSteps, "a step whose Moves were revealed is not clean")
        val clean = start("lesson-02-05", TeachingMode.GUIDE_ME).after(commit(f2))
        assertEquals(1, clean.cleanSteps)
        val afterMistake = clean.after(stepAdvanced, commit(F), undo, commit(r2))
        assertEquals(0, afterMistake.cleanSteps, "a mistake ends the clean run, and its step does not complete cleanly")
    }

    @Test
    fun `the configured thresholds decide each level change`() {
        val config = AdaptiveThresholds(2, 4, 6, 3)
        val expected =
            listOf(
                AssistanceLevel.USUAL,
                AssistanceLevel.USUAL,
                AssistanceLevel.EXPLAIN,
                AssistanceLevel.EXPLAIN,
                AssistanceLevel.DEMONSTRATE,
                AssistanceLevel.DEMONSTRATE,
                AssistanceLevel.GUIDE,
            )
        val levels = (0..6).map { start(thresholds = config).trippedUp(it).assistance }
        assertEquals(expected, levels)

        val twoClean = AdaptiveThresholds(2, 4, 6, 3)
        val s =
            start(
                "lesson-02-05",
                TeachingMode.GUIDE_ME,
                thresholds = twoClean,
            ).after(commit(f2), stepAdvanced, commit(r2), stepAdvanced)
        assertEquals(AssistanceLevel.USUAL, s.assistance, "two clean steps are below a threshold of 3")
        val three = s.after(commit(FaceTurn(Face.B, Turn.HALF)))
        assertEquals(3, three.cleanSteps)
        assertEquals(AssistanceLevel.REDUCED, three.assistance)
    }

    @Test
    fun `back to my usual help dismisses the level for the step until it rises again`() {
        val twice = start().trippedUp(2)
        val usual = twice.after(usualHelp)
        assertEquals(AssistanceLevel.USUAL, usual.assistance)
        assertEquals(usual.hintRung, usual.shownRung)
        assertFalse(checkNotNull(usual.decision).reveal)
        assertNull(usual.line)
        assertEquals(twice.revision, usual.revision)
        val again = usual.trippedUp(1)
        assertEquals(AssistanceLevel.GUIDE, again.assistance)
        assertEquals(Text("msg.assistance.guide", mapOf("count" to 3)), again.line)
        assertModeKept(TeachingMode.TEST_ME, usual, again)
    }

    @Test
    fun `back to my usual help at the usual level changes nothing but the generic clear`() {
        val usualAlready = start()
        assertEquals(usualAlready, usualAlready.after(usualHelp))
        val withFeedback = start(adaptive = false).after(commit(R))
        val cleared = withFeedback.after(usualHelp)
        assertEquals(withFeedback.copy(lastMoveFeedback = null, lastDiagnosis = null, guidedRecovery = GuidedRecovery.None), cleared)
    }

    @Test
    fun `with the flag off the Hint Ladder stays fixed`() {
        val off = start(adaptive = false)
        listOf(off.trippedUp(1), off.trippedUp(2), off.trippedUp(3)).forEach { s ->
            assertEquals(AssistanceLevel.USUAL, s.assistance)
            assertEquals(s.hintRung, s.shownRung)
            assertFalse(checkNotNull(s.decision).reveal)
            assertNull(s.line)
            assertModeKept(TeachingMode.TEST_ME, s)
        }
        val success =
            start(
                "lesson-02-05",
                TeachingMode.GUIDE_ME,
                adaptive = false,
            ).after(commit(f2), stepAdvanced, commit(r2), stepAdvanced)
        assertEquals(AssistanceLevel.USUAL, success.assistance)
        assertEquals(
            listOf(HintRung.CLUE, HintRung.TARGET, HintRung.DEMONSTRATION),
            run(success, hint, hint, hint).map { it.state.shownRung },
        )
        assertEquals(
            mapOf(0 to 0).filterValues {
                it > 0
            },
            start("lesson-03-01", TeachingMode.GUIDE_ME, adaptive = false).after(commit(R)).stepMistakes,
        )
    }

    @Test
    fun `guide me and let me try count mistakes only with the flag on, and keep no feedback`() {
        listOf(TeachingMode.GUIDE_ME, TeachingMode.LET_ME_TRY).forEach { mode ->
            val on = start(mode = mode).after(commit(R))
            assertEquals(mapOf(0 to 1), on.stepMistakes, "$mode")
            assertNull(on.lastMoveFeedback)
            assertNull(on.lastDiagnosis)
            assertEquals(AssistanceLevel.EXPLAIN, on.assistance)
        }
        assertEquals(AssistanceLevel.USUAL, start(mode = TeachingMode.SHOW_ME).after(commit(R)).assistance, "Show me shows everything")
    }

    @Test
    fun `reset, retry, undo and redo never count, and only a new Lesson resets the counts`() {
        val once = start().trippedUp(1)
        listOf(once.after(resetEvent), once.after(redo, retry), once.after(redo), once.after(redo, undo)).forEach {
            assertEquals(once.stepMistakes, it.stepMistakes)
            assertEquals(once.cleanSteps, it.cleanSteps)
        }
        val dismissed = once.after(usualHelp)
        val restarted = dismissed.after(lessonStarted(shippedLesson("lesson-03-01"), adaptiveAssistance = true))
        assertEquals(emptyMap<Int, Int>(), restarted.stepMistakes)
        assertEquals(0, restarted.cleanSteps)
        assertEquals(emptyMap<Int, AssistanceLevel>(), restarted.assistanceDismissed)
        val playback = SessionState.IDLE.after(playbackStarted())
        assertEquals(AssistanceLevel.USUAL, playback.assistance)
        assertFalse(playback.adaptiveAssistance)
        assertEquals(emptyMap<Int, Int>(), SessionState.IDLE.stepMistakes)
    }
}
