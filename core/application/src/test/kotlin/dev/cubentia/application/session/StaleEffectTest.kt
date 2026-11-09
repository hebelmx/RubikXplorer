package dev.cubentia.application.session

import dev.cubentia.domain.text.Text
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** AC 2: a result for an effect issued at revision N that arrives after N+1 is recorded and discarded. */
class StaleEffectTest {
    @Test
    fun `a confirmation issued at N and answered after a Move at N+1 is discarded and re-offered`() {
        val committed = started().after(commit(R))
        val n = committed.revision
        val asked = reduce(committed, resetEvent)
        val issued = asked.effects.single() as Effect.ConfirmReset
        assertEquals(n, issued.revision)

        val moved = reduce(asked.state, commit(U)).state
        assertEquals(n + 1, moved.revision)

        val t = reduce(moved, issued.confirm())
        assertEquals(EffectResultDiscarded(issued.effectId, n, n + 1), t.state.lastDiscarded)
        assertEquals(n + 1, t.state.revision)
        assertEquals(moved.virtual, t.state.virtual)
        assertEquals(moved.history, t.state.history)
        assertEquals(emptyList<Presentation>(), t.presentation)
        val fresh = Effect.ConfirmReset(issued.effectId + 1, n + 1, Text("msg.session.reset-unsaved"))
        assertEquals(listOf(fresh), t.effects)
        assertEquals(fresh, t.state.pendingReset)

        // The fresh offer is current and resets.
        val reset = reduce(t.state, fresh.confirm()).state
        assertEquals(reset.entry, reset.virtual)
        assertEquals(n + 2, reset.revision)
    }

    @Test
    fun `a stale confirmation is not re-offered once nothing would be lost`() {
        val asked = reduce(started().after(commit(R)), resetEvent)
        val issued = asked.effects.single() as Effect.ConfirmReset
        val undone = reduce(asked.state, undo).state
        val t = reduce(undone, issued.confirm())
        assertEquals(EffectResultDiscarded(issued.effectId, issued.revision, undone.revision), t.state.lastDiscarded)
        assertEquals(emptyList<Effect>(), t.effects)
        assertNull(t.state.pendingReset)
        assertEquals(undone.copy(lastDiscarded = t.state.lastDiscarded), t.state)
    }

    @Test
    fun `a stale decline is recorded and changes nothing else`() {
        val asked = reduce(started().after(commit(R)), resetEvent)
        val issued = asked.effects.single() as Effect.ConfirmReset
        val moved = reduce(asked.state, commit(U)).state
        val t = reduce(moved, issued.decline())
        assertEquals(moved.copy(lastDiscarded = EffectResultDiscarded(issued.effectId, issued.revision, moved.revision)), t.state)
        assertEquals(emptyList<Effect>(), t.effects)
    }

    @Test
    fun `a result for an unknown effect id is discarded even at the current revision`() {
        val asked = reduce(started().after(commit(R)), resetEvent).state
        val pending = asked.pendingReset!!
        val wrongId = SessionEvent.ResetConfirmed(pending.effectId + 7, pending.revision, T0)
        val t = reduce(asked, wrongId)
        assertEquals(EffectResultDiscarded(pending.effectId + 7, pending.revision, asked.revision), t.state.lastDiscarded)
        assertEquals(pending, t.state.pendingReset)
        assertEquals(emptyList<Effect>(), t.effects)
        assertEquals(asked.virtual, t.state.virtual)
    }

    @Test
    fun `decline then a late confirm at the same revision is discarded and not re-offered`() {
        val asked = reduce(started().after(commit(R)), resetEvent).state
        val pending = asked.pendingReset!!
        val declined = reduce(asked, pending.decline()).state
        val t = reduce(declined, pending.confirm())
        assertEquals(declined.copy(lastDiscarded = EffectResultDiscarded(pending.effectId, pending.revision, declined.revision)), t.state)
        assertEquals(emptyList<Effect>(), t.effects)
        assertNull(t.state.pendingReset)
    }

    @Test
    fun `answering twice discards the second answer`() {
        val asked = reduce(started().after(commit(R)), resetEvent).state
        val pending = asked.pendingReset!!
        val declined = reduce(asked, pending.decline()).state
        val again = reduce(declined, pending.decline())
        assertEquals(EffectResultDiscarded(pending.effectId, pending.revision, declined.revision), again.state.lastDiscarded)
    }
}
