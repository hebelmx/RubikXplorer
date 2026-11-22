package dev.cubentia.app.render

import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import android.view.View
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.cubentia.app.R
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.render.gl.CubeGlRenderer
import dev.cubentia.app.render.gl.CubeMesh
import dev.cubentia.app.render.gl.CubieModel
import dev.cubentia.app.render.gl.GlSnapshot
import dev.cubentia.app.render.gl.GlyphAtlas
import dev.cubentia.app.render.gl.PresentationQueue
import dev.cubentia.app.render.gl.StickerDraw
import dev.cubentia.app.ui.editor.colorName
import dev.cubentia.app.ui.freecube.movePlainName
import dev.cubentia.app.ui.theme.CubeTokens
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.input.DragPreview
import dev.cubentia.domain.input.FaceLabel
import dev.cubentia.domain.input.FaceLabels
import dev.cubentia.domain.input.MoveArrow
import dev.cubentia.domain.input.MoveIntent
import dev.cubentia.domain.input.ScreenPoint
import dev.cubentia.domain.input.Vector3
import dev.cubentia.domain.input.ViewOrbit
import dev.cubentia.domain.input.ViewPose
import dev.cubentia.domain.text.Text
import kotlinx.coroutines.channels.Channel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

val RenderedStickersKey = SemanticsPropertyKey<List<StickerDraw>>("RenderedStickers")

data class RenderedArrow(
    val notation: String,
    val progress: Float,
)

val RenderedArrowKey = SemanticsPropertyKey<RenderedArrow>("RenderedArrow")

data class PreviewedMove(
    val notation: String,
    val degrees: Double,
)

val PreviewedMoveKey = SemanticsPropertyKey<PreviewedMove>("PreviewedMove")

val RenderedOrbitKey = SemanticsPropertyKey<ViewOrbit>("RenderedOrbit")

val RenderedFovKey = SemanticsPropertyKey<Double>("RenderedFov")

val RenderedFaceLabelsKey = SemanticsPropertyKey<List<Face>>("RenderedFaceLabels")

