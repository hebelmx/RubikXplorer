package dev.cubentia.app.camera.recorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.StickerTruth
import dev.cubentia.domain.recognition.StickerTruthCheck
import dev.cubentia.domain.recognition.continuous.session.SessionCountCheck
import dev.cubentia.domain.recognition.continuous.session.SessionPartition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.Color as UiColor

/** Story 10.34: the 54 truth stickers of six [TruthEntry] faces in URFDLB order. */
internal fun sessionTruth(faces: List<TruthEntry>): List<StickerTruth> =
    faces.flatMap { entry -> entry.colors.indices.map { StickerTruth(entry.colors[it], entry.sources[it], entry.reviewed[it]) } }

/** Story 10.34: six label faces pre-filled from the session's last net (54, URFDLB; null stays an explicit unknown). */
internal fun sessionPrefill(net: List<dev.cubentia.domain.cube.Color?>): List<TruthEntry> =
    Face.entries.map { face -> TruthEntry().withPrefill(net.subList(face.ordinal * 9, face.ordinal * 9 + 9)) }

/**
 * Story 10.34, debug only: the recorder's Continuous mode. Start/Stop a session through the Solve-tab continuous
 * pipeline, then Save (primary) or Discard (set apart, confirmed); a baseline run measures the no-capture analyzer
 * latency. After Save the operator may label the 54 stickers (pre-filled from the last net, as the corner flow does);
 * [StickerTruthCheck] decides, with the Story 10.28 refusal text and a reason-required override.
 */
