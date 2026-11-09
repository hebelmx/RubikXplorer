package dev.cubentia.domain.tutor

import dev.cubentia.domain.content.Lesson
import dev.cubentia.domain.content.LessonId
import dev.cubentia.domain.content.Stage
import dev.cubentia.domain.content.StageId

/**
 * The derived status of a [Stage] in the current epoch (FR-4, FR-19, FR-20, AD-10, AR-14).
 */
enum class StageStatus {
    LOCKED,
    IN_PROGRESS,
    COMPLETED,
    SKIPPED,
    SKIPPED_BY_PLACEMENT,
    MASTERED,
}

/**
 * Pure Stage status derivation and prerequisite gating over the append-only [ProgressEvent] log
 * (FR-4, FR-19, FR-20, AD-10, AR-14, AR-21).
 */
object StageProgress {
    /** The active epoch: the last [ProgressEvent.ProgressReset.epoch], or `0L` when never reset. */
    fun currentEpoch(events: List<ProgressEvent>): Long =
        (events.lastOrNull { it is ProgressEvent.ProgressReset } as? ProgressEvent.ProgressReset)?.epoch ?: 0L

    /** The events in the active epoch (everything after the last [ProgressEvent.ProgressReset]). */
    fun currentEpochEvents(events: List<ProgressEvent>): List<ProgressEvent> {
        val lastReset = events.indexOfLast { it is ProgressEvent.ProgressReset }
        return if (lastReset < 0) events else events.subList(lastReset + 1, events.size)
    }

    /**
     * True when [stage]'s prerequisites hold in the current epoch: its transitive external
     * [Lesson.prerequisites], or the preceding Stage in [stages] when [stage] is after index 0 and
     * declares no external Lesson prerequisites.
     */
    fun prerequisitesMet(
        stage: Stage,
        stages: List<Stage>,
        events: List<ProgressEvent>,
        mastered: Set<StageId> = emptySet(),
    ): Boolean {
        val epochEvents = currentEpochEvents(events)
        val ownLessons = stage.lessons.mapTo(mutableSetOf()) { it.id }
        val externalPrerequisites = transitivePrerequisites(stage.lessons, stages).filterNot { it in ownLessons }
        if (externalPrerequisites.isNotEmpty()) {
            return externalPrerequisites.all { isLessonPrerequisiteMet(it, stages, epochEvents, mastered) }
        }
        val stageIndex = stages.indexOfFirst { it.id == stage.id }
        if (stageIndex < 0) return false
        if (stageIndex == 0) return true
        val preceding = stages[stageIndex - 1]
        if (preceding.lessons.isEmpty()) {
            return isStageSatisfied(preceding.id, stageIndex - 1, stages, epochEvents, mastered)
        }
        val precedingLessons = preceding.lessons.map { it.id } + transitivePrerequisites(preceding.lessons, stages)
        return precedingLessons.all { isLessonPrerequisiteMet(it, stages, epochEvents, mastered) }
    }

    /**
     * True when [lesson]'s Stage is unlocked and all transitive [Lesson.prerequisites] are met in
     * the current epoch (or the Stage is already completed, skipped, or mastered so it remains revisitable).
     */
    fun isLessonUnlocked(
        lesson: Lesson,
        stages: List<Stage>,
        events: List<ProgressEvent>,
        mastered: Set<StageId> = emptySet(),
    ): Boolean {
        val epochEvents = currentEpochEvents(events)
        val stage =
            stages.firstOrNull { it.id == lesson.stageId }
                ?: stages.firstOrNull { candidate -> candidate.lessons.any { it.id == lesson.id } }
        if (stage != null) {
            when (status(stage, stages, epochEvents, mastered)) {
                StageStatus.LOCKED -> return false

                StageStatus.COMPLETED,
                StageStatus.SKIPPED,
                StageStatus.SKIPPED_BY_PLACEMENT,
                StageStatus.MASTERED,
                -> return true

                StageStatus.IN_PROGRESS -> Unit
            }
        }
        val ownLessons = stage?.lessons?.mapTo(mutableSetOf()) { it.id }.orEmpty()
        return transitivePrerequisites(listOf(lesson), stages).all { prerequisiteId ->
            if (prerequisiteId in ownLessons) {
                epochEvents.any { e -> e is ProgressEvent.LessonCompleted && e.lessonId == prerequisiteId }
            } else {
                isLessonPrerequisiteMet(prerequisiteId, stages, epochEvents, mastered)
            }
        }
    }

