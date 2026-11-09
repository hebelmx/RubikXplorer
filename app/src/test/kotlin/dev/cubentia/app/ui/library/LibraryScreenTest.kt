package dev.cubentia.app.ui.library

import android.app.Application
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.text.TextLayoutResult
import dev.cubentia.app.content.CatalogState
import dev.cubentia.app.ui.shell.WindowWidthClass
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.domain.content.AlgorithmId
import dev.cubentia.domain.content.AlgorithmStatus
import dev.cubentia.domain.content.ContentLoadError
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.content.LibraryFilter
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w320dp-h640dp")
class LibraryScreenTest {
    @get:Rule val rule = createComposeRule()
    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
    private var opened = 0
    private var ink = Color.Unspecified

    private fun launch(
        catalog: CatalogState = CatalogState.Ready(bundle),
        statuses: Map<AlgorithmId, AlgorithmStatus> = emptyMap(),
    ) {
        rule.setContent {
            CubentiaTheme {
                ink = RubikTheme.tokens.inkPrimary
                var filter by remember { mutableStateOf(LibraryFilter()) }
                LibraryScreen(
                    catalog,
                    WindowWidthClass.COMPACT,
                    filter,
                    { filter = it },
                    rememberLazyGridState(),
                    { opened++ },
                    statuses = statuses,
                )
            }
        }
    }

    @Test fun `all 78 cases can be reached in the grid`() {
        launch()
        rule.onNodeWithText("OLL · Dot").assertIsDisplayed().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        bundle.cases.forEach { case ->
            rule.onNodeWithTag("library:grid").performScrollToKey(case.id.value)
            rule.onNodeWithTag("library:case:${case.id.value}").assertIsDisplayed()
        }
    }

