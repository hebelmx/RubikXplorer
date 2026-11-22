package dev.cubentia.application.usecase

import dev.cubentia.application.port.ProgressLoad
import dev.cubentia.application.port.ProgressRecord
import dev.cubentia.application.port.ProgressRepository
import dev.cubentia.application.port.ProgressSave
import dev.cubentia.application.port.ProgressWrite
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.content.StageId
import dev.cubentia.domain.tutor.ProgressEvent
import dev.cubentia.domain.tutor.StageProgress
import dev.cubentia.domain.tutor.StageStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/** Story 14.1 (FR-4, AD-10): every I/O matrix row of [ChooseEntryLevel]. */
class ChooseEntryLevelTest {
    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
    private val course = bundle.stages
    private val twoLook = bundle.twoLookStages
    private val t0 = Instant.fromEpochMilliseconds(1_000)
    private val stage5 = StageId("stage-05-last-layer-orientation")
    private val oll = StageId("stage-08-two-look-oll")
    private val pll = StageId("stage-09-two-look-pll")

    private class Repo(
        initial: List<ProgressEvent> = emptyList(),
    ) : ProgressRepository {
        val records = initial.map { ProgressRecord("seed", 0L, it) }.toMutableList()
        val events: List<ProgressEvent> get() = records.map { it.event }
        var failAppend = false
        var failLoad = false

        override suspend fun all(): ProgressLoad = if (failLoad) ProgressLoad.Failed else ProgressLoad.Found(events, records.toList())

        override suspend fun append(records: List<ProgressRecord>): ProgressSave {
            if (failAppend) return ProgressSave.Failed
            this.records += records
            return ProgressSave.Saved
        }
    }

    private fun skipped(ids: List<StageId>) = ids.map { ProgressEvent.StageSkippedByChoice(it, t0) }

    @Test
    fun `first two layers from a fresh log skips Stages 1 to 4 and unlocks Stage 5`() =
        runBlocking {
            val repo = Repo()
            val write = ChooseEntryLevel(repo)(EntryLevel.FIRST_TWO_LAYERS, course, twoLook, t0)

            val expected = skipped(course.take(4).map { it.id }) + ProgressEvent.StageUnlocked(stage5, t0)
            assertEquals(ProgressWrite.Written(expected), write)
            assertEquals(expected, repo.events)
            assertTrue(repo.records.all { it.sessionId == "entry-level:FIRST_TWO_LAYERS:epoch-0" && it.revision == 0L })
            val statuses = StageProgress.statuses(course, repo.events)
            course.take(4).forEach { assertEquals(StageStatus.SKIPPED, statuses[it.id]) }
            assertEquals(StageStatus.IN_PROGRESS, statuses[stage5])
            assertEquals(StageStatus.LOCKED, statuses[course[5].id])
        }

    @Test
    fun `beginner method from a fresh log skips Stages 1 to 7 and unlocks 2-look OLL`() =
        runBlocking {
            val repo = Repo()
            val write = ChooseEntryLevel(repo)(EntryLevel.BEGINNER_METHOD, course, twoLook, t0)

            val expected = skipped(course.map { it.id }) + ProgressEvent.StageUnlocked(oll, t0)
            assertEquals(ProgressWrite.Written(expected), write)
            assertEquals(7, course.size)
            val ollStage = twoLook.first()
            assertEquals(oll, ollStage.id)
            assertEquals(StageStatus.IN_PROGRESS, StageProgress.status(ollStage, listOf(ollStage), repo.events))
        }

    @Test
    fun `2-look from a fresh log skips the Course and both 2-look Stages without an unlock`() =
        runBlocking {
            val repo = Repo()
            val write = ChooseEntryLevel(repo)(EntryLevel.TWO_LOOK, course, twoLook, t0)

            val expected = skipped(course.map { it.id } + oll + pll)
            assertEquals(ProgressWrite.Written(expected), write)
            assertTrue(repo.events.none { it is ProgressEvent.StageUnlocked })
            twoLook.forEach { assertEquals(StageStatus.SKIPPED, StageProgress.status(it, listOf(it), repo.events)) }
        }

