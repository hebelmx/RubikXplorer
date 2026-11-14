package dev.cubentia.app.ui.lesson

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.ui.a11y.assertNoMidWordBreaks
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.tutor.TeachingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TeachingModeSwitcherTest {
    @get:Rule val rule = createComposeRule()

    private fun check(
        width: Int,
        scale: Float,
        grid: Boolean? = null,
        modes: List<TeachingMode> = TeachingMode.entries,
    ) {
        var selected: TeachingMode? = null
        var wordRequiresGrid = false
        val strings = ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                CubentiaTheme {
                    val measurer =
                        androidx.compose.ui.text
                            .rememberTextMeasurer()
                    val style = androidx.compose.material3.MaterialTheme.typography.labelLarge
                    val labels =
                        modes.map {
                            androidx.compose.ui.res.stringResource(
                                when (it) {
                                    TeachingMode.SHOW_ME -> dev.cubentia.app.R.string.mode_show_me
                                    TeachingMode.GUIDE_ME -> dev.cubentia.app.R.string.mode_guide_me
                                    TeachingMode.TEST_ME -> dev.cubentia.app.R.string.mode_test_me
                                    TeachingMode.LET_ME_TRY -> dev.cubentia.app.R.string.mode_let_me_try
                                },
                            )
                        }
                    val available = with(LocalDensity.current) { (width.dp / modes.size - dev.cubentia.app.ui.theme.Spacing.s3 * 2).toPx() }
                    wordRequiresGrid =
                        labels.any { label -> label.split(Regex("\\s+")).any { measurer.measure(it, style).size.width > available } }
                    Column(Modifier.width(width.dp)) {
                        TeachingModeSwitcher(modes, TeachingMode.SHOW_ME, { selected = it })
                        ConfirmCubeCard(strings, {}, {}, Modifier.width(width.dp))
                    }
                }
            }
        }
        rule.assertNoMidWordBreaks()
        rule.onNodeWithTag("lesson:modeSwitcher").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
        val first = rule.onNodeWithTag("mode:${modes.first().name}")
        first.assertIsSelected()
        modes.forEach {
            rule
                .onNodeWithTag(
                    "mode:${it.name}",
                ).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        }
        val last = rule.onNodeWithTag("mode:${modes.last().name}")
        grid?.let { assertEquals("Measured word width must justify the expected layout", it, wordRequiresGrid) }
        assertEquals(wordRequiresGrid, last.fetchSemanticsNode().boundsInRoot.top > first.fetchSemanticsNode().boundsInRoot.top)
        last.performClick()
        assertEquals(modes.last(), selected)
        if (wordRequiresGrid &&
            modes.size == 3
        ) {
            assertTrue(last.fetchSemanticsNode().boundsInRoot.width > first.fetchSemanticsNode().boundsInRoot.width)
        }
    }

    @Test fun normalPhone() = check(411, 1f, false)

    @Test fun largePhone() = check(411, 1.5f)

    @Test fun largestPhone() = check(411, 2f)

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun narrowLargePhone() = check(360, 1.5f)

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun narrowLargestPhone() = check(360, 2f)

    @Test fun tabletPane() = check(268, 1f)

    @Test fun threeModes() = check(220, 2f, true, TeachingMode.entries.filter { it != TeachingMode.TEST_ME })

    @Test fun confirmButtonsStackWhenWordsCannotFit() {
        check(220, 2f, true)
        val enter = rule.onNodeWithTag("confirmCube:enter").fetchSemanticsNode().boundsInRoot
        val matches = rule.onNodeWithTag("confirmCube:matches").fetchSemanticsNode().boundsInRoot
        assertTrue(matches.top >= enter.bottom)
        assertEquals(enter.width, matches.width)
    }
}
