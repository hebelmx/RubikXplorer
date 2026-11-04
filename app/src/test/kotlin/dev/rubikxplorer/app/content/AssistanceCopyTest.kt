package dev.rubikxplorer.app.content

import android.app.Application
import dev.rubikxplorer.app.ui.lesson.USUAL_HELP_LABEL
import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.content.ContentLoader
import dev.rubikxplorer.domain.content.ContentSource
import dev.rubikxplorer.domain.tutor.AdaptivePolicy
import dev.rubikxplorer.domain.tutor.AssistanceLevel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Story 4.6 (FR-13, UX-DR10): every Assistance line in the shipped `en.json` resolves through ICU to its exact copy. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AssistanceCopyTest {
    private val strings: ContentStrings by lazy {
        ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)
    }

    private fun line(
        level: AssistanceLevel,
        mistakes: Int,
    ): String = strings.resolve(checkNotNull(AdaptivePolicy.line(level, mistakes)))

    @Test
    fun `explain and reduced lines`() {
        assertEquals("Pointing out the piece, since this step tripped you up.", line(AssistanceLevel.EXPLAIN, 1))
        assertEquals("Fewer hints now — you've got this step.", line(AssistanceLevel.REDUCED, 0))
    }

    @Test
    fun `demonstrate and guide lines count the mistakes`() {
        assertEquals("Showing the turn, since this step tripped you up once.", line(AssistanceLevel.DEMONSTRATE, 1))
        assertEquals("Showing the turn, since this step tripped you up twice.", line(AssistanceLevel.DEMONSTRATE, 2))
        assertEquals("Showing the turn, since this step tripped you up 3 times.", line(AssistanceLevel.DEMONSTRATE, 3))
        assertEquals("One turn at a time, since this step tripped you up once.", line(AssistanceLevel.GUIDE, 1))
        assertEquals("One turn at a time, since this step tripped you up twice.", line(AssistanceLevel.GUIDE, 2))
        assertEquals("One turn at a time, since this step tripped you up 3 times.", line(AssistanceLevel.GUIDE, 3))
    }

    @Test
    fun `the usual help label`() {
        assertEquals("Back to my usual help", strings.resolve(USUAL_HELP_LABEL))
    }
}
