package dev.cubentia.app.ui.solve

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.ui.editor.ManualCubeEditor
import dev.cubentia.app.ui.freecube.FreeCubeScreen
import dev.cubentia.app.ui.freecube.FreeCubeViewModel
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.text.Text as ContentText

@Composable
fun SolveScreen(
    flow: SolveFlowViewModel,
    strings: ContentStrings,
    freeCube: FreeCubeViewModel,
    showDebugToggles: Boolean,
    pausesLesson: Boolean,
    onSolved: suspend (CubeState, List<FaceTurn>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase by flow.phase.collectAsState()
    BackHandler(phase != SolvePhase.Home) {
        if (phase is SolvePhase.Solving) flow.cancel() else flow.closeEditor()
    }
    Column(modifier) {
        when (val current = phase) {
            SolvePhase.Home -> {
                Button(onClick = flow::enterColors, modifier = Modifier.testTag("solve:enterColors")) {
                    Text(strings.resolve(ContentText("btn.solve.enter-colors")))
                }
                Box(Modifier.weight(1f)) { FreeCubeScreen(freeCube, showDebugToggles, Modifier.fillMaxSize()) }
            }

            is SolvePhase.Editing -> {
                TextButton(onClick = flow::closeEditor, modifier = Modifier.testTag("solve:close")) {
                    Text(strings.resolve(ContentText("btn.solve.close")))
                }
                if (pausesLesson) {
                    Text(
                        strings.resolve(ContentText("msg.solve.pauses-lesson")),
                        Modifier.padding(horizontal = Spacing.s4).testTag("solve:pauseNote"),
                    )
                }
                current.error?.let {
                    Text(strings.resolve(it), Modifier.testTag("solve:error").semantics { liveRegion = LiveRegionMode.Polite })
                }
                ManualCubeEditor(strings, { flow.accept(it, onSolved) }, Modifier.weight(1f), initial = current.initial)
            }

            is SolvePhase.Solving -> {
                if (pausesLesson) {
                    Text(
                        strings.resolve(ContentText("msg.solve.pauses-lesson")),
                        Modifier.padding(horizontal = Spacing.s4).testTag("solve:pauseNote"),
                    )
                }
                LinearProgressIndicator(Modifier.testTag("solve:progress"))
                val preparingKey =
                    when (current.preparation) {
                        Preparation.NONE -> null
                        Preparation.WARMING_UP -> "msg.solve.preparing"
                        Preparation.FIRST_USE -> "msg.solve.preparing-first-use"
                    }
                preparingKey?.let { Text(strings.resolve(ContentText(it)), Modifier.testTag("solve:preparing")) }
                Button(onClick = flow::cancel, modifier = Modifier.testTag("solve:cancel")) {
                    Text(strings.resolve(ContentText("btn.solve.cancel")))
                }
            }
        }
    }
}
