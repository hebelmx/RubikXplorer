package dev.cubentia.app.camera

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.preferences.LocalHaptics
import dev.cubentia.app.render.StickerGlyph
import dev.cubentia.app.ui.editor.colorName
import dev.cubentia.app.ui.lesson.rememberReduceMotion
import dev.cubentia.app.ui.theme.LocalRubikTextStyles
import dev.cubentia.domain.appearance.StickerAppearance
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.Coaching
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.GuidedScanPlan
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.QuickScanPlan
import dev.cubentia.domain.recognition.ScanStep
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color as UiColor
import dev.cubentia.domain.cube.Color as CubeColor
import dev.cubentia.domain.text.Text as ContentText

/** What the Guided Scan screen shows. */
internal sealed interface GuidedScanPhase {
    data class Review(
        val review: dev.cubentia.domain.recognition.ScanReview,
        val offerCalibration: Boolean = false,
        /** The Learner changed a sticker, kept across a trip to calibration so *Close* still confirms. */
        val edited: Boolean = false,
        val corrections: Map<Int, dev.cubentia.domain.cube.Color> = emptyMap(),
        val initialStickers: List<CubeColor?> = review.stickers,
    ) : GuidedScanPhase

    data class Calibration(
        val session: dev.cubentia.domain.recognition.CalibrationSession =
            dev.cubentia.domain.recognition
                .CalibrationSession(),
        val coaching: Coaching? = null,
        val saveFailed: Boolean = false,
        val fromReview: Review? = null,
    ) : GuidedScanPhase

    data class CornerViewfinder(
        val hold: Int,
        val captured: List<FaceSample> = emptyList(),
        val coaching: Coaching? = null,
        val prompt: ContentText? = null,
        val totalHolds: Int = QuickScanPlan.holds.size,
    ) : GuidedScanPhase

    /** The camera rationale, before any system dialog. */
    data object Rationale : GuidedScanPhase

    /** The permission was denied; [permanent] when the system will not ask again. */
    data class Denied(
        val permanent: Boolean,
    ) : GuidedScanPhase

    /** CameraX could not bind a back camera. */
    data object Unavailable : GuidedScanPhase

    /**
     * The viewfinder at first-round plan step [stepIndex], with the faces [captured] so far, the current [coaching], and
     * [canUseAnyway] after a repeated face was rejected twice at this step. [step] is the prompt to show (by default the
     * plan step at [stepIndex]; null when done); [again] is the re-prompt round's progress, null in the first round.
     */
    data class Viewfinder(
        val stepIndex: Int = 0,
        val captured: List<FaceSample> = emptyList(),
        val coaching: Coaching? = null,
        val canUseAnyway: Boolean = false,
        val step: ScanStep? = GuidedScanPlan.steps.getOrNull(stepIndex),
        val again: RepromptProgress? = null,
        /** How many faces the first round asks for: 6, or a Quick Check's 1 or 2. */
        val total: Int = GuidedScanPlan.steps.size,
        /** A Quick Check face captured once more because it was doubtful: "Show the red face again, …" (PO 2026-11-17). */
        val retake: Boolean = false,
    ) : GuidedScanPhase
}

/** Re-prompt round progress (Story 10.14): face [n] (1-based) of [total] faces asked again. */
internal data class RepromptProgress(
    val n: Int,
    val total: Int,
)

internal class GuidedScanActions(
    val onClose: () -> Unit = {},
    val onContinue: () -> Unit = {},
    val onEnterColors: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onCapture: () -> Unit = {},
    val onUseAnyway: () -> Unit = {},
    val onKeep: () -> Unit = {},
    val onRetake: () -> Unit = {},
    val onSave: () -> Unit = {},
    /** The freeze of capture [FrozenCapture.id] ended: live view resumes. */
    val onFreezeEnd: (Long) -> Unit = {},
)

