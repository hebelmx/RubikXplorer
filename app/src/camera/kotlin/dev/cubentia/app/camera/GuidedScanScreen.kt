package dev.cubentia.app.camera

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.render.StickerGlyph
import dev.cubentia.app.ui.editor.colorName
import dev.cubentia.domain.appearance.StickerAppearance
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.Coaching
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.GuidedScanPlan
import dev.cubentia.domain.recognition.Lab
import kotlin.math.pow
import androidx.compose.ui.graphics.Color as UiColor
import dev.cubentia.domain.cube.Color as CubeColor
import dev.cubentia.domain.text.Text as ContentText

/** What the Guided Scan screen shows. */
internal sealed interface GuidedScanPhase {
    /** The camera rationale, before any system dialog. */
    data object Rationale : GuidedScanPhase

    /** The permission was denied; [permanent] when the system will not ask again. */
    data class Denied(
        val permanent: Boolean,
    ) : GuidedScanPhase

    /** CameraX could not bind a back camera. */
    data object Unavailable : GuidedScanPhase

    /**
     * The viewfinder at plan step [stepIndex], with the faces [captured] so far, the current [coaching], and
     * [canUseAnyway] after a repeated face was rejected twice at this step.
     */
    data class Viewfinder(
        val stepIndex: Int = 0,
        val captured: List<FaceSample> = emptyList(),
        val coaching: Coaching? = null,
        val canUseAnyway: Boolean = false,
    ) : GuidedScanPhase
}

internal class GuidedScanActions(
    val onClose: () -> Unit = {},
    val onContinue: () -> Unit = {},
    val onEnterColors: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onCapture: () -> Unit = {},
    val onUseAnyway: () -> Unit = {},
)

private const val VIEWFINDER_HEIGHT_FRACTION = 0.45f
private val EXPANDED_WIDTH = 600.dp
private const val NET_COLUMNS = 4
private const val NET_ROWS = 3
private const val SIDE = 3
private const val QUARTER = 90

/** Guided 6-face Scan (Story 10.2); [viewfinder] draws the camera preview (a PreviewView in the app). */
@Composable
internal fun GuidedScanScreen(
    phase: GuidedScanPhase,
    strings: ContentStrings,
    actions: GuidedScanActions,
    viewfinder: @Composable () -> Unit = {},
) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = actions.onClose,
                    modifier = Modifier.testTag("scanClose"),
                ) { Text(strings.resolve(ContentText("btn.scan.close"))) }
                if (phase is GuidedScanPhase.Viewfinder) {
                    Text(
                        strings.resolve(
                            ContentText("msg.scan.progress", mapOf("n" to minOf(phase.stepIndex + 1, GuidedScanPlan.steps.size))),
                        ),
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

                is GuidedScanPhase.Viewfinder -> {
                    Viewfinder(phase, strings, actions, viewfinder)
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

@Composable
private fun Viewfinder(
    phase: GuidedScanPhase.Viewfinder,
    strings: ContentStrings,
    actions: GuidedScanActions,
    preview: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= EXPANDED_WIDTH || maxWidth > maxHeight
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) { CameraArea(phase, strings, preview) }
                Column(
                    Modifier.weight(1f).fillMaxHeight().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Prompt(phase, strings)
                    MiniNet(phase, Modifier.weight(1f).fillMaxWidth())
                    CaptureButtons(phase, strings, actions)
                }
            }
        } else {
            val cameraHeight = maxHeight * VIEWFINDER_HEIGHT_FRACTION
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(cameraHeight)) {
                    CameraArea(phase, strings, preview)
                    Prompt(phase, strings, Modifier.align(Alignment.TopCenter).padding(8.dp))
                }
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MiniNet(phase, Modifier.weight(1f).fillMaxWidth())
                    CaptureButtons(phase, strings, actions)
                }
            }
        }
    }
}

/** The preview with the grid overlay and the coaching line (a polite live region). */
@Composable
private fun CameraArea(
    phase: GuidedScanPhase.Viewfinder,
    strings: ContentStrings,
    preview: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(UiColor.Black).testTag("scanViewfinder")) {
        preview()
        Canvas(Modifier.fillMaxSize()) {
            val side = minOf(size.width, size.height) * FrameReader.GRID_FRACTION.toFloat()
            val left = (size.width - side) / 2
            val top = (size.height - side) / 2
            val stroke = 2.dp.toPx()
            drawRect(UiColor.White, Offset(left, top), Size(side, side), style = Stroke(stroke))
            for (line in 1..2) {
                val at = side * line / SIDE
                drawLine(UiColor.White, Offset(left + at, top), Offset(left + at, top + side), stroke)
                drawLine(UiColor.White, Offset(left, top + at), Offset(left + side, top + at), stroke)
            }
        }
        val coaching = phase.coaching?.let { strings.resolve(ContentText(coachingKey(it))) }.orEmpty()
        Text(
            coaching,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(UiColor.Black.copy(alpha = 0.6f))
                    .padding(8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag("scanCoaching"),
            color = UiColor.White,
            textAlign = TextAlign.Center,
        )
    }
}

internal fun coachingKey(coaching: Coaching): String =
    when (coaching) {
        Coaching.GLARE -> "msg.scan.coach.glare"
        Coaching.BLURRED -> "msg.scan.coach.blur"
        Coaching.LOW_LIGHT -> "msg.scan.coach.low-light"
        Coaching.MISALIGNED -> "msg.scan.coach.misaligned"
        Coaching.WRONG_FACE -> "msg.scan.coach.wrong-face"
    }

/** "Show the green center, with white on top." with a glyph swatch for each color. */
@Composable
private fun Prompt(
    phase: GuidedScanPhase.Viewfinder,
    strings: ContentStrings,
    modifier: Modifier = Modifier,
) {
    val step = GuidedScanPlan.steps.getOrNull(phase.stepIndex) ?: return
    val center = stringResource(colorName(step.centerColor)).lowercase()
    val up = stringResource(colorName(step.upNeighborColor)).lowercase()
    Row(
        modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
            .padding(8.dp)
            .testTag("scanPrompt"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Swatch(step.centerColor, 32.dp)
        Swatch(step.upNeighborColor, 24.dp)
        Text(
            strings.resolve(ContentText("msg.scan.prompt", mapOf("center" to center, "up" to up))),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun Swatch(
    color: CubeColor,
    size: Dp,
) {
    val appearance = StickerAppearance.of(color)
    Box(
        Modifier.size(size).background(UiColor(appearance.fill), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) { StickerGlyph(appearance, size) }
}

/** The six faces as a cross net: captured faces filled with their read colors, the current face outlined. */
@Composable
private fun MiniNet(
    phase: GuidedScanPhase.Viewfinder,
    modifier: Modifier,
) {
    val current = GuidedScanPlan.steps.getOrNull(phase.stepIndex)?.face
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
                        .then(if (face == current) Modifier.border(BorderStroke(2.dp, outline)) else Modifier)
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
