package dev.cubentia.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.preferences.LocalRenderStyle
import dev.cubentia.app.render.RenderStyle
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.domain.content.Algorithm
import dev.cubentia.domain.content.AlgorithmStatus
import dev.cubentia.domain.content.Case
import dev.cubentia.domain.lastlayer.RecognitionDiagram
import dev.cubentia.domain.lastlayer.RecognitionDiagramResult
import dev.cubentia.domain.text.Text as ContentText

/** The cut-order switch (AR-23): false ships tiles with the Case name and Notation only. */
const val RECOGNITION_DIAGRAMS_ENABLED: Boolean = true

private const val DIAGRAM_DESCRIPTION_KEY = "msg.library.diagram.description"
private const val CASE_NAME_ARGUMENT = "caseName"

/**
 * One Algorithm of a Case: its recognition diagram when [showDiagram] is on and one can be derived,
 * the Case name and the Notation. With no diagram nothing stands in for it.
 */
@Composable
fun AlgorithmTile(
    case: Case,
    algorithm: Algorithm,
    strings: ContentStrings,
    modifier: Modifier = Modifier,
    showDiagram: Boolean = RECOGNITION_DIAGRAMS_ENABLED,
    style: RenderStyle = LocalRenderStyle.current,
    status: AlgorithmStatus? = null,
) {
    val tokens = RubikTheme.tokens
    val caseName = strings.resolve(case.name)
    val derived = remember(algorithm, showDiagram) { if (showDiagram) RecognitionDiagram.derive(algorithm) else null }
    Column(
        modifier = modifier.background(tokens.surfaceRaised, MaterialTheme.shapes.medium).padding(Spacing.s3),
        verticalArrangement = Arrangement.spacedBy(Spacing.s2),
    ) {
        if (derived is RecognitionDiagramResult.Derived) {
            RecognitionDiagramView(
                diagram = derived.diagram,
                description = strings.resolve(ContentText(DIAGRAM_DESCRIPTION_KEY, mapOf(CASE_NAME_ARGUMENT to caseName))),
                style = style,
            )
        }
        Text(text = caseName, style = MaterialTheme.typography.labelLarge, color = tokens.inkPrimary)
        Text(text = algorithm.notation, style = RubikTheme.textStyles.notation, color = tokens.inkPrimary)
        if (status == AlgorithmStatus.LEARNING || status == AlgorithmStatus.LEARNED) {
            Text(
                strings.resolve(ContentText("msg.library.status.${status.name.lowercase()}")),
                Modifier.testTag("library:status-label"),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.inkSecondary,
            )
        }
    }
}