    @Test
    fun `existing progress only gains skips for locked or in-progress Stages and nothing is re-locked`() =
        runBlocking {
            val stage1 = course[0]
            val seed =
                stage1.lessons.map { ProgressEvent.LessonCompleted(it.id, t0) } +
                    ProgressEvent.StageUnlocked(course[1].id, t0) +
                    ProgressEvent.StageSkippedByCheck(course[1].id, t0) +
                    ProgressEvent.StageUnlocked(course[2].id, t0)
            val mastered = setOf(course[2].id)
            val repo = Repo(seed)
            val before = StageProgress.statuses(course, seed, mastered)
            assertEquals(StageStatus.COMPLETED, before[course[0].id])
            assertEquals(StageStatus.SKIPPED, before[course[1].id])

            val write = ChooseEntryLevel(repo)(EntryLevel.FIRST_TWO_LAYERS, course, twoLook, t0)

            val expected = skipped(listOf(course[2].id, course[3].id)) + ProgressEvent.StageUnlocked(stage5, t0)
            assertEquals(ProgressWrite.Written(expected), write)
            assertTrue(repo.events.none { it is ProgressEvent.ProgressReset })
            assertEquals(seed, repo.events.take(seed.size))
            val after = StageProgress.statuses(course, repo.events, mastered)
            assertEquals(StageStatus.COMPLETED, after[course[0].id])
            assertEquals(StageStatus.SKIPPED, after[course[1].id])
            assertEquals(StageStatus.MASTERED, after[course[2].id])
        }

    @Test
    fun `a target already reached is not unlocked again and only missing skips are written`() =
        runBlocking {
            val seed = listOf(ProgressEvent.StageSkippedByPlacement(course[0].id, t0), ProgressEvent.StageUnlocked(stage5, t0))
            val repo = Repo(seed)

            val write = ChooseEntryLevel(repo)(EntryLevel.FIRST_TWO_LAYERS, course, twoLook, t0)

            assertEquals(ProgressWrite.Written(skipped(course.subList(1, 4).map { it.id })), write)
            assertEquals(1, repo.events.count { it is ProgressEvent.StageUnlocked && it.stageId == stage5 })
        }

    @Test
    fun `choosing the same level twice writes nothing new`() =
        runBlocking {
            val repo = Repo()
            val choose = ChooseEntryLevel(repo)
            choose(EntryLevel.BEGINNER_METHOD, course, twoLook, t0)
            val count = repo.records.size

            assertEquals(ProgressWrite.Written(emptyList()), choose(EntryLevel.BEGINNER_METHOD, course, twoLook, t0))
            assertEquals(count, repo.records.size)
        }

    @Test
    fun `a failed write or load reports Failed and a missing target reports UnknownStage`() =
        runBlocking {
            val failing = Repo().apply { failAppend = true }
            assertEquals(ProgressWrite.Failed, ChooseEntryLevel(failing)(EntryLevel.FIRST_TWO_LAYERS, course, twoLook, t0))
            assertTrue(failing.records.isEmpty())
            val unloadable = Repo().apply { failLoad = true }
            assertEquals(ProgressWrite.Failed, ChooseEntryLevel(unloadable)(EntryLevel.TWO_LOOK, course, twoLook, t0))

            val repo = Repo()
            assertEquals(
                ProgressWrite.UnknownStage(stage5),
                ChooseEntryLevel(repo)(EntryLevel.FIRST_TWO_LAYERS, course.filterNot { it.id == stage5 }, twoLook, t0),
            )
            assertTrue(ChooseEntryLevel(repo)(EntryLevel.BEGINNER_METHOD, course, emptyList(), t0) is ProgressWrite.UnknownStage)
            assertTrue(ChooseEntryLevel(repo)(EntryLevel.TWO_LOOK, course, emptyList(), t0) is ProgressWrite.UnknownStage)
            assertTrue(repo.records.isEmpty())
        }

    @Test
    fun `inMemory reads and appends through the caller`() =
        runBlocking {
            var events = emptyList<ProgressEvent>()
            val choose = ChooseEntryLevel.inMemory(events = { events }, onWritten = { events = events + it })

            choose(EntryLevel.FIRST_TWO_LAYERS, course, twoLook, t0, sessionId = "s", revision = 3L)

            assertEquals(5, events.size)
        }
}
