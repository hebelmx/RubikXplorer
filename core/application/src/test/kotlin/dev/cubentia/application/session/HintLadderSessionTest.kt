package dev.cubentia.application.session

import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.content.Lesson
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.tutor.HintRung
import dev.cubentia.domain.tutor.TeachingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** Story 4.3 I/O matrix through [reduce]: the Hint Ladder climbs one rung per Hint, resets per step, counts per attempt. */
class HintLadderSessionTest {
    private val lessons: List<Lesson> by lazy {
        val result = ContentLoader.load(ContentSource.SHIPPED)
        check(result is ContentLoadResult.Loaded) { "the shipped content does not load: $result" }
        result.bundle.stages.flatMap { it.lessons }
    }

    private fun lesson(id: String): Lesson = lessons.single { it.id.value == id }

    private fun moves(notation: String): List<Move> = (Notation.parse(notation) as NotationResult.Parsed).moves

    private fun SessionState.turning(notation: String): SessionState = after(*moves(notation).map { commit(it) }.toTypedArray())

    private fun start(
        id: String = "lesson-02-05",
        mode: TeachingMode = TeachingMode.TEST_ME,
    ): SessionState = started(lesson(id)).after(modeChanged(mode))

    private val SessionState.reveal: Boolean get() = checkNotNull(decision).reveal

    @Test
    fun `three hints climb clue, target, demonstration, counting each and keeping the revision`() {
        val fresh = start()
        assertEquals(HintRung.NONE, fresh.hintRung)
        assertEquals(0, fresh.hintsUsed)
        val steps = run(fresh, hint, hint, hint).map { it.state }
        assertEquals(listOf(HintRung.CLUE, HintRung.TARGET, HintRung.DEMONSTRATION), steps.map { it.hintRung })
        assertEquals(listOf(1, 2, 3), steps.map { it.hintsUsed })
        assertEquals(listOf(false, true, true), steps.map { it.reveal })
        steps.forEach {
            assertEquals(fresh.revision, it.revision)
            assertEquals(fresh.history, it.history)
        }
    }

    @Test
    fun `a fourth hint past the top changes nothing`() {
        val top = start().after(hint, hint, hint)
        val past = reduce(top, hint)
        assertEquals(top, past.state)
        assertEquals(emptyList<Effect>(), past.effects)
    }

    @Test
    fun `show me ignores a hint`() {
        val showMe = start(mode = TeachingMode.SHOW_ME)
        assertEquals(showMe, showMe.after(hint))
    }

    @Test
    fun `a hint while the Notice waits for Next changes nothing`() {
        val done = start().after(hint, hint).turning("F2")
        assertTrue(checkNotNull(done.decision).advance)
        assertEquals(HintRung.TARGET, done.hintRung, "the Notice keeps the step shown, so the rung stays")
        val asked = done.after(hint)
        assertEquals(done.hintRung, asked.hintRung)
        assertEquals(done.hintsUsed, asked.hintsUsed)
    }

    @ParameterizedTest
    @EnumSource(value = TeachingMode::class, names = ["TEST_ME", "LET_ME_TRY"])
    fun `the Moves stay hidden at rungs none and clue`(mode: TeachingMode) {
        val fresh = start(mode = mode)
        assertFalse(fresh.reveal)
        assertFalse(fresh.after(hint).reveal)
        assertTrue(fresh.after(hint, hint).reveal)
    }

    @ParameterizedTest
    @EnumSource(value = TeachingMode::class, names = ["TEST_ME", "LET_ME_TRY"])
    fun `the rung resets when the step advances, keeping the count`(mode: TeachingMode) {
        val advanced = start(mode = mode).after(hint, hint).turning("F2").after(stepAdvanced)
        assertEquals(1, advanced.stepIndex)
        assertEquals(HintRung.NONE, advanced.hintRung)
        assertFalse(advanced.reveal)
        assertEquals(2, advanced.hintsUsed)
    }

