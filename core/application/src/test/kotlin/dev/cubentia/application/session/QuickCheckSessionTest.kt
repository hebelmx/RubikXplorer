package dev.cubentia.application.session

import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.recognition.PhysicalObservation
import dev.cubentia.domain.text.Text
import dev.cubentia.domain.tutor.Certainty
import dev.cubentia.domain.tutor.MoveFeedback
import dev.cubentia.domain.tutor.ObservationCheck
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class QuickCheckSessionTest {
    private fun physical(full: Boolean = true): SessionState {
        val predicted = started().after(SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0), SessionEvent.PhysicalConfirmed(null, T0))
        return if (full) predicted.after(SessionEvent.EditorEntered(predicted.expected, T0)) else predicted
    }

    private fun partial(
        state: CubeState,
        faces: Set<Face>,
    ): PhysicalObservation.Partial {
        val colors = state.reoriented(Orientation.HOME).toFacelets().colors
        return PhysicalObservation.Partial(faces.associateWith { colors.subList(it.ordinal * 9, it.ordinal * 9 + 9) })
    }

    private fun requested(
        state: SessionState,
        byLearner: Boolean = true,
    ) = state.after(SessionEvent.QuickCheckRequested(byLearner, T0))

    private fun observed(
        state: SessionState,
        observation: PhysicalObservation,
    ): SessionState {
        val effect = checkNotNull(state.pendingQuickCheck)
        val next = state.after(SessionEvent.QuickCheckObserved(effect.effectId, effect.revision, observation, T0))
        assertEquals(state.expected, next.expected)
        assertEquals(state.virtual, next.virtual)
        assertEquals(state.history, next.history)
        assertEquals(state.revision, next.revision)
        assertNull(next.pendingQuickCheck)
        return next
    }

    @Test fun `idle and playback ignore late quick check observations`() {
        val event = SessionEvent.QuickCheckObserved(-1, 0, PhysicalObservation.Full(CubeState.SOLVED), T0)
        assertSame(SessionState.IDLE, reduce(SessionState.IDLE, event).state)
        val playback = SessionState.IDLE.after(playbackStarted())
        assertEquals(playback.copy(lastDiscarded = EffectResultDiscarded(-1, 0, playback.revision)), reduce(playback, event).state)
    }

    @Test fun `editor confirming expected clears mismatch evidence`() {
        val state = physical().after(SessionEvent.PhysicalConfirmed(R, T0))
        val mismatch = observed(requested(state), PhysicalObservation.Full(state.expected.apply(R)))
        assertNotNull(mismatch.quickCheck)
        val entered = mismatch.after(SessionEvent.EditorEntered(mismatch.expected, T0))
        assertEquals(SyncState.PHYSICAL_VERIFIED, entered.syncState)
        assertNull(entered.quickCheck)
    }

    @Test fun `effect carries plan and revision partial verification retains coverage full resets`() {
        val state = physical().after(SessionEvent.PhysicalConfirmed(R, T0), SessionEvent.PhysicalConfirmed(U, T0))
        assertEquals(setOf(Face.R, Face.U), state.touchedSinceVerified)
        val request = reduce(state, SessionEvent.QuickCheckRequested(at = T0))
        val pending = request.state
        assertEquals(pending.pendingQuickCheck, request.effects.single())
        assertEquals(state.revision, pending.pendingQuickCheck!!.revision)
        assertEquals(setOf(Face.R, Face.U), pending.pendingQuickCheck.faces)
        val partial = partial(state.expected, pending.pendingQuickCheck.faces)
        val checked = observed(pending, partial)
        assertEquals(Certainty.VERIFIED, checked.quickCheck!!.certainty)
        assertEquals(SyncState.PHYSICAL_VERIFIED, checked.syncState)
        assertSame(partial, checked.observed)
        assertEquals(state.touchedSinceVerified, checked.touchedSinceVerified)
        val full = observed(requested(checked), PhysicalObservation.Full(checked.expected))
        assertEquals(emptySet<Face>(), full.touchedSinceVerified)
    }

    @Test fun `without full evidence partial cannot verify`() {
        val state = physical(false).after(SessionEvent.PhysicalConfirmed(R, T0))
        val checked = observed(requested(state), partial(state.expected, setOf(Face.R, Face.U)))
        assertEquals(Certainty.LIKELY, checked.quickCheck!!.certainty)
        assertEquals(SyncState.PHYSICAL_PREDICTED, checked.syncState)
        assertNull(checked.observed)
        assertEquals(Face.entries.toSet(), requested(state).pendingQuickCheck!!.faces)
    }

    @Test fun `verified mismatch and unknown consistent observation set mismatch`() {
        val state = physical().after(SessionEvent.PhysicalConfirmed(R, T0))
        val actual = state.expected.apply(FaceTurn(Face.R, Turn.HALF))
        val mismatch = observed(requested(state), partial(actual, setOf(Face.R, Face.U)))
        assertEquals(Certainty.LIKELY, mismatch.quickCheck!!.certainty)
        assertEquals(listOf(listOf(FaceTurn(Face.R, Turn.HALF))), mismatch.quickCheck.consistent.map { it.moves })
        val fullMismatch = observed(requested(state), PhysicalObservation.Full(actual))
        assertEquals(Certainty.VERIFIED, fullMismatch.quickCheck!!.certainty)
        assertEquals(SyncState.PHYSICAL_MISMATCH, fullMismatch.syncState)
        assertFalse(mismatch.quickCheck.matchesExpected)
        assertEquals(SyncState.PHYSICAL_MISMATCH, mismatch.syncState)
        val unknown = observed(requested(state), PhysicalObservation.Full(state.expected.apply(R, U, F)))
        assertEquals(Certainty.UNKNOWN, unknown.quickCheck!!.certainty)
        assertEquals(SyncState.PHYSICAL_MISMATCH, unknown.syncState)
    }

    @Test fun `likely mismatch without baseline still records partial evidence`() {
        val state = physical(false)
        val evidence = partial(state.expected.apply(R), setOf(Face.R, Face.U))
        val next = observed(requested(state), evidence)
        assertEquals(Certainty.LIKELY, next.quickCheck!!.certainty)
        assertFalse(next.quickCheck.matchesExpected)
        assertEquals(SyncState.PHYSICAL_MISMATCH, next.syncState)
        assertSame(evidence, next.observed)
        assertNull(next.touchedSinceVerified)
    }

    @Test fun `likely expected and inconsistent partial retain evidence and sync`() {
        val state = physical()
        val likely = observed(requested(state), partial(state.expected, setOf(Face.U)))
        assertEquals(Certainty.LIKELY, likely.quickCheck!!.certainty)
        assertEquals(state.observed, likely.observed)
        assertEquals(state.syncState, likely.syncState)
        val inconsistent = observed(requested(state), PhysicalObservation.Partial(mapOf(Face.U to emptyList())))
        assertEquals(Certainty.UNKNOWN, inconsistent.quickCheck!!.certainty)
        assertEquals(state.observed, inconsistent.observed)
        assertEquals(state.syncState, inconsistent.syncState)
    }

    @Test fun `stale learner result reoffers only learner checks and unknown id keeps pending`() {
        for (byLearner in listOf(true, false)) {
            val pending = requested(physical(), byLearner)
            val effect = pending.pendingQuickCheck!!
            val moved = pending.after(SessionEvent.PhysicalConfirmed(R, T0))
            val next =
                moved.after(
                    SessionEvent.QuickCheckObserved(effect.effectId, effect.revision, PhysicalObservation.Full(pending.expected), T0),
                )
            assertEquals(EffectResultDiscarded(effect.effectId, effect.revision, moved.revision), next.lastDiscarded)
            assertNull(next.pendingQuickCheck)
            assertEquals(if (byLearner) Text("msg.quick-check.stale") else null, next.quickCheckRetry)
            assertEquals(moved.expected, next.expected)
            assertEquals(moved.observed, next.observed)
            assertEquals(moved.syncState, next.syncState)
            val unknown =
                pending.after(
                    SessionEvent.QuickCheckObserved(-1, pending.revision, PhysicalObservation.Full(pending.expected), T0),
                )
            assertEquals(effect, unknown.pendingQuickCheck)
            assertNotNull(unknown.lastDiscarded)
        }
    }

    @Test fun `cancellation reset and undo coverage`() {
        val pending = requested(physical())
        assertEquals(pending, pending.after(SessionEvent.QuickCheckCancelled(-1, T0)))
        assertNull(pending.after(SessionEvent.QuickCheckCancelled(pending.pendingQuickCheck!!.effectId, T0)).pendingQuickCheck)
        val moved = physical().after(SessionEvent.PhysicalConfirmed(R, T0), undo)
        assertEquals(setOf(Face.R), moved.touchedSinceVerified)
        assertNull(moved.after(SessionEvent.ModeSwitched(CubeMode.VIRTUAL, T0)).touchedSinceVerified)
        assertNull(
            moved
                .after(
                    SessionEvent.ModeSwitched(CubeMode.VIRTUAL, T0),
                    SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0),
                ).touchedSinceVerified,
        )
    }

    @Test fun `undo after a full verification records the undone layer`() {
        val moved = physical().after(SessionEvent.PhysicalConfirmed(R, T0))
        val verified = observed(requested(moved), PhysicalObservation.Full(moved.expected))
        assertEquals(emptySet<Face>(), verified.touchedSinceVerified)
        val undone = verified.after(undo)
        assertEquals(setOf(Face.R), undone.touchedSinceVerified)
        assertEquals(true, Face.R in requested(undone).pendingQuickCheck!!.faces)
    }

    @Test fun `quick check state ends with physical mode and evidence ends with its expected`() {
        val pending = requested(physical(), byLearner = true)
        val virtual = pending.after(SessionEvent.ModeSwitched(CubeMode.VIRTUAL, T0))
        assertNull(virtual.pendingQuickCheck)
        val stale = requested(physical()).let { it.after(SessionEvent.PhysicalConfirmed(R, T0)) }
        val retried =
            stale.after(
                SessionEvent.QuickCheckObserved(
                    requested(physical()).pendingQuickCheck!!.effectId,
                    physical().revision,
                    PhysicalObservation.Full(physical().expected),
                    T0,
                ),
            )
        assertEquals(Text("msg.quick-check.stale"), retried.quickCheckRetry)
        val restarted = retried.after(lessonStarted(whiteCrossLesson()))
        assertNull(restarted.quickCheckRetry)
        assertNull(restarted.pendingQuickCheck)
        val checked = observed(requested(physical()), PhysicalObservation.Full(physical().expected))
        assertNotNull(checked.quickCheck)
        assertNull(checked.after(SessionEvent.PhysicalConfirmed(R, T0)).quickCheck)
    }

    @Test fun `a cancellation of another capture changes nothing`() {
        val pending = requested(physical()).copy(lastMoveFeedback = MoveFeedback.NEUTRAL)
        assertEquals(pending, pending.after(SessionEvent.QuickCheckCancelled(-1, T0)))
    }

    @Test fun `a consistent partial fitting no candidate is an unknown mismatch`() {
        val state = physical().after(SessionEvent.PhysicalConfirmed(R, T0))
        val evidence = partial(state.expected.apply(R, U, F, R), setOf(Face.R, Face.U))
        val next = observed(requested(state), evidence)
        assertEquals(Certainty.UNKNOWN, next.quickCheck!!.certainty)
        assertEquals(emptyList<Any>(), next.quickCheck.consistent)
        assertEquals(SyncState.PHYSICAL_MISMATCH, next.syncState)
        assertSame(evidence, next.observed)
    }

    @Test fun `a move replacing the last confirm on the ladder keeps the replaced layer touched`() {
        val moved = physical().after(SessionEvent.PhysicalConfirmed(R, T0))
        val verified = observed(requested(moved), PhysicalObservation.Full(moved.expected))
        assertEquals(emptySet<Face>(), verified.touchedSinceVerified)
        val replaced = verified.after(SessionEvent.MismatchReported(T0), SessionEvent.PhysicalConfirmed(U, T0))
        assertEquals(moved.expected.apply(R.inverse(), U), replaced.expected)
        assertEquals(setOf(Face.R, Face.U), replaced.touchedSinceVerified)
    }

    @Test fun `dismissing a mismatch drops its evidence and the editor drops a re-offer`() {
        val state = physical().after(SessionEvent.PhysicalConfirmed(R, T0))
        val mismatch = observed(requested(state), partial(state.expected.apply(FaceTurn(Face.R, Turn.HALF)), setOf(Face.R, Face.U)))
        assertEquals(SyncState.PHYSICAL_MISMATCH, mismatch.syncState)
        val dismissed = mismatch.after(SessionEvent.PhysicalConfirmed(null, T0))
        assertEquals(SyncState.PHYSICAL_PREDICTED, dismissed.syncState)
        assertNull(dismissed.quickCheck)
        val pending = requested(physical())
        val effect = pending.pendingQuickCheck!!
        val stale =
            pending.after(
                SessionEvent.PhysicalConfirmed(R, T0),
                SessionEvent.QuickCheckObserved(effect.effectId, effect.revision, PhysicalObservation.Full(pending.expected), T0),
            )
        assertEquals(Text("msg.quick-check.stale"), stale.quickCheckRetry)
        assertNull(stale.after(SessionEvent.EditorEntered(stale.expected, T0)).quickCheckRetry)
    }

    @Test fun `idle virtual and playback never issue capture`() {
        listOf(
            SessionState.IDLE,
            started(),
            SessionState.IDLE.after(playbackStarted(), SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0)),
        ).forEach {
            assertEquals(it, requested(it))
        }
    }

    @Test fun `empty partial records unknown and clears pending without changing the physical cube`() {
        val state = physical().after(SessionEvent.PhysicalConfirmed(R, T0))
        val before = observed(requested(state), partial(state.expected.apply(R), setOf(Face.R, Face.U)))
        val next = observed(requested(before), PhysicalObservation.Partial(emptyMap()))
        assertEquals(ObservationCheck(Certainty.UNKNOWN, emptyList()), next.quickCheck)
        assertEquals(before.touchedSinceVerified, next.touchedSinceVerified)
        assertEquals(before.physicalSolveConfirmed, next.physicalSolveConfirmed)
        assertEquals(before.observed, next.observed)
        assertEquals(before.syncState, next.syncState)
    }

    @Test fun `editor cancels pending so the old result is discarded without retry`() {
        val pending = requested(physical())
        val effect = pending.pendingQuickCheck!!
        val entered = pending.after(SessionEvent.EditorEntered(pending.expected, T0))
        assertNull(entered.pendingQuickCheck)
        val late =
            entered.after(
                SessionEvent.QuickCheckObserved(effect.effectId, effect.revision, PhysicalObservation.Full(pending.expected), T0),
            )
        assertNotNull(late.lastDiscarded)
        assertNull(late.quickCheckRetry)
        assertEquals(entered.observed, late.observed)
    }

    @Test fun `mismatch decision reports partial likely and full verified with appropriate actions`() {
        val state = physical().after(SessionEvent.PhysicalConfirmed(R, T0))
        val actual = state.expected.apply(FaceTurn(Face.R, Turn.HALF))
        val likely = observed(requested(state), partial(actual, setOf(Face.R, Face.U)))
        assertEquals(Certainty.LIKELY, likely.decision!!.certainty)
        assertEquals(true, likely.offersFullScan)
        assertFalse(likely.canAdoptObserved)
        val full = observed(requested(state), PhysicalObservation.Full(actual))
        assertEquals(Certainty.VERIFIED, full.decision!!.certainty)
        assertFalse(full.offersFullScan)
        assertEquals(true, full.canAdoptObserved)
    }

    @Test fun `full request overrides the subset plan`() {
        val state = physical().after(SessionEvent.PhysicalConfirmed(R, T0))
        val next = state.after(SessionEvent.QuickCheckRequested(at = T0, fullScan = true))
        assertEquals(Face.entries.toSet(), next.pendingQuickCheck!!.faces)
    }

    @Test fun `adopt full mismatch keeps checkpoint and resets history coverage and expected`() {
        val state = physical().after(SessionEvent.PhysicalConfirmed(R, T0))
        val actual = state.expected.apply(R)
        val mismatch = observed(requested(state), PhysicalObservation.Full(actual))
        val next = mismatch.after(SessionEvent.AdoptObserved(T0))
        assertEquals(actual, next.expected)
        assertEquals(actual, next.virtual)
        assertEquals(mismatch.checkpoint, next.checkpoint)
        assertFalse(next.history.canUndo)
        assertEquals(PhysicalObservation.Full(actual), next.observed)
        assertEquals(emptySet<Face>(), next.touchedSinceVerified)
        assertEquals(SyncState.PHYSICAL_PREDICTED, next.syncState)
        listOf(state, mismatch.copy(observed = null), mismatch.copy(observed = partial(actual, setOf(Face.R))))
            .forEach { assertEquals(it, it.after(SessionEvent.AdoptObserved(T0))) }
    }

    @Test fun `verified objective match confirms the physical solve`() {
        val state = physical().after(SessionEvent.EditorEntered(CubeState.SOLVED, T0))
        assertEquals(true, state.objectiveMet)
        assertFalse(state.physicalSolveConfirmed)
        val checked = observed(requested(state), PhysicalObservation.Full(state.expected))
        assertEquals(true, checked.physicalSolveConfirmed)
        assertFalse(checked.physicalSolveUnconfirmed)
    }

    @Test fun `configured playback checks cancels and commits on top after mismatch`() {
        val start = CubeState.SOLVED.apply(R, U)
        var state =
            SessionState.IDLE.after(
                SessionEvent.SolvePlaybackStarted(start, listOf(U.inverse(), R.inverse()), T0, TUTOR_CONFIG),
                SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0),
                SessionEvent.PhysicalConfirmed(null, T0),
                SessionEvent.PhysicalConfirmed(U.inverse(), T0),
            )
        val pending = requested(state)
        assertNotNull(pending.pendingQuickCheck)
        assertNull(pending.after(SessionEvent.QuickCheckCancelled(pending.pendingQuickCheck!!.effectId, T0)).pendingQuickCheck)
        val verified = observed(pending, PhysicalObservation.Full(state.expected))
        assertEquals(SyncState.PHYSICAL_VERIFIED, verified.syncState)
        val mismatch = observed(requested(verified), PhysicalObservation.Full(state.expected.apply(R)))
        assertEquals(SyncState.PHYSICAL_MISMATCH, mismatch.syncState)
        assertFalse(mismatch.canAdoptObserved)
        assertEquals(mismatch, mismatch.after(SessionEvent.AdoptObserved(T0)))
        state = mismatch.after(SessionEvent.PhysicalConfirmed(R.inverse(), T0))
        assertEquals(2, state.history.done.size)
        assertEquals(mismatch.expected.apply(R.inverse()), state.expected)
        assertEquals(SyncState.PHYSICAL_PREDICTED, state.syncState)
    }

    @Test fun `playback without tutor configuration ignores a quick check request`() {
        val start = CubeState.SOLVED.apply(R, U)
        val state =
            SessionState.IDLE.after(
                SessionEvent.SolvePlaybackStarted(start, listOf(U.inverse(), R.inverse()), T0),
                SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0),
            )
        val transition = reduce(state, SessionEvent.QuickCheckRequested(at = T0))
        assertSame(state, transition.state)
        assertEquals(emptyList<Effect>(), transition.effects)
    }

    @Test fun `reported mismatch after a verified match clears stale evidence`() {
        val state = physical()
        val verified = observed(requested(state), PhysicalObservation.Full(state.expected))
        assertEquals(SyncState.PHYSICAL_VERIFIED, verified.syncState)
        assertNotNull(verified.observed)
        assertNotNull(verified.quickCheck)
        val mismatch = verified.after(SessionEvent.MismatchReported(T0))
        assertEquals(SyncState.PHYSICAL_MISMATCH, mismatch.syncState)
        assertNull(mismatch.observed)
        assertNull(mismatch.quickCheck)
        assertFalse(mismatch.canAdoptObserved)
    }

    @Test fun `a second request while one is pending is ignored`() {
        val pending = requested(physical())
        val effect = checkNotNull(pending.pendingQuickCheck)
        val again = reduce(pending, SessionEvent.QuickCheckRequested(at = T0))
        assertEquals(pending, again.state)
        assertEquals(effect, again.state.pendingQuickCheck)
        assertEquals(emptyList<Effect>(), again.effects)
    }

    @Test fun `playback from an accepted scan keeps the scan as the full baseline into physical mode`() {
        val start = CubeState.SOLVED.apply(R, U)
        val playback =
            SessionState.IDLE.after(
                SessionEvent.SolvePlaybackStarted(start, listOf(U.inverse(), R.inverse()), T0, TUTOR_CONFIG, fromAcceptedScan = true),
            )
        assertEquals(PhysicalObservation.Full(start), playback.observed)
        assertEquals(emptySet<Face>(), playback.touchedSinceVerified)
        val physical = playback.after(SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0))
        assertEquals(emptySet<Face>(), physical.touchedSinceVerified)
        assertEquals(PhysicalObservation.Full(start), physical.observed)
        val turned = physical.after(SessionEvent.PhysicalConfirmed(U.inverse(), T0))
        assertEquals(setOf(Face.U), turned.touchedSinceVerified)
        val effect = requested(turned).pendingQuickCheck!!
        assertEquals(2, effect.faces.size)
        assertEquals(true, Face.U in effect.faces)
        // A switch back to virtual and on again drops the baseline: the cube may have changed meanwhile.
        val again = physical.after(SessionEvent.ModeSwitched(CubeMode.VIRTUAL, T0), SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0))
        assertNull(again.touchedSinceVerified)
        assertEquals(Face.entries.toSet(), requested(again).pendingQuickCheck!!.faces)
    }

    @Test fun `playback from manual entry still asks for every face`() {
        val start = CubeState.SOLVED.apply(R, U)
        val physical =
            SessionState.IDLE.after(
                SessionEvent.SolvePlaybackStarted(start, listOf(U.inverse(), R.inverse()), T0, TUTOR_CONFIG),
                SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0),
                SessionEvent.PhysicalConfirmed(U.inverse(), T0),
            )
        assertNull(physical.observed)
        assertNull(physical.touchedSinceVerified)
        assertEquals(Face.entries.toSet(), requested(physical).pendingQuickCheck!!.faces)
    }

    @Test fun `playback full mismatch offers a re-solve and a partial mismatch offers a full scan`() {
        val start = CubeState.SOLVED.apply(R, U)
        val physical =
            SessionState.IDLE.after(
                SessionEvent.SolvePlaybackStarted(start, listOf(U.inverse(), R.inverse()), T0, TUTOR_CONFIG, fromAcceptedScan = true),
                SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0),
                SessionEvent.PhysicalConfirmed(U.inverse(), T0),
            )
        val full = observed(requested(physical), PhysicalObservation.Full(physical.expected.apply(R)))
        assertEquals(SyncState.PHYSICAL_MISMATCH, full.syncState)
        assertEquals(true, full.canResolveObserved)
        assertFalse(full.canAdoptObserved)
        assertFalse(full.offersFullScan)
        val pending = requested(physical)
        val part = observed(pending, partial(physical.expected.apply(R), pending.pendingQuickCheck!!.faces))
        assertEquals(SyncState.PHYSICAL_MISMATCH, part.syncState)
        assertFalse(part.canResolveObserved)
        assertEquals(true, part.offersFullScan)
        assertFalse(
            physical()
                .after(SessionEvent.PhysicalConfirmed(R, T0))
                .let {
                    observed(requested(it), PhysicalObservation.Full(it.expected.apply(R)))
                }.canResolveObserved,
        )
    }

    // Story 14.3: a Quick Check is accepted partway through a step's Moves, in a Lesson and a 2-look Lesson alike.
    private fun midStep(
        kind: SessionKind,
        full: Boolean = true,
    ): SessionState {
        val lesson = oneLongStepLesson()
        val predicted =
            SessionState.IDLE.after(
                SessionEvent.LessonStarted(lesson, TUTOR_CONFIG, T0, kind = kind),
                SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0),
                SessionEvent.PhysicalConfirmed(null, T0),
            )
        val baseline = if (full) predicted.after(SessionEvent.EditorEntered(predicted.expected, T0)) else predicted
        // The step is `U F'`: one Done leaves it unfinished.
        return baseline.after(SessionEvent.PhysicalConfirmed(U, T0))
    }

    @Test fun `mid-step quick check in a Lesson and a 2-look Lesson verifies only a full match`() {
        for (kind in listOf(SessionKind.LESSON, SessionKind.TWO_LOOK)) {
            val state = midStep(kind)
            assertEquals(kind, state.kind)
            assertEquals(SyncState.PHYSICAL_PREDICTED, state.syncState)
            assertFalse(state.objectiveMet)
            val request = reduce(state, SessionEvent.QuickCheckRequested(at = T0))
            assertEquals(request.state.pendingQuickCheck, request.effects.single())
            val verified = observed(request.state, PhysicalObservation.Full(state.expected))
            assertEquals(Certainty.VERIFIED, verified.quickCheck!!.certainty)
            assertEquals(SyncState.PHYSICAL_VERIFIED, verified.syncState)
        }
    }

    @Test fun `mid-step partial mismatch is at most likely and offers a full scan`() {
        for (kind in listOf(SessionKind.LESSON, SessionKind.TWO_LOOK)) {
            val state = midStep(kind)
            val pending = requested(state)
            val actual = state.expected.apply(FaceTurn(Face.U, Turn.HALF))
            val mismatch = observed(pending, partial(actual, pending.pendingQuickCheck!!.faces))
            assertEquals(Certainty.LIKELY, mismatch.quickCheck!!.certainty)
            assertEquals(SyncState.PHYSICAL_MISMATCH, mismatch.syncState)
            assertEquals(true, mismatch.offersFullScan)
        }
    }

    @Test fun `mid-step partial match without a full baseline is never verified`() {
        for (kind in listOf(SessionKind.LESSON, SessionKind.TWO_LOOK)) {
            val state = midStep(kind, full = false)
            val pending = requested(state)
            val checked = observed(pending, partial(state.expected, setOf(Face.U, Face.F)))
            assertEquals(Certainty.LIKELY, checked.quickCheck!!.certainty)
            assertEquals(SyncState.PHYSICAL_PREDICTED, checked.syncState)
            assertNull(checked.observed)
        }
    }
}
