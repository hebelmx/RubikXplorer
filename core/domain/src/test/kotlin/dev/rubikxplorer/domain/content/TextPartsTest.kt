package dev.rubikxplorer.domain.content

import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Facelets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** [IcuArguments], [FaceletString] and the ID types the validator builds on. */
class TextPartsTest {
    private fun args(message: String) = (IcuArguments.parse(message) as IcuResult.Parsed).arguments

    @Test
    fun `top-level ICU arguments are read and nested sub-message text is skipped`() {
        assertEquals(emptySet<String>(), args("Plain text, no arguments."))
        assertEquals(setOf("piece"), args("One {piece} shows colors no real {piece} has."))
        assertEquals(setOf("count", "name"), args("{count, plural, one {# move {nested}} other {# moves}} for { name }"))
        assertEquals(setOf("0"), args("Step {0}"))
    }

    @Test
    fun `ICU apostrophe quoting`() {
        assertEquals(emptySet<String>(), args("Braces '{like this}' are literal"))
        assertEquals(setOf("x"), args("It''s {x}"))
        assertEquals(setOf("x"), args("don't {x}"))
        assertEquals(emptySet<String>(), args("trailing '"))
        assertEquals(emptySet<String>(), args("open quote '{ never closes"))
        assertEquals(setOf("n"), args("{n, select, a {it''s '{'a'}'} other {b}}"))
    }

    @Test
    fun `unbalanced braces and nameless arguments are malformed`() {
        assertEquals(IcuResult.Malformed(5), IcuArguments.parse("Turn {face"))
        assertEquals(IcuResult.Malformed(4), IcuArguments.parse("Turn} it"))
        assertEquals(IcuResult.Malformed(0), IcuArguments.parse("{}"))
        assertEquals(IcuResult.Malformed(0), IcuArguments.parse("{two words}"))
    }

    @Test
    fun `facelet strings round-trip`() {
        val solved = CubeState.SOLVED.toFacelets()
        val text = FaceletString.encode(solved)
        assertEquals("W".repeat(9) + "R".repeat(9) + "G".repeat(9) + "Y".repeat(9) + "O".repeat(9) + "B".repeat(9), text)
        assertEquals(FaceletStringResult.Decoded(solved), FaceletString.decode(text))
        assertEquals(FaceletStringResult.WrongLength(Facelets.SIZE + 1), FaceletString.decode(text + "W"))
        assertEquals(FaceletStringResult.BadLetter(53, 'X'), FaceletString.decode(text.dropLast(1) + "X"))
    }

    @Test
    fun `id formats`() {
        assertEquals(true, StageId("stage-02-first-cross").isWellFormed)
        assertEquals(false, StageId("stage-2-first-cross").isWellFormed)
        assertEquals(true, LessonId("lesson-02-03").isWellFormed)
        assertEquals(false, LessonId("lesson-02-03a").isWellFormed)
        assertEquals(true, RecoveryRef("recovery-undo").isWellFormed)
        assertEquals(false, RecoveryRef("recovery-").isWellFormed)
        assertEquals("lesson-02-03", TextKey("lesson.lesson-02-03.goal.2").contentId)
        assertEquals("validation", TextKey("msg.validation.parity").contentId)
        assertNull(TextKey("lesson.lesson-02-03").contentId)
        assertEquals(
            "stage-02-first-cross|lesson-02-03|recovery-undo|a.b.c",
            listOf(
                StageId("stage-02-first-cross"),
                LessonId("lesson-02-03"),
                RecoveryRef("recovery-undo"),
                TextKey("a.b.c"),
            ).joinToString("|"),
        )
    }
}