@Composable
internal fun ContinuousRecorderScreen(
    camera: RecorderCamera,
    controller: ContinuousRecorderController,
    onLeave: () -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        camera.continuousMode(true)
        onDispose { camera.continuousMode(false) }
    }
    val ui by controller.state
    var cube by remember { mutableStateOf(controller.meta.cube) }
    var light by remember { mutableStateOf(controller.meta.lighting) }
    var partition by remember { mutableStateOf(controller.meta.partition) }
    LaunchedEffect(cube, light, partition) { controller.meta = ContinuousSessionMeta(cube, light, partition) }
    var confirmDiscard by remember { mutableStateOf(false) }

    // Labelling after Save (optional; skipping leaves no labels.json).
    val faces = remember { mutableStateListOf<TruthEntry>() }
    var labelFace by remember { mutableStateOf(Face.U) }
    var labelling by remember { mutableStateOf(false) }
    var refusal by remember { mutableStateOf<StickerTruthCheck?>(null) }
    var overriding by remember { mutableStateOf(false) }
    var overrideText by remember { mutableStateOf("") }
    var labelStatus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ui.saved) {
        labelling = false
        refusal = null
        labelStatus = null
        faces.clear()
    }

    fun writeLabels(reason: String?) {
        val stickers = sessionTruth(faces)
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { controller.saveLabels(stickers, reason) } }
            result
                .onSuccess { labels ->
                    labelStatus =
                        when (labels.countCheck) {
                            SessionCountCheck.PASSED -> "Labels saved · 9 of each colour."
                            SessionCountCheck.OVERRIDDEN -> "Labels saved with an override."
                            SessionCountCheck.REFUSED -> "Labels kept as refused; tuning and scoring skip them."
                        }
                    labelling = false
                    refusal = null
                    overriding = false
                }.onFailure { labelStatus = "Labels not saved: ${it.message}" }
        }
    }

    fun saveLabels() {
        val check = StickerTruthCheck.of(sessionTruth(faces))
        if (check.passed) writeLabels(null) else refusal = check
    }

    val recording = ui.phase == ContinuousPhase.RECORDING || ui.phase == ContinuousPhase.BASELINE
    val idle = ui.phase == ContinuousPhase.IDLE || ui.phase == ContinuousPhase.SAVED

    val preview: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier.clipToBounds().background(UiColor.Black), contentAlignment = Alignment.Center) {
            if (hasPermission) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }.also(camera::bind)
                    },
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                )
            } else {
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            }
        }
    }
    val panel: @Composable (Modifier) -> Unit = { modifier ->
        Column(
            modifier.padding(horizontal = 8.dp, vertical = 4.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Continuous session · ${Build.MODEL}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onLeave, enabled = idle) { Text("Faces / corners") }
            }
            if (idle && !labelling) {
                OutlinedTextField(value = cube, onValueChange = { cube = it }, label = { Text("Cube (e.g. stla)") }, singleLine = true)
                OutlinedTextField(value = light, onValueChange = { light = it }, label = { Text("Light (e.g. studio)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SessionPartition.entries.forEach { option ->
                        FilterChip(selected = partition == option, onClick = { partition = option }, label = { Text(option.name) })
                    }
                }
            }
            // Plain status line, no animation and no live net beyond the sides count.
            Text(ui.statusLine, style = MaterialTheme.typography.bodyMedium)
            labelStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            when (ui.phase) {
                ContinuousPhase.IDLE, ContinuousPhase.SAVED -> {
                    if (!labelling) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { controller.start() },
                                enabled = hasPermission && controller.meta.ready && cube.isNotBlank() && light.isNotBlank(),
                                modifier = Modifier.weight(1f),
                            ) { Text("Start") }
                            OutlinedButton(onClick = { controller.baseline() }, enabled = hasPermission) { Text("Baseline run") }
                        }
                        if (ui.phase == ContinuousPhase.SAVED) {
                            OutlinedButton(onClick = {
                                faces.clear()
                                faces.addAll(sessionPrefill(ui.lastNet))
                                labelFace = Face.U
                                labelling = true
                            }) { Text("Label stickers…") }
                        }
                    }
                }

                ContinuousPhase.RECORDING, ContinuousPhase.BASELINE -> {
                    Button(onClick = { controller.stop() }, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
                }

                ContinuousPhase.STOPPED -> {
                    Button(onClick = { controller.save() }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
                    // Destructive control set apart and confirmed (UX rule for recorder controls).
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(onClick = { confirmDiscard = true }) { Text("Discard…") }
                }

                ContinuousPhase.SAVING -> {
                    Unit
                }
            }
            if (labelling && faces.size == Face.entries.size) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Face.entries.forEach { face ->
                        FilterChip(selected = labelFace == face, onClick = { labelFace = face }, label = { Text(faceChipLabel(face)) })
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val side = minOf(maxWidth * 0.5f, 240.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val index = labelFace.ordinal
                        Net(Modifier.size(side), faces[index], onSelect = { faces[index] = faces[index].tap(it) })
                        Box(Modifier.width(1.5.dp).height(side).background(MaterialTheme.colorScheme.outlineVariant))
                        Palette(Modifier.height(side).weight(1f), highlighted = null, done = emptySet()) { color ->
                            val current = faces[index]
                            val ready = current.selected?.let { current } ?: current.select(current.colors.indexOfFirst { it == null })
                            if (ready.selected != null) faces[index] = ready.set(color, keepSamePrefill = true)
                        }
                    }
                }
                Text("Blank stickers stay explicit unknowns.", style = MaterialTheme.typography.bodySmall)
                refusal?.let { check ->
                    Text(truthRefusal(check), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            overrideText = ""
                            overriding = true
                        }, modifier = Modifier.weight(1f)) { Text("Override…") }
                        OutlinedButton(onClick = { writeLabels(null) }, modifier = Modifier.weight(1f)) { Text("Keep as refused") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::saveLabels, modifier = Modifier.weight(1f)) { Text("Save labels") }
                    TextButton(onClick = { labelling = false }) { Text("Skip") }
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (maxWidth >= 600.dp || maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize()) {
                preview(Modifier.weight(1f).fillMaxHeight())
                panel(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                preview(Modifier.fillMaxWidth().weight(0.45f))
                panel(Modifier.fillMaxWidth().weight(0.55f))
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this session?") },
            text = { Text("Deletes the recorded frames; nothing is kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    controller.discard()
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Cancel") } },
        )
    }
    val overrideFor = refusal
    if (overriding && overrideFor != null) {
        val reason = overrideReason(overrideText)
        AlertDialog(
            onDismissRequest = { overriding = false },
            title = { Text("Override the count check") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(truthRefusal(overrideFor) + " Tuning and scoring skip this session unless the PO includes it.")
                    OutlinedTextField(value = overrideText, onValueChange = { overrideText = it }, label = { Text("Reason") })
                }
            },
            confirmButton = { TextButton(onClick = { reason?.let(::writeLabels) }, enabled = reason != null) { Text("Save with reason") } },
            dismissButton = { TextButton(onClick = { overriding = false }) { Text("Cancel") } },
        )
    }
}
