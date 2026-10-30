package dev.rubikxplorer.app.session

import dev.rubikxplorer.application.session.SessionEvent
import dev.rubikxplorer.application.session.SessionKind
import dev.rubikxplorer.application.session.SessionState
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Turn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import kotlin.time.Instant

/** AC 1: one session, every event stamped at dispatch, concurrent dispatches serialized. */
class SessionHostTest {
    private val r = FaceTurn(Face.R, Turn.CLOCKWISE)

    @Test
    fun `starts idle and stamps each event with the clock at dispatch`() =
        runBlocking {
            val clock = TickingClock(startMillis = 5_000)
            val host = SessionHost(clock)
            assertSame(SessionState.IDLE, host.state.value)
            val stamped = mutableListOf<Instant>()
            val started =
                host.dispatch { at ->
                    stamped += at
                    SessionEvent.LessonStarted(whiteCrossLesson(), at)
                }
            val moved =
                host.dispatch { at ->
                    stamped += at
                    SessionEvent.MoveCommitted(r, at)
                }
            assertEquals(listOf(Instant.fromEpochMilliseconds(5_000), Instant.fromEpochMilliseconds(5_001)), stamped)
            assertEquals(1L, started.state.revision)
            assertEquals(2L, moved.state.revision)
            assertSame(moved.state, host.state.value)
            assertEquals(SessionKind.LESSON, host.state.value.kind)
        }

    @Test
    fun `holds at most one session - a new Lesson replaces the current one`() =
        runBlocking {
            val host = SessionHost(TickingClock())
            host.dispatch { SessionEvent.LessonStarted(whiteCrossLesson(), it) }
            host.dispatch { SessionEvent.MoveCommitted(r, it) }
            val second = whiteCrossLesson("lesson-01-02")
            host.dispatch { SessionEvent.LessonStarted(second, it) }
            val state = host.state.value
            assertEquals(second, state.lesson)
            assertEquals(state.entry, state.virtual)
            assertEquals(3L, state.revision)
        }

    @Test
    fun `concurrent dispatches apply one at a time with strictly increasing revisions`() =
        runBlocking {
            val host = SessionHost(TickingClock())
            host.dispatch { SessionEvent.LessonStarted(whiteCrossLesson(), it) }
            val count = 500
            val transitions =
                (0 until count)
                    .map { i ->
                        async(Dispatchers.Default) {
                            host.dispatch { at -> SessionEvent.MoveCommitted(allMoves[i % allMoves.size], at) }
                        }
                    }.awaitAll()
            val revisions = transitions.map { it.state.revision }
            assertEquals((2L..count + 1L).toList(), revisions.sorted())
            assertEquals(count + 1L, host.state.value.revision)
            assertEquals(count, host.state.value.history.done.size)
            assertTrue(transitions.all { it.presentation.single().revision == it.state.revision })
        }

    @Test
    fun `the clock is read inside the confined loop, so stamps follow revision order`() =
        runBlocking {
            val host = SessionHost(TickingClock())
            host.dispatch { SessionEvent.LessonStarted(whiteCrossLesson(), it) }
            val seen = Collections.synchronizedList(mutableListOf<Pair<Long, Instant>>())
            (0 until 200)
                .map { i ->
                    async(Dispatchers.Default) {
                        var at: Instant? = null
                        val t =
                            host.dispatch { stamp ->
                                at = stamp
                                SessionEvent.MoveCommitted(allMoves[i % allMoves.size], stamp)
                            }
                        seen += t.state.revision to at!!
                    }
                }.awaitAll()
            val byRevision = seen.sortedBy { it.first }.map { it.second }
            assertEquals(byRevision.sorted(), byRevision)
            assertEquals(byRevision.size, byRevision.toSet().size)
        }
}