    @ParameterizedTest
    @EnumSource(value = TeachingMode::class, names = ["TEST_ME", "LET_ME_TRY"])
    fun `undo across a step boundary resets the rung, keeping the count`(mode: TeachingMode) {
        val onStep2 = start(mode = mode).after(hint).turning("F2").after(stepAdvanced, hint, hint)
        assertEquals(1, onStep2.stepIndex)
        assertEquals(HintRung.TARGET, onStep2.hintRung)
        val undone = onStep2.after(undo)
        assertEquals(0, undone.stepIndex)
        assertEquals(HintRung.NONE, undone.hintRung)
        assertFalse(undone.reveal)
        assertEquals(3, undone.hintsUsed)
    }

    @Test
    fun `a move that keeps the shown step keeps the rung`() {
        val clued = start().after(hint)
        val moved = clued.turning("D")
        assertEquals(0, moved.stepIndex)
        assertEquals(HintRung.CLUE, moved.hintRung)
    }

    @Test
    fun `a new Objective starts at rung none with no hints, for the same Lesson and another`() {
        val hinted = start().after(hint, hint)
        listOf("lesson-02-05", "lesson-03-01").forEach { id ->
            val next = hinted.after(lessonStarted(lesson(id)))
            assertEquals(HintRung.NONE, next.hintRung, id)
            assertEquals(0, next.hintsUsed, id)
        }
        val playback = SessionState.IDLE.after(playbackStarted())
        assertEquals(HintRung.NONE, playback.hintRung)
        assertEquals(0, playback.hintsUsed)
    }

    @Test
    fun `a mode switch keeps the rung and the count`() {
        val clued = start().after(hint)
        val switched = clued.after(modeChanged(TeachingMode.LET_ME_TRY))
        assertEquals(HintRung.CLUE, switched.hintRung)
        assertEquals(1, switched.hintsUsed)
    }

    @Test
    fun `reset returns the rung to none and keeps the count, also once confirmed`() {
        val clean = start().after(hint, hint).after(resetEvent)
        assertEquals(HintRung.NONE, clean.hintRung)
        assertEquals(2, clean.hintsUsed)

        val moved = start().turning("D").after(hint)
        val asked = moved.after(resetEvent)
        assertEquals(HintRung.CLUE, asked.hintRung, "a Reset that only asks leaves the rung")
        val confirmed = asked.after(checkNotNull(asked.pendingReset).confirm())
        assertEquals(HintRung.NONE, confirmed.hintRung)
        assertEquals(1, confirmed.hintsUsed)
    }

    @Test
    fun `undo that keeps the shown step keeps the rung and the count`() {
        val moved = start().turning("D").after(hint)
        val undone = moved.after(undo)
        assertEquals(HintRung.CLUE, undone.hintRung)
        assertEquals(1, undone.hintsUsed)
    }

    @Test
    fun `a hint clears a pending diagnosis and the last Move's feedback`() {
        val turned = started().after(modeChanged(TeachingMode.TEST_ME), commit(Y))
        assertNotNull(turned.lastDiagnosis)
        assertNotNull(checkNotNull(turned.decision).diagnosis)
        val hinted = turned.after(hint)
        assertNull(hinted.lastMoveFeedback)
        assertNull(hinted.lastDiagnosis)
        assertNull(checkNotNull(hinted.decision).diagnosis)
        assertEquals(HintRung.CLUE, hinted.hintRung)
        assertEquals(turned.revision, hinted.revision)
    }

    @Test
    fun `revealing the Move in Guide me is not a hint`() {
        val shown = start(mode = TeachingMode.GUIDE_ME).after(reveal)
        assertEquals(HintRung.NONE, shown.hintRung)
        assertEquals(0, shown.hintsUsed)
    }

    @Test
    fun `an idle session ignores a hint`() {
        assertEquals(SessionState.IDLE, SessionState.IDLE.after(hint))
    }
}