    /**
     * Derives [stage]'s [StageStatus] purely from the current epoch's [events] and externally
     * evaluated [mastered] Stage IDs. Neither Lesson completion nor skipping ever produces [StageStatus.MASTERED].
     */
    fun status(
        stage: Stage,
        stages: List<Stage>,
        events: List<ProgressEvent>,
        mastered: Set<StageId> = emptySet(),
    ): StageStatus {
        val epochEvents = currentEpochEvents(events)
        if (!isStageUnlocked(stage, stages, epochEvents, mastered)) return StageStatus.LOCKED
        if (stage.id in mastered) return StageStatus.MASTERED
        val completedLessons =
            epochEvents.mapNotNullTo(mutableSetOf()) { (it as? ProgressEvent.LessonCompleted)?.lessonId }
        if (stage.lessons.isNotEmpty() && stage.lessons.all { it.id in completedLessons }) {
            return StageStatus.COMPLETED
        }
        val latestSkip =
            epochEvents.lastOrNull { event ->
                (event is ProgressEvent.StageSkippedByCheck && event.stageId == stage.id) ||
                    (event is ProgressEvent.StageSkippedByPlacement && event.stageId == stage.id)
            }
        return when (latestSkip) {
            is ProgressEvent.StageSkippedByCheck -> StageStatus.SKIPPED
            is ProgressEvent.StageSkippedByPlacement -> StageStatus.SKIPPED_BY_PLACEMENT
            else -> StageStatus.IN_PROGRESS
        }
    }

    /** Maps every Stage in [stages] to its derived [StageStatus]. */
    fun statuses(
        stages: List<Stage>,
        events: List<ProgressEvent>,
        mastered: Set<StageId> = emptySet(),
    ): Map<StageId, StageStatus> = stages.associate { stage -> stage.id to status(stage, stages, events, mastered) }

    /**
     * The Stages in [stages] that transition from [StageStatus.LOCKED] under [before] to unlocked
     * (and not skipped) under [after].
     */
    fun newlyUnlocked(
        stages: List<Stage>,
        before: List<ProgressEvent>,
        after: List<ProgressEvent>,
        mastered: Set<StageId> = emptySet(),
    ): List<StageId> =
        stages
            .filter { stage ->
                val prior = status(stage, stages, before, mastered)
                val next = status(stage, stages, after, mastered)
                prior == StageStatus.LOCKED &&
                    next != StageStatus.LOCKED &&
                    next != StageStatus.SKIPPED &&
                    next != StageStatus.SKIPPED_BY_PLACEMENT
            }.map { it.id }

    private fun isStageUnlocked(
        stage: Stage,
        stages: List<Stage>,
        epochEvents: List<ProgressEvent>,
        mastered: Set<StageId>,
    ): Boolean {
        if (stage.id in mastered) return true
        if (
            epochEvents.any { event ->
                (event is ProgressEvent.StageUnlocked && event.stageId == stage.id) ||
                    (event is ProgressEvent.StageSkippedByCheck && event.stageId == stage.id) ||
                    (event is ProgressEvent.StageSkippedByPlacement && event.stageId == stage.id)
            }
        ) {
            return true
        }
        val ownLessons = stage.lessons.mapTo(mutableSetOf()) { it.id }
        if (epochEvents.any { it is ProgressEvent.LessonCompleted && it.lessonId in ownLessons }) {
            return true
        }
        return prerequisitesMet(stage, stages, epochEvents, mastered)
    }

    private fun isLessonPrerequisiteMet(
        lessonId: LessonId,
        stages: List<Stage>,
        epochEvents: List<ProgressEvent>,
        mastered: Set<StageId>,
    ): Boolean {
        if (epochEvents.any { it is ProgressEvent.LessonCompleted && it.lessonId == lessonId }) {
            return true
        }
        val ownerIndex = stages.indexOfFirst { stage -> stage.lessons.any { it.id == lessonId } }
        if (ownerIndex < 0) return false
        return isStageSatisfied(stages[ownerIndex].id, ownerIndex, stages, epochEvents, mastered)
    }

    private fun isStageSatisfied(
        stageId: StageId,
        stageIndex: Int,
        stages: List<Stage>,
        epochEvents: List<ProgressEvent>,
        mastered: Set<StageId>,
    ): Boolean {
        if (stageId in mastered) return true
        if (
            epochEvents.any { event ->
                (event is ProgressEvent.StageSkippedByCheck && event.stageId == stageId) ||
                    (event is ProgressEvent.StageSkippedByPlacement && event.stageId == stageId)
            }
        ) {
            return true
        }
        val subsequentIds = stages.subList(stageIndex + 1, stages.size).mapTo(mutableSetOf()) { it.id }
        return epochEvents.any { event ->
            (event is ProgressEvent.StageUnlocked && event.stageId in subsequentIds) ||
                (event is ProgressEvent.StageSkippedByCheck && event.stageId in subsequentIds) ||
                (event is ProgressEvent.StageSkippedByPlacement && event.stageId in subsequentIds)
        }
    }

    private fun transitivePrerequisites(
        roots: List<Lesson>,
        stages: List<Stage>,
    ): Set<LessonId> {
        val lessonsById = stages.flatMap { it.lessons }.associateBy { it.id }
        val visited = LinkedHashSet<LessonId>()
        val queue = ArrayDeque(roots.flatMap { it.prerequisites })
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (visited.add(id)) {
                lessonsById[id]?.prerequisites?.forEach(queue::addLast)
            }
        }
        return visited
    }
}
