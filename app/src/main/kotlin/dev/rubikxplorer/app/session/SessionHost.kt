package dev.rubikxplorer.app.session

import dev.rubikxplorer.app.data.SerialWriter
import dev.rubikxplorer.app.preferences.PreferenceStore
import dev.rubikxplorer.application.port.CheckpointLoad
import dev.rubikxplorer.application.port.CheckpointRepository
import dev.rubikxplorer.application.port.CheckpointSave
import dev.rubikxplorer.application.port.Clock
import dev.rubikxplorer.application.port.SessionSnapshotRepository
import dev.rubikxplorer.application.port.SnapshotLoad
import dev.rubikxplorer.application.port.SnapshotSave
import dev.rubikxplorer.application.session.AttemptOutcome
import dev.rubikxplorer.application.session.AttemptRecord
import dev.rubikxplorer.application.session.Checkpoint
import dev.rubikxplorer.application.session.Effect
import dev.rubikxplorer.application.session.SessionEvent
import dev.rubikxplorer.application.session.SessionKind
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
    private val checkpoints: CheckpointRepository? = null,
    private val preferences: PreferenceStore? = null,
    /** Sees every event this host reduced, in order; tests use it to observe events that change no state. */
    private val reduced: (SessionEvent) -> Unit = {},
) {
    @Inject
    constructor(
        clock: Clock,
        repository: SessionSnapshotRepository,
        writer: SerialWriter,
        checkpoints: CheckpointRepository,
        preferences: PreferenceStore,
    ) :
        this(clock, Dispatchers.Default.limitedParallelism(1), repository, writer, checkpoints = checkpoints, preferences = preferences)

    constructor(clock: Clock) : this(clock, Dispatchers.Default.limitedParallelism(1))

    private val restored = CompletableDeferred<Unit>().also { if (repository == null && checkpoints == null) it.complete(Unit) }
    private val restoring = Mutex()
    private var sessionId = ""
    private var contentRevision: String? = null

    /** The open attempt's id; confined like [sessionId]. */
    private var attemptId: String? = null

    /** Attempt rows whose snapshot save failed; touched only inside [writer] operations, which run one at a time. */
    private var pendingAttempts = emptyList<AttemptRecord>()

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
        val snapshot = (loaded as? SnapshotLoad.Found)?.snapshot
        if (snapshot?.kind == SessionKind.SOLVE_PLAYBACK) return
        if (snapshot != null && snapshot.kind.resumesOnColdStart && snapshot.contentRevision == bundle.contentRevision) {
            val lesson = bundle.stages.flatMap { it.lessons }.firstOrNull { it.id.value == snapshot.lessonId }
            if (lesson != null) dispatch { SessionEvent.SessionRestored(snapshot, lesson, bundle.tutorConfig, it) }
        }
        if (state.value.isActive) return
        val checkpoint =
            try {
                writer?.read { checkpoints?.find(SessionKind.LESSON) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                CheckpointLoad.Failed
            }
        if (checkpoint is CheckpointLoad.Failed) dispatch { SessionEvent.CheckpointLoadFailed(it) }
        if (checkpoint is CheckpointLoad.Found) {
            val stored = checkpoint.checkpoint
            // A removed Lesson restarts its Stage's first Lesson at the stored cube; a removed Stage stays IDLE.
            val lesson =
                bundle.stages.flatMap { it.lessons }.firstOrNull { it.id.value == stored.lessonId }
                    ?: bundle.stages
                        .firstOrNull { it.id.value == stored.stageId }
                        ?.lessons
                        ?.firstOrNull()
                    ?: return
            dispatch { SessionEvent.CheckpointRestored(checkpoint.checkpoint, lesson, bundle.tutorConfig, bundle.contentRevision, it) }
        }
    }

    private val current = MutableStateFlow(SessionState.IDLE)

    val state: StateFlow<SessionState> = current.asStateFlow()

    /**
     * Builds the event with the dispatch time (`SessionEvent::Undo`, or `{ MoveCommitted(move, it) }`),
     * reduces it and publishes the new state; returns the [Transition] for its effects and presentation.
     */
    suspend fun dispatch(event: (at: Instant) -> SessionEvent): Transition =
        withContext(confined) {
            val previousSessionId = sessionId
            val before = current.value
            val stamped = event(clock.now())
            val transition = reduce(before, stamped)
            current.value = transition.state
            reduced(stamped)
            if (transition.state !== before && transition.state.isActive) {
                if (stamped is SessionEvent.LessonStarted || stamped is SessionEvent.SolvePlaybackStarted ||
                    stamped is SessionEvent.SessionRestored || stamped is SessionEvent.CheckpointRestored
                ) {
                    sessionId = UUID.randomUUID().toString()
                }
                // The open attempt keeps its id across a snapshot restore, so its STARTED and ENDED rows pair exactly.
                if (stamped is SessionEvent.SessionRestored) attemptId = stamped.snapshot.attemptId
                val attempts =
                    transition.effects.mapNotNull { effect ->
                        when (effect) {
                            is Effect.AttemptStarted -> {
                                val id = UUID.randomUUID().toString()
                                attemptId = id
                                AttemptRecord.of(effect, sessionId, id)
                            }

                            is Effect.AttemptEnded -> {
                                val id = attemptId ?: UUID.randomUUID().toString()
                                attemptId = null
                                val owner = if (effect.outcome == AttemptOutcome.ABANDONED) previousSessionId else sessionId
                                AttemptRecord.of(effect, owner, id)
                            }

                            else -> {
                                null
                            }
                        }
                    }
                val snapshot = SessionSnapshot.of(transition.state, sessionId, contentRevision, attemptId)
                if (snapshot != null && repository != null) {
                    writer?.enqueue {
                        // AD-16: a failed write is an event; the session keeps running and the next commit writes again.
                        // Attempt rows of a failed write stay pending and retry with that next commit; their
                        // (sessionId, revision) keys make a replay a no-op.
                        val batch = pendingAttempts + attempts
                        val saved =
                            try {
                                repository.save(snapshot, batch)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                pendingAttempts = batch
                                throw cancelled
                            } catch (_: Exception) {
                                SnapshotSave.Failed
                            }
                        pendingAttempts = if (saved is SnapshotSave.Failed) batch else emptyList()
                        if (saved is SnapshotSave.Failed) dispatch { SessionEvent.SnapshotSaveFailed(it) }
                    }
                }
            }
            transition.effects.filterIsInstance<Effect.PersistCheckpoint>().forEach { effect ->
                val dto = Checkpoint.of(effect, contentRevision.orEmpty())
                if (checkpoints != null) {
                    writer?.enqueue {
                        val saved =
                            try {
                                checkpoints.save(dto)
                            } catch (
                                cancelled: kotlinx.coroutines.CancellationException,
                            ) {
                                throw cancelled
                            } catch (_: Exception) {
                                CheckpointSave.Failed
                            }
                        if (saved is CheckpointSave.Failed) dispatch { SessionEvent.CheckpointSaveFailed(it) }
                    }
                }
            }
            transition.effects.filterIsInstance<Effect.UpdatePreference>().forEach { preferences?.update(it.preference) }
            transition
        }
}
