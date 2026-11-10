package dev.cubentia.app.ui.a11y

import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class BannedPatternsTest {
    @Test fun `content avoids banned copy`() {
        val strings = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings
        strings.forEach { (key, value) ->
            assertTrue('!' !in value || key.startsWith("msg.milestone."), "$key: exclamation outside milestone")
            assertTrue(!Regex("(?i)\\b(streaks?|points?|badges?|countdowns?)\\b").containsMatchIn(value), "$key: banned copy")
        }
    }

    @Test fun `sources avoid banned APIs`() {
        val sources = File("src/main/kotlin")
        assertTrue(sources.isDirectory, "Missing app sources: ${sources.absolutePath}")
        val banned = Regex("\\b(HorizontalPager|VerticalPager|Carousel|NotificationCompat|NotificationManager|POST_NOTIFICATIONS)\\b")
        sources.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            assertTrue(!banned.containsMatchIn(file.readText()), "${file.path}: banned API")
        }
    }
}
