package dev.cubentia.application.session

import dev.cubentia.domain.predicate.ObjectiveProgress
import dev.cubentia.domain.tutor.TeachingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** AC 3–4: every Teaching Mode evaluates the same Completion Predicate, and switching mode keeps the cube. */
class TeachingModesScenarioTest {
    private val lesson = whiteCrossLesson()

    /** The judgments that must not depend on the mode, at each point of a run. */
    private data class Judgment(
        val objectiveMet: Boolean,
        val progress: ObjectiveProgress?,
        val satisfied: Boolean,
    )

    private fun judge(state: SessionState): Judgment {
        val decision = checkNotNull(state.decision)
        // Each judgment equals the Completion Predicate on the current Cube State.
        assertEquals(lesson.completion.isSatisfied(state.virtual), state.objectiveMet)
        assertEquals(lesson.completion.isSatisfied(state.virtual), decision.satisfied)
        assertEquals(lesson.completion.progress(state.virtual), state.progress)
        return Judgment(state.objectiveMet, state.progress, decision.satisfied)
    }

    /** Start, Hint, a neutral Move, then the solving Move, in [mode]. */
    private fun runIn(mode: TeachingMode): List<Judgment> {
        val events = listOf(modeChanged(mode), reveal, commit(D), commit(F_PRIME))
        var state = started(lesson)
        return listOf(judge(state)) + events.map { event -> judge(reduce(state, event).state.also { state = it }) }
    }

    @Test
    fun `all four modes report the same predicate results along the same run`() {
        val runs = TeachingMode.entries.associateWith { runIn(it) }
        assertEquals(TeachingMode.entries.size, runs.size)
        val reference = runs.getValue(TeachingMode.DEFAULT)
        runs.forEach { (mode, judgments) -> assertEquals(reference, judgments, "$mode") }
        assertTrue(reference.last().satisfied)
    }

    @Test
    fun `switching modes keeps the Cube State, the history, the step, the revision and a reveal`() {
        val moved = started(lesson).after(commit(D), reveal)
        assertTrue(moved.revealRequested)
        var state = moved
        listOf(TeachingMode.LET_ME_TRY, TeachingMode.SHOW_ME, TeachingMode.TEST_ME, TeachingMode.GUIDE_ME).forEach { mode ->
            state = reduce(state, modeChanged(mode)).state
            assertEquals(mode, state.teachingMode)
            assertEquals(moved.virtual, state.virtual, "$mode")
            assertEquals(moved.history, state.history, "$mode")
            assertEquals(moved.stepIndex, state.stepIndex, "$mode")
            assertEquals(moved.revision, state.revision, "$mode")
            assertEquals(moved.revealRequested, state.revealRequested, "$mode")
        }
    }
}
