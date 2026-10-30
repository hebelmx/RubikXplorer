package dev.rubikxplorer.app.content

import android.app.Application
import dev.rubikxplorer.app.session.testBundle
import dev.rubikxplorer.app.session.whiteCrossLesson
import dev.rubikxplorer.domain.content.ContentLoadError
import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.content.TextKey
import dev.rubikxplorer.domain.text.Text
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
