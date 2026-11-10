package dev.cubentia.app.ui.a11y

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

@Composable
internal fun fontScale200(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f), content = content)
}

internal fun ComposeTestRule.assertNoTextOverflow(screen: String) {
    val nodes =
        onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
    assertTrue("$screen has text", nodes.isNotEmpty())
    nodes.forEach { node ->
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        results.forEach { result ->
            assertFalse(
                "$screen: '${node.config.getOrNull(SemanticsProperties.Text)}' overflows " +
                    "(size=${result.size}, lines=${result.lineCount}, constraints=${result.layoutInput.constraints})",
                result.overflows(),
            )
        }
    }
}

/**
 * The semantics action rebuilds the paragraph at the full max width, so [TextLayoutResult.didOverflowWidth]
 * is true for any text narrower than its constraints. Height overflow (which includes ellipsized
 * `maxLines` via `didExceedMaxLines` and text cut by a fixed-height parent) is reliable, an ellipsized
 * last line is checked directly, and width is checked against the widest laid-out line instead.
 */
private fun TextLayoutResult.overflows(): Boolean {
    val widest = (0 until lineCount).maxOfOrNull { getLineRight(it) - getLineLeft(it) } ?: 0f
    val ellipsized = lineCount > 0 && isLineEllipsized(lineCount - 1)
    return didOverflowHeight || ellipsized || widest > size.width + 1f
}

internal fun ComposeTestRule.assertClickablesLabelled(screen: String) {
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes().forEach { node ->
        val labels =
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                node.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .map { it.text }
        assertTrue("$screen: unlabelled ${node.describe()}", labels.any { it.isNotBlank() })
    }
}

internal fun ComposeTestRule.assertClickablesAtLeast48dp(screen: String) {
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes().forEach { node ->
        val density = node.layoutInfo.density.density
        val width = node.layoutInfo.width / density
        val height = node.layoutInfo.height / density
        assertTrue("$screen: $width x $height dp clickable ${node.describe()}", width >= 48f && height >= 48f)
    }
}

private fun SemanticsNode.describe(): String {
    val tags = generateSequence(this) { it.parent }.mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.toList()
    return "node ${config.getOrNull(SemanticsProperties.TestTag) ?: "(untagged)"} under $tags: $config"
}
