package dev.cubentia.application.usecase

import dev.cubentia.application.port.ProgressLoad
import dev.cubentia.application.port.ProgressRecord
import dev.cubentia.application.port.ProgressRepository
import dev.cubentia.application.port.ProgressSave
import dev.cubentia.application.port.ProgressWrite
import dev.cubentia.domain.content.Stage
import dev.cubentia.domain.content.StageId
import dev.cubentia.domain.tutor.ProgressEvent
import dev.cubentia.domain.tutor.StageProgress
import dev.cubentia.domain.tutor.StageStatus
import kotlin.time.Instant

/** The entry level a Learner who can already solve picks on "Where do you want to start?" (FR-4). */
enum class EntryLevel {
    /** Stages before *Last-layer orientation* are skipped; that Stage unlocks. */
    FIRST_TWO_LAYERS,

    /** Every Course Stage is skipped; the first 2-look Stage (*Faster last layer*) unlocks. */
    BEGINNER_METHOD,

    /** Every Course Stage and both 2-look Stages are skipped; the Learner continues in the Library. */
    TWO_LOOK,
}

/**
 * Single writer for [ProgressEvent.StageSkippedByChoice] (FR-4, FR-19, AD-10): only adds, never
 * downgrades. It skips only planned Stages that are `LOCKED` or `IN_PROGRESS` on the loaded log and
 * unlocks the target Stage only while it is `LOCKED`; it never writes [ProgressEvent.ProgressReset]
 * and never touches Lesson completions, Checkpoints, attempts or Algorithm Statuses.
 */
class ChooseEntryLevel(
    private val repository: ProgressRepository,
) {
    suspend operator fun invoke(
        level: EntryLevel,
        courseStages: List<Stage>,
        twoLookStages: List<Stage>,
        at: Instant = Instant.fromEpochMilliseconds(0),
        sessionId: String = "",
        revision: Long = -1L,
    ): ProgressWrite {
        val plan =
            when (val planned = plan(level, courseStages, twoLookStages)) {
                is Plan.Ready -> planned
                is Plan.Missing -> return ProgressWrite.UnknownStage(planned.stageId)
            }
        val loaded =
            when (val result = repository.all()) {
                is ProgressLoad.Found -> result
                ProgressLoad.Failed -> return ProgressWrite.Failed
            }
        val existing = loaded.events
        val skippedEvents =
            plan.skipped
                .filter { stage ->
                    val status = StageProgress.status(stage, plan.stages, existing)
                    status == StageStatus.LOCKED || status == StageStatus.IN_PROGRESS
                }.map { stage -> ProgressEvent.StageSkippedByChoice(stage.id, at) }
        val unlockedEvents =
            plan.target
                ?.takeIf { target -> StageProgress.status(target, plan.stages, existing) == StageStatus.LOCKED }
                ?.let { target -> listOf(ProgressEvent.StageUnlocked(target.id, at)) }
                .orEmpty()
        val newEvents = skippedEvents + unlockedEvents
        if (newEvents.isEmpty()) return ProgressWrite.Written(emptyList())
        val epoch = StageProgress.currentEpoch(existing)
        val actualSessionId = sessionId.ifEmpty { "entry-level:${level.name}:epoch-$epoch" }
        val actualRevision = if (revision >= 0L) revision else 0L
        val records = newEvents.map { event -> ProgressRecord(actualSessionId, actualRevision, event) }
        return when (repository.append(records)) {
            ProgressSave.Saved -> ProgressWrite.Written(newEvents)
            ProgressSave.Failed -> ProgressWrite.Failed
        }
    }

    private sealed interface Plan {
        data class Ready(
            val stages: List<Stage>,
            val skipped: List<Stage>,
            val target: Stage?,
        ) : Plan

        data class Missing(
            val stageId: StageId,
        ) : Plan
    }

    private fun plan(
        level: EntryLevel,
        course: List<Stage>,
        twoLook: List<Stage>,
    ): Plan =
        when (level) {
            EntryLevel.FIRST_TWO_LAYERS -> {
                val targetIndex = course.indexOfFirst { it.id == LAST_LAYER_ORIENTATION }
                if (targetIndex < 0) {
                    Plan.Missing(LAST_LAYER_ORIENTATION)
                } else {
                    Plan.Ready(course, course.subList(0, targetIndex), course[targetIndex])
                }
            }

            EntryLevel.BEGINNER_METHOD -> {
                val target = twoLook.firstOrNull()
                if (target == null) {
                    Plan.Missing(TWO_LOOK_PLACEHOLDER)
                } else {
                    Plan.Ready(course + target, course, target)
                }
            }

            EntryLevel.TWO_LOOK -> {
                if (twoLook.isEmpty()) {
                    Plan.Missing(TWO_LOOK_PLACEHOLDER)
                } else {
                    Plan.Ready(course + twoLook, course + twoLook, null)
                }
            }
        }

    companion object {
        /** The Stage *I can solve the first two layers* unlocks. */
        val LAST_LAYER_ORIENTATION = StageId("stage-05-last-layer-orientation")

        /** Reported when no 2-look Stage is supplied. */
        private val TWO_LOOK_PLACEHOLDER = StageId("stage-08-two-look-oll")

        /** Uses a caller's in-memory progress when no persistent repository is configured. */
        fun inMemory(
            events: () -> List<ProgressEvent>,
            onWritten: (List<ProgressEvent>) -> Unit,
        ): ChooseEntryLevel =
            ChooseEntryLevel(
                object : ProgressRepository {
                    override suspend fun all(): ProgressLoad = ProgressLoad.Found(events())

                    override suspend fun append(records: List<ProgressRecord>): ProgressSave {
                        onWritten(records.map { it.event })
                        return ProgressSave.Saved
                    }
                },
            )
    }
}
