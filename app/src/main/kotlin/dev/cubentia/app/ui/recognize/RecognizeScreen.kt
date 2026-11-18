package dev.cubentia.app.ui.recognize

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.cubentia.app.content.CatalogState
import dev.cubentia.app.ui.editor.ManualCubeEditor
import dev.cubentia.app.ui.library.AlgorithmTile
import dev.cubentia.application.usecase.Recognition
import dev.cubentia.domain.content.AlgorithmId
import dev.cubentia.domain.text.Text as ContentText

@Composable
fun RecognizeScreen(
    phase: RecognizePhase,
    catalog: CatalogState.Ready,
    flow: RecognizeFlowViewModel,
    onScan: (Int) -> Unit,
    onPlacement: (() -> Unit)?,
    onOpen: (AlgorithmId, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = catalog.strings

    fun text(
        key: String,
        args: Map<String, Any> = emptyMap(),
    ) = strings.resolve(ContentText(key, args))
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = flow::reset, modifier = Modifier.testTag("recognize:close")) {
            Text(text("btn.solve.close"))
        }
        when (phase) {
            RecognizePhase.Idle -> {
                Unit
            }

            is RecognizePhase.Editing -> {
                Text(strings.resolve(phase.note))
                ManualCubeEditor(strings, flow::editorAccepted, Modifier.weight(1f))
            }

            is RecognizePhase.Result -> {
                val cases = catalog.bundle.cases
                val primaries =
                    catalog.bundle.algorithms
                        .filter { it.isPrimary }
                        .associateBy { it.caseId }
                when (val recognition = phase.recognition) {
                    is Recognition.Unique -> {
                        val case = cases.firstOrNull { it.id == recognition.caseId }
                        val algorithm = primaries[recognition.caseId]
                        if (case != null && algorithm != null) {
                            OutlinedButton(
                                onClick = { onOpen(algorithm.id, true) },
                                modifier = Modifier.fillMaxWidth().testTag("recognize:match"),
                            ) {
                                Text(
                                    text(
                                        "msg.recognize.match",
                                        mapOf(
                                            "case" to strings.resolve(case.name),
                                            "family" to text("msg.library.family.${case.family.value}"),
                                        ),
                                    ),
                                )
                            }
                        }
                    }

                    is Recognition.Several -> {
                        Text(
                            text("msg.recognize.several", mapOf("n" to recognition.caseIds.size)),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Button(onClick = { onScan(1) }) { Text(text("btn.recognize.scan-another-angle")) }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(cases.filter { it.id in recognition.caseIds }, key = { it.id.value }) { case ->
                                primaries[case.id]?.let { algorithm ->
                                    AlgorithmTile(
                                        case,
                                        algorithm,
                                        strings,
                                        Modifier.fillMaxWidth().clickable { onOpen(algorithm.id, false) },
                                    )
                                }
                            }
                        }
                    }

                    Recognition.NoFit -> {
                        Text(text("msg.recognize.no-fit"))
                        Button(onClick = { onScan(2) }) { Text(text("btn.recognize.scan-another-angle")) }
                        OutlinedButton(onClick = flow::reset) { Text(text("btn.recognize.browse-library")) }
                    }

                    Recognition.LastLayerSolved -> {
                        Text(text("msg.recognize.solved"))
                        Button(onClick = flow::reset) { Text(text("btn.recognize.back-to-library")) }
                    }

                    Recognition.NotAtLastLayer -> {
                        Text(text("msg.recognize.not-at-ll"))
                        if (onPlacement != null) Button(onClick = onPlacement) { Text(text("btn.recognize.start-from-cube")) }
                    }
                }
            }
        }
    }
}
