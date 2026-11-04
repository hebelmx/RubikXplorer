package dev.rubikxplorer.app.render

import android.opengl.GLSurfaceView
import android.view.View
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.rubikxplorer.app.R
import dev.rubikxplorer.app.render.gl.CubeGlRenderer
import dev.rubikxplorer.app.render.gl.CubeMesh
import dev.rubikxplorer.app.render.gl.CubieModel
import dev.rubikxplorer.app.render.gl.GlSnapshot
import dev.rubikxplorer.app.render.gl.GlyphAtlas
import dev.rubikxplorer.app.render.gl.PresentationQueue
import dev.rubikxplorer.app.render.gl.StickerDraw
import dev.rubikxplorer.app.ui.editor.colorName
import dev.rubikxplorer.app.ui.theme.CubeTokens
import dev.rubikxplorer.app.ui.theme.RubikTheme
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Facelets
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.input.GestureInterpreter
import dev.rubikxplorer.domain.input.MoveArrow
import dev.rubikxplorer.domain.input.MoveIntent
import dev.rubikxplorer.domain.input.ScreenPoint
import dev.rubikxplorer.domain.input.TouchPath
import dev.rubikxplorer.domain.input.Vector3
import dev.rubikxplorer.domain.input.ViewOrbit
import dev.rubikxplorer.domain.input.ViewPose
import kotlinx.coroutines.channels.Channel

val RenderedStickersKey = SemanticsPropertyKey<List<StickerDraw>>("RenderedStickers")

data class RenderedArrow(
    val notation: String,
    val progress: Float,
)

val RenderedArrowKey = SemanticsPropertyKey<RenderedArrow>("RenderedArrow")

