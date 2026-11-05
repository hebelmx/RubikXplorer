package dev.rubikxplorer.app.session

import dev.rubikxplorer.app.data.SerialWriter
import dev.rubikxplorer.application.port.Clock
import dev.rubikxplorer.application.port.SessionSnapshotRepository
import dev.rubikxplorer.application.port.SnapshotLoad
import dev.rubikxplorer.application.port.SnapshotSave
import dev.rubikxplorer.application.session.SessionEvent
import dev.rubikxplorer.application.session.SessionSnapshot
import dev.rubikxplorer.application.session.SessionState
import dev.rubikxplorer.application.session.Transition
import dev.rubikxplorer.application.session.reduce
import dev.rubikxplorer.domain.content.ContentBundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * The one owner of the app's `LearningSession` (AD-3): it holds exactly one [SessionState] ([state])
 * and is the only place it changes. [dispatch] stamps each event with [Clock.now] and runs the pure
 * [reduce] on one confined dispatcher, so events apply one at a time, in dispatch order.
 */
@Singleton
class SessionHost internal constructor(
    private val clock: Clock,
    private val confined: CoroutineDispatcher,
    private val repository: SessionSnapshotRepository? = null,
    private val writer: SerialWriter? = null,
    /** Sees every event this host reduced, in order; tests use it to observe events that change no state. */
    private val reduced: (SessionEvent) -> Unit = {},
) {
    @Inject
    constructor(clock: Clock, repository: SessionSnapshotRepository, writer: SerialWriter) :
        this(clock, Dispatchers.Default.limitedParallelism(1), repository, writer)

    constructor(clock: Clock) : this(clock, Dispatchers.Default.limitedParallelism(1))

    private val restored = CompletableDeferred<Unit>().also { if (repository == null) it.complete(Unit) }
    private val restoring = Mutex()
    private var sessionId = ""
    private var contentRevision: String? = null

    suspend fun awaitRestored() {
        restored.await()
    }

    /**
     * One cold-start read, ordered with all saves; every outcome but cancellation releases waiting
     * screens. A cancelled attempt (the shell left composition mid-read) stays open for the next call.
     */
    suspend fun restore(bundle: ContentBundle) {
        restoring.withLock {
            if (restored.isCompleted) return
            try {
                restoreLatest(bundle)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed restore releases the screen to start a fresh Lesson.
            }
            restored.complete(Unit)
        }
    }

    private suspend fun restoreLatest(bundle: ContentBundle) {
        withContext(confined) { contentRevision = bundle.contentRevision }
        val loaded =
            try {
                writer?.read { repository?.latest() }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SnapshotLoad.Failed
            }
        // AD-16: a failed read is an event, not an absent snapshot; the state stays idle, so the Lesson starts fresh.
        if (loaded is SnapshotLoad.Failed) dispatch { SessionEvent.SnapshotLoadFailed(it) }
        val latest = loaded as? SnapshotLoad.Found ?: return
        val snapshot = latest.snapshot
        if (!snapshot.kind.resumesOnColdStart || snapshot.contentRevision != bundle.contentRevision) return
        val lesson = bundle.stages.flatMap { it.lessons }.firstOrNull { it.id.value == snapshot.lessonId } ?: return
        dispatch { SessionEvent.SessionRestored(snapshot, lesson, bundle.tutorConfig, it) }
    }

    private val current = MutableStateFlow(SessionState.IDLE)

    val state: StateFlow<SessionState> = current.asStateFlow()

    /**
     * Builds the event with the dispatch time (`SessionEvent::Undo`, or `{ MoveCommitted(move, it) }`),
     * reduces it and publishes the new state; returns the [Transition] for its effects and presentation.
     */
    suspend fun dispatch(event: (at: Instant) -> SessionEvent): Transition =
        withContext(confined) {
            val before = current.value
            val stamped = event(clock.now())
            val transition = reduce(before, stamped)
            current.value = transition.state
            reduced(stamped)
            if (transition.state !== before && transition.state.isActive) {
                if (stamped is SessionEvent.LessonStarted || stamped is SessionEvent.SolvePlaybackStarted ||
                    stamped is SessionEvent.SessionRestored
                ) {
                    sessionId = UUID.randomUUID().toString()
                }
                val snapshot = SessionSnapshot.of(transition.state, sessionId, contentRevision)
                if (snapshot != null && repository != null) {
                    writer?.enqueue {
                        // AD-16: a failed write is an event; the session keeps running and the next commit writes again.
                        val saved =
                            try {
                                repository.save(snapshot)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                SnapshotSave.Failed
                            }
                        if (saved is SnapshotSave.Failed) dispatch { SessionEvent.SnapshotSaveFailed(it) }
                    }
                }
            }
            transition
        }
}
