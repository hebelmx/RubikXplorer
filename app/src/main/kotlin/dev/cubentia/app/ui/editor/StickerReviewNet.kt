package dev.cubentia.app.ui.editor

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.cubentia.app.R
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.preferences.LocalRenderStyle
import dev.cubentia.app.render.Net2DRenderer
import dev.cubentia.app.render.RenderStyle
import dev.cubentia.app.render.STICKER_GAP_FRACTION
import dev.cubentia.app.render.StickerGlyph
import dev.cubentia.app.render.StickerLabel
import dev.cubentia.app.render.stickerColor
import dev.cubentia.app.render.stickerGlyphInk
import dev.cubentia.app.ui.lesson.rememberReduceMotion
import dev.cubentia.app.ui.theme.CubeTokens
import dev.cubentia.app.ui.theme.DarkTokens
import dev.cubentia.app.ui.theme.Radii
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.domain.appearance.StickerAppearance
import dev.cubentia.domain.cube.Facelets
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import dev.cubentia.domain.cube.Color as CubeColor
import dev.cubentia.domain.text.Text as ContentText

/** Whether a sticker carries the invalid-piece outline (it belongs to an offending piece). */
val StickerInvalidKey = SemanticsPropertyKey<Boolean>("StickerInvalid")
var SemanticsPropertyReceiver.stickerInvalid by StickerInvalidKey

/** The color of an invalid sticker's outline: `sticker-highlight`, per the cube-highlight rule. */
val StickerOutlineColorKey = SemanticsPropertyKey<Color>("StickerOutlineColor")
var SemanticsPropertyReceiver.stickerOutlineColor by StickerOutlineColorKey

/** The dashed edge color of a blank sticker's well. */
val StickerBlankEdgeKey = SemanticsPropertyKey<Color>("StickerBlankEdge")
var SemanticsPropertyReceiver.stickerBlankEdge by StickerBlankEdgeKey

/** Whether the selected sticker's ring pulses (false while reduce motion is on: the ring is static). */
val StickerPulsingKey = SemanticsPropertyKey<Boolean>("StickerPulsing")
var SemanticsPropertyReceiver.stickerPulsing by StickerPulsingKey

/**
 * The selected sticker's ring: a dashed `accent` ring, unlike an invalid piece's solid outline (PO 2026-11-16).
 */
data class SelectedRing(
    val color: Color,
    val width: Dp,
    val dash: Dp,
)

/** The ring drawn on the selected sticker. */
val StickerSelectedRingKey = SemanticsPropertyKey<SelectedRing>("StickerSelectedRing")
var SemanticsPropertyReceiver.stickerSelectedRing by StickerSelectedRingKey

/** Whether a sticker's update cue scales it (false while reduce motion is on: only the ✓ shows). */
val StickerCueScalesKey = SemanticsPropertyKey<Boolean>("StickerCueScales")
var SemanticsPropertyReceiver.stickerCueScales by StickerCueScalesKey

/**
 * The update cue for sticker [index]: played once per [serial], when a color has just been set on it.
 */
data class StickerCue(
    val index: Int,
    val serial: Int,
)

/** The `cubie-body` color drawn behind every face of the net, so a white sticker always sits on black. */
val CubeBodyColorKey = SemanticsPropertyKey<Color>("CubeBodyColor")
var SemanticsPropertyReceiver.cubeBodyColor by CubeBodyColorKey

/** The hairline drawn around the cube body, so it stays distinct from a dark page background. */
val CubeBodyOutlineKey = SemanticsPropertyKey<Color>("CubeBodyOutline")
var SemanticsPropertyReceiver.cubeBodyOutline by CubeBodyOutlineKey

private const val OUTLINE_DP = 3
private const val INNER_STROKE_DP = 1
private const val DASH_DP = 4
private const val BLANK_EDGE_DP = 2
private const val PULSE_MIN_ALPHA = 0.35f

/**
 * The selected ring (PO 2026-11-16): 3dp `accent` dashes, 8dp long with 4dp gaps, each edged by 1dp of `cubie-body`
 * (5dp in all), so it is thicker and longer-dashed than a blank's 2dp × 4dp dashed edge and never reads as an invalid
 * piece's solid outline, also where `accent` equals `sticker-highlight` (dark theme) and under reduce motion.
 */
private const val SELECTED_DASH_STROKE_DP = 3
private const val SELECTED_EDGE_DP = 1
private const val SELECTED_DASH_DP = 8
private const val SELECTED_GAP_DP = 4

