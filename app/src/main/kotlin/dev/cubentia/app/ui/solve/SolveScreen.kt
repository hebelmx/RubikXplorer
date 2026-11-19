package dev.cubentia.app.ui.solve

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.ui.beta.BetaLabel
import dev.cubentia.app.ui.beta.BetaNote
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
    onLearn: () -> Unit,
    modifier: Modifier = Modifier,
    onScan: (() -> Unit)? = null,
    scanBeta: Boolean = false,
    onQuickScan: (() -> Unit)? = null,
    quickScanBeta: Boolean = false,
) {
    val phase by flow.phase.collectAsState()
    BackHandler(phase != SolvePhase.Home) {
        if (phase is SolvePhase.Solving) flow.cancel() else flow.closeEditor()
    }
    Column(modifier) {
        when (val current = phase) {
            SolvePhase.Home -> {
                val guidedBeta = scanBeta && onScan != null
                if (guidedBeta || onQuickScan != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s1)) {
                        if (guidedBeta) EnterColors(strings, flow::enterColors)
                        onScan?.let {
                            ScanButton(
                                strings,
                                it,
                                ContentText("btn.solve.scan"),
                                "solve:scan",
                                guidedBeta,
                                outlined = guidedBeta,
                            )
                        }
                        onQuickScan?.let {
                            ScanButton(strings, it, ContentText("btn.solve.quick-scan"), "solve:quickScan", quickScanBeta, outlined = true)
                        }
                        if (!guidedBeta) EnterColors(strings, flow::enterColors)
                    }
                    if (guidedBeta || (quickScanBeta && onQuickScan != null)) {
                        val tag = if (guidedBeta) "solve:scan:betaNote" else "solve:quickScan:betaNote"
                        BetaNote(
                            strings.resolve(ContentText("msg.feature.camera.beta-note")),
                            Modifier.padding(horizontal = Spacing.s4).testTag(tag),
                        )
                    }
                } else {
                    Row {
                        onScan?.let { ScanButton(strings, it, ContentText("btn.solve.scan"), "solve:scan", beta = false, outlined = false) }
                        EnterColors(strings, flow::enterColors)
                    }
                }
                Box(Modifier.weight(1f)) { FreeCubeScreen(freeCube, showDebugToggles, Modifier.fillMaxSize(), strings) }
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
                current.note?.let { Text(strings.resolve(it), Modifier.padding(horizontal = Spacing.s4).testTag("solve:scanNote")) }
                current.error?.let {
                    Text(strings.resolve(it), Modifier.testTag("solve:error").semantics { liveRegion = LiveRegionMode.Polite })
                }
                ManualCubeEditor(strings, { flow.confirm(it, onSolved) }, Modifier.weight(1f), initial = current.initial)
            }

            SolvePhase.AlreadySolved -> {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(Spacing.s4),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s2),
                ) {
                    Text(
                        strings.resolve(ContentText("msg.solve.already-solved")),
                        Modifier.testTag("solve:alreadySolved").semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                        Button(onClick = flow::closeEditor, modifier = Modifier.testTag("solve:alreadySolved:again")) {
                            Text(strings.resolve(ContentText("btn.solve.again")))
                        }
                        OutlinedButton(onClick = onLearn, modifier = Modifier.testTag("solve:alreadySolved:learn")) {
                            Text(strings.resolve(ContentText("btn.solve.go-learn")))
                        }
                    }
                }
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

@Composable
private fun EnterColors(
    strings: ContentStrings,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, modifier = Modifier.testTag("solve:enterColors")) {
        Text(strings.resolve(ContentText("btn.solve.enter-colors")))
    }
}

@Composable
private fun ScanButton(
    strings: ContentStrings,
    onClick: () -> Unit,
    labelText: ContentText,
    tag: String,
    beta: Boolean,
    outlined: Boolean,
) {
    val label = strings.resolve(labelText)
    val modifier = Modifier.testTag(tag)
    if (outlined) {
        val betaLabel = strings.resolve(ContentText("msg.feature.beta"))
        OutlinedButton(
            onClick,
            if (beta) modifier.semantics { contentDescription = "$label, $betaLabel" } else modifier,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s1), verticalAlignment = Alignment.CenterVertically) {
                Text(label)
                if (beta) {
                    BetaLabel(betaLabel, LocalContentColor.current, MaterialTheme.typography.labelMedium, Modifier.testTag("$tag:beta"))
                }
            }
        }
    } else {
        Button(onClick, modifier) { Text(label) }
    }
}
