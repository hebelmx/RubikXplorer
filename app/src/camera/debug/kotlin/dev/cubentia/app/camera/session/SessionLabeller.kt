package dev.cubentia.app.camera.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.StickerTruth
import dev.cubentia.domain.recognition.StickerTruthCheck
import dev.cubentia.domain.recognition.TruthSource
import androidx.compose.ui.graphics.Color as UiColor

/*
 * Story 10.34, debug only (AD-15: developer tooling, plain literals, no Learner text): the labeller a saved Quick Scan
 * session opens from its notice. Six face chips, the chosen face's 3x3 net and a palette (six colours and Unknown),
 * pre-filled from the session's net; Save labels is locked until every pre-filled sticker is tapped, and
 * [StickerTruthCheck] decides PASSED, OVERRIDDEN (with a non-empty reason) or REFUSED.
 */

/** Stickers per face. */
private const val CELLS = 9

/** One face of the labeller: its nine truth colours (null = unknown), where each came from, and which were tapped. */
internal data class LabelFace(
    val colors: List<Color?>,
    val sources: List<TruthSource?>,
    val reviewed: List<Boolean> = List(CELLS) { false },
    val selected: Int? = 0,
) {
    /** The operator tapped sticker [index]: it is selected and counts as reviewed (Story 10.28). */
    fun tap(index: Int): LabelFace {
        if (index !in 0 until CELLS) return this
        return copy(selected = index, reviewed = reviewed.toMutableList().also { it[index] = true })
    }

    /**
     * Sets the selected sticker to [color] (null: an explicit unknown) and marks it reviewed. Picking the colour a
     * pre-filled sticker already has keeps it [TruthSource.PREFILLED] (the operator confirmed it); otherwise it becomes
     * [TruthSource.ENTERED]. The selection moves to the next sticker not reviewed yet, if any.
     */
    fun set(color: Color?): LabelFace {
        val index = selected ?: return this
        val confirms = sources[index] == TruthSource.PREFILLED && colors[index] == color && color != null
        val nextColors = colors.toMutableList().also { it[index] = color }
        val nextSources = sources.toMutableList().also { it[index] = if (confirms) TruthSource.PREFILLED else TruthSource.ENTERED }
        val nextReviewed = reviewed.toMutableList().also { it[index] = true }
        val next = (1..CELLS).map { (index + it) % CELLS }.firstOrNull { !nextReviewed[it] }
        return LabelFace(nextColors, nextSources, nextReviewed, next)
    }

    companion object {
        /** A face pre-filled with [nine] predicted colours (null stays an explicit unknown, never pre-filled). */
        fun prefilled(nine: List<Color?>): LabelFace {
            require(nine.size == CELLS) { "A face has $CELLS stickers, got ${nine.size}" }
            return LabelFace(nine, nine.map { color -> color?.let { TruthSource.PREFILLED } })
        }
    }
}

/** Six label faces pre-filled from the session's net (54, URFDLB; null stays an explicit unknown). */
internal fun sessionPrefill(net: List<Color?>): List<LabelFace> {
    require(net.size == StickerTruthCheck.STICKERS) { "A net has ${StickerTruthCheck.STICKERS} stickers, got ${net.size}" }
    return Face.entries.map { face -> LabelFace.prefilled(net.subList(face.ordinal * CELLS, face.ordinal * CELLS + CELLS)) }
}

/** The 54 truth stickers of six label faces in URFDLB order. */
internal fun sessionTruth(faces: List<LabelFace>): List<StickerTruth> =
    faces.flatMap { face -> face.colors.indices.map { StickerTruth(face.colors[it], face.sources[it], face.reviewed[it]) } }

/** Operator-facing colour name, capitalised, e.g. `Green`. */
internal fun colorLabel(color: Color): String = color.name.lowercase().replaceFirstChar { it.uppercase() }

/** The centre colour of each face in the home frame (WHITE on U, GREEN on F), which a session net is in. */
private val HOME_CENTER: Map<Face, Color> =
    CubeState.SOLVED
        .toFacelets()
        .colors
        .let { colors -> Face.entries.associateWith { colors[it.ordinal * CELLS + 4] } }

/** Face chip text, e.g. `F · Green`, so a face letter never reads as a colour. */
internal fun faceChipLabel(face: Face): String = "${face.name} · ${colorLabel(HOME_CENTER.getValue(face))}"

/**
 * The refusal line of a [StickerTruthCheck], e.g. `Not saved: White 10 (over) · Blue 8 (under) · 2 unknown` (the Story
 * 10.28 text, off colours in [Color.entries] order), with `N unknown` and `N pre-filled not checked` appended when any.
 */
internal fun truthRefusal(check: StickerTruthCheck): String =
    "Not saved: " +
        (
            Color.entries.mapNotNull { color ->
                val count = check.counts[color] ?: 0
                when {
                    count > StickerTruthCheck.STICKERS_PER_COLOR -> "${colorLabel(color)} $count (over)"
                    count < StickerTruthCheck.STICKERS_PER_COLOR -> "${colorLabel(color)} $count (under)"
                    else -> null
                }
            } + listOfNotNull(check.unknownCount.takeIf { it > 0 }?.let { "$it unknown" }) +
                listOfNotNull(check.unreviewedPrefills.takeIf { it > 0 }?.let { "$it pre-filled not checked" })
        ).joinToString(" · ")