/** Half a pulse cycle (100% → 35%); the full cycle back to 100% takes about 1.5 s. */
private const val PULSE_HALF_MS = 750
private const val CUE_SCALE = 1.1f
private const val CUE_PEAK_MS = 80
private const val CUE_SETTLE_MS = 200
private const val CHECK_FADE_MS = 600
private const val CHECK_SIZE_FRACTION = 0.45f
private const val CELLS_PER_FACE = 9
private const val FACE_SIDE = 3
private const val FACE_COUNT = 6

/** How far the cube body extends past the outer stickers, on top of the sticker gap. */
private const val BODY_RIM_DP = 2
private const val BODY_OUTLINE_DP = 1

/** Plain face names in URFDLB order, for sticker descriptions. */
internal val FACE_NAMES =
    listOf(
        R.string.move_face_u,
        R.string.move_face_r,
        R.string.move_face_f,
        R.string.move_face_d,
        R.string.move_face_l,
        R.string.move_face_b,
    )

/**
 * A tappable Kociemba cross net of [facelets] (Sticker review net, UX-DR16): fills and glyphs come
 * from [StickerAppearance] (glyphs while [style] asks for them), the [selected] sticker gets a thick dashed
 * `accent` ring that pulses slowly (static and dashed under reduce motion), every [invalid] sticker the
 * solid cube-highlight outline (`sticker-highlight`), and a blank sticker is a dark well with a dashed
 * `sticker-highlight` edge and a "?". A [cue] plays the update cue on its sticker. Tapping an [editable] sticker
 * calls [onStickerClick]; other stickers are not clickable. With a [face] (URFDLB index) only that
 * face's 3×3 stickers are drawn, for windows too narrow to give every net sticker a touch-sized cell.
 * Reusable for scan review.
 */
