package dev.cubentia.app.content

import android.app.Application
import dev.cubentia.app.session.testBundle
import dev.cubentia.app.session.whiteCrossLesson
import dev.cubentia.domain.content.ContentLoadError
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.content.TextKey
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.PieceCheck
import dev.cubentia.domain.text.Text
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** AC 1: content text resolves through ICU, and the catalog turns a load into a sealed state. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ContentStringsTest {
    private val strings =
        ContentStrings(
            mapOf(
                "msg.validation.impossible-piece" to "One {piece} shows colors that no real {piece} has.",
                "msg.session.reset-unsaved" to "Reset the cube? This lesson's unsaved steps will be lost.",
                "lesson.lesson-01-01.goal.1" to "Turn {count, plural, one {# face} other {# faces}}",
            ),
        )

    @Test
    fun `named ICU arguments are substituted`() {
        assertEquals(
            "One edge shows colors that no real edge has.",
            strings.resolve(Text("msg.validation.impossible-piece", mapOf("piece" to "edge"))),
        )
        assertEquals("Turn 2 faces", strings.resolve(Text("lesson.lesson-01-01.goal.1", mapOf("count" to 2))))
    }

    @Test
    fun `shipped repeated-color messages say three for a one-color corner and two otherwise`() {
        val shipped = ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)
        // The URF corner (U9, R1, F3) and the UF edge (U8, F2) in URFDLB facelet order.
        val corner = listOf(8, 9, 20)
        val edge = listOf(7, 19)
        val opposite = mapOf(Color.WHITE to Color.YELLOW, Color.GREEN to Color.BLUE, Color.RED to Color.ORANGE)
        Color.entries.forEach { color ->
            val name = color.name.lowercase()
            val other = Color.entries.first { it != color && opposite[it] != color && opposite[color] != it }

            fun line(vararg stickers: Pair<Int, Color>): String {
                val colors = MutableList<Color?>(54) { null }.also { list -> stickers.forEach { (i, c) -> list[i] = c } }
                return shipped.resolve(PieceCheck.problems(colors).single().reason)
            }
            assertEquals("This corner shows three ${name}s.", line(*corner.map { it to color }.toTypedArray()))
            assertEquals("This corner shows two ${name}s.", line(corner[0] to color, corner[1] to color, corner[2] to other))
            assertEquals("This edge shows two ${name}s.", line(*edge.map { it to color }.toTypedArray()))
        }
    }

    @Test
    fun `shipped editor messages pluralize and fill their lists (#253)`() {
        val shipped = ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)

        fun line(
            key: String,
            vararg args: Pair<String, Any>,
        ) = shipped.resolve(Text(key, mapOf(*args)))
        assertEquals("1 face left to enter: Down.", line("msg.editor.faces-left", "count" to 1, "faces" to "Down"))
        assertEquals("We turned the Up face to match the others.", line("msg.editor.turned", "count" to 1, "faces" to "Up"))
        assertEquals(
            "We turned the Up and Front faces to match the others.",
            line(
                "msg.editor.turned",
                "count" to 2,
                "faces" to "Up and Front",
            ),
        )
        assertEquals("Red: 10 (one too many)", line("msg.editor.count-over", "color" to "Red", "count" to 10, "diff" to 1))
        assertEquals("Green: 6 (3 missing)", line("msg.editor.count-under", "color" to "Green", "count" to 6, "diff" to 3))
        assertEquals(
            "The corner between Up, Front and Right shows three greens.",
            line("msg.editor.piece-repeats.green", "piece" to "corner", "faces" to "Up, Front and Right", "count" to 3),
        )
        assertEquals(
            "The edge between Front and Right has colors that don't fit the rest of the cube.",
            line("msg.editor.piece-impossible", "piece" to "edge", "faces" to "Front and Right"),
        )
        assertEquals("Front done ✓ → Right", line("msg.editor.face-done", "face" to "Front", "next" to "Right"))
        assertEquals("Front face done. Right face next.", line("msg.editor.face-done-spoken", "face" to "Front", "next" to "Right"))
        assertEquals(
            "6 faces left to enter: Up, Front, Right, Back, Left, Down.",
            line("msg.editor.faces-left", "count" to 6, "faces" to "Up, Front, Right, Back, Left, Down"),
        )
        assertEquals(
            "Check the Up face: hold green toward you and look at the top.",
            line("msg.editor.check-face-top", "face" to "Up", "front" to "green"),
        )
        assertEquals(
            "Check the Down face: hold green on top and turn yellow toward you.",
            line("msg.editor.check-face-side", "face" to "Down", "up" to "green", "center" to "yellow"),
        )
        assertEquals(
            "Check the Right face: hold white on top and turn red toward you.",
            line("msg.editor.check-face-side", "face" to "Right", "up" to "white", "center" to "red"),
        )
        assertEquals("Turned to match", line("msg.editor.face-turned"))
        assertEquals("All stickers set", line("msg.editor.face-complete"))
        assertEquals("Red: 11 (two too many)", line("msg.editor.count-over", "color" to "Red", "count" to 11, "diff" to 2))
        assertEquals("Green: 8 (one missing)", line("msg.editor.count-under", "color" to "Green", "count" to 8, "diff" to 1))
        assertEquals(
            "Red: 10 (one too many) · Green: 8 (one missing)",
            line(
                "msg.editor.counts",
                "parts" to "Red: 10 (one too many) · Green: 8 (one missing)",
            ),
        )
        listOf("white", "yellow", "red", "orange", "green", "blue").forEach { color ->
            assertEquals(
                "The edge between Up and Front shows two ${color}s.",
                line("msg.editor.piece-repeats.$color", "piece" to "edge", "faces" to "Up and Front", "count" to 2),
            )
        }
        assertEquals(
            "The corner between Up, Front and Left shows opposite colors.",
            line("msg.editor.piece-opposite", "piece" to "corner", "faces" to "Up, Front and Left"),
        )
        assertEquals("Up and Front", line("msg.editor.list-two", "a" to "Up", "b" to "Front"))
        assertEquals("Up, Front and Right", line("msg.editor.list-three", "a" to "Up", "b" to "Front", "c" to "Right"))
        assertEquals("Up, Front, Right and Back", line("msg.editor.list-more", "rest" to "Up, Front, Right", "last" to "Back"))
    }

    @Test
    fun `a lone apostrophe stays literal and a text key resolves like a text`() {
        assertEquals("Reset the cube? This lesson's unsaved steps will be lost.", strings.resolve(Text("msg.session.reset-unsaved")))
        assertEquals("Turn 1 face", ContentStrings(mapOf("a.b.c" to "Turn 1 face")).resolve(TextKey("a.b.c")))
    }

    @Test
    fun `a missing key resolves to the key itself`() {
        assertEquals("lesson.lesson-09-09.goal.1", strings.resolve(TextKey("lesson.lesson-09-09.goal.1")))
    }

    @Test
    fun `the catalog loads into Ready once, and a failure into Failed`() {
        var loads = 0
        val catalog =
            LessonCatalog({
                loads++
                ContentLoadResult.Loaded(testBundle())
            }, kotlinx.coroutines.Dispatchers.Unconfined)
        assertEquals(CatalogState.Loading, catalog.state.value)
        runBlocking {
            catalog.load()
            catalog.load()
        }
        assertEquals(1, loads)
        val ready = catalog.state.value as CatalogState.Ready
        assertEquals(listOf("lesson-01-01", "lesson-01-03"), ready.lessons.map { it.lesson.id.value })
        assertEquals(whiteCrossLesson(), ready.find(whiteCrossLesson().id)?.lesson)

        val failed = LessonCatalog.of(ContentLoadResult.Failed(ContentLoadError.MissingResource("bundle.json")))
        runBlocking { failed.load() }
        assertTrue(failed.state.value is CatalogState.Failed)
    }
}
