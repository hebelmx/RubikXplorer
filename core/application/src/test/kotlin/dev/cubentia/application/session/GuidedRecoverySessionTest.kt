package dev.cubentia.application.session

import dev.cubentia.application.usecase.PlanRecovery
import dev.cubentia.application.usecase.result
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.content.Lesson
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.predicate.Piece
import dev.cubentia.domain.text.Text
import dev.cubentia.domain.tutor.ErrorCategory
import dev.cubentia.domain.tutor.Recovery
import dev.cubentia.domain.tutor.RecoveryPath
import dev.cubentia.domain.tutor.RecoveryPlanner
import dev.cubentia.domain.tutor.TeachingMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Story 4.5 AC 1–3 (FR-12, AD-3, AD-5, AD-8): *Guide me back* issues a revision-tagged
 * [Effect.PlanRecovery]; its result becomes a Planned or NotFound Guided recovery, a stale one is
 * discarded and offered again, and the Learner follows the plan Move by Move.
 */
class GuidedRecoverySessionTest {
    private val lessons: List<Lesson> by lazy {
        val result = ContentLoader.load(ContentSource.SHIPPED)
        check(result is ContentLoadResult.Loaded) { "the shipped content does not load: $result" }
        result.bundle.stages.flatMap { it.lessons }
    }

    private fun moves(notation: String): List<Move> = (Notation.parse(notation) as NotationResult.Parsed).moves

    private fun move(notation: String): Move = moves(notation).single()

    private val crossEdges: Set<Piece> =
        setOf(Color.GREEN, Color.RED, Color.BLUE, Color.ORANGE).map { Piece(setOf(Color.WHITE, it)) }.toSet()

    /** The shipped `lesson-03-01` in *Test me*, with [played] committed. */
    private fun testMe(played: String = ""): SessionState {
        var state = started(lessons.single { it.id.value == "lesson-03-01" }).after(modeChanged(TeachingMode.TEST_ME))
        moves(played).forEach { state = state.after(commit(it)) }
        return state
    }

    private fun planning(state: SessionState): Effect.PlanRecovery =
        assertInstanceOf(GuidedRecovery.Planning::class.java, state.guidedRecovery).effect

    /** Runs the real [PlanRecovery] for [effect] and returns its result event. */
    private fun runPlan(effect: Effect.PlanRecovery): SessionEvent = runBlocking { effect.result(PlanRecovery()(effect), T0) }

    @Test
    fun `plan ready - one PlanRecovery at the current revision, pending, then Ready with the plan copy`() {
        val harmed = testMe("R")
        assertEquals(ErrorCategory.COMPLETED_LAYER_DISTURBED, harmed.lastDiagnosis?.category)
        assertTrue(harmed.canGuideBack)

        val asked = reduce(harmed, guideBack)
        val effect = assertInstanceOf(Effect.PlanRecovery::class.java, asked.effects.single())
        assertEquals(harmed.nextEffectId, effect.effectId)
        assertEquals(harmed.revision, effect.revision)
        assertEquals(harmed.virtual, effect.state)
        assertEquals(TUTOR_CONFIG.recoveryMaxDepth, effect.maxDepth)
        assertEquals(TUTOR_CONFIG.moves, effect.moveSet)
        assertEquals(crossEdges, effect.goal.pieces)
        val pending = asked.state
        assertEquals(GuidedRecovery.Planning(effect), pending.guidedRecovery)
        assertEquals(harmed.revision, pending.revision)
        assertEquals(harmed.nextEffectId + 1, pending.nextEffectId)
        assertEquals(Recovery.Pending(effect.effectId), pending.decision?.recovery)
        assertEquals(harmed.lastDiagnosis, pending.lastDiagnosis, "the Diagnosis is kept")
        assertNotNull(pending.decision?.diagnosis)
        assertEquals(RecoveryPlanner.PENDING, pending.guidedRecovery.text)
        assertFalse(pending.canGuideBack)

        val ready = pending.after(runPlan(effect))
        assertEquals(Recovery.Ready(moves("R'")), ready.decision?.recovery)
        val planned = assertInstanceOf(GuidedRecovery.Planned::class.java, ready.guidedRecovery)
        assertEquals(crossEdges, planned.pieces)
        assertEquals(Text("msg.recovery.plan", mapOf("count" to 1, "layer" to "cross")), planned.text)
        assertEquals(harmed.lastDiagnosis, ready.lastDiagnosis)
        assertEquals(harmed.virtual, ready.virtual, "a plan is never applied to the cube")
        assertEquals(harmed.revision, ready.revision)
        assertFalse(ready.canGuideBack)
    }

