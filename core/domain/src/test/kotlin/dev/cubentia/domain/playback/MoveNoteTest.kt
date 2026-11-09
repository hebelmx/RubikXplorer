package dev.cubentia.domain.playback

import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.content.VoiceGuide
import dev.cubentia.domain.cube.Axis
import dev.cubentia.domain.cube.CubeRotation
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Slice
import dev.cubentia.domain.cube.SliceTurn
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.cube.WideTurn
import dev.cubentia.domain.text.Text
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Story 3.6 AC 1 (AD-20): one fixed, argument-free note per Move value, saying what moves, never why. */
class MoveNoteTest {
    private val strings: Map<String, String> by lazy {
        (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings
    }

    private val moves: List<Move> =
        Turn.entries.flatMap { turn ->
            Face.entries.map { FaceTurn(it, turn) } +
                Face.entries.map { WideTurn(it, turn) } +
                Slice.entries.map { SliceTurn(it, turn) } +
                Axis.entries.map { CubeRotation(it, turn) }
        }

    private val template =
        Regex(
            "Turn the (.+) (clockwise|counter-clockwise|a half turn)(, like the (Up|Down|Left|Right|Front|Back) face)?: " +
                "the (.+) (moves|turns|move|turn) (.+)\\.",
        )

    private val purpose =
        Regex(
            "\\b(solve\\w*|so that|in order|to (place|put|fix|move|bring|get|make)|because|pair\\w*|insert\\w*|set ?up|" +
                "prepare\\w*|cross|corners?|edges?|algorithms?|goal|ready)\\b",
            RegexOption.IGNORE_CASE,
        )

    private fun message(move: Move): String = strings.getValue(MoveNote.of(move).key)

    @Test
    fun `the Right face clockwise names the right column moving up`() {
        val note = MoveNote.of(FaceTurn(Face.R, Turn.CLOCKWISE))
        assertEquals(Text("msg.move-note.face.r.clockwise"), note)
        assertEquals("Turn the Right face clockwise: the right column moves up.", strings.getValue(note.key))
    }

    @Test
    fun `every one of the 54 Moves has its own argument-free key in en dot json`() {
        assertEquals(54, moves.toSet().size)
        val notes = moves.map(MoveNote::of)
        assertEquals(54, notes.map { it.key }.toSet().size)
        notes.forEach { note ->
            assertEquals(emptyMap<String, Any>(), note.args, note.key)
            assertTrue(note.key in strings, "${note.key} is missing from en.json")
        }
        assertEquals(54, moves.map(::message).toSet().size, "two Moves share a message")
    }

    @Test
    fun `every note follows the face, direction and piece-group template`() {
        moves.forEach { move ->
            val text = message(move)
            assertTrue(template.matches(text), "$move: \"$text\" does not match the template")
            assertTrue(text.split(' ').size <= VoiceGuide.MAX_SENTENCE_WORDS, "$move: \"$text\" is too long")
        }
    }

    @Test
    fun `every note follows the voice guide`() {
        moves.forEach { move -> assertEquals(emptyList<String>(), VoiceGuide.problems(message(move)), "$move: ${message(move)}") }
    }

    @Test
    fun `no note claims a strategic purpose`() {
        moves.forEach { move ->
            val text = message(move)
            assertEquals(null, purpose.find(text)?.value, "$move: \"$text\" claims a purpose")
        }
    }

    @Test
    fun `the purpose check catches a purpose`() {
        assertTrue(purpose.containsMatchIn("Turn the Right face clockwise to bring the edge home."))
        assertTrue(purpose.containsMatchIn("This sets up the corner so that it pairs."))
    }
}