/**
 * A face just captured (PO 2026-11-16): the preview [image] at the capture (null when the preview gave none) stays frozen
 * on the viewfinder for [CaptureFreeze.FREEZE_MS], then shrinks into [face] on the mini net. [id] tells consecutive
 * captures apart. Held in memory only until the freeze ends.
 */
internal class FrozenCapture(
    val id: Long,
    val face: Face,
    val image: ImageBitmap?,
    val shrink: Boolean = true,
)

private const val VIEWFINDER_HEIGHT_FRACTION = 0.45f
private val EXPANDED_WIDTH = 600.dp
private const val NET_COLUMNS = 4
private const val NET_ROWS = 3
private const val SIDE = 3
private const val QUARTER = 90

/** How long the frozen frame takes to shrink into its face on the mini net. */
internal const val SHRINK_MS = 300

/**
 * Guided 6-face Scan (Story 10.2); [viewfinder] draws the camera preview (a PreviewView in the app), and [frozen] is the
 * face just captured, shown frozen over it.
 */
@Composable
internal fun GuidedScanScreen(
    phase: GuidedScanPhase,
    strings: ContentStrings,
    actions: GuidedScanActions,
    frozen: FrozenCapture? = null,
    offerEnterColors: Boolean = false,
    viewfinder: @Composable () -> Unit = {},
) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = actions.onClose,
                    modifier = Modifier.testTag("scanClose"),
                ) { Text(strings.resolve(ContentText("btn.scan.close"))) }
                if (phase is GuidedScanPhase.CornerViewfinder && phase.totalHolds == 2) {
                    Text(
                        strings.resolve(
                            ContentText("msg.quick-scan.counter", mapOf("n" to phase.hold, "total" to phase.totalHolds)),
                        ),
                        Modifier
                            .weight(
                                1f,
                            ).padding(end = 8.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag("quickScan:counter"),
                        textAlign = TextAlign.End,
                        style = LocalRubikTextStyles.current.data,
                    )
                }
                if (phase is GuidedScanPhase.Viewfinder) {
                    Text(
                        strings.resolve(progressText(phase)),
                        // Announces each capture to TalkBack as the count moves on.
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite }
                                .testTag("scanProgress"),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            when (phase) {
                is GuidedScanPhase.Calibration -> {
                    CalibrationViewfinder(phase, strings, actions, offerEnterColors, viewfinder)
                }

                is GuidedScanPhase.Review -> {
                    Unit
                }

                GuidedScanPhase.Rationale -> {
                    Card(strings.resolve(ContentText("msg.scan.camera-rationale")), "scanRationale") {
                        Button(
                            onClick = actions.onContinue,
                            Modifier.fillMaxWidth(),
                        ) { Text(strings.resolve(ContentText("btn.scan.continue"))) }
                        OutlinedButton(onClick = actions.onEnterColors, Modifier.fillMaxWidth()) {
                            Text(strings.resolve(ContentText("btn.scan.enter-colors-instead")))
                        }
                    }
                }

                is GuidedScanPhase.Denied -> {
                    val key = if (phase.permanent) "msg.scan.camera-denied-permanently" else "msg.scan.camera-denied"
                    Card(strings.resolve(ContentText(key)), "scanDenied") {
                        Button(onClick = actions.onEnterColors, Modifier.fillMaxWidth()) {
                            Text(strings.resolve(ContentText("btn.scan.enter-colors-instead")))
                        }
                        if (phase.permanent) {
                            OutlinedButton(onClick = actions.onOpenSettings, Modifier.fillMaxWidth()) {
                                Text(strings.resolve(ContentText("btn.scan.open-settings")))
                            }
                        }
                    }
                }

                GuidedScanPhase.Unavailable -> {
                    Card(strings.resolve(ContentText("msg.scan.camera-unavailable")), "scanUnavailable") {
                        Button(onClick = actions.onEnterColors, Modifier.fillMaxWidth()) {
                            Text(strings.resolve(ContentText("btn.scan.enter-colors-instead")))
                        }
                    }
                }

                is GuidedScanPhase.CornerViewfinder -> {
                    Viewfinder(
                        GuidedScanPhase.Viewfinder(captured = phase.captured, coaching = phase.coaching, step = null),
                        strings,
                        actions,
                        frozen,
                        offerEnterColors,
                        viewfinder,
                        cornerHold = phase.hold,
                        cornerPrompt = phase.prompt,
                    )
                }

                is GuidedScanPhase.Viewfinder -> {
                    Viewfinder(phase, strings, actions, frozen, offerEnterColors, viewfinder)
                }
            }
        }
    }
}

