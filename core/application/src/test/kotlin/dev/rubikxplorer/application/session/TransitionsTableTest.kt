package dev.rubikxplorer.application.session

import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.tutor.MoveFeedback
import dev.rubikxplorer.domain.tutor.TeachingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** AC 4: `session/TRANSITIONS.md` has one row per [SessionEvent] type, and each row holds for [reduce]. */
class TransitionsTableTest {
    private data class Row(
        val event: String,
        val mutates: Set<String>,
        val undoable: Boolean,
        val clearsRedo: Boolean,
    )

    private val table = File("src/main/kotlin/dev/rubikxplorer/application/session/TRANSITIONS.md")

    private fun yesNo(cell: String): Boolean =
        when (cell) {
            "yes" -> true
            "no" -> false
            else -> error("expected yes or no, got '$cell'")
        }

    private fun rows(): List<Row> =
        table
            .readLines()
            .map { it.trim() }
            .filter { it.startsWith("|") && !it.startsWith("|-") && !it.startsWith("| Event") }
            .map { line ->
                val cells = line.trim('|').split("|").map { it.trim() }
                assertEquals(4, cells.size, line)
                val mutates = if (cells[1] == "—") emptySet() else cells[1].split(",").map { it.trim() }.toSet()
                Row(cells[0], mutates, yesNo(cells[2]), yesNo(cells[3]))
            }

    /** Every [SessionEvent] type (all are nested in it), by simple name. */
    private val eventTypes: Set<String> =
        SessionEvent::class.java.declaredClasses
            .filter { SessionEvent::class.java.isAssignableFrom(it) }
            .map { it.simpleName }
            .toSet()

    // Fixture states: a = R U committed; b = R committed, U undoable-redo; c = everything undone.
    private val other = whiteCrossLesson("lesson-01-02")
    private val fresh = started()
    private val a = fresh.after(commit(R), commit(U))
    private val b = a.after(undo)
    private val c = a.after(undo, undo)

    private fun asked(state: SessionState) = state.after(resetEvent).also { check(it.pendingReset != null) }

    private val askedB = asked(b)
    private val askedPending = askedB.pendingReset!!
    private val staleB = askedB.after(commit(F))

    // Tutor fixtures: a revealed fresh start; b revealed and asked; a two-step Lesson on its step-1 path state, with and without redo.
    private val revealed = fresh.after(reveal)
    private val askedRevealedB = asked(b.after(reveal))
    private val showMe = fresh.after(modeChanged(TeachingMode.SHOW_ME))
    private val two = started(twoStepLesson())
    private val twoAtStep1 = two.after(commit(U))
    private val twoAtStep1WithRedo = two.after(commit(U), commit(R), undo)

    // A Solve Playback session: the start of a Lesson replaces it, other events leave it as it is.
    private val playback = SessionState.IDLE.after(playbackStarted()).also { check(it.kind == SessionKind.SOLVE_PLAYBACK) }

    // Feedback and a Diagnosis standing with redo available is unreachable through reduce() (only a
    // Test me commit sets them, and a commit clears redo); this fixture checks Undo, Redo and a
    // commit in another mode clear them all the same.
    private val bWithFeedback = b.copy(lastMoveFeedback = MoveFeedback.NEUTRAL, lastDiagnosis = TURNED_DIAGNOSIS)

    // Checkpoint fixtures (white cross, entry SOLVED + F): F' completes it, so the Checkpoint moves to [F'];
    // R then R' completes it again ([F', R, R']); undoing back onto [F'] makes [F'] the Checkpoint again.
    private val crossed = fresh.after(commit(F_PRIME)).also { check(it.checkpoint == it.history) }
    private val recrossed = crossed.after(commit(R), commit(R_PRIME))
    private val recrossedUndone = recrossed.after(undo)
    private val backAtCrossWithRedo = recrossed.after(undo, undo, redo)
    private val crossedRedoR = crossed.after(commit(R), undo).also { check(it.history.canRedo && it.checkpoint != it.history) }