    @Test fun `chips toggle independently and every empty filter offers the link`() {
        launch()
        rule.onNodeWithTag("library:family:oll-dot").performClick().assertIsSelected()
        rule.onNodeWithTag(SELECTED_ICON_TAG, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("library:status:LEARNING").performClick().assertIsSelected()
        rule.onNodeWithText("No algorithms marked Learning yet.").assertIsDisplayed()
        rule.onNodeWithTag("library:grid").assertDoesNotExist()
        rule.onNodeWithTag("library:two-look").performClick()
        assertEquals(1, opened)
        rule.onNodeWithTag("library:status:LEARNED").performClick()
        rule.onNodeWithText("No algorithms marked Learning yet.").assertIsDisplayed()
        rule.onNodeWithTag("library:status:LEARNED").performClick()
        rule.onNodeWithTag("library:grid").assertExists()
        rule.onNodeWithTag("library:family:oll-dot").performClick()
        rule.onNodeWithTag("library:grid").performScrollToKey(
            bundle.cases
                .last()
                .id.value,
        )
        rule.onNodeWithTag("library:case:${bundle.cases.last().id.value}").assertIsDisplayed()
    }

    @Test fun `supplied status matches the primary Algorithm`() {
        val algorithm = bundle.algorithms.first { it.isPrimary }
        launch(statuses = mapOf(algorithm.id to AlgorithmStatus.LEARNING))
        rule.onNodeWithTag("library:status:LEARNING").performClick()
        rule.onNodeWithTag("library:case:${algorithm.caseId.value}").assertIsDisplayed()
        rule.onNodeWithTag("library:case:${bundle.cases.last().id.value}").assertDoesNotExist()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `selected chip uses the raised surface and a checkmark`() {
        var raised = Color.Unspecified
        rule.setContent {
            CubentiaTheme {
                raised = RubikTheme.tokens.surfaceRaised
                LibraryScreen(
                    CatalogState.Ready(bundle),
                    WindowWidthClass.COMPACT,
                    LibraryFilter(status = AlgorithmStatus.LEARNING),
                    {},
                    rememberLazyGridState(),
                    {},
                )
            }
        }
        val chip = rule.onNodeWithTag("library:status:LEARNING").assertIsSelected()
        rule.onNodeWithTag(SELECTED_ICON_TAG, useUnmergedTree = true).assertExists()
        val pixels = chip.captureToImage().toPixelMap()
        assertEquals(raised, pixels[pixels.width / 2, pixels.height / 2 + 12])
    }

    @Test
    @Config(qualifiers = "w1000dp-h900dp")
    fun `filter and grid survive restoration and width class changes`() {
        val restoration = StateRestorationTester(rule)
        var width by mutableStateOf(WindowWidthClass.EXPANDED)
        lateinit var grid: LazyGridState
        restoration.setContent {
            CubentiaTheme {
                var filter by rememberSaveable(stateSaver = LibraryFilterSaver) { mutableStateOf(LibraryFilter()) }
                grid = rememberLazyGridState()
                LibraryScreen(CatalogState.Ready(bundle), width, filter, { filter = it }, grid, {})
            }
        }
        rule.onNodeWithTag("library:status:NOT_STARTED").performClick()
        rule.onNodeWithTag("library:grid").performScrollToKey("case-oll-45")
        val position = rule.runOnIdle { grid.firstVisibleItemIndex }
        rule.runOnIdle { width = WindowWidthClass.MEDIUM }
        rule.runOnIdle { assertEquals(position, grid.firstVisibleItemIndex) }
        rule.runOnIdle { width = WindowWidthClass.COMPACT }
        rule.runOnIdle { assertEquals(position, grid.firstVisibleItemIndex) }
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("library:status:NOT_STARTED").assertIsSelected()
        rule.runOnIdle { assertEquals(position, grid.firstVisibleItemIndex) }
    }

    private fun textColorOf(text: String) =
        mutableListOf<TextLayoutResult>()
            .also {
                rule
                    .onNodeWithText(text)
                    .fetchSemanticsNode()
                    .config[SemanticsActions.GetTextLayoutResult]
                    .action
                    ?.invoke(it)
            }.single()
            .layoutInput.style.color

    @Test fun `family and status filters constrain the visible tiles together`() {
        val dot = bundle.cases.filter { it.family.value == "oll-dot" }
        val other = bundle.cases.first { it.family.value != "oll-dot" }
        val algorithm = bundle.algorithms.first { it.isPrimary && it.caseId == dot.first().id }
        launch(statuses = mapOf(algorithm.id to AlgorithmStatus.LEARNING))
        rule.onNodeWithTag("library:family:oll-dot").performClick()
        dot.forEach { case ->
            rule.onNodeWithTag("library:grid").performScrollToKey(case.id.value)
            rule.onNodeWithTag("library:case:${case.id.value}").assertIsDisplayed()
        }
        rule.onNodeWithTag("library:case:${other.id.value}").assertDoesNotExist()
        rule.onNodeWithTag("library:status:LEARNING").performClick()
        rule.onNodeWithTag("library:case:${dot.first().id.value}").assertIsDisplayed()
        rule.onNodeWithTag("library:case:${dot.last().id.value}").assertDoesNotExist()
        rule.onNodeWithTag("library:family:oll-dot").performClick()
        rule.onNodeWithTag("library:status:LEARNING").assertIsSelected()
        rule.onNodeWithTag("library:case:${dot.first().id.value}").assertIsDisplayed()
        rule.onNodeWithTag("library:case:${other.id.value}").assertDoesNotExist()
    }

    @Test fun `title headings and empty copy use the primary ink`() {
        launch()
        rule.onNodeWithText("Library").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        assertEquals(ink, textColorOf("Library"))
        assertEquals(ink, textColorOf("OLL · Dot"))
        rule.onNodeWithTag("library:status:LEARNING").performClick()
        assertEquals(ink, textColorOf("No algorithms marked Learning yet."))
    }

    @Test fun `failure shows Library error`() {
        launch(CatalogState.Failed(ContentLoadError.MissingResource("bundle.json")))
        rule.onNodeWithText("The Library could not be loaded.").assertIsDisplayed()
    }
}