/** Main-thread adapter. Compose owns presentation; GL only draws published snapshots. */
class Cube3DRenderer(
    initial: Facelets = CubeState.SOLVED.toFacelets(),
) : CubeRenderer {
    var orbit by mutableStateOf(ViewOrbit.DEFAULT)
        private set

    fun rotateView(
        yaw: Double,
        pitch: Double,
    ) {
        orbit = orbit.rotated(yaw, pitch)
    }

    fun dragView(
        dx: Double,
        dy: Double,
        sidePx: Double,
    ) {
        orbit = orbit.dragged(dx, dy, sidePx)
    }

    fun resetView() {
        orbit = ViewOrbit.DEFAULT
    }

    private val model = CubieModel(initial)
    private val queue = PresentationQueue(model)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var drawn by mutableStateOf(initial)

    /**
     * The Move the queue is presenting: the animating item's Move (the inverse during Undo or Step
     * Back), kept across the gap to the next waiting item; null once the queue is idle.
     */
    var presentedMove by mutableStateOf<Move?>(null)
        private set
    private var meshVersion = 0
    private var builtColors = -1
    private var builtStyle: RenderStyle? = null
    private var vertices = floatArrayOf()
    private val counts = CubeMesh.vertexCounts(model)
    private var geometryRevision by mutableIntStateOf(0)
    private var gl: CubeGlRenderer? = null
    val drawnFrames: Long get() = gl?.drawnFrames?.get() ?: 0

    /** The GL snapshot last published; null before [Content] first composes. */
    internal val glSnapshot: GlSnapshot? get() = gl?.snapshot

    /** The camera eye last handed to the GL renderer; null before [Content] first composes. */
    internal val glEye: Vector3? get() = gl?.eye

    override fun submit(items: List<PresentationItem>) {
        queue.submit(items)
        wake.trySend(Unit)
    }

    override fun renderedFacelets(): Facelets = drawn

    @Composable
    fun Content(
        style: RenderStyle,
        modifier: Modifier = Modifier,
        onMoveIntent: ((MoveIntent) -> Unit)? = null,
        emphasis: NetEmphasis? = null,
        arrow: Move? = null,
        reduceMotion: Boolean = false,
    ) {
        val latestCommit by rememberUpdatedState(onMoveIntent)
        val latestStyle by rememberUpdatedState(style)
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
            presentedMove = queue.activeMove ?: presentedMove?.takeIf { queue.isBusy }
            view.requestRender()
        }
        renderer.eye = orbit.eye()
        LaunchedEffect(orbit) { view.requestRender() }
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
                            queue.onFrame(it)
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
        // Minimal AD-17 description from the drawn state; Story 5.5 owns the full TalkBack experience.
        val description =
            stringResource(
                R.string.cube_description,
                stringResource(colorName(drawn.colors[U_CENTER])),
                stringResource(colorName(drawn.colors[F_CENTER])),
            )
        val state = stringResource(if (isSolved(drawn)) R.string.cube_state_solved else R.string.cube_state_unsolved)
        // Semantics on an AndroidView's own node never reach TalkBack (the interop view replaces it),
        // so a plain Compose node wraps the surface and is the single focusable leaf that speaks for it.
        Box(
            modifier =
                modifier.testTag("cube3d").clearAndSetSemantics {
                    this[RenderedStickersKey] = stickers
                    if (arrow != null) this[RenderedArrowKey] = RenderedArrow(Notation.format(arrow), arrowProgress)
                    contentDescription = description
                    stateDescription = state
                },
        ) {
            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
            if (arrow != null) {
                val measurer = rememberTextMeasurer()
                val label =
                    measurer.measure(
                        Notation.format(arrow),
                        RubikTheme.textStyles.notation.copy(color = CubeTokens.stickerHighlight),
                    )
                Canvas(Modifier.matchParentSize()) {
                    val pose = ViewPose(size.width.toInt(), size.height.toInt(), orbit.eye())
                    val points =
                        MoveArrow.arc(arrow, orbit.eye()).map {
                            pose.project(it).let { p ->
                                Offset(p.x.toFloat(), p.y.toFloat())
                            }
                        }
                    val position = arrowProgress * points.lastIndex
                    val whole = position.toInt().coerceAtMost(points.lastIndex)
                    val tip =
                        if (whole ==
                            points.lastIndex
                        ) {
                            points.last()
                        } else {
                            points[whole] + (points[whole + 1] - points[whole]) * (position - whole)
                        }
                    val path =
                        Path().apply {
                            moveTo(points.first().x, points.first().y)
                            for (i in 1..whole) lineTo(points[i].x, points[i].y)
                            lineTo(tip.x, tip.y)
                        }
                    drawPath(path, CubeTokens.stickerHighlight, style = Stroke(4.dp.toPx()))
                    if (arrowProgress > 0f) {
                        val previous = points[(whole - 1).coerceAtLeast(0)]
                        val direction = (tip - previous).let { if (it.getDistance() > 0f) it / it.getDistance() else Offset(1f, 0f) }
                        val back = tip - direction * 12.dp.toPx()
                        val side = Offset(-direction.y, direction.x) * 6.dp.toPx()
                        drawLine(CubeTokens.stickerHighlight, tip, back + side, 4.dp.toPx())
                        drawLine(CubeTokens.stickerHighlight, tip, back - side, 4.dp.toPx())
                        drawText(label, topLeft = tip + Offset(8.dp.toPx(), 0f))
                    }
                }
            }
            if (onMoveIntent != null) {
                Box(
                    Modifier.matchParentSize().pointerInput(Unit) {
                        var previousTap: Long? = null
                        awaitEachGesture {
                            val down = awaitFirstDown()

                            fun point(p: androidx.compose.ui.geometry.Offset) = ScreenPoint(p.x.toDouble(), p.y.toDouble())
                            val first = point(down.position)
                            var last = first
                            val initialOrbit = orbit
                            val pose = ViewPose(size.width, size.height, initialOrbit.eye())
                            val onCube = GestureInterpreter.startsOnCube(first, pose)
                            val side = minOf(size.width, size.height).toDouble()
                            val initialSecond = currentEvent.changes.firstOrNull { it.id != down.id && it.pressed }
                            var secondId = initialSecond?.id
                            var startA = first
                            var startB = initialSecond?.let { point(it.position) } ?: first
                            var endA = first
                            var endB = startB
                            var pairOrbit = initialOrbit
                            var pairPose = pose
                            var twisted = false
                            var moved = false
                            var valid = true
                            down.consume()
                            initialSecond?.consume()
                            do {
                                val event = awaitPointerEvent()
                                val primary = event.changes.firstOrNull { it.id == down.id }
                                if (secondId == null) {
                                    val second = event.changes.firstOrNull { it.id != down.id && it.pressed }
                                    if (second != null && primary != null && primary.pressed) {
                                        secondId = second.id
                                        startA = point(primary.position)
                                        startB = point(second.position)
                                        endA = startA
                                        endB = startB
                                        pairOrbit = orbit
                                        // Judge the twist against the camera shown when the pair formed.
                                        pairPose = ViewPose(size.width, size.height, pairOrbit.eye())
                                    }
                                }
                                if ((primary == null && secondId == null) || primary?.isConsumed == true) valid = false
                                if (primary != null) {
                                    last = point(primary.position)
                                    val dx = last.x - first.x
                                    val dy = last.y - first.y
                                    if (dx * dx + dy * dy >= viewConfiguration.touchSlop * viewConfiguration.touchSlop) moved = true
                                }
                                if (secondId != null) {
                                    val second = event.changes.firstOrNull { it.id == secondId }
                                    if (primary != null && second != null && primary.previousPressed && second.previousPressed) {
                                        endA = point(primary.position)
                                        endB = point(second.position)
                                        if (GestureInterpreter.interpretTwist(startA, startB, endA, endB, pairPose) != null) twisted = true
                                        orbit =
                                            if (twisted) {
                                                pairOrbit
                                            } else {
                                                pairOrbit.dragged(
                                                    (endA.x + endB.x - startA.x - startB.x) / 2,
                                                    (endA.y + endB.y - startA.y - startB.y) / 2,
                                                    side,
                                                )
                                            }
                                    }
                                } else if (!onCube) {
                                    orbit = initialOrbit.dragged(last.x - first.x, last.y - first.y, side)
                                }
                                event.changes.filter { it.id == down.id || it.id == secondId }.forEach { it.consume() }
                            } while (event.changes.any { (it.id == down.id || it.id == secondId) && it.pressed })
                            if (secondId != null) {
                                previousTap = null
                                if (valid &&
                                    twisted
                                ) {
                                    GestureInterpreter
                                        .interpretTwist(
                                            startA,
                                            startB,
                                            endA,
                                            endB,
                                            pairPose,
                                        )?.let { latestCommit?.invoke(it) }
                                }
                            } else if (!moved && valid) {
                                val previous = previousTap
                                if (previous != null && down.uptimeMillis - previous <= viewConfiguration.doubleTapTimeoutMillis) {
                                    resetView()
                                    previousTap = null
                                } else {
                                    previousTap = down.uptimeMillis
                                }
                            } else {
                                previousTap = null
                                if (valid &&
                                    onCube
                                ) {
                                    GestureInterpreter.interpret(TouchPath(listOf(first, last)), pose)?.let { latestCommit?.invoke(it) }
                                }
                            }
                        }
                    },
                )
            }
        }
    }

    companion object {
        private const val PER_FACE = 9
        private const val U_CENTER = 4
        private const val F_CENTER = 2 * PER_FACE + 4

        /** True when every face shows one color, in any orientation. */
        internal fun isSolved(facelets: Facelets): Boolean = facelets.colors.chunked(PER_FACE).all { face -> face.all { it == face[0] } }

        const val ARROW_IN_MILLIS = 200
        const val TURN_MILLIS = PresentationQueue.TURN_MILLIS
        const val FAST_FORWARD_AFTER = PresentationQueue.FAST_FORWARD_AFTER
        const val FAST_FORWARD_DIVISOR = PresentationQueue.FAST_FORWARD_DIVISOR
    }
}
