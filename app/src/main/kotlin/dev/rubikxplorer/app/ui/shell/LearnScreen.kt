package dev.rubikxplorer.app.ui.shell

import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import dev.rubikxplorer.app.R
import dev.rubikxplorer.app.content.CatalogEntry
import dev.rubikxplorer.app.content.CatalogState
import dev.rubikxplorer.app.content.ContentStrings
import dev.rubikxplorer.app.ui.theme.RubikTheme
import dev.rubikxplorer.app.ui.theme.Spacing
import dev.rubikxplorer.domain.content.LessonId

/**
 * The Learn tab: every Lesson of the loaded bundle in order, as a flat list (Story 7.2 adds Stage
 * rows). A row shows the Lesson's Objective over its Stage title; tapping it reports [onOpen]. An
 * empty bundle or a load failure shows one line. [scrollState] is hoisted by the shell so the list
 * keeps its place while a Lesson is open.
 */
@Composable
fun LearnScreen(
    catalog: CatalogState,
    onOpen: (LessonId) -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(RubikTheme.tokens.surfaceBase)
                .verticalScroll(scrollState)
                .testTag("learn"),
    ) {
        when (catalog) {
            // Nothing to show until the content arrives; loading is quick and never fails silently.
            CatalogState.Loading -> {}

            is CatalogState.Failed -> {
                Message(R.string.learn_error, "learn:error")
            }

            is CatalogState.Ready -> {
                if (catalog.lessons.isEmpty()) Message(R.string.learn_empty, "learn:empty")
                catalog.lessons.forEach { entry ->
                    LessonRow(entry, catalog.strings, onOpen)
                    HorizontalDivider(color = RubikTheme.tokens.hairline)
                }
            }
        }
    }
}

@Composable
private fun LessonRow(
    entry: CatalogEntry,
    strings: ContentStrings,
    onOpen: (LessonId) -> Unit,
) {
    val tokens = RubikTheme.tokens
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.touchMin)
                .clickable(role = Role.Button) { onOpen(entry.lesson.id) }
                .testTag("learn:lesson:${entry.lesson.id}")
                .padding(horizontal = Spacing.s4, vertical = Spacing.s3),
        verticalArrangement = Arrangement.spacedBy(Spacing.s1),
    ) {
        Text(strings.resolve(entry.lesson.objective), style = MaterialTheme.typography.bodyLarge, color = tokens.inkPrimary)
        Text(strings.resolve(entry.stage.title), style = MaterialTheme.typography.bodySmall, color = tokens.inkSecondary)
    }
}

@Composable
private fun Message(
    @StringRes text: Int,
    tag: String,
) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyLarge,
        color = RubikTheme.tokens.inkSecondary,
        modifier = Modifier.padding(Spacing.s4).testTag(tag),
    )
}