    /** Scenarios per event type; the exhaustive `when` fails to compile when an event type is added. */
    private fun scenarios(sample: SessionEvent): List<Pair<SessionState, SessionEvent>> =
        when (sample) {
            is SessionEvent.LessonStarted -> {
                listOf(SessionState.IDLE, a, b, askedB, revealed, playback).map { it to lessonStarted(other) }
            }

            is SessionEvent.SolvePlaybackStarted -> {
                listOf(SessionState.IDLE, a, b, askedB, revealed).map { it to playbackStarted() } +
                    listOf(a to playbackStarted(solution = listOf(U_PRIME)), playback to playbackStarted(CubeState.SOLVED, emptyList()))
            }

            is SessionEvent.MoveCommitted -> {
                listOf(fresh to commit(F), b to commit(R), askedB to commit(F), playback to commit(F)) +
                    // Test me sets the feedback; a commit in another mode clears a standing one.
                    listOf(fresh.after(modeChanged(TeachingMode.TEST_ME)) to commit(D), bWithFeedback to commit(F)) +
                    // Completing the cross is a boundary: the Checkpoint follows.
                    listOf(fresh to commit(F_PRIME))
            }

            is SessionEvent.Undo -> {
                listOf(fresh, a, b, askedB, playback, bWithFeedback, recrossedUndone).map { it to undo }
            }

            is SessionEvent.Redo -> {
                listOf(fresh, b, c, askedB, bWithFeedback, backAtCrossWithRedo).map { it to redo }
            }

            is SessionEvent.Reset -> {
                listOf(fresh, a, b, c, revealed, playback, bWithFeedback, crossed.after(undo)).map { it to resetEvent }
            }

            is SessionEvent.ResetConfirmed -> {
                listOf(
                    askedB to askedPending.confirm(),
                    staleB to askedPending.confirm(),
                    fresh to askedPending.confirm(),
                    askedRevealedB to askedRevealedB.pendingReset!!.confirm(),
                    asked(crossed).let { it to it.pendingReset!!.confirm() },
                )
            }

            is SessionEvent.ResetDeclined -> {
                listOf(askedB to askedPending.decline(), staleB to askedPending.decline()) +
                    listOf(askedB.copy(lastMoveFeedback = MoveFeedback.NEUTRAL, lastDiagnosis = TURNED_DIAGNOSIS) to askedPending.decline())
            }

            is SessionEvent.TeachingModeChanged -> {
                listOf(SessionState.IDLE, fresh, b, askedB, showMe, playback).map { it to modeChanged(TeachingMode.SHOW_ME) }
            }

            is SessionEvent.RevealRequested -> {
                listOf(SessionState.IDLE, fresh, b, askedB, revealed, playback, bWithFeedback).map { it to reveal }
            }

            is SessionEvent.HintRequested -> {
                // Climbs in Guide me (fresh, b with redo, a pending ask, standing feedback); ignored idle, in Show me, at the top and in playback.
                val top = fresh.after(hint, hint, hint)
                listOf(SessionState.IDLE, fresh, b, askedB, showMe, top, playback, bWithFeedback).map { it to hint }
            }

            is SessionEvent.StepAdvanced -> {
                listOf(SessionState.IDLE, fresh, b, twoAtStep1, twoAtStep1WithRedo).map { it to stepAdvanced }
            }

            is SessionEvent.RetryRequested -> {
                // Ignored idle, in playback and at the Checkpoint; moves the cube back, clears redo, cancels an ask, resets the rung.
                val ignored = listOf(SessionState.IDLE, playback, fresh, crossed)
                val applied =
                    listOf(
                        fresh.after(commit(R)),
                        crossedRedoR,
                        asked(crossed.after(commit(R))),
                        fresh.after(commit(R), hint),
                        bWithFeedback,
                    )
                (ignored + applied).map { it to retry }
            }
        }

    private val samples: Map<String, SessionEvent> =
        listOf(
            lessonStarted(other),
            playbackStarted(),
            commit(F),
            undo,
            redo,
            resetEvent,
            askedPending.confirm(),
            askedPending.decline(),
            modeChanged(TeachingMode.SHOW_ME),
            reveal,
            hint,
            stepAdvanced,
            retry,
        ).associateBy { it::class.java.simpleName }

    /** What the `session` column covers: the kind, its Lesson and tutor configuration or its Solution, and its entry state. */
    private fun session(s: SessionState) = listOf(s.kind, s.lesson, s.tutorConfig, s.solution, s.entry)

    private fun changed(
        before: SessionState,
        after: SessionState,
    ): Set<String> =
        buildSet {
            if (session(before) != session(after)) add("session")
            if (before.virtual != after.virtual) add("virtual")
            if (before.pendingReset != after.pendingReset) add("pending")

            fun tutor(s: SessionState) =
                listOf(
                    s.teachingMode,
                    s.noticeStep,
                    s.revealRequested,
                    s.hintRung,
                    s.hintsUsed,
                    s.lastMoveFeedback,
                    s.lastDiagnosis,
                )
            if (tutor(before) != tutor(after)) add("tutor")
            if (before.checkpoint != after.checkpoint) add("checkpoint")
        }

    @Test
    fun `every SessionEvent type has exactly one row and every row names a type`() {
        val names = rows().map { it.event }
        assertEquals(names.size, names.toSet().size, "duplicate rows: $names")
        assertEquals(eventTypes, names.toSet())
        assertEquals(eventTypes, samples.keys)
        assertEquals(13, eventTypes.size)
    }

    @Test
    fun `every row's Mutates, Undoable and Clears redo cells hold for the reducer`() {
        rows().forEach { row ->
            val runs = scenarios(samples.getValue(row.event)).map { (before, event) -> Triple(before, event, reduce(before, event).state) }
            val bumped = runs.filter { (before, _, after) -> after.revision > before.revision }

            val seen = runs.flatMap { (before, _, after) -> changed(before, after) }.toSet()
            assertEquals(row.mutates, seen, "${row.event}: Mutates")

            runs.forEach { (before, event, after) ->
                assertTrue(after.revision - before.revision in 0..1, "${row.event}: revision step from $event")
                val contentChanged = session(before) != session(after) || before.history != after.history
                assertEquals(contentChanged, after.revision == before.revision + 1, "${row.event}: revision iff content changed")
            }

            if (row.undoable) {
                assertTrue(bumped.isNotEmpty(), "${row.event}: no scenario applies")
                bumped.forEach { (before, _, after) ->
                    assertEquals(before.history.done.size + 1, after.history.done.size, "${row.event}: pushes one Move")
                    assertEquals(before.virtual, reduce(after, undo).state.virtual, "${row.event}: Undo returns")
                }
            } else {
                runs.forEach { (before, _, after) ->
                    assertTrue(after.history.done.size <= before.history.done.size, "${row.event}: pushes nothing undoable")
                }
            }

            if (row.clearsRedo) {
                assertTrue(bumped.any { (before, _, _) -> before.history.canRedo }, "${row.event}: no scenario with redo to clear")
                bumped.forEach { (_, _, after) -> assertTrue(!after.history.canRedo, "${row.event}: redo left after") }
            } else {
                assertTrue(
                    runs.any { (before, _, after) -> before.history.canRedo && after.history.canRedo },
                    "${row.event}: never keeps redo",
                )
            }
        }
    }
}