    @Test
    fun `no plan - a failed result is NotFound with the not-found copy`() {
        val pending = testMe("R").after(guideBack)
        val failed = pending.after(planning(pending).failed())
        assertEquals(GuidedRecovery.NotFound, failed.guidedRecovery)
        assertEquals(Recovery.Unavailable, failed.decision?.recovery)
        assertEquals(Text("msg.recovery.not-found"), failed.guidedRecovery.text)
        assertFalse(failed.canGuideBack)
    }

    @Test
    fun `a planned result that does not restore the goal is NotFound`() {
        val pending = testMe("R").after(guideBack)
        assertEquals(GuidedRecovery.NotFound, pending.after(planning(pending).planned(moves("U"))).guidedRecovery)
    }

    @Test
    fun `stale and re-offered - a commit overtakes the plan, the old result is discarded and planning starts again`() {
        val pending = testMe("R").after(guideBack)
        val effect = planning(pending)
        val n = pending.revision
        val moved = pending.after(commit(move("F")))
        assertEquals(GuidedRecovery.None, moved.guidedRecovery, "a commit cancels the plan")
        assertEquals(ErrorCategory.COMPLETED_LAYER_DISTURBED, moved.lastDiagnosis?.category)

        val stale = reduce(moved, runPlan(effect))
        assertEquals(EffectResultDiscarded(effect.effectId, n, n + 1), stale.state.lastDiscarded)
        val fresh = assertInstanceOf(Effect.PlanRecovery::class.java, stale.effects.single())
        assertEquals(effect.effectId + 1, fresh.effectId)
        assertEquals(n + 1, fresh.revision)
        assertEquals(GuidedRecovery.Planning(fresh), stale.state.guidedRecovery)
        assertEquals(moved.virtual, stale.state.virtual)

        // The fresh plan restores the whole cross.
        val ready = stale.state.after(runPlan(fresh))
        val planned = assertInstanceOf(GuidedRecovery.Planned::class.java, ready.guidedRecovery)
        assertEquals(moves("F' R'"), planned.moves)
    }

    @Test
    fun `stale with nothing to offer - after Undo the old result is only discarded`() {
        val pending = testMe("R").after(guideBack)
        val effect = planning(pending)
        val undone = pending.after(undo)
        val stale = reduce(undone, effect.planned(moves("R'")))
        assertEquals(EffectResultDiscarded(effect.effectId, effect.revision, undone.revision), stale.state.lastDiscarded)
        assertEquals(GuidedRecovery.None, stale.state.guidedRecovery)
        assertEquals(emptyList<Effect>(), stale.effects)
        assertEquals(undone.copy(lastDiscarded = stale.state.lastDiscarded), stale.state)
    }

    @Test
    fun `a result for another plan at the same revision is discarded without a re-offer`() {
        val pending = testMe("R").after(guideBack)
        val effect = planning(pending)
        val other = effect.copy(effectId = effect.effectId + 7)
        val stale = reduce(pending, other.failed())
        assertEquals(EffectResultDiscarded(other.effectId, other.revision, pending.revision), stale.state.lastDiscarded)
        assertEquals(GuidedRecovery.Planning(effect), stale.state.guidedRecovery)
        assertEquals(emptyList<Effect>(), stale.effects)
    }

