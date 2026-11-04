package dev.rubikxplorer.app.content

import android.app.Application
import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.content.ContentLoader
import dev.rubikxplorer.domain.content.ContentSource
import dev.rubikxplorer.domain.text.Text
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Story 4.3 (FR-11, UX-DR9): every Hint Ladder line in the shipped `en.json` resolves through ICU to its exact copy. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HintCopyTest {
    private val strings: ContentStrings by lazy {
        ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)
    }

    private fun resolve(
        key: String,
        vararg args: Pair<String, String>,
    ): String = strings.resolve(Text(key, mapOf(*args)))

    @Test
    fun `the piece clue names the target piece`() {
        assertEquals(
            "Find the white-green edge. Where does it belong, and which face can carry it there?",
            resolve("msg.hint.clue.piece", "piece" to "white_green"),
        )
        val names =
            mapOf(
                "white_green" to "white-green edge",
                "white_red" to "white-red edge",
                "white_blue" to "white-blue edge",
                "white_orange" to "white-orange edge",
                "white_green_red" to "white-green-red corner",
                "white_red_blue" to "white-red-blue corner",
                "white_blue_orange" to "white-blue-orange corner",
                "white_orange_green" to "white-orange-green corner",
            )
        names.forEach { (id, name) ->
            assertEquals(
                "Find the $name. Where does it belong, and which face can carry it there?",
                resolve("msg.hint.clue.piece", "piece" to id),
            )
        }
    }

    @Test
    fun `the goal clue, target and demonstration lines`() {
        assertEquals(
            "Read the Goal again: what has to move, and what has to stay where it is?",
            resolve("msg.hint.clue.goal"),
        )
        assertEquals("The outlined piece is the one to move. The Moves are below.", resolve("msg.hint.target"))
        assertEquals("The Moves for this step are below.", resolve("msg.hint.moves"))
        assertEquals("Watch this step on the cube, then try it yourself.", resolve("msg.hint.demonstration"))
    }
}
