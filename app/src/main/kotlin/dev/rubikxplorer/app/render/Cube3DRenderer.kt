package dev.rubikxplorer.app.render

import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import dev.rubikxplorer.app.ui.theme.RubikTheme
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Facelets
import kotlinx.coroutines.channels.Channel

val RenderedStickersKey = SemanticsPropertyKey<List<StickerDraw>>("RenderedStickers")

/** Main-thread adapter. Compose owns presentation; GL only draws published snapshots. */
class Cube3DRenderer(
    initial: Facelets = CubeState.SOLVED.toFacelets(),
) : CubeRenderer {
    private val model = CubieModel(initial)
    private val queue = PresentationQueue(model)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var drawn by mutableStateOf(initial)
    private var meshVersion = 0
    private var builtColors = -1
    private var builtStyle: RenderStyle? = null
    private var vertices = floatArrayOf()
    private val counts = CubeMesh.vertexCounts(model)
    private var geometryRevision by mutableIntStateOf(0)
    private var gl: CubeGlRenderer? = null
    val drawnFrames: Long get() = gl?.drawnFrames?.get() ?: 0

    override fun submit(items: List<PresentationItem>) {
        queue.submit(items)
        wake.trySend(Unit)
    }

    override fun renderedFacelets(): Facelets = drawn

    @Composable
    fun Content(
        style: RenderStyle,
        modifier: Modifier = Modifier,
    ) {
        val latestStyle by rememberUpdatedState(style)
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
            renderer.snapshot = GlSnapshot(matrices, vertices, counts, meshVersion)
            geometryRevision++
            drawn = model.facelets()
            view.requestRender()
        }
        renderer.background = stage
        LaunchedEffect(style, stage) { wake.trySend(Unit) }
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
        val stickers = CubeMesh.stickerDraws(model, style)
        // Minimal AD-17 description from the drawn state; Story 5.5 owns the full TalkBack experience.
        val description =
            stringResource(
                R.string.cube_description,
                stringResource(colorName(drawn.colors[U_CENTER])),
                stringResource(colorName(drawn.colors[F_CENTER])),
            )
        val state = stringResource(if (isSolved(drawn)) R.string.cube_state_solved else R.string.cube_state_unsolved)
        AndroidView(
            factory = { view },
            modifier =
                modifier.testTag("cube3d").semantics {
                    this[RenderedStickersKey] = stickers
                    contentDescription = description
                    stateDescription = state
                },
        )
    }

    companion object {
        private const val PER_FACE = 9
        private const val U_CENTER = 4
        private const val F_CENTER = 2 * PER_FACE + 4

        /** True when every face shows one color, in any orientation. */
        internal fun isSolved(facelets: Facelets): Boolean = facelets.colors.chunked(PER_FACE).all { face -> face.all { it == face[0] } }

        const val TURN_MILLIS = PresentationQueue.TURN_MILLIS
        const val FAST_FORWARD_AFTER = PresentationQueue.FAST_FORWARD_AFTER
        const val FAST_FORWARD_DIVISOR = PresentationQueue.FAST_FORWARD_DIVISOR
    }
}