    @Test
    fun `not offerable - no Diagnosis, a REORIENT Diagnosis or already Planning changes nothing`() {
        val fresh = testMe()
        val turned = testMe("y")
        assertEquals(ErrorCategory.UNEXPECTED_ORIENTATION, turned.lastDiagnosis?.category)
        val pending = testMe("R").after(guideBack)
        val guideMe = started(lessons.single { it.id.value == "lesson-03-01" }).after(commit(move("R")))
        listOf(fresh, turned, pending, guideMe, SessionState.IDLE).forEach { state ->
            assertFalse(state.canGuideBack)
            assertEquals(Transition(state), reduce(state, guideBack))
        }
    }

    @Test
    fun `follow plan - each next Move keeps the rest of the plan, the last one ends it`() {
        val harmed = testMe("R F")
        val pending = harmed.after(guideBack)
        val ready = pending.after(runPlan(planning(pending)))
        val planned = assertInstanceOf(GuidedRecovery.Planned::class.java, ready.guidedRecovery)
        assertEquals(moves("F' R'"), planned.moves)
        assertEquals(Text("msg.recovery.plan", mapOf("count" to 2, "layer" to "cross")), planned.text)

        val halfway = ready.after(commit(move("F'")))
        assertEquals(
            GuidedRecovery.Planned(moves("R'"), planned.pieces, RecoveryPlanner.describe(moves("R'"), planned.pieces)),
            halfway.guidedRecovery,
        )
        assertEquals(Recovery.Ready(moves("R'")), halfway.decision?.recovery)
        val home = halfway.after(commit(move("R'")))
        assertEquals(GuidedRecovery.None, home.guidedRecovery)
        assertEquals(harmed.entry, home.virtual)
        assertTrue(home.recoveryGoal!!.isMet(home.virtual))

        // Any other Move ends the plan.
        assertEquals(GuidedRecovery.None, ready.after(commit(move("U"))).guidedRecovery)
    }

    @Test
    fun `the other Lesson events end a Guided recovery, the recovery events keep the Diagnosis`() {
        val pending = testMe("R").after(guideBack)
        listOf(undo, resetEvent, reveal, hint, retry, modeChanged(TeachingMode.GUIDE_ME), stepAdvanced).forEach { event ->
            assertEquals(GuidedRecovery.None, pending.after(event).guidedRecovery, "$event")
        }
        assertEquals(GuidedRecovery.None, pending.after(lessonStarted(checkNotNull(pending.lesson))).guidedRecovery)
        assertEquals(pending.lastDiagnosis, pending.after(planning(pending).failed()).lastDiagnosis)
    }

    @Test
    fun `undo after a wrong piece leaves the Lesson's preserve satisfied`() {
        val lesson = lessons.single { it.id.value == "lesson-02-05" }
        var state = started(lesson).after(modeChanged(TeachingMode.TEST_ME))
        moves(lesson.steps.first().moves).forEach { state = state.after(commit(it)) }
        state = state.after(stepAdvanced)
        val harmed = state.after(commit(move("F")))
        assertEquals(ErrorCategory.WRONG_PIECE, harmed.lastDiagnosis?.category)
        assertEquals(RecoveryPath.UNDO, harmed.lastDiagnosis?.recoveryPath)
        val undone = harmed.after(undo)
        assertEquals(state.virtual, undone.virtual)
        assertTrue(lesson.preserve.isSatisfied(undone.virtual))
    }

    @Test
    fun `try again after several bad turns returns to the Checkpoint with the Lesson's preserve satisfied`() {
        // A session commit is always one Move from its expected state, so the enumerator explains it and
        // UNKNOWN_DISCREPANCY is not reachable through MoveCommitted; RETRY is exercised as Try again here.
        val harmed = testMe("R U F")
        assertFalse(checkNotNull(harmed.lesson).preserve.isSatisfied(harmed.virtual))
        val retried = harmed.after(retry)
        assertEquals(checkNotNull(harmed.checkpoint).state, retried.virtual)
        assertEquals(harmed.entry, retried.virtual)
        assertTrue(checkNotNull(retried.lesson).preserve.isSatisfied(retried.virtual))
    }
}