@Composable
private fun Card(
    message: String,
    tag: String,
    buttons: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().testTag(tag),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(message, style = MaterialTheme.typography.bodyLarge)
            buttons()
        }
    }
}

/** Where the viewfinder's parts sit, in root coordinates, for the frozen frame's shrink into the mini net. */
private class ViewfinderGeometry {
    var origin by mutableStateOf(Offset.Zero)
    var pane by mutableStateOf<Rect?>(null)
    var grid by mutableStateOf<Rect?>(null)
    val faces = mutableStateMapOf<Face, Rect>()
}

/**
 * Compact portrait: the prompt above the viewfinder, the coaching line below it, then the mini net and *Capture*, so
 * neither overlay covers the grid. Medium/expanded or landscape: the viewfinder left, an opaque panel right.
 */
@Composable
private fun Viewfinder(
    phase: GuidedScanPhase.Viewfinder,
    strings: ContentStrings,
    actions: GuidedScanActions,
    frozen: FrozenCapture?,
    offerEnterColors: Boolean,
    preview: @Composable () -> Unit,
    cornerHold: Int? = null,
    cornerPrompt: ContentText? = null,
) {
    val geometry = remember { ViewfinderGeometry() }
    val steps = cornerHold?.takeIf { cornerPrompt == null }?.let { QuickScanPlan.holds[it - 1] }.orEmpty()
    val highlighted = steps.map { it.face }.toSet() + listOfNotNull(phase.step?.face)
    val showCorner = cornerHold != null || frozen?.shrink == false
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { geometry.origin = it.boundsInRoot().topLeft }) {
        val wide = maxWidth >= EXPANDED_WIDTH || maxWidth > maxHeight
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) { CameraArea(geometry, preview, showCorner) }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (cornerHold == null) Prompt(phase, strings) else CornerPrompt(cornerHold, strings, cornerPrompt)
                    CoachingLine(phase, strings)
                    if (cornerPrompt == null) MiniNet(phase, geometry, Modifier.weight(1f).fillMaxWidth(), highlighted)
                    CaptureButtons(phase, strings, actions)
                    if (offerEnterColors) EnterColorsButton(strings, actions.onEnterColors)
                }
            }
        } else {
            val cameraHeight = maxHeight * VIEWFINDER_HEIGHT_FRACTION
            Column(Modifier.fillMaxSize()) {
                if (cornerHold == null) {
                    Prompt(phase, strings, Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp))
                } else {
                    CornerPrompt(cornerHold, strings, cornerPrompt)
                }
                Box(Modifier.fillMaxWidth().height(cameraHeight)) { CameraArea(geometry, preview, showCorner) }
                CoachingLine(phase, strings)
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (cornerPrompt == null) MiniNet(phase, geometry, Modifier.weight(1f).fillMaxWidth(), highlighted)
                    CaptureButtons(phase, strings, actions)
                    if (offerEnterColors) EnterColorsButton(strings, actions.onEnterColors)
                }
            }
        }
        if (frozen != null) FrozenOverlay(frozen, geometry, actions.onFreezeEnd)
    }
}

