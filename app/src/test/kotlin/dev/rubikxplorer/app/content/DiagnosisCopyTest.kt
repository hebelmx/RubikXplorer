package dev.rubikxplorer.app.content

import android.app.Application
import dev.rubikxplorer.app.ui.lesson.SHOW_ME_HOW_LABEL
import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.content.ContentLoader
import dev.rubikxplorer.domain.content.ContentSource
import dev.rubikxplorer.domain.text.Text
import dev.rubikxplorer.domain.tutor.MoveFeedback
import dev.rubikxplorer.domain.tutor.RecoveryPath
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Story 4.2 (UX-DR24, UX-DR25): the shipped `en.json` resolves every Diagnosis, Recovery Path and
 * *Test me* text through ICU to the exact copy, including the EXPERIENCE literals.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DiagnosisCopyTest {
    private val strings: ContentStrings by lazy {
        ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)
    }

    private fun resolve(
        key: String,
        vararg args: Pair<String, String>,
    ): String = strings.resolve(Text(key, mapOf(*args)))

    @Test
    fun `test me lines`() {
        assertEquals(
            listOf("That's the move — keep going.", "That works too — you're closer.", "That didn't change much. Try another turn.", null),
            MoveFeedback.entries.map { feedback -> feedback.text?.let { strings.resolve(it) } },
        )
    }

    @Test
    fun `unexpected orientation names the held colors, including the EXPERIENCE literal`() {
        assertEquals(
            "Your cube is turned — hold it with white on the bottom, green in front.",
            resolve("msg.diagnosis.unexpected-orientation", "bottom" to "white", "front" to "green"),
        )
        assertEquals(
            "Your cube is turned — hold it with yellow on the bottom, green in front.",
            resolve("msg.diagnosis.unexpected-orientation", "bottom" to "yellow", "front" to "green"),
        )
        listOf("white", "yellow", "green", "blue", "red", "orange").forEach { color ->
            assertEquals(
                "Your cube is turned — hold it with $color on the bottom, $color in front.",
                resolve("msg.diagnosis.unexpected-orientation", "bottom" to color, "front" to color),
            )
        }
    }

    @Test
    fun `wrong piece names both pieces by their canonical names`() {
        assertEquals(
            "That moved the white-green edge; we want the white-red edge.",
            resolve("msg.diagnosis.wrong-piece", "moved" to "white_green", "target" to "white_red"),
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
            assertEquals("That moved the $name; we want the $name.", resolve("msg.diagnosis.wrong-piece", "moved" to id, "target" to id))
        }
    }

    @Test
    fun `wrong piece orientation, including the EXPERIENCE literal`() {
        assertEquals(
            "Right corner, but twisted — white should face down.",
            resolve("msg.diagnosis.wrong-piece-orientation", "kind" to "corner", "face" to "down"),
        )
        assertEquals(
            "Right corner, but twisted — white should face up.",
            resolve("msg.diagnosis.wrong-piece-orientation", "kind" to "corner", "face" to "up"),
        )
        assertEquals(
            "Right edge, but flipped — white should face front.",
            resolve("msg.diagnosis.wrong-piece-orientation", "kind" to "edge", "face" to "front"),
        )
    }

    @Test
    fun `completed layer, wrong move and unknown copy`() {
        assertEquals("That turn moved the cross.", resolve("msg.diagnosis.completed-layer-disturbed", "layer" to "cross"))
        assertEquals("That turn moved the first layer.", resolve("msg.diagnosis.completed-layer-disturbed", "layer" to "first_layer"))
        assertEquals("That turn moved the pieces you'd solved.", resolve("msg.diagnosis.completed-layer-disturbed", "layer" to "other"))
        assertEquals("That turn moved a piece you'd placed.", resolve("msg.diagnosis.wrong-move-or-direction"))
        assertEquals("I can't tell what changed.", resolve("msg.diagnosis.unknown-discrepancy"))
    }

    @Test
    fun `every recovery path prompt`() {
        assertEquals(
            mapOf(
                RecoveryPath.UNDO to "Undo, or try another turn.",
                RecoveryPath.GUIDED_RECOVERY to "Undo, or let me guide you back.",
                RecoveryPath.RETRY to "Undo, or start this step again.",
                RecoveryPath.REORIENT to "Turn it back with the rotation keys.",
                RecoveryPath.RE_ENTER to "Fix the outlined stickers and enter your cube again.",
            ),
            RecoveryPath.entries.associateWith { strings.resolve(it.prompt) },
        )
    }

    @Test
    fun `show me how offer label`() {
        assertEquals("Show me how", strings.resolve(SHOW_ME_HOW_LABEL))
    }
}
