package dev.rubikxplorer.application.usecase

import dev.rubikxplorer.application.session.entryState
import dev.rubikxplorer.domain.content.StageId
import dev.rubikxplorer.domain.predicate.AllOf
import dev.rubikxplorer.domain.predicate.CubeSolved
import dev.rubikxplorer.domain.predicate.LastLayerOriented
import dev.rubikxplorer.domain.predicate.MiddleLayerSolved
import dev.rubikxplorer.domain.predicate.WhiteCornersSolved
import dev.rubikxplorer.domain.predicate.WhiteCrossSolved
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
}
