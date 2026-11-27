package dev.cubentia.app.camera.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.cubentia.domain.recognition.continuous.session.SessionPartition
import kotlinx.coroutines.delay

/*
 * Story 10.34, debug only (AD-15: developer tooling, plain Kotlin literals, never content strings): the Quick Scan
 * session recording's controls inside the Quick Scan camera area (W1, W9, answers 1, 2, 9, 10).
 */

private val INDICATOR_BACKGROUND = Color(0xB3000000)
private val RECORDING_RED = Color(0xFFE53935)

/** Quick chips beside the free-text fields (answer 11). */
private val CUBE_CHIPS = listOf("stla", "stlb", "stka", "stkb")
private val LIGHTING_CHIPS = listOf("warm", "low", "cool", "backlit")

/** The ticker period of the indicator and the cap and baseline checks (about 4 Hz, W1). */
private const val TICK_MS = 250L

@Composable
internal fun QuickScanSessionOverlay(
    controller: QuickScanSessionController,
    scope: BoxScope,
) = with(scope) {
    val phase = controller.phase
    val ticking = phase is SessionPhase.Recording || phase is SessionPhase.Baseline || phase is SessionPhase.Stopped
    LaunchedEffect(ticking, phase) {
        while (ticking) {
            controller.tick()
            delay(TICK_MS)
        }
    }
    when (phase) {
        is SessionPhase.Recording -> {
            Indicator(
                QuickScanSessionController.recIndicator(controller.status),
                Modifier.align(Alignment.TopCenter),
            )
        }

        is SessionPhase.Baseline -> {
            Indicator(
                QuickScanSessionController.baselineIndicator(controller.baselineSecondsLeft),
                Modifier.align(Alignment.TopCenter),
            )
        }

        else -> {
            Unit
        }
    }
    val notice = controller.notice
    if (notice != null && controller.labeller == null) {
        Notice(
            QuickScanSessionController.noticeLine(notice.saved),
            notice.labelStatus,
            onLabel = controller::openLabeller,
            onDismiss = controller::dismissNotice,
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = 72.dp, end = 8.dp, bottom = 52.dp),
        )
    }
    // Answer 2: bottom-start; "Rec" idle, "Stop" recording; hidden on the sheet (and the unavailable card, not composed).
    val recording = phase is SessionPhase.Recording
    if (recording || controller.recAvailable) {
        Button(
            onClick = if (recording) controller::stop else controller::rec,
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).testTag("quickScan:rec"),
        ) { Text(if (recording) "Stop" else "Rec") }
    }
    if (controller.sheetShown) SessionSheet(controller)
    if (controller.confirmDiscard && controller.sheetShown) {
        AlertDialog(
            onDismissRequest = controller::cancelDiscard,
            title = { Text("Discard this recording?") },
            text = { Text("Deletes the recorded frames; nothing is kept.") },
            confirmButton = {
                TextButton(
                    onClick = controller::discard,
                    modifier = Modifier.testTag("session:discard:confirm"),
                ) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = controller::cancelDiscard) { Text("Cancel") } },
        )
    }
    val labeller = controller.labeller
    if (labeller != null && notice != null) {
        SessionLabellerDialog(
            sessionId = notice.saved.sessionId,
            state = labeller,
            onChange = controller::updateLabeller,
            onSave = controller::saveLabels,
            onKeepRefused = { controller.writeLabels(null) },
            onOverride = { controller.writeLabels(it) },
            onClose = { controller.closeLabeller() },
        )
    }
}

/** W1: a static red dot and white text on a translucent dark pill, top-centre, clear of Close, torch and Rec. */
@Composable
private fun Indicator(
    text: String,
    modifier: Modifier,
) {
    Row(
        modifier
            .padding(top = 8.dp)
            .background(INDICATOR_BACKGROUND, RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("quickScan:recIndicator"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(10.dp).background(RECORDING_RED, CircleShape))
        // The dot is drawn; the text keeps the rest of the indicator ("REC n · k skips" or "Baseline s s").
        Text(text.removePrefix("● "), color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

/** Answer 9 / W9: "Saved <id>" (with its defects) and Label / Dismiss, bottom-centre above the Rec row. */
@Composable
private fun Notice(
    line: String,
    labelStatus: String?,
    onLabel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier
            .widthIn(max = 420.dp)
            .background(INDICATOR_BACKGROUND, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("quickScan:sessionNotice"),
    ) {
        Text(line, color = Color.White, style = MaterialTheme.typography.bodySmall)
        labelStatus?.let { Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onLabel, modifier = Modifier.testTag("session:label")) { Text("Label", color = Color.White) }
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("session:dismiss")) { Text("Dismiss", color = Color.White) }
        }
    }
}

/**
 * W4/W5 and answer 10: the stopped session's sheet, a modal bottom sheet that swipe, an outside tap and Back cannot close;
 * only Save or Discard… end it. The live scan holds its analysis while it is open.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SessionSheet(controller: QuickScanSessionController) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden })
    val phase = controller.phase
    val stopped =
        when (phase) {
            is SessionPhase.Stopped -> phase
            is SessionPhase.Busy -> phase.stopped
            else -> return
        }
    val busy = phase is SessionPhase.Busy
    ModalBottomSheet(
        onDismissRequest = {},
        sheetState = state,
        sheetGesturesEnabled = false,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false, shouldDismissOnClickOutside = false),
        modifier = Modifier.testTag("quickScan:sessionSheet"),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(QuickScanSessionController.stopLine(stopped.reason), style = MaterialTheme.typography.titleMedium)
            Text(QuickScanSessionController.countsLine(controller.status))
            Text(QuickScanSessionController.latencyLine(controller.latency))
            Text(QuickScanSessionController.baselineLine(controller.baseline))
            Text(QuickScanSessionController.outcomeLine(controller.outcome))
            QuickScanSessionController.defectPreview(controller.status, controller.latency, controller.baseline)?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            (if (phase is SessionPhase.Busy) phase.what else stopped.error)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val fields = controller.fields
            OutlinedTextField(
                value = fields.cube,
                onValueChange = { text -> controller.updateFields { it.copy(cube = text) } },
                label = { Text("Cube") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("session:cube"),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CUBE_CHIPS.forEach { chip ->
                    AssistChip(onClick = { controller.updateFields { it.copy(cube = chip) } }, label = { Text(chip) })
                }
            }
            OutlinedTextField(
                value = fields.lighting,
                onValueChange = { text -> controller.updateFields { it.copy(lighting = text) } },
                label = { Text("Lighting") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("session:lighting"),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LIGHTING_CHIPS.forEach { chip ->
                    AssistChip(onClick = { controller.updateFields { it.copy(lighting = chip) } }, label = { Text(chip) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(SessionPartition.TUNING, SessionPartition.HELDOUT, SessionPartition.UNASSIGNED).forEach { option ->
                    FilterChip(
                        selected = fields.partition == option,
                        onClick = { controller.updateFields { it.copy(partition = option) } },
                        label = { Text(option.name) },
                    )
                }
            }
            OutlinedButton(
                onClick = controller::runBaseline,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Run baseline (30 s)") }
            Button(
                onClick = { controller.save() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("session:save"),
            ) { Text("Save") }
            TextButton(
                onClick = controller::askDiscard,
                enabled = !busy,
                modifier = Modifier.testTag("session:discard"),
            ) { Text("Discard…") }
        }
    }
}