/** Main-thread adapter. Compose owns presentation; GL only draws published snapshots. */
class Cube3DRenderer(
    initial: Facelets = CubeState.SOLVED.toFacelets(),
) : CubeRenderer {
    var orbit by mutableStateOf(ViewOrbit.DEFAULT)
        private set

    var fovDegrees by mutableStateOf(38.0)

    internal fun pose(
        width: Int,
        height: Int,
        eye: Vector3 = orbit.eye(),
    ) = ViewPose(width, height, eye, fovDegrees)

    val isViewRotated: Boolean
        get() = !orbit.isHome()

    private class ResetRun(
        val from: ViewOrbit,
    )

    private var resetFrom by mutableStateOf<ResetRun?>(null)
    private var latestReduceMotion = false

    fun rotateView(
        yaw: Double,
        pitch: Double,
    ) {
        cancelViewReset()
        orbit = orbit.rotated(yaw, pitch)
    }

    fun dragView(
        dx: Double,
        dy: Double,
        sidePx: Double,
    ) {
        cancelViewReset()
        orbit = orbit.dragged(dx, dy, sidePx)
    }

    internal fun dragViewFrom(
        base: ViewOrbit,
        dx: Double,
        dy: Double,
        sidePx: Double,
    ) {
        cancelViewReset()
        orbit = base.dragged(dx, dy, sidePx)
    }

    fun resetView() {
        if (orbit.isHome()) {
            cancelViewReset()
            orbit = ViewOrbit.DEFAULT
            return
        }
        if (publishNow == null || latestReduceMotion) {
            cancelViewReset()
            orbit = ViewOrbit.DEFAULT
        } else {
            resetFrom = ResetRun(orbit)
        }
    }

    fun cancelViewReset() {
        resetFrom = null
    }

    private val model = CubieModel(initial)
    private val queue = PresentationQueue(model)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var drawn by mutableStateOf(initial)

    /**
     * The item the queue is presenting: the animating one, kept across the gap to the next waiting
     * item; null once the queue is idle.
     */
    private var presented by mutableStateOf<PresentationItem.Animate?>(null)

    /** The presented item's Move (the inverse during Undo or Step Back); null once the queue is idle. */
    val presentedMove: Move? get() = presented?.move

    /** The presented item's [PresentationItem.revision]; null once the queue is idle. */
    val presentedRevision: Long? get() = presented?.revision

    /**
     * The oldest revision [presentedRevision] may still report: the presented item's, else the
     * oldest item not yet finished; null once idle. Every earlier item has drained (FIFO).
     */
    val oldestLiveRevision: Long? get() = presented?.revision ?: queue.oldestRevision
    private var meshVersion = 0
    private var builtColors = -1
    private var builtStyle: RenderStyle? = null
    private var vertices = floatArrayOf()
    private val counts = CubeMesh.vertexCounts(model)
    private var geometryRevision by mutableIntStateOf(0)
    private var gl: CubeGlRenderer? = null

    /** Publishes the model to the composed [Content]; null while it is not composed. */
    private var publishNow: (() -> Unit)? = null
    val drawnFrames: Long get() = gl?.drawnFrames?.get() ?: 0

    /** The GL snapshot last published; null before [Content] first composes. */
    internal val glSnapshot: GlSnapshot? get() = gl?.snapshot

    /** The camera eye last handed to the GL renderer; null before [Content] first composes. */
    internal val glEye: Vector3? get() = gl?.eye

    /** The FOV last handed to the GL renderer; null before [Content] first composes. */
    internal val glFovDegrees: Double? get() = gl?.fovDegrees

    /** The presented turn traced as an async `Cube3D.turn` section (Story 12.2); null when none is open. */
    private var tracedTurn: PresentationItem.Animate? = null
    private var tracedCookie = 0
    private var tracedVisible = false
    private var nextCookie = 0

    /**
     * Opens or closes the `Cube3D.turn` async section as the queue's active item changes, and marks
     * the first publish that draws the turn partway (`Cube3D.turnVisible`). Called after each
     * snapshot is handed to GL, so any GL frame starting after a marker draws at least that snapshot.
     */
    private fun traceTurn() {
        val active = queue.active
        if (active !== tracedTurn) {
            endTracedTurn()
            if (active != null) {
                tracedTurn = active
                tracedCookie = ++nextCookie
                tracedVisible = false
                Trace.beginAsyncSection(TURN_SECTION, tracedCookie)
            }
        }
        if (tracedTurn != null && !tracedVisible && model.showsTurnProgress) {
            tracedVisible = true
            Trace.beginSection(TURN_VISIBLE_SECTION)
            Trace.endSection()
        }
    }

    /** Closes the open `Cube3D.turn` section, if any; every opened section is closed exactly once. */
    private fun endTracedTurn() {
        if (tracedTurn == null) return
        Trace.endAsyncSection(TURN_SECTION, tracedCookie)
        tracedTurn = null
    }

    override fun submit(items: List<PresentationItem>) {
        queue.submit(items)
        wake.trySend(Unit)
    }

    override fun renderedFacelets(): Facelets = drawn

    /** Discards everything outstanding and draws [target] at once, without waiting for a frame. */
    fun snapNow(target: Facelets) {
        clearPreview()
        queue.snapNow(target)
        drawn = model.facelets()
        publishNow?.invoke()
        endTracedTurn()
    }

    private var preview by mutableStateOf<DragPreview?>(null)
    private var finger by mutableStateOf(Offset.Zero)
    private var ghost: DragPreview.Active? = null
    private var cancelFrom by mutableStateOf<DragPreview.Active?>(null)
    private var heldUntil by mutableStateOf<Long?>(null)
    private var announcement by mutableStateOf<DragPreview?>(null)

    internal fun clearAnnouncement() {
        announcement = null
    }

    internal fun announceCancelled() {
        announcement = DragPreview.CancelZone
    }

    fun beginDrag() {
        clearPreview()
        queue.finishAll()
        endTracedTurn()
        drawn = model.facelets()
        presented = null
        publishNow?.invoke()
    }

    fun showPreview(
        next: DragPreview?,
        position: Offset,
        reduceMotion: Boolean = false,
    ) {
        val previous = preview
        preview = next
        finger = position
        when (next) {
            is DragPreview.Active -> {
                if ((previous as? DragPreview.Active)?.move != next.move) announcement = next
                cancelFrom = null
                if (reduceMotion) {
                    ghost = null
                    model.clearGhost()
                } else {
                    ghost = next
                    model.setGhost(next.move, next.degrees.toFloat())
                }
                publishNow?.invoke()
            }

            DragPreview.CancelZone -> {
                announcement = null
                if (previous is DragPreview.Active) cancelPreview(animated = true)
            }

            null -> {
                if (previous != null) cancelPreview(animated = false)
            }
        }
    }

    fun holdForCommit() {
        preview = null
        cancelFrom = null
        if (ghost != null) {
            heldUntil = SystemClock.uptimeMillis() + HOLD_TIMEOUT_MILLIS
            model.hideGhostHighlight()
            publishNow?.invoke()
        }
    }

    internal fun endDrag() {
        if (heldUntil == null) cancelPreview(animated = false)
    }

    fun cancelPreview(animated: Boolean) {
        if (animated) {
            cancelFrom = ghost
        } else if (clearPreview()) {
            publishNow?.invoke()
        }
    }

    private fun clearPreview(): Boolean {
        val hadGhost = ghost != null
        preview = null
        cancelFrom = null
        heldUntil = null
        ghost = null
        model.clearGhost()
        return hadGhost
    }

    @Composable
    fun Content(
        style: RenderStyle,
        modifier: Modifier = Modifier,
        onMoveIntent: ((MoveIntent) -> Unit)? = null,
        emphasis: NetEmphasis? = null,
        arrow: Move? = null,
        reduceMotion: Boolean = false,
        summary: String? = null,
        freeViewRotation: Boolean = false,
        onDragStart: (() -> Unit)? = null,
        viewGestures: Boolean = onMoveIntent != null || freeViewRotation,
        strings: ContentStrings? = null,
    ) {
        val latestStyle by rememberUpdatedState(style)
        SideEffect {
            queue.animationSpeed = style.animationSpeed
            latestReduceMotion = reduceMotion
        }
        val latestEmphasis by rememberUpdatedState(emphasis)
        val density by rememberUpdatedState(LocalDensity.current.density)
        var arrowProgress by remember(arrow, reduceMotion) { mutableFloatStateOf(if (reduceMotion) 1f else 0f) }
        LaunchedEffect(arrow, reduceMotion) {
            if (arrow != null &&
                !reduceMotion
            ) {
                animate(0f, 1f, animationSpec = tween(ARROW_IN_MILLIS, easing = LinearEasing)) { value, _ ->
                    arrowProgress =
                        value
                }
            }
        }
        val stage =
            RubikTheme.tokens.surfaceStage
                .toArgb()
                .toLong() and 0xFFFFFFFFL
        val context = LocalContext.current
        val owner = LocalLifecycleOwner.current
        val renderer =
            remember {
                CubeGlRenderer(
                    GlyphAtlas.create(context),
                    context.resources
                        .openRawResource(R.raw.cube_vertex)
                        .bufferedReader()
                        .use { it.readText() },
                    context.resources
                        .openRawResource(R.raw.cube_fragment)
                        .bufferedReader()
                        .use { it.readText() },
                ).also { gl = it }
            }
        val view =
            remember {
                GLSurfaceView(context).apply {
                    setEGLContextClientVersion(3)
                    setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    // The surface has no accessible content; the wrapping Compose node speaks for it.
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
            }

        fun publish() {
            val matrices = FloatArray(27 * 16)
            repeat(27) { model.modelMatrix(it, matrices, it * 16) }
            if (builtColors != model.colorVersion || builtStyle != latestStyle) {
                vertices = CubeMesh.vertices(model, latestStyle)
                builtColors = model.colorVersion
                builtStyle = latestStyle
                meshVersion++
            }
            renderer.snapshot =
                GlSnapshot(
                    matrices,
                    vertices,
                    counts,
                    meshVersion,
                    CubeMesh.emphasisFlags(model, latestEmphasis),
                    latestEmphasis?.pulse?.coerceIn(0f, 1f) ?: 0f,
                    density,
                )
            geometryRevision++
            drawn = model.facelets()
            presented = queue.active ?: presented?.takeIf { queue.isBusy }
            traceTurn()
            view.requestRender()
        }
        DisposableEffect(this, view) {
            publishNow = { publish() }
            onDispose {
                publishNow = null
                // A held ghost loses its timeout with the stage; never bring a stale pose back on return.
                clearPreview()
                endTracedTurn()
            }
        }
        val easing = cancelFrom
        LaunchedEffect(easing) {
            if (easing != null) {
                val duration = (style.animationSpeed.scale(TURN_MILLIS.toLong()) * abs(easing.degrees) / 90).toInt()
                animate(easing.degrees.toFloat(), 0f, animationSpec = tween(duration, easing = LinearEasing)) { value, _ ->
                    if (cancelFrom == easing) {
                        ghost = easing.copy(degrees = value.toDouble())
                        model.setGhost(easing.move, value)
                        publish()
                    }
                }
            }
        }
        val resetRun = resetFrom
        LaunchedEffect(resetRun) {
            val run = resetRun ?: return@LaunchedEffect
            try {
                animate(0f, 1f, animationSpec = tween(RESET_MILLIS, easing = FastOutSlowInEasing)) { value, _ ->
                    if (resetFrom === run) {
                        orbit = run.from.towards(ViewOrbit.DEFAULT, value.toDouble())
                    }
                }
            } finally {
                if (resetFrom === run) {
                    orbit = ViewOrbit.DEFAULT
                    resetFrom = null
                }
            }
        }
        val deadline = heldUntil
        DisposableEffect(deadline) {
            val handler = Handler(Looper.getMainLooper())
            val expire =
                Runnable {
                    if (heldUntil == deadline) cancelPreview(animated = false)
                }
            if (deadline != null) handler.postAtTime(expire, deadline)
            onDispose { handler.removeCallbacks(expire) }
        }
        renderer.eye = orbit.eye()
        renderer.fovDegrees = fovDegrees
        LaunchedEffect(orbit, fovDegrees) { view.requestRender() }
        renderer.background = stage
        LaunchedEffect(style, stage, emphasis, density) { wake.trySend(Unit) }
        LaunchedEffect(this, view) {
            publish()
            try {
                while (true) {
                    if (!queue.isBusy) {
                        wake.receive()
                        publish()
                    }
                    while (queue.isBusy) {
                        withFrameNanos {
                            if (preview != null) {
                                // A prior session dispatch can reach the queue after the new drag starts.
                                queue.finishAll()
                                ghost?.let { model.setGhost(it.move, it.degrees.toFloat()) }
                            } else {
                                if (queue.active == null) {
                                    val deadlineAtFrame = heldUntil
                                    if (deadlineAtFrame == null || SystemClock.uptimeMillis() >= deadlineAtFrame) model.clearGhost()
                                    heldUntil = null
                                    ghost = null
                                }
                                queue.onFrame(it)
                            }
                            publish()
                        }
                    }
                }
            } finally {
                queue.finishCurrent()
                publish()
            }
        }
        DisposableEffect(owner, view) {
            val observer =
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> view.onResume()
                        Lifecycle.Event.ON_PAUSE -> view.onPause()
                        else -> Unit
                    }
                }
            owner.lifecycle.addObserver(observer)
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
            onDispose {
                owner.lifecycle.removeObserver(observer)
                view.onPause()
            }
        }
        // Track model updates so semantics always describe committed geometry.
        geometryRevision
        val stickers = CubeMesh.stickerDraws(model, style, emphasis)
        // The fallback frame description and face reading both follow the drawn geometry.
        val description =
            stringResource(
                R.string.cube_description,
                stringResource(colorName(drawn.colors[U_CENTER])),
                stringResource(colorName(drawn.colors[F_CENTER])),
            )
        var facesMode by remember(drawn) { mutableStateOf(false) }
        val faceNames =
            listOf(
                R.string.editor_face_u,
                R.string.editor_face_r,
                R.string.editor_face_f,
                R.string.editor_face_d,
                R.string.editor_face_l,
                R.string.editor_face_b,
            )
        val faces =
            if (facesMode) {
                drawn.colors
                    .chunked(PER_FACE)
                    .mapIndexed { index, colors ->
                        val names = colors.map { stringResource(colorName(it)).lowercase(java.util.Locale.ROOT) }
                        stringResource(R.string.cube_face_colors, stringResource(faceNames[index]), names.joinToString(", "))
                    }.joinToString(" ")
            } else {
                ""
            }
        val actionLabel = stringResource(if (facesMode) R.string.cube_read_summary else R.string.cube_read_faces)
        val state = stringResource(if (isSolved(drawn)) R.string.cube_state_solved else R.string.cube_state_unsolved)
        // Semantics on an AndroidView's own node never reach TalkBack (the interop view replaces it),
        // so a plain Compose node wraps the surface and is the single focusable leaf that speaks for it.
        val teachingArrow = arrow.takeIf { preview == null }
        val activePreview = preview as? DragPreview.Active
        val overlayMove = activePreview?.move ?: teachingArrow
        val overlayProgress = if (activePreview != null) 1f else arrowProgress
        val ghostArrow = activePreview != null
        val labelColor = arrowColors().fill.copy(alpha = if (ghostArrow) GHOST_ARROW_ALPHA else 1f)
        val gesture = if (viewGestures) cubeGestureInput(this, onMoveIntent, onDragStart, freeViewRotation, reduceMotion) else Modifier
        val rotated = isViewRotated
        var wasRotated by remember(this) { mutableStateOf(rotated) }
        var viewNoticeKey by remember(this) { mutableStateOf<String?>(null) }
        SideEffect {
            if (rotated != wasRotated) {
                wasRotated = rotated
                viewNoticeKey = if (rotated) VIEW_ROTATED_KEY else VIEW_RESET_KEY
            }
        }
        val labelGlyphs = rememberFaceLabelGlyphs()
        val labelGapPx = with(LocalDensity.current) { FACE_LABEL_GAP.toPx().toDouble() }
        BoxWithConstraints(modifier) {
            val stageW = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
            val stageH = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
            val faceLabels =
                if (rotated && stageW > 0 && stageH > 0) {
                    FaceLabels.place(
                        pose(stageW, stageH),
                        labelGlyphs.boxWidth.toDouble(),
                        labelGlyphs.boxHeight.toDouble(),
                        labelGapPx,
                    )
                } else {
                    emptyList()
                }
            Box(
                modifier =
                    Modifier.fillMaxSize().testTag("cube3d").clearAndSetSemantics {
                        this[RenderedStickersKey] = stickers
                        this[RenderedOrbitKey] = orbit
                        this[RenderedFovKey] = fovDegrees
                        if (rotated) this[RenderedFaceLabelsKey] = faceLabels.map { it.face }
                        if (teachingArrow != null) this[RenderedArrowKey] = RenderedArrow(Notation.format(teachingArrow), arrowProgress)
                        if (activePreview != null) {
                            this[PreviewedMoveKey] =
                                PreviewedMove(Notation.format(activePreview.move), activePreview.degrees)
                        }
                        contentDescription = if (facesMode) faces else summary ?: description
                        customActions =
                            listOf(
                                CustomAccessibilityAction(actionLabel) {
                                    facesMode = !facesMode
                                    true
                                },
                            )
                        if (facesMode) liveRegion = LiveRegionMode.Polite
                        stateDescription = state
                    },
            ) {
                AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                if (overlayMove != null) {
                    val measurer = rememberTextMeasurer()
                    val label =
                        measurer.measure(
                            Notation.format(overlayMove),
                            RubikTheme.textStyles.notation.copy(color = labelColor),
                        )
                    Canvas(Modifier.matchParentSize()) {
                        val pose = pose(size.width.toInt(), size.height.toInt())
                        val points = arrowPoints(overlayMove, pose)
                        val cut = arrowCut(points, overlayProgress)
                        val tip = cut.last()
                        if (overlayProgress > 0f) {
                            drawMoveArrow(cut, ghost = ghostArrow)
                            drawText(
                                label,
                                topLeft =
                                    arrowLabelTopLeft(
                                        if (activePreview !=
                                            null
                                        ) {
                                            finger
                                        } else {
                                            tip
                                        },
                                        label.size,
                                        size,
                                        8.dp.toPx(),
                                    ),
                            )
                        }
                    }
                }
                FaceLabelOverlay(
                    orbit = orbit,
                    modifier = Modifier.matchParentSize(),
                    glyphs = labelGlyphs,
                    labels = faceLabels,
                    pose = { width, height -> pose(width, height) },
                )
                if (viewGestures) Box(Modifier.matchParentSize().then(gesture))
            }
            if (onMoveIntent != null) {
                val message =
                    when (val notice = announcement) {
                        is DragPreview.Active -> movePlainName(notice.move)
                        DragPreview.CancelZone -> strings?.resolve(Text(TURN_CANCELLED_KEY))?.takeIf { it != TURN_CANCELLED_KEY }.orEmpty()
                        null -> ""
                    }
                Box(
                    Modifier.size(1.dp).testTag("cube3d:announcement").semantics {
                        liveRegion = LiveRegionMode.Polite
                        if (message.isNotEmpty()) text = AnnotatedString(message)
                    },
                )
            }
            val viewMessage =
                viewNoticeKey
                    ?.let { key -> strings?.resolve(Text(key))?.takeIf { it != key } }
                    .orEmpty()
            Box(
                Modifier.size(1.dp).testTag("cube3d:viewAnnouncement").semantics {
                    liveRegion = LiveRegionMode.Polite
                    // Keep the invisible announcement leaf after the stage controls in traversal order.
                    traversalIndex = 100f
                    if (viewMessage.isNotEmpty()) text = AnnotatedString(viewMessage)
                },
            )
        }
    }

    companion object {
        private const val PER_FACE = 9
        private const val U_CENTER = 4
        private const val F_CENTER = 2 * PER_FACE + 4

        /** True when every face shows one color, in any orientation. */
        internal fun isSolved(facelets: Facelets): Boolean = facelets.colors.chunked(PER_FACE).all { face -> face.all { it == face[0] } }

        const val HOLD_TIMEOUT_MILLIS = 500L

        /** Content key of the cancellation announcement; with no content loaded it stays silent instead of reading the key. */
        internal const val TURN_CANCELLED_KEY = "msg.gesture.turn-cancelled"

        const val ARROW_IN_MILLIS = 200

        /** Async trace section from a turn becoming active until it finishes (Story 12.2, `GlFrameMetric`). */
        const val TURN_SECTION = "Cube3D.turn"

        /** Instant-like section at the first publish that draws the active turn partway. */
        const val TURN_VISIBLE_SECTION = "Cube3D.turnVisible"

        /**
         * Where the arrow's notation label of [label] size goes in a [stage]: beside the [tip], [gap]
         * away on the side toward the stage's center, vertically centered on it, then clamped so the
         * whole label stays inside the stage (pinned to its top-left when larger than the stage).
         */
        internal fun arrowLabelTopLeft(
            tip: Offset,
            label: IntSize,
            stage: Size,
            gap: Float,
        ): Offset {
            val x = if (tip.x > stage.width / 2) tip.x - gap - label.width else tip.x + gap
            val y = tip.y - label.height / 2f
            return Offset(
                x.coerceIn(0f, max(0f, stage.width - label.width)),
                y.coerceIn(0f, max(0f, stage.height - label.height)),
            )
        }

        /** Width of the Move arrow's sticker-highlight shaft (DESIGN.md, Move arrow). */
        internal val ARROW_STROKE: Dp = 4.dp

        /** Width of the cubie-body casing on each side of the shaft and around the arrowhead. */
        internal val ARROW_CASING: Dp = 1.5.dp

        /** Length of the filled arrowhead along the direction of travel. */
        internal val ARROWHEAD_LENGTH: Dp = 18.dp

        /** Half-width of the filled arrowhead at its base. */
        internal val ARROWHEAD_HALF_WIDTH: Dp = 9.dp

        /** Opacity of the whole ghost Move arrow while a drag preview is active. */
        internal const val GHOST_ARROW_ALPHA = 0.6f

        /** Opaque colours of the Move arrow: [fill] for the shaft and head, [casing] around them. */
        internal data class ArrowColors(
            val fill: Color,
            val casing: Color,
        )

        /** Sticker-highlight on a cubie-body casing; the ghost arrow fades the whole arrow instead. */
        internal fun arrowColors(): ArrowColors = ArrowColors(fill = CubeTokens.stickerHighlight, casing = CubeTokens.cubieBody)

        /**
         * Corners of a filled arrowhead with its apex on [tip], pointing along the unit [direction]:
         * the apex, then the two base corners [length] back and [halfWidth] either side.
         */
        internal fun arrowhead(
            tip: Offset,
            direction: Offset,
            length: Float,
            halfWidth: Float,
        ): List<Offset> {
            val base = tip - direction * length
            val side = Offset(-direction.y, direction.x) * halfWidth
            return listOf(tip, base + side, base - side)
        }

        /**
         * The drawn part of the arc [points] at animation [progress] (0..1): the points up to the
         * animated tip, then the tip interpolated along the next segment; the last point is the tip.
         */
        internal fun arrowCut(
            points: List<Offset>,
            progress: Float,
        ): List<Offset> {
            val position = progress * points.lastIndex
            val whole = position.toInt().coerceAtMost(points.lastIndex)
            val tip =
                if (whole == points.lastIndex) {
                    points.last()
                } else {
                    points[whole] + (points[whole + 1] - points[whole]) * (position - whole)
                }
            return points.subList(0, whole + 1).let { if (tip == it.last()) it else it + tip }
        }

        /** The point [distance] back from the last of [points], measured along the polyline, clamped at its first point. */
        internal fun pointBack(
            points: List<Offset>,
            distance: Float,
        ): Offset = trimBack(points, distance).last()

        /**
         * [points] cut to end [distance] back from their last point, measured along the polyline;
         * just the first point when the polyline is no longer than [distance].
         */
        internal fun trimBack(
            points: List<Offset>,
            distance: Float,
        ): List<Offset> {
            var remaining = distance
            for (i in points.lastIndex downTo 1) {
                val segment = points[i] - points[i - 1]
                val length = segment.getDistance()
                if (length > 0f && length >= remaining) {
                    return points.subList(0, i) + (points[i] - segment * (max(0f, remaining) / length))
                }
                remaining -= length
            }
            return points.take(1)
        }

        /** Projected curvature (sagitta over chord) below which the drawn arc would read as a straight line. */
        internal const val MIN_ARC_CURVATURE = 0.05

        /** How far, in arc radii, a degenerate arc bows out of its turn plane at its middle. */
        private const val ARC_BOW = 0.35

        /**
         * Screen points of the teaching arc for [move] seen through [pose]; the last point is the tip.
         * When the camera lies in (or nearly in) the turn plane the projected arc collapses to a line,
         * so the arc is then bowed out of its plane, as that circle looks from just off the plane, which
         * keeps it a visible curve with the same start, end and direction of travel.
         */
        internal fun arrowPoints(
            move: Move,
            pose: ViewPose,
        ): List<Offset> {
            val arc = MoveArrow.arc(move, pose.eye)
            val plain = arc.map { pose.project(it) }
            if (curvature(plain) >= MIN_ARC_CURVATURE) return plain.map { Offset(it.x.toFloat(), it.y.toFloat()) }
            val middle = arc[arc.size / 2]
            val normal = (arc.first() - middle).cross(arc.last() - middle).normalized()
            // Bow the near middle away from the camera's side of the plane, as perspective would.
            val away = if ((pose.eye - middle).dot(normal) >= 0) -1.0 else 1.0
            return arc.mapIndexed { i, point ->
                val bow = sin(Math.PI * i / arc.lastIndex) * MoveArrow.ARROW_RADIUS * ARC_BOW * away
                pose.project(point + normal * bow).let { Offset(it.x.toFloat(), it.y.toFloat()) }
            }
        }

        /** Largest distance of [points] from the chord between their ends, over the chord's length. */
        internal fun curvature(points: List<ScreenPoint>): Double {
            val dx = points.last().x - points.first().x
            val dy = points.last().y - points.first().y
            val chord = sqrt(dx * dx + dy * dy)
            if (chord == 0.0) return 0.0
            return points.maxOf { abs((it.x - points.first().x) * dy - (it.y - points.first().y) * dx) } / (chord * chord)
        }

        const val TURN_MILLIS = PresentationQueue.TURN_MILLIS
        const val FAST_FORWARD_AFTER = PresentationQueue.FAST_FORWARD_AFTER
        const val FAST_FORWARD_DIVISOR = PresentationQueue.FAST_FORWARD_DIVISOR
        const val RESET_MILLIS = 200
        internal const val VIEW_ROTATED_KEY = "msg.view.rotated"
        internal const val VIEW_RESET_KEY = "msg.view.reset"
    }
}