@Composable
fun StickerReviewNet(
    facelets: Facelets? = null,
    style: RenderStyle = LocalRenderStyle.current,
    selected: Int?,
    invalid: Set<Int>,
    onStickerClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    editable: (Int) -> Boolean = { true },
    face: Int? = null,
    unresolved: List<CubeColor?>? = null,
    strings: ContentStrings? = null,
    cue: StickerCue? = null,
) {
    val reduceMotion = rememberReduceMotion()
    // Cues already played, so a sticker scrolled back into a one-face view does not replay its cue.
    val playedCues = remember { mutableSetOf<Int>() }
    val columns = if (face == null) Net2DRenderer.NET_COLUMNS else FACE_SIDE
    val rows = if (face == null) Net2DRenderer.NET_ROWS else FACE_SIDE
    val indices = if (face == null) 0 until Facelets.SIZE else face * CELLS_PER_FACE until (face + 1) * CELLS_PER_FACE
    val faceOrigins =
        if (face == null) (0 until FACE_COUNT).map { Net2DRenderer.netCell(it * CELLS_PER_FACE) } else listOf(0 to 0)
    val body = RubikTheme.tokens.cubieBody
    val outline = RubikTheme.tokens.hairline
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val cell = min(maxWidth.value / columns, maxHeight.value / rows).dp
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(
                Modifier
                    .requiredSize(cell * columns, cell * rows)
                    .drawBehind { drawCubeBody(faceOrigins, cell.toPx(), body, outline) }
                    .testTag("reviewNet")
                    .semantics {
                        cubeBodyColor = body
                        cubeBodyOutline = outline
                    },
            ) {
                for (index in indices) {
                    val (column, row) =
                        if (face == null) {
                            Net2DRenderer.netCell(index)
                        } else {
                            (index % CELLS_PER_FACE % FACE_SIDE) to (index % CELLS_PER_FACE / FACE_SIDE)
                        }
                    ReviewSticker(
                        color = if (unresolved != null) unresolved[index] else requireNotNull(facelets).colors[index],
                        strings = strings,
                        index = index,
                        style = style,
                        isSelected = index == selected,
                        isInvalid = index in invalid,
                        onClick = if (editable(index)) ({ onStickerClick(index) }) else null,
                        cell = cell,
                        reduceMotion = reduceMotion,
                        cueSerial = cue?.takeIf { it.index == index }?.serial,
                        playedCues = playedCues,
                        modifier = Modifier.offset(cell * column, cell * row),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewSticker(
    color: CubeColor?,
    strings: ContentStrings?,
    index: Int,
    style: RenderStyle,
    isSelected: Boolean,
    isInvalid: Boolean,
    onClick: (() -> Unit)?,
    cell: Dp,
    reduceMotion: Boolean,
    cueSerial: Int?,
    playedCues: MutableSet<Int>,
    modifier: Modifier,
) {
    val appearance = color?.let(StickerAppearance::of)
    val tokens = RubikTheme.tokens
    // A blank is a `surface-stage-dark` well, never a light fill that could read as white.
    val fill = appearance?.let { Color(it.fill) } ?: DarkTokens.surfaceStage
    val ink = appearance?.let { Color(it.glyphInk) } ?: tokens.stickerHighlight
    val pulsing = isSelected && !reduceMotion
    val ringAlpha: State<Float> =
        if (pulsing) {
            rememberInfiniteTransition().animateFloat(
                initialValue = 1f,
                targetValue = PULSE_MIN_ALPHA,
                animationSpec = infiniteRepeatable(tween(PULSE_HALF_MS), RepeatMode.Reverse),
            )
        } else {
            remember { mutableFloatStateOf(1f) }
        }
    val cueScale = remember { Animatable(1f) }
    val checkAlpha = remember { Animatable(0f) }
    LaunchedEffect(cueSerial) {
        if (cueSerial == null || !playedCues.add(cueSerial)) return@LaunchedEffect
        checkAlpha.snapTo(1f)
        if (!reduceMotion) {
            launch {
                cueScale.animateTo(
                    1f,
                    keyframes {
                        durationMillis = CUE_SETTLE_MS
                        CUE_SCALE at CUE_PEAK_MS
                    },
                )
            }
        }
        checkAlpha.animateTo(0f, tween(CHECK_FADE_MS))
    }
    val description =
        if (color == null) {
            requireNotNull(strings).resolve(
                ContentText(
                    "msg.scan.blank-sticker",
                    mapOf(
                        "face" to stringResource(FACE_NAMES[index / CELLS_PER_FACE]),
                        "position" to (index % CELLS_PER_FACE + 1).toString(),
                    ),
                ),
            )
        } else {
            stringResource(
                R.string.editor_sticker,
                stringResource(colorName(color)),
                stringResource(FACE_NAMES[index / CELLS_PER_FACE]),
                index % CELLS_PER_FACE + 1,
            )
        }
    val invalidState = stringResource(R.string.editor_sticker_invalid)
    Box(
        modifier =
            modifier
                .requiredSize(cell)
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(cell * STICKER_GAP_FRACTION)
                .graphicsLayer {
                    scaleX = cueScale.value
                    scaleY = cueScale.value
                }.background(fill, RoundedCornerShape(Radii.xs))
                .drawWithContent {
                    drawContent()
                    var inset = 0f
                    if (color == null) {
                        inset = BLANK_EDGE_DP.dp.toPx()
                        val edge = BLANK_EDGE_DP.dp.toPx()
                        val dash = DASH_DP.dp.toPx()
                        drawRoundRect(
                            color = tokens.stickerHighlight,
                            topLeft = Offset(edge / 2, edge / 2),
                            size = Size(size.width - edge, size.height - edge),
                            cornerRadius = CornerRadius(Radii.xs.toPx()),
                            style = Stroke(edge, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
                        )
                    }
                    // The cube-highlight rule: never `status-invalid` red on the cube body.
                    if (isInvalid) inset = drawRing(tokens.stickerHighlight, inset)
                    if (isSelected) drawSelectedRing(tokens.accent, inset, ringAlpha.value)
                }.testTag("sticker:$index")
                .semantics {
                    contentDescription = description
                    stickerColor = fill
                    if (style.colorBlindGlyphs && color != null) stickerGlyphInk = ink
                    selected = isSelected
                    stickerInvalid = isInvalid
                    if (isInvalid) stickerOutlineColor = tokens.stickerHighlight
                    if (color == null) stickerBlankEdge = tokens.stickerHighlight
                    if (isSelected) stickerPulsing = pulsing
                    if (isSelected) {
                        stickerSelectedRing =
                            SelectedRing(tokens.accent, (SELECTED_DASH_STROKE_DP + 2 * SELECTED_EDGE_DP).dp, SELECTED_DASH_DP.dp)
                    }
                    if (cueSerial != null) stickerCueScales = !reduceMotion
                    if (isInvalid) stateDescription = invalidState
                    if (color == null) {
                        stateDescription =
                            requireNotNull(strings).resolve(
                                ContentText("msg.scan.blank-state"),
                            )
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        if (appearance == null) {
            StickerLabel(
                text = requireNotNull(strings).resolve(ContentText("msg.scan.blank-label")),
                color = ink,
                cell = cell,
            )
        } else if (style.colorBlindGlyphs) {
            StickerGlyph(appearance, cell)
        }
        if (checkAlpha.value > 0f) {
            Box(
                Modifier
                    .requiredSize(cell * CHECK_SIZE_FRACTION)
                    .graphicsLayer { alpha = checkAlpha.value }
                    .background(tokens.accent, CircleShape)
                    .testTag("sticker:$index:cue"),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", color = tokens.onAccent, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * Draws the near-black cube body behind each 3×3 face at [faceOrigins] (cell column, row), extended
 * by a rim past the outer stickers, so the sticker gaps and the face edge read as cube plastic. All
 * hairline [outline]s are drawn first and every body fill after, so in the net only the outer edge of
 * the joined faces keeps its outline, which separates the body from a dark page background.
 */
private fun DrawScope.drawCubeBody(
    faceOrigins: List<Pair<Int, Int>>,
    cell: Float,
    body: Color,
    outline: Color,
) {
    val rim = BODY_RIM_DP.dp.toPx()
    val stroke = BODY_OUTLINE_DP.dp.toPx()
    val side = cell * FACE_SIDE
    val radius = CornerRadius(Radii.xs.toPx() + rim)
    for ((column, row) in faceOrigins) {
        val reach = rim + stroke / 2
        drawRoundRect(
            color = outline,
            topLeft = Offset(column * cell - reach, row * cell - reach),
            size = Size(side + 2 * reach, side + 2 * reach),
            cornerRadius = radius,
            style = Stroke(stroke),
        )
    }
    for ((column, row) in faceOrigins) {
        drawRoundRect(
            color = body,
            topLeft = Offset(column * cell - rim, row * cell - rim),
            size = Size(side + 2 * rim, side + 2 * rim),
            cornerRadius = radius,
        )
    }
}

/**
 * A solid 3dp [color] ring with a 1dp `cubie-body` inner stroke, [inset] px inside the sticker edge,
 * so it reads on every sticker fill, drawn at [alpha]; returns the inset for a further ring.
 */
private fun DrawScope.drawRing(
    color: Color,
    inset: Float,
    alpha: Float = 1f,
): Float {
    val outer = OUTLINE_DP.dp.toPx()
    val inner = INNER_STROKE_DP.dp.toPx()
    val radius = Radii.xs.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(inset + outer / 2, inset + outer / 2),
        size = Size(size.width - 2 * inset - outer, size.height - 2 * inset - outer),
        cornerRadius = CornerRadius(max(0f, radius - inset)),
        style = Stroke(outer),
        alpha = alpha,
    )
    drawRoundRect(
        color = CubeTokens.cubieBody,
        topLeft = Offset(inset + outer + inner / 2, inset + outer + inner / 2),
        size = Size(size.width - 2 * (inset + outer) - inner, size.height - 2 * (inset + outer) - inner),
        cornerRadius = CornerRadius(max(0f, radius - inset - outer)),
        style = Stroke(inner),
        alpha = alpha,
    )
    return inset + outer + inner
}

/**
 * The selected sticker's dashed [color] ring, [inset] px inside the sticker edge, drawn at [alpha]: each dash is
 * edged by `cubie-body` so it reads on every sticker fill (the same dash pattern on the same path, so they align).
 */
private fun DrawScope.drawSelectedRing(
    color: Color,
    inset: Float,
    alpha: Float,
) {
    val width = (SELECTED_DASH_STROKE_DP + 2 * SELECTED_EDGE_DP).dp.toPx()
    val dashes = PathEffect.dashPathEffect(floatArrayOf(SELECTED_DASH_DP.dp.toPx(), SELECTED_GAP_DP.dp.toPx()))
    val topLeft = Offset(inset + width / 2, inset + width / 2)
    val ringSize = Size(size.width - 2 * inset - width, size.height - 2 * inset - width)
    val corner = CornerRadius(max(0f, Radii.xs.toPx() - inset))
    drawRoundRect(CubeTokens.cubieBody, topLeft, ringSize, corner, Stroke(width, pathEffect = dashes), alpha)
    drawRoundRect(color, topLeft, ringSize, corner, Stroke(SELECTED_DASH_STROKE_DP.dp.toPx(), pathEffect = dashes), alpha)
}

/** The plain name of a sticker [color], for content descriptions and palette labels. */
@StringRes
internal fun colorName(color: CubeColor): Int =
    when (color) {
        CubeColor.WHITE -> R.string.color_white
        CubeColor.YELLOW -> R.string.color_yellow
        CubeColor.RED -> R.string.color_red
        CubeColor.ORANGE -> R.string.color_orange
        CubeColor.GREEN -> R.string.color_green
        CubeColor.BLUE -> R.string.color_blue
    }