/** The preview, clipped to its own pane, with the grid overlay centered on it. */
@Composable
private fun CameraArea(
    geometry: ViewfinderGeometry,
    preview: @Composable () -> Unit,
    corner: Boolean = false,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .background(UiColor.Black)
            .onGloballyPositioned { geometry.pane = it.boundsInRoot() }
            .testTag("scanViewfinder"),
    ) {
        preview()
        val side = minOf(maxWidth, maxHeight) * FrameReader.GRID_FRACTION.toFloat()
        if (corner) {
            CornerOutline(Modifier.align(Alignment.Center).size(side).onGloballyPositioned { geometry.grid = it.boundsInRoot() })
            return@BoxWithConstraints
        }
        Canvas(
            Modifier
                .align(Alignment.Center)
                .size(side)
                .onGloballyPositioned { geometry.grid = it.boundsInRoot() }
                .testTag("scanGrid"),
        ) {
            val stroke = 2.dp.toPx()
            drawRect(UiColor.White, Offset.Zero, Size(size.width, size.height), style = Stroke(stroke))
            for (line in 1..2) {
                val at = size.width * line / SIDE
                drawLine(UiColor.White, Offset(at, 0f), Offset(at, size.height), stroke)
                drawLine(UiColor.White, Offset(0f, at), Offset(size.width, at), stroke)
            }
        }
    }
}