internal val FACE_LABEL_GAP = 6.dp

/**
 * Draws the Move arrow along [points], whose last point is the tip: a sticker-highlight shaft and
 * filled arrowhead, both cased in cubie-body. A [ghost] arrow is drawn opaque into one offscreen
 * layer faded to [Cube3DRenderer.GHOST_ARROW_ALPHA], so overlapping paths never stack alpha.
 */
internal fun DrawScope.drawMoveArrow(
    points: List<Offset>,
    ghost: Boolean,
) {
    if (points.size < 2) return
    val colors = Cube3DRenderer.arrowColors()
    val stroke = Cube3DRenderer.ARROW_STROKE.toPx()
    val casing = Cube3DRenderer.ARROW_CASING.toPx()
    val headLength = Cube3DRenderer.ARROWHEAD_LENGTH.toPx()
    val tip = points.last()
    // Head direction: the chord over the head's own arc length, else the last non-zero segment.
    val direction =
        (tip - Cube3DRenderer.pointBack(points, headLength)).takeIf { it.getDistance() > 0f }
            ?: points.zipWithNext().lastOrNull { (a, b) -> b != a }?.let { (a, b) -> b - a }
    val unit = direction?.let { it / it.getDistance() }
    val head =
        unit?.let {
            val corners = Cube3DRenderer.arrowhead(tip, it, headLength, Cube3DRenderer.ARROWHEAD_HALF_WIDTH.toPx())
            Path().apply {
                moveTo(corners[0].x, corners[0].y)
                lineTo(corners[1].x, corners[1].y)
                lineTo(corners[2].x, corners[2].y)
                close()
            }
        }
    // The shaft ends inside the head, where the head is wider than the cased shaft, so the head
    // covers its end: no seam at the base and nothing past the narrow apex. The end sits on the
    // head's axis so a tightly curved arc cannot sag it out of the head's side.
    val trimmed = Cube3DRenderer.trimBack(points, headLength / 2)
    val shaftPoints =
        if (unit != null && trimmed.size >= 2) trimmed.dropLast(1) + (tip - unit * (headLength / 2)) else trimmed
    val shaft =
        if (shaftPoints.size < 2) {
            null
        } else {
            Path().apply {
                moveTo(shaftPoints.first().x, shaftPoints.first().y)
                for (i in 1..shaftPoints.lastIndex) lineTo(shaftPoints[i].x, shaftPoints[i].y)
            }
        }
    val canvas = drawContext.canvas
    if (ghost) {
        val reach = headLength + casing
        val bounds =
            Rect(
                min(0f, points.minOf { it.x } - reach),
                min(0f, points.minOf { it.y } - reach),
                max(size.width, points.maxOf { it.x } + reach),
                max(size.height, points.maxOf { it.y } + reach),
            )
        canvas.saveLayer(bounds, Paint().apply { alpha = Cube3DRenderer.GHOST_ARROW_ALPHA })
    }
    try {
        shaft?.let { drawPath(it, colors.casing, style = Stroke(stroke + 2 * casing)) }
        head?.let { drawPath(it, colors.casing, style = Stroke(2 * casing, join = StrokeJoin.Round)) }
        shaft?.let { drawPath(it, colors.fill, style = Stroke(stroke)) }
        head?.let { drawPath(it, colors.fill) }
    } finally {
        if (ghost) canvas.restore()
    }
}

