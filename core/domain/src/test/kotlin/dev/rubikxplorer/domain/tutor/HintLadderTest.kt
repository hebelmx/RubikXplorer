package dev.rubikxplorer.domain.tutor

import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.content.ContentLoader
import dev.rubikxplorer.domain.content.ContentSource
import dev.rubikxplorer.domain.content.IcuArguments
import dev.rubikxplorer.domain.content.IcuResult
import dev.rubikxplorer.domain.predicate.WhitePieceSolved
import dev.rubikxplorer.domain.text.Text
import dev.rubikxplorer.domain.tutor.TutorFixtures.shipped
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Story 4.3: the Hint Ladder's rungs, its climb rule, the derived clue and the step target. */
class HintLadderTest {
    private val strings: Map<String, String> by lazy {
        (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings
    }

    @Test
    fun `the ladder climbs one rung at a time and stops at the demonstration`() {
        assertEquals(HintRung.CLUE, HintRung.NONE.next())
        assertEquals(HintRung.TARGET, HintRung.CLUE.next())
        assertEquals(HintRung.DEMONSTRATION, HintRung.TARGET.next())
        assertEquals(HintRung.DEMONSTRATION, HintRung.DEMONSTRATION.next())
        assertEquals(3, HintLadder.RUNGS)
    }

    @Test
    fun `a hint may climb only below the top, outside show me and while the step is not done`() {
        TeachingMode.entries.forEach { mode ->
            HintRung.entries.forEach { rung ->
                listOf(false, true).forEach { advance ->
                    val expected = mode != TeachingMode.SHOW_ME && rung != HintRung.DEMONSTRATION && !advance
                    assertEquals(expected, HintLadder.canClimb(mode, rung, advance), "$mode $rung $advance")
                }
            }
        }
        assertFalse(HintLadder.canClimb(TeachingMode.SHOW_ME, HintRung.NONE, false))
        assertTrue(HintLadder.canClimb(TeachingMode.TEST_ME, HintRung.TARGET, false))
    }

    @Test
    fun `a step bringing one white piece into place names it in the clue`() {
        assertEquals(Text("msg.hint.clue.piece", mapOf("piece" to "white_green")), HintLadder.clue(shipped("lesson-02-05"), 0))
        assertEquals(setOf(WhitePieceSolved.pieceNamed("white-green")), HintLadder.target(shipped("lesson-02-05"), 0))
    }

    @Test
    fun `a step with no single named target gets the generic clue`() {
        assertEquals(Text("msg.hint.clue.goal"), HintLadder.clue(shipped("lesson-01-01"), 0))
    }

    @Test
    fun `an out-of-range step has no target and the generic clue`() {
        val lesson = shipped("lesson-02-05")
        assertEquals(emptySet<Any>(), HintLadder.target(lesson, 99))
        assertEquals(emptySet<Any>(), HintLadder.target(lesson, -1))
        assertEquals(Text("msg.hint.clue.goal"), HintLadder.clue(lesson, 99))
    }

    @Test
    fun `a Lesson whose Demonstration cannot be built has no target`() {
        val broken = shipped("lesson-02-05").copy(entryState = "not a cube")
        assertEquals(emptySet<Any>(), HintLadder.target(broken, 0))
        assertEquals(Text("msg.hint.clue.goal"), HintLadder.clue(broken, 0))
    }

    @Test
    fun `rung 2 outlines a setup step's piece through the later step that brings it into place`() {
        // lesson-02-04 step 1 (U' R' U) brings nothing into place itself; F' then completes the white-green edge.
        val lesson = shipped("lesson-02-04")
        assertEquals(setOf(WhitePieceSolved.pieceNamed("white-green")), HintLadder.highlight(lesson, 0))
        assertEquals(HintLadder.target(lesson, 1), HintLadder.highlight(lesson, 1))
        assertEquals(HintLadder.target(shipped("lesson-02-05"), 0), HintLadder.highlight(shipped("lesson-02-05"), 0))
        assertEquals(emptySet<Any>(), HintLadder.highlight(lesson, 99))
    }

    @Test
    fun `each rung has its line, and none at rung zero`() {
        val lesson = shipped("lesson-02-05")
        assertNull(HintLadder.line(lesson, 0, HintRung.NONE))
        assertEquals(HintLadder.clue(lesson, 0), HintLadder.line(lesson, 0, HintRung.CLUE))
        assertEquals(Text("msg.hint.target"), HintLadder.line(lesson, 0, HintRung.TARGET))
        // Only a single target piece is outlined; with none or many, the line points at the Moves alone.
        assertEquals(emptySet<Any>(), HintLadder.target(shipped("lesson-02-04"), 0))
        assertEquals(Text("msg.hint.moves"), HintLadder.line(shipped("lesson-02-04"), 0, HintRung.TARGET))
        assertTrue(HintLadder.target(shipped("lesson-01-01"), 0).size > 1)
        assertEquals(Text("msg.hint.moves"), HintLadder.line(shipped("lesson-01-01"), 0, HintRung.TARGET))
        assertEquals(Text("msg.hint.demonstration"), HintLadder.line(lesson, 0, HintRung.DEMONSTRATION))
    }

    @Test
    fun `every rung line of every shipped Lesson step names a key in en dot json with matching arguments`() {
        shipped.forEach { lesson ->
            lesson.steps.indices.forEach { step ->
                HintRung.entries.mapNotNull { HintLadder.line(lesson, step, it) }.forEach { text ->
                    val message = strings[text.key]
                    assertTrue(message != null, "${lesson.id} step $step: ${text.key} is missing")
                    assertEquals(IcuResult.Parsed(text.args.keys), IcuArguments.parse(message!!), "${lesson.id} step $step")
                }
            }
        }
    }

    @Test
    fun `the clue names each of the eight white pieces by an id its select knows`() {
        val message = strings.getValue("msg.hint.clue.piece")
        WhitePieceSolved.NAMES.forEach { name ->
            assertTrue("${name.replace('-', '_')} {" in message, name)
        }
    }
}