/** The coaching line (a polite live region), outside the viewfinder so it never covers the grid. */
@Composable
private fun CoachingLine(
    phase: GuidedScanPhase.Viewfinder,
    strings: ContentStrings,
) {
    val coaching = phase.coaching?.let { strings.resolve(ContentText(coachingKey(it))) }.orEmpty()
    Text(
        coaching,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("scanCoaching"),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * The frame just captured, frozen over the viewfinder for [CaptureFreeze.FREEZE_MS] with a haptic tick, then shrinking
 * its grid square into the face on the mini net (skipped under reduced motion; the freeze stays). Leaving composition
 * also ends the freeze (GH #158): when the phase leaves the viewfinder or the Activity is recreated, a held result is
 * published and a recreated screen shows no overlay, so it never ticks twice. Ending a freeze already ended is ignored.
 */
@Composable
private fun FrozenOverlay(
    frozen: FrozenCapture,
    geometry: ViewfinderGeometry,
    onEnd: (Long) -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    val haptics = LocalHapticFeedback.current
    val hapticsOn = LocalHaptics.current
    val end by rememberUpdatedState(onEnd)
    val progress = remember(frozen.id) { Animatable(0f) }
    DisposableEffect(frozen.id) { onDispose { end(frozen.id) } }
    LaunchedEffect(frozen.id) {
        if (hapticsOn) haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
        delay(CaptureFreeze.FREEZE_MS)
        if (frozen.shrink && !reduceMotion) progress.animateTo(1f, tween(SHRINK_MS))
        end(frozen.id)
    }
    val image = frozen.image ?: return
    val pane = geometry.pane ?: return
    val grid = geometry.grid ?: pane
    val target = geometry.faces[frozen.face] ?: grid
    // Scaled about the pane's top-left, the grid square lands on the net face at progress 1.
    val scale = target.width / grid.width
    val shiftX = target.left - pane.left - scale * (grid.left - pane.left)
    val shiftY = target.top - pane.top - scale * (grid.top - pane.top)
    val density = LocalDensity.current
    Image(
        image,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier =
            Modifier
                .offset { IntOffset((pane.left - geometry.origin.x).roundToInt(), (pane.top - geometry.origin.y).roundToInt()) }
                .size(with(density) { pane.width.toDp() }, with(density) { pane.height.toDp() })
                .graphicsLayer {
                    val p = progress.value
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = 1f + (scale - 1f) * p
                    scaleY = scaleX
                    translationX = shiftX * p
                    translationY = shiftY * p
                }.testTag("scanFrozen"),
    )
}

internal fun coachingKey(coaching: Coaching): String =
    when (coaching) {
        Coaching.GLARE -> "msg.scan.coach.glare"
        Coaching.BLURRED -> "msg.scan.coach.blur"
        Coaching.LOW_LIGHT -> "msg.scan.coach.low-light"
        Coaching.MISALIGNED -> "msg.scan.coach.misaligned"
        Coaching.WRONG_FACE -> "msg.scan.coach.wrong-face"
        Coaching.MOVE_CLOSER -> "msg.scan.coach.move-closer"
        Coaching.MOVE_LEFT -> "msg.scan.coach.move-left"
        Coaching.MOVE_RIGHT -> "msg.scan.coach.move-right"
        Coaching.MOVE_UP -> "msg.scan.coach.move-up"
        Coaching.MOVE_DOWN -> "msg.scan.coach.move-down"
    }

/** "Face 2 of 6" (a Quick Check: "Face 1 of 2") in the first round; "Again: face 1 of 2" in the re-prompt round. */
private fun progressText(phase: GuidedScanPhase.Viewfinder): ContentText =
    phase.again?.let { ContentText("msg.scan.progress-again", mapOf("n" to it.n, "total" to it.total)) }
        ?: ContentText("msg.scan.progress", mapOf("n" to minOf(phase.stepIndex + 1, phase.total), "total" to phase.total))

/**
 * "Show the green center." with the center's glyph swatch; a re-prompt that names a top reads "Show the green center,
 * with white on top." with a swatch for each color.
 */
@Composable
private fun Prompt(
    phase: GuidedScanPhase.Viewfinder,
    strings: ContentStrings,
    modifier: Modifier = Modifier,
) {
    val step = phase.step ?: return
    val locale = LocalConfiguration.current.locales[0]
    val center = promptColorName(stringResource(colorName(step.centerColor)), locale)
    val top = step.upNeighborColor
    val up = top?.let { promptColorName(stringResource(colorName(it)), locale) }
    Row(
        modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
            .padding(8.dp)
            .testTag("scanPrompt"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Swatch(step.centerColor, 32.dp)
        if (top != null) Swatch(top, 24.dp)
        Text(
            strings.resolve(
                if (up == null) {
                    ContentText("msg.scan.prompt", mapOf("center" to center))
                } else if (phase.retake) {
                    ContentText("msg.scan.prompt-again-with-top", mapOf("center" to center, "up" to up))
                } else {
                    ContentText("msg.scan.prompt-with-top", mapOf("center" to center, "up" to up))
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** A color name as it reads mid-sentence in a prompt: lowercased by the casing rules of the UI [locale] (GH #158). */
internal fun promptColorName(
    name: String,
    locale: Locale,
): String = name.lowercase(locale)

@Composable
internal fun CornerPrompt(
    hold: Int,
    strings: ContentStrings,
    casePrompt: ContentText? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(8.dp).testTag("scanPrompt"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (casePrompt == null) QuickScanPlan.holds[hold - 1].forEach { Swatch(it.centerColor, 24.dp) }
        val prompt = if (hold == 1) ContentText("msg.quick-scan.hold-first") else ContentText("msg.quick-scan.hold-second")
        // Re-prompts replace this text: TalkBack announces the change.
        Text(
            strings.resolve(casePrompt ?: prompt),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun CornerOutline(modifier: Modifier) {
    Canvas(modifier.testTag("quickScan:outline")) {
        val points =
            CornerGuide(GridSquare(0.0, 0.0, size.width.toDouble()))
                .vertices()
                .map { Offset(it.x.toFloat(), it.y.toFloat()) }
        val edges = (0..5).map { points[it] to points[(it + 1) % 6] } + listOf(1, 3, 5).map { points[it] to points[6] }
        edges.forEach { (a, b) -> drawLine(UiColor.Black, a, b, 4.dp.toPx()) }
        edges.forEach { (a, b) -> drawLine(UiColor.White.copy(alpha = 0.7f), a, b, 2.dp.toPx()) }
    }
}

@Composable
private fun Swatch(
    color: CubeColor,
    size: Dp,
) {
    val appearance = StickerAppearance.of(color)
    Box(
        Modifier
            .size(size)
            .background(UiColor(appearance.fill), RoundedCornerShape(4.dp))
            .testTag("scanSwatch:${color.name}"),
        contentAlignment = Alignment.Center,
    ) { StickerGlyph(appearance, size) }
}

/** The six faces as a cross net: captured faces filled with their read colors, the current face outlined. */
@Composable
private fun MiniNet(
    phase: GuidedScanPhase.Viewfinder,
    geometry: ViewfinderGeometry,
    modifier: Modifier,
    highlighted: Set<Face>,
) {
    val byFace = phase.captured.associateBy { it.face }
    BoxWithConstraints(modifier.testTag("scanNet"), contentAlignment = Alignment.Center) {
        val cell = minOf(maxWidth / (NET_COLUMNS * SIDE), maxHeight / (NET_ROWS * SIDE))
        val outline = MaterialTheme.colorScheme.primary
        val empty = MaterialTheme.colorScheme.surfaceVariant
        Box(Modifier.requiredSize(cell * NET_COLUMNS * SIDE, cell * NET_ROWS * SIDE)) {
            Face.entries.forEach { face ->
                val (column, row) = NET_POSITION.getValue(face)
                val colors = byFace[face]?.let { uprightNet(it).map(::labColor) }
                Box(
                    Modifier
                        .offset(cell * SIDE * column, cell * SIDE * row)
                        .requiredSize(cell * SIDE)
                        .then(if (face in highlighted) Modifier.border(BorderStroke(2.dp, outline)) else Modifier)
                        .onGloballyPositioned { geometry.faces[face] = it.boundsInRoot() }
                        .testTag("scanNetFace:${face.name}"),
                ) {
                    for (index in 0 until SIDE * SIDE) {
                        Box(
                            Modifier
                                .offset(cell * (index % SIDE), cell * (index / SIDE))
                                .requiredSize(cell)
                                .padding(1.dp)
                                .background(colors?.get(index) ?: empty, RoundedCornerShape(2.dp)),
                        )
                    }
                }
            }
        }
    }
}

/** The primary *Capture*, plus *Use this face anyway* after a repeated face was rejected twice at this step. */
@Composable
private fun CaptureButtons(
    phase: GuidedScanPhase.Viewfinder,
    strings: ContentStrings,
    actions: GuidedScanActions,
) {
    if (phase.canUseAnyway) {
        OutlinedButton(
            onClick = actions.onUseAnyway,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("scanUseAnyway"),
        ) { Text(strings.resolve(ContentText("btn.scan.use-anyway"))) }
    }
    Button(onClick = actions.onCapture, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("scanCapture")) {
        Text(strings.resolve(ContentText("btn.scan.capture")))
    }
}

/** Net cell (column, row) of each face's 3×3 in the cross net. */
private val NET_POSITION: Map<Face, Pair<Int, Int>> =
    mapOf(Face.U to (1 to 0), Face.L to (0 to 1), Face.F to (1 to 1), Face.R to (2 to 1), Face.B to (3 to 1), Face.D to (1 to 2))

/** The sample's stickers as the viewfinder shows them (pixel order turned by its orientation), i.e. plan net order. */
internal fun uprightNet(sample: FaceSample): List<Lab> {
    val clockwise = listOf(6, 3, 0, 7, 4, 1, 8, 5, 2)
    return (0 until Math.floorMod(sample.orientationDegrees / QUARTER, 4)).fold(sample.stickers) { grid, _ -> clockwise.map { grid[it] } }
}

/** A measured CIELAB value as a display color (D65 sRGB), for the mini net. */
internal fun labColor(lab: Lab): UiColor {
    val fy = (lab.l + 16) / 116
    val fx = fy + lab.a / 500
    val fz = fy - lab.b / 200

    fun inverse(f: Double) = if (f.pow(3) > 0.0088564517) f.pow(3) else (f - 16.0 / 116.0) / 7.787037
    val x = inverse(fx) * 0.95047
    val y = inverse(fy)
    val z = inverse(fz) * 1.08883

    fun gamma(linear: Double): Float {
        val c = linear.coerceIn(0.0, 1.0)
        return (if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055).toFloat()
    }
    return UiColor(
        gamma(3.2404542 * x - 1.5371385 * y - 0.4985314 * z),
        gamma(-0.9692660 * x + 1.8760108 * y + 0.0415560 * z),
        gamma(0.0556434 * x - 0.2040259 * y + 1.0572252 * z),
    )
}

@Composable
private fun EnterColorsButton(
    strings: ContentStrings,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick,
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("scanEnterColors"),
    ) {
        Text(strings.resolve(ContentText("btn.scan.enter-colors-instead")))
    }
}

@Composable
private fun CalibrationViewfinder(
    phase: GuidedScanPhase.Calibration,
    strings: ContentStrings,
    actions: GuidedScanActions,
    offerEnterColors: Boolean,
    preview: @Composable () -> Unit,
) {
    val session = phase.session
    val color = session.current
    val geometry = remember { ViewfinderGeometry() }
    val panel: @Composable () -> Unit = {
        Column {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    // Large type or a short screen must never push Keep or Save out of reach.
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val prompt =
                    if (color == null) {
                        "msg.calibration.done"
                    } else {
                        "msg.calibration.${if (session.plainSticker) "plain" else "prompt"}.${color.name.lowercase()}"
                    }
                Text(
                    strings.resolve(ContentText(prompt)),
                    // A polite live region, so TalkBack hears the next color and the plain-sticker re-prompt.
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("calibration:prompt"),
                )
                if (color !=
                    null
                ) {
                    Text(strings.resolve(ContentText("msg.calibration.progress", mapOf("n" to (session.kept.size + 1).toString()))))
                }
                CoachingLine(GuidedScanPhase.Viewfinder(coaching = phase.coaching), strings)
                session.pending?.let {
                    val swatchName = color?.let { current -> stringResource(colorName(current)) }.orEmpty()
                    Box(
                        Modifier
                            .size(48.dp)
                            .background(labColor(it))
                            .semantics { contentDescription = swatchName }
                            .testTag("calibration:swatch"),
                    )
                    Text(strings.resolve(ContentText("msg.calibration.swatch")))
                    Row {
                        Button(
                            actions.onKeep,
                            Modifier.testTag("calibration:keep"),
                        ) { Text(strings.resolve(ContentText("btn.calibration.keep"))) }
                        OutlinedButton(
                            actions.onRetake,
                            Modifier.testTag("calibration:retake"),
                        ) { Text(strings.resolve(ContentText("btn.calibration.try-again"))) }
                    }
                }
                if (color != null && session.pending == null) {
                    Button(actions.onCapture, Modifier.testTag("calibration:capture")) {
                        Text(strings.resolve(ContentText("btn.scan.capture")))
                    }
                }
                Button(actions.onSave, enabled = session.calibration != null, modifier = Modifier.testTag("calibration:save")) {
                    Text(strings.resolve(ContentText("btn.calibration.save")))
                }
                if (phase.saveFailed) {
                    Text(
                        strings.resolve(ContentText("msg.calibration.save-failed")),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("calibration:save-failed"),
                    )
                }
            }
            if (offerEnterColors) {
                Box(Modifier.padding(horizontal = 12.dp)) { EnterColorsButton(strings, actions.onEnterColors) }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cameraHeight = maxHeight * VIEWFINDER_HEIGHT_FRACTION
        if (maxWidth >= EXPANDED_WIDTH || maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) { CameraArea(geometry, preview) }
                Box(Modifier.weight(1f)) { panel() }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(cameraHeight)) { CameraArea(geometry, preview) }
                panel()
            }
        }
    }
}
