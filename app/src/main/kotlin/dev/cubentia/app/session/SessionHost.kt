package dev.cubentia.app.session

import dev.cubentia.app.content.twoLookMoves
import dev.cubentia.app.data.SerialWriter
import dev.cubentia.app.preferences.PreferenceStore
import dev.cubentia.application.port.AlgorithmStatusRepository
import dev.cubentia.application.port.AlgorithmStatusWrite
import dev.cubentia.application.port.AttemptLoad
import dev.cubentia.application.port.AttemptRepository
import dev.cubentia.application.port.CheckpointLoad
import dev.cubentia.application.port.CheckpointRepository
import dev.cubentia.application.port.CheckpointSave
import dev.cubentia.application.port.Clock
import dev.cubentia.application.port.ProgressLoad
import dev.cubentia.application.port.ProgressRepository
import dev.cubentia.application.port.ProgressWrite
import dev.cubentia.application.port.SessionSnapshotRepository
import dev.cubentia.application.port.SnapshotLoad
import dev.cubentia.application.port.SnapshotSave
import dev.cubentia.application.session.AttemptOutcome
import dev.cubentia.application.session.AttemptRecord
import dev.cubentia.application.session.Checkpoint
import dev.cubentia.application.session.Effect
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.application.session.SessionKind
import dev.cubentia.application.session.SessionSnapshot
import dev.cubentia.application.session.SessionState
import dev.cubentia.application.session.Transition
import dev.cubentia.application.session.reduce
import dev.cubentia.application.usecase.CompleteLesson
import dev.cubentia.application.usecase.PassSkipCheck
import dev.cubentia.application.usecase.ResetProgress
import dev.cubentia.application.usecase.SetAlgorithmStatus
import dev.cubentia.application.usecase.TwoLookPractice
import dev.cubentia.domain.content.AlgorithmId
import dev.cubentia.domain.content.AlgorithmStatus
import dev.cubentia.domain.content.ContentBundle
import dev.cubentia.domain.content.Stage
import dev.cubentia.domain.content.StageId
import dev.cubentia.domain.tutor.ProgressEvent
import dev.cubentia.domain.tutor.StageProgress
import dev.cubentia.domain.tutor.StageStatus
import dev.cubentia.domain.tutor.TwoLookMastery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
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
    private val completeLesson: CompleteLesson? = null,
    private val progress: ProgressRepository? = null,
    private val attempts: AttemptRepository? = null,
    private val resetProgress: ResetProgress? = null,
    private val passSkipCheck: PassSkipCheck? = null,
    private val algorithmStatusRepository: AlgorithmStatusRepository? = null,
    private val setAlgorithmStatus: SetAlgorithmStatus? = null,
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
        completeLesson: CompleteLesson,
        progress: ProgressRepository,
        attempts: AttemptRepository,
        resetProgress: ResetProgress,
        passSkipCheck: PassSkipCheck,
        algorithmStatusRepository: AlgorithmStatusRepository,
        setAlgorithmStatus: SetAlgorithmStatus,
    ) :
        this(
            clock,
            Dispatchers.Default.limitedParallelism(1),
            repository,
            writer,
            checkpoints = checkpoints,
            preferences = preferences,
            completeLesson = completeLesson,
            progress = progress,
            attempts = attempts,
            resetProgress = resetProgress,
            passSkipCheck = passSkipCheck,
            algorithmStatusRepository = algorithmStatusRepository,
            setAlgorithmStatus = setAlgorithmStatus,
        )

    constructor(clock: Clock) : this(clock, Dispatchers.Default.limitedParallelism(1))

    private val memoryStatuses = MutableStateFlow<Map<AlgorithmId, AlgorithmStatus>>(emptyMap())
    val algorithmStatuses: Flow<Map<AlgorithmId, AlgorithmStatus>> = algorithmStatusRepository?.statuses() ?: memoryStatuses.asStateFlow()
    private val statusUseCase = setAlgorithmStatus ?: algorithmStatusRepository?.let { SetAlgorithmStatus(it) }

    /** Status taps belong to no solve session; together these name each Learner command for this app run (AD-10). */
    private val statusSessionId = UUID.randomUUID().toString()
    private val nextStatusRevision = AtomicLong()

    suspend fun setAlgorithmStatus(
        id: AlgorithmId,
        status: AlgorithmStatus,
    ): Boolean {
        val useCase = statusUseCase
        if (useCase == null) {
            memoryStatuses.value = memoryStatuses.value + (id to status)
            return true
        }
        val revision = nextStatusRevision.getAndIncrement()
        return try {
            val result =
                if (writer != null) {
                    writer.read { useCase(id, status, clock.now(), statusSessionId, revision) }
                } else {
                    useCase(id, status, clock.now(), statusSessionId, revision)
                }
            result is AlgorithmStatusWrite.Written
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private val resetProgressUseCase = resetProgress ?: progress?.let { ResetProgress(it) }
    private val passSkipCheckUseCase = passSkipCheck ?: progress?.let { PassSkipCheck(it) }

    private val restored =
        CompletableDeferred<Unit>().also {
            if (repository == null && checkpoints == null && progress == null && attempts == null) it.complete(Unit)
        }
    private val restoring = Mutex()
    private var sessionId = ""
    private var contentRevision: String? = null
    private var stages: List<Stage> = emptyList()

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
        withContext(confined) {
            contentRevision = bundle.contentRevision
            stages = bundle.stages
        }
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
        withContext(confined) {
            contentRevision = bundle.contentRevision
            stages = bundle.stages
        }
        reloadProgress()
        reloadAttempts()
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
        if (snapshot != null && snapshot.kind.resumesOnColdStart && snapshot.contentRevision == bundle.contentRevision) {
            val lesson = (bundle.stages + bundle.twoLookStages).flatMap { it.lessons }.firstOrNull { it.id.value == snapshot.lessonId }
            if (lesson != null) {
                dispatch { SessionEvent.SessionRestored(snapshot, lesson, bundle.tutorConfig, it) }
            } else if (snapshot.kind == SessionKind.PRACTICE && snapshot.lessonId == TwoLookMastery.PRACTICE_ID.value) {
                // The generated LL practice is not content: rebuild it at its stored scramble with its 2-look set.
                // An older snapshot has no practiceEntry and falls back to its start cube.
                val practice = TwoLookPractice.lessonAt(snapshot.practiceEntry ?: snapshot.start)
                dispatch { SessionEvent.SessionRestored(snapshot, practice, bundle.tutorConfig, it, bundle.twoLookMoves()) }
            }
        }
        if (state.value.isActive) return
        resumeCheckpoint(bundle)
    }

    /** End playback, then read the Lesson Checkpoint after all queued writes. */
    suspend fun endSolve(bundle: ContentBundle) {
        withContext(confined) {
            contentRevision = bundle.contentRevision
            stages = bundle.stages
        }
        dispatch { SessionEvent.SolvePlaybackEnded(it) }
        if (!state.value.isActive) resumeCheckpoint(bundle)
    }

    private suspend fun resumeCheckpoint(bundle: ContentBundle) {
        withContext(confined) {
            contentRevision = bundle.contentRevision
            stages = bundle.stages
        }
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

    private val currentProgress = MutableStateFlow<List<ProgressEvent>>(emptyList())

    /** Reactive `progress_event` log loaded from [progress] on [restore] and refreshed on Lesson completion or [reloadProgress]. */
    val progressEvents: StateFlow<List<ProgressEvent>> = currentProgress.asStateFlow()

    private val currentAttempts = MutableStateFlow<List<AttemptRecord>>(emptyList())

    /** Reactive `attempt` log loaded from [attempts] on [restore] and refreshed on attempt completion or [reloadAttempts]. */
    val attemptRecords: StateFlow<List<AttemptRecord>> = currentAttempts.asStateFlow()

    /** Reloads [progressEvents] from [progress], ordered with queued writes when [writer] is present. */
    suspend fun reloadProgress() {
        val repo = progress ?: return
        val loaded =
            try {
                if (writer != null) writer.read { repo.all() } else repo.all()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ProgressLoad.Failed
            }
        if (loaded is ProgressLoad.Found) {
            currentProgress.value = loaded.events
        }
    }

    /**
     * Appends a [ProgressEvent.ProgressReset] via [resetProgress] single-writer use case
     * and refreshes [progressEvents].
     *
     * Returns true if the reset succeeded, or false if the repository write failed.
     */
    suspend fun resetProgress(): Boolean {
        val useCase = resetProgressUseCase
        if (useCase == null) {
            val nextEpoch = StageProgress.currentEpoch(currentProgress.value) + 1L
            val event = ProgressEvent.ProgressReset(nextEpoch, clock.now())
            currentProgress.value = currentProgress.value + event
            if (algorithmStatusRepository == null) memoryStatuses.value = emptyMap()
            return true
        }
        val result =
            try {
                if (writer != null) {
                    writer.read { useCase(at = clock.now()) }
                } else {
                    useCase(at = clock.now())
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ProgressWrite.Failed
            }
        return when (result) {
            is ProgressWrite.Written -> {
                refreshProgressAfterWrite(result.events)
                if (algorithmStatusRepository == null) memoryStatuses.value = emptyMap()
                true
            }

            else -> {
                false
            }
        }
    }

    /**
     * Appends a [ProgressEvent.StageSkippedByCheck] for [stageIds] and [ProgressEvent.StageUnlocked]
     * for newly unlocked stages via [passSkipCheck] single-writer use case and refreshes [progressEvents].
     */
    suspend fun passSkipCheck(
        stageIds: List<StageId>,
        stages: List<Stage>,
    ): Boolean {
        val useCase = passSkipCheckUseCase
        if (useCase == null) {
            val now = clock.now()
            val skipped = stageIds.map { ProgressEvent.StageSkippedByCheck(it, now) }
            val skippedSet = stageIds.toSet()
            val newlyUnlocked =
                StageProgress.newlyUnlocked(stages, currentProgress.value, currentProgress.value + skipped).toSet()
            val immediatelyFollowingLocked =
                stageIds
                    .mapNotNull { id ->
                        val index = stages.indexOfFirst { it.id == id }
                        stages.getOrNull(index + 1)?.id
                    }.filter { nextId ->
                        nextId !in skippedSet &&
                            StageProgress.status(stages.first { it.id == nextId }, stages, currentProgress.value) == StageStatus.LOCKED
                    }.toSet()
            val unlocked =
                stages
                    .map { it.id }
                    .filter { it in newlyUnlocked || it in immediatelyFollowingLocked }
                    .map { ProgressEvent.StageUnlocked(it, now) }
            val written = skipped + unlocked
            currentProgress.value = currentProgress.value + written
            return true
        }
        val result =
            try {
                if (writer != null) {
                    writer.read { useCase(stageIds, stages, at = clock.now()) }
                } else {
                    useCase(stageIds, stages, at = clock.now())
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ProgressWrite.Failed
            }
        return when (result) {
            is ProgressWrite.Written -> {
                refreshProgressAfterWrite(result.events)
                true
            }

            else -> {
                false
            }
        }
    }

    suspend fun passSkipCheck(
        stageId: StageId,
        stages: List<Stage>,
    ): Boolean = passSkipCheck(listOf(stageId), stages)

    /** Reloads [attemptRecords] from [attempts], ordered with queued writes when [writer] is present. */
    suspend fun reloadAttempts() {
        val repo = attempts ?: return
        val loaded =
            try {
                if (writer != null) writer.read { repo.all() } else repo.all()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AttemptLoad.Failed
            }
        if (loaded is AttemptLoad.Found) {
            currentAttempts.value = loaded.records
        }
    }

    private suspend fun refreshAttemptsAfterWrite(written: List<AttemptRecord>) {
        val repo = attempts
        if (repo != null) {
            val loaded =
                try {
                    repo.all()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    AttemptLoad.Failed
                }
            if (loaded is AttemptLoad.Found) {
                val knownKeys = loaded.records.map { it.attemptId to it.revision }.toSet()
                val uncommitted = written.filter { (it.attemptId to it.revision) !in knownKeys }
                currentAttempts.value = loaded.records + uncommitted
            } else {
                currentAttempts.value = currentAttempts.value + written
            }
        } else {
            currentAttempts.value = currentAttempts.value + written
        }
    }

    private suspend fun refreshProgressAfterWrite(written: List<ProgressEvent>) {
        val repo = progress
        if (repo != null) {
            val loaded =
                try {
                    repo.all()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ProgressLoad.Failed
                }
            if (loaded is ProgressLoad.Found) {
                currentProgress.value = loaded.events
            } else {
                currentProgress.value = currentProgress.value + written
            }
        } else {
            currentProgress.value = currentProgress.value + written
        }
    }

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
                if (stamped is SessionEvent.LessonStarted || stamped is SessionEvent.PracticeStarted ||
                    stamped is SessionEvent.SolvePlaybackStarted ||
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
                                if (effect.objectiveId.startsWith("skip-check-")) {
                                    null
                                } else {
                                    val id = UUID.randomUUID().toString()
                                    attemptId = id
                                    AttemptRecord.of(effect, sessionId, id)
                                }
                            }

                            is Effect.AttemptEnded -> {
                                if (effect.objectiveId.startsWith("skip-check-")) {
                                    attemptId = null
                                    null
                                } else {
                                    val id = attemptId ?: UUID.randomUUID().toString()
                                    attemptId = null
                                    val owner = if (effect.outcome == AttemptOutcome.ABANDONED) previousSessionId else sessionId
                                    AttemptRecord.of(effect, owner, id)
                                }
                            }

                            else -> {
                                null
                            }
                        }
                    }
                val snapshot = SessionSnapshot.of(transition.state, sessionId, contentRevision, attemptId)
                if (snapshot != null && repository != null && writer != null) {
                    writer.enqueue {
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
                        if (saved !is SnapshotSave.Failed && batch.isNotEmpty()) {
                            refreshAttemptsAfterWrite(batch)
                        }
                        if (saved is SnapshotSave.Failed) dispatch { SessionEvent.SnapshotSaveFailed(it) }
                    }
                } else if (attempts.isNotEmpty()) {
                    refreshAttemptsAfterWrite(attempts)
                }
            }
            val lesson = transition.state.lesson
            // In physical mode objectiveMet is only a prediction: completion waits for the Learner's word.
            val completed =
                if (transition.state.isPhysical) {
                    !before.physicalSolveConfirmed && transition.state.physicalSolveConfirmed
                } else {
                    transition.state.objectiveMet && (!before.objectiveMet || before.lesson?.id != lesson?.id)
                }
            if (completeLesson != null &&
                transition.state.kind?.isTaught == true &&
                lesson != null &&
                !lesson.id.value.startsWith("skip-check-") &&
                stamped !is SessionEvent.SessionRestored &&
                stamped !is SessionEvent.CheckpointRestored &&
                completed
            ) {
                val cube = transition.state.virtual
                val currentStages = stages
                val at = stamped.at
                val currentSessionId = sessionId
                val currentRevision = transition.state.revision
                if (writer != null) {
                    writer.enqueue {
                        val written = completeLesson.invoke(lesson, cube, currentStages, at, currentSessionId, currentRevision)
                        if (written is ProgressWrite.Written) {
                            refreshProgressAfterWrite(written.events)
                        }
                    }
                } else {
                    val written = completeLesson.invoke(lesson, cube, currentStages, at, currentSessionId, currentRevision)
                    if (written is ProgressWrite.Written) {
                        refreshProgressAfterWrite(written.events)
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