internal class FaceLabelGlyphs(
    val layouts: Map<Face, TextLayoutResult>,
    val boxWidth: Int,
    val boxHeight: Int,
)

@Composable
internal fun rememberFaceLabelGlyphs(): FaceLabelGlyphs {
    val measurer = rememberTextMeasurer()
    val style = RubikTheme.textStyles.notation.copy(color = RubikTheme.tokens.inkSecondary)
    val layouts = Face.entries.associateWith { face -> measurer.measure(face.name, style) }
    val boxWidth = layouts.values.maxOf { it.size.width }
    val boxHeight = layouts.values.maxOf { it.size.height }
    return FaceLabelGlyphs(layouts, boxWidth, boxHeight)
}

@Composable
internal fun FaceLabelOverlay(
    orbit: ViewOrbit,
    modifier: Modifier = Modifier,
    glyphs: FaceLabelGlyphs = rememberFaceLabelGlyphs(),
    labels: List<FaceLabel>? = null,
    pose: (Int, Int) -> ViewPose = { width, height -> ViewPose(width, height, orbit.eye()) },
) {
    if (orbit.isHome()) return
    Canvas(modifier) {
        val placed =
            labels
                ?: FaceLabels.place(
                    pose(size.width.toInt(), size.height.toInt()),
                    glyphs.boxWidth.toDouble(),
                    glyphs.boxHeight.toDouble(),
                    FACE_LABEL_GAP.toPx().toDouble(),
                )
        for (label in placed) {
            val layout = glyphs.layouts.getValue(label.face)
            val dx = (glyphs.boxWidth - layout.size.width) / 2f
            val dy = (glyphs.boxHeight - layout.size.height) / 2f
            drawText(layout, topLeft = Offset(label.left.toFloat() + dx, label.top.toFloat() + dy))
        }
    }
}