/** Why Save labels is locked (Story 10.28 review provenance): a pre-filled sticker is the prediction until it is tapped. */
internal fun labelLock(check: StickerTruthCheck): String? =
    check.unreviewedPrefills.takeIf { it > 0 }?.let { "Save locked: tap the $it pre-filled stickers not checked yet" }

/** The labeller's state, kept in the controller so a rotation keeps it. */
internal data class LabellerState(
    val faces: List<LabelFace>,
    val face: Face = Face.U,
    /** The refused check after Save labels, offering Override… and Keep as refused. */
    val refusal: StickerTruthCheck? = null,
    val message: String? = null,
    val overriding: Boolean = false,
    val saving: Boolean = false,
) {
    val check: StickerTruthCheck get() = StickerTruthCheck.of(sessionTruth(faces))

    fun onFace(change: (LabelFace) -> LabelFace): LabellerState =
        copy(faces = faces.toMutableList().also { it[face.ordinal] = change(it[face.ordinal]) }, refusal = null, message = null)
}

private fun stickerColor(color: Color?): UiColor =
    when (color) {
        Color.WHITE -> UiColor(0xFFF5F5F5)
        Color.YELLOW -> UiColor(0xFFFFD600)
        Color.GREEN -> UiColor(0xFF2E7D32)
        Color.BLUE -> UiColor(0xFF1565C0)
        Color.RED -> UiColor(0xFFC62828)
        Color.ORANGE -> UiColor(0xFFEF6C00)
        null -> UiColor(0xFF9E9E9E)
    }

private fun stickerText(color: Color?): String = color?.name?.take(1) ?: "?"

/** The labeller: a full-screen dialog over Quick Scan (the live scan holds its analysis meanwhile). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SessionLabellerDialog(
    sessionId: String,
    state: LabellerState,
    onChange: (LabellerState) -> Unit,
    onSave: () -> Unit,
    onKeepRefused: () -> Unit,
    onOverride: (String) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize().testTag("quickScan:labeller")) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Label $sessionId", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Face.entries.forEach { face ->
                        FilterChip(
                            selected = state.face == face,
                            onClick = { onChange(state.copy(face = face)) },
                            label = { Text(faceChipLabel(face)) },
                        )
                    }
                }
                val current = state.faces[state.face.ordinal]
                Column(Modifier.widthIn(max = 280.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (row in 0 until 3) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (column in 0 until 3) {
                                val index = row * 3 + column
                                val color = current.colors[index]
                                val unchecked = current.sources[index] == TruthSource.PREFILLED && !current.reviewed[index]
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .background(stickerColor(color), RoundedCornerShape(6.dp))
                                        .border(
                                            width =
                                                if (current.selected == index) {
                                                    4.dp
                                                } else if (unchecked) {
                                                    2.dp
                                                } else {
                                                    0.dp
                                                },
                                            color = if (current.selected == index) MaterialTheme.colorScheme.primary else UiColor.Black,
                                            shape = RoundedCornerShape(6.dp),
                                        ).clickable { onChange(state.onFace { it.tap(index) }) }
                                        .testTag("labeller:sticker:$index"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(stickerText(color) + if (unchecked) "·" else "", color = UiColor.Black)
                                }
                            }
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (Color.entries + listOf<Color?>(null)).forEach { color ->
                        Box(
                            Modifier
                                .size(48.dp)
                                .background(stickerColor(color), RoundedCornerShape(24.dp))
                                .clickable { onChange(state.onFace { it.set(color) }) }
                                .testTag("labeller:palette:${color?.name ?: "UNKNOWN"}"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(color?.let(::colorLabel)?.take(3) ?: "Unk", color = UiColor.Black)
                        }
                    }
                }
                Text(
                    "Blank stickers stay explicit unknowns. Tap (or colour-pick) every pre-filled sticker to check it before Save labels.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                val refusal = state.refusal
                if (refusal != null) {
                    Text(truthRefusal(refusal), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onChange(state.copy(overriding = true)) }, enabled = !state.saving) { Text("Override…") }
                        OutlinedButton(onClick = onKeepRefused, enabled = !state.saving) { Text("Keep as refused") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSave, enabled = !state.saving, modifier = Modifier.testTag("labeller:save")) { Text("Save labels") }
                    TextButton(onClick = onClose, enabled = !state.saving) { Text("Close") }
                }
            }
        }
    }
    val refusal = state.refusal
    if (state.overriding && refusal != null) {
        var text by remember { mutableStateOf("") }
        val reason = StickerTruthCheck.overrideReason(text)
        AlertDialog(
            onDismissRequest = { onChange(state.copy(overriding = false)) },
            title = { Text("Override the count check") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(truthRefusal(refusal) + ". Tuning and scoring skip this session unless the PO includes it.")
                    OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Reason") })
                }
            },
            confirmButton = { TextButton(onClick = { reason?.let(onOverride) }, enabled = reason != null) { Text("Save with reason") } },
            dismissButton = { TextButton(onClick = { onChange(state.copy(overriding = false)) }) { Text("Cancel") } },
        )
    }
}
