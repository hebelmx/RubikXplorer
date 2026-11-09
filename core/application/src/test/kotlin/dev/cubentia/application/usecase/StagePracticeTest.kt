package dev.cubentia.application.usecase

import dev.cubentia.application.session.entryState
import dev.cubentia.domain.content.StageId
import dev.cubentia.domain.predicate.AllOf
import dev.cubentia.domain.predicate.CubeSolved
import dev.cubentia.domain.predicate.LastLayerOriented
import dev.cubentia.domain.predicate.MiddleLayerSolved
import dev.cubentia.domain.predicate.WhiteCornersSolved
import dev.cubentia.domain.predicate.WhiteCrossSolved
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StagePracticeTest {
    @Test
    fun `stage 1 does not support practice`() {
        val practice = StagePractice(SeededRandomSource(1))
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                practice(StagePractice.STAGE_1_ID)
            }
        assertTrue(error.message?.contains("Stage 1") == true)
    }

    @Test
    fun `unknown stage throws`() {
        val practice = StagePractice(SeededRandomSource(1))
        assertThrows(IllegalArgumentException::class.java) {
            practice(StageId("stage-99-unknown"))
        }
    }

    @Test
    fun `stage 2 practice generates valid scramble where cross is not solved`() {
        val practice = StagePractice(SeededRandomSource(42))
        repeat(20) {
            val lesson = practice(StagePractice.STAGE_2_ID)
            assertEquals("practice-stage-02-first-cross", lesson.id.value)
            assertEquals(StagePractice.STAGE_2_ID, lesson.stageId)
            assertEquals(WhiteCrossSolved, lesson.completion)
            val state = entryState(lesson)
            assertFalse(WhiteCrossSolved.isSatisfied(state))
        }
    }

    @Test
    fun `stage 3 practice preserves white cross and scrambles corners`() {
        val practice = StagePractice(SeededRandomSource(100))
        repeat(20) {
            val lesson = practice(StagePractice.STAGE_3_ID)
            assertEquals("practice-stage-03-first-layer-corners", lesson.id.value)
            assertEquals(StagePractice.STAGE_3_ID, lesson.stageId)
            val state = entryState(lesson)
            assertTrue(WhiteCrossSolved.isSatisfied(state), "White cross must hold on entry for Stage 3")
            assertFalse(
                AllOf(listOf(WhiteCrossSolved, WhiteCornersSolved)).isSatisfied(state),
                "Corners must not already be solved on entry for Stage 3",
            )
        }
    }

    @Test
    fun `stage 4 practice preserves first layer and scrambles middle layer`() {
        val practice = StagePractice(SeededRandomSource(200))
        repeat(20) {
            val lesson = practice(StagePractice.STAGE_4_ID)
            assertEquals("practice-stage-04-middle-layer", lesson.id.value)
            assertEquals(StagePractice.STAGE_4_ID, lesson.stageId)
            val state = entryState(lesson)
            assertTrue(WhiteCrossSolved.isSatisfied(state), "White cross must hold on entry for Stage 4")
            assertTrue(WhiteCornersSolved.isSatisfied(state), "White corners must hold on entry for Stage 4")
            assertFalse(
                MiddleLayerSolved.isSatisfied(state),
                "Middle layer must not already be solved on entry for Stage 4",
            )
        }
    }

    @Test
    fun `stage 5 practice preserves F2L and scrambles yellow face orientation`() {
        val practice = StagePractice(SeededRandomSource(300))
        repeat(20) {
            val lesson = practice(StagePractice.STAGE_5_ID)
            assertEquals("practice-stage-05-last-layer-orientation", lesson.id.value)
            assertEquals(StagePractice.STAGE_5_ID, lesson.stageId)
            val state = entryState(lesson)
            assertTrue(WhiteCrossSolved.isSatisfied(state), "White cross must hold on entry for Stage 5")
            assertTrue(WhiteCornersSolved.isSatisfied(state), "White corners must hold on entry for Stage 5")
            assertTrue(MiddleLayerSolved.isSatisfied(state), "Middle layer must hold on entry for Stage 5")
            assertFalse(
                LastLayerOriented.isSatisfied(state),
                "Last layer must not already be oriented on entry for Stage 5",
            )
        }
    }

    @Test
    fun `stage 6 practice preserves F2L and yellow orientation and permutes pieces`() {
        val practice = StagePractice(SeededRandomSource(400))
        repeat(20) {
            val lesson = practice(StagePractice.STAGE_6_ID)
            assertEquals("practice-stage-06-last-layer-completion", lesson.id.value)
            assertEquals(StagePractice.STAGE_6_ID, lesson.stageId)
            val state = entryState(lesson)
            assertTrue(WhiteCrossSolved.isSatisfied(state), "White cross must hold on entry for Stage 6")
            assertTrue(WhiteCornersSolved.isSatisfied(state), "White corners must hold on entry for Stage 6")
            assertTrue(MiddleLayerSolved.isSatisfied(state), "Middle layer must hold on entry for Stage 6")
            assertTrue(LastLayerOriented.isSatisfied(state), "Last layer orientation must hold on entry for Stage 6")
            assertFalse(CubeSolved.isSatisfied(state), "Cube must not already be solved on entry for Stage 6")
        }
    }

    @Test
    fun `stage 7 practice generates full 25-move scramble`() {
        val practice = StagePractice(SeededRandomSource(500))
        repeat(20) {
            val lesson = practice(StagePractice.STAGE_7_ID)
            assertEquals("practice-stage-07-independent-practice", lesson.id.value)
            assertEquals(StagePractice.STAGE_7_ID, lesson.stageId)
            assertEquals(CubeSolved, lesson.completion)
            val state = entryState(lesson)
            assertFalse(CubeSolved.isSatisfied(state), "Cube must not already be solved on entry for Stage 7")
        }
    }

    @Test
    fun `skipCheckLesson for stage 1 generates simple 1-move scramble returning to CubeSolved`() {
        val practice = StagePractice(SeededRandomSource(10))
        repeat(20) {
            val lesson = practice.skipCheckLesson(StagePractice.STAGE_1_ID)
            assertEquals("skip-check-stage-01-fundamentals", lesson.id.value)
            assertEquals(StagePractice.STAGE_1_ID, lesson.stageId)
            assertEquals(CubeSolved, lesson.completion)
            val state = entryState(lesson)
            assertFalse(CubeSolved.isSatisfied(state), "Cube must not already be solved on entry for Stage 1 skip check")
        }
    }

    @Test
    fun `skipCheckLesson for stages 2 to 7 validates preservation of earlier stages and non-satisfaction of target`() {
        repeat(10) { iteration ->
            val practice = StagePractice(SeededRandomSource(77L + iteration))

            // Stage 2
            val lesson2 = practice.skipCheckLesson(StagePractice.STAGE_2_ID)
            assertEquals("skip-check-stage-02-first-cross", lesson2.id.value)
            assertEquals(WhiteCrossSolved, lesson2.completion)
            assertFalse(WhiteCrossSolved.isSatisfied(entryState(lesson2)))

            // Stage 3
            val lesson3 = practice.skipCheckLesson(StagePractice.STAGE_3_ID)
            assertEquals("skip-check-stage-03-first-layer-corners", lesson3.id.value)
            val state3 = entryState(lesson3)
            assertTrue(WhiteCrossSolved.isSatisfied(state3))
            assertFalse(AllOf(listOf(WhiteCrossSolved, WhiteCornersSolved)).isSatisfied(state3))

            // Stage 4
            val lesson4 = practice.skipCheckLesson(StagePractice.STAGE_4_ID)
            assertEquals("skip-check-stage-04-middle-layer", lesson4.id.value)
            val state4 = entryState(lesson4)
            assertTrue(WhiteCrossSolved.isSatisfied(state4))
            assertTrue(WhiteCornersSolved.isSatisfied(state4))
            assertFalse(MiddleLayerSolved.isSatisfied(state4))

            // Stage 5
            val lesson5 = practice.skipCheckLesson(StagePractice.STAGE_5_ID)
            assertEquals("skip-check-stage-05-last-layer-orientation", lesson5.id.value)
            val state5 = entryState(lesson5)
            assertTrue(WhiteCrossSolved.isSatisfied(state5))
            assertTrue(WhiteCornersSolved.isSatisfied(state5))
            assertTrue(MiddleLayerSolved.isSatisfied(state5))
            assertFalse(LastLayerOriented.isSatisfied(state5))

            // Stage 6
            val lesson6 = practice.skipCheckLesson(StagePractice.STAGE_6_ID)
            assertEquals("skip-check-stage-06-last-layer-completion", lesson6.id.value)
            val state6 = entryState(lesson6)
            assertTrue(WhiteCrossSolved.isSatisfied(state6))
            assertTrue(WhiteCornersSolved.isSatisfied(state6))
            assertTrue(MiddleLayerSolved.isSatisfied(state6))
            assertTrue(LastLayerOriented.isSatisfied(state6))
            assertFalse(CubeSolved.isSatisfied(state6))

            // Stage 7
            val lesson7 = practice.skipCheckLesson(StagePractice.STAGE_7_ID)
            assertEquals("skip-check-stage-07-independent-practice", lesson7.id.value)
            assertEquals(CubeSolved, lesson7.completion)
            assertFalse(CubeSolved.isSatisfied(entryState(lesson7)))
        }
    }

    @Test
    fun `courseSkipCheckLesson generates full 25-move scramble targeting CubeSolved`() {
        val practice = StagePractice(SeededRandomSource(99))
        repeat(20) {
            val lesson = practice.courseSkipCheckLesson()
            assertEquals("skip-check-course", lesson.id.value)
            assertEquals(StagePractice.STAGE_7_ID, lesson.stageId)
            assertEquals(CubeSolved, lesson.completion)
            val state = entryState(lesson)
            assertFalse(CubeSolved.isSatisfied(state), "Cube must not already be solved on entry for course skip check")
        }
    }

    @Test
    fun `skipCheckLesson with unknown stage throws`() {
        val practice = StagePractice(SeededRandomSource(1))
        assertThrows(IllegalArgumentException::class.java) {
            practice.skipCheckLesson(StageId("stage-unknown"))
        }
    }
}
