package dev.cubentia.app.spike3d

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.cubentia.domain.appearance.StickerAppearance
import dev.cubentia.domain.cube.Color
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * OQ-3 spike, throwaway: the cube in SceneView 4.63.0 (Filament 1.72.1). 27 cubie [Node]s, each
 * with a body `CubeNode` and one thin `CubeNode` per sticker, colored by a per-[Color] material
 * instance. Every frame `onFrame` advances the queue and writes each cubie node's transform from
 * [SpikeCubeModel.modelMatrix] (equivalent to parenting the turning layer under a pivot at the
 * origin, without reparenting Compose-owned nodes). Sticker materials change only on a Snap,
 * through recomposition.
 */
class SceneViewCubeRenderer : SpikeRenderer("sceneview") {
    private val nodes = arrayOfNulls<Node>(SpikeCubeModel.CUBIE_COUNT)
    private val matrix = FloatArray(MAT4 * SpikeCubeModel.CUBIE_COUNT)

    @Composable
    override fun Content(modifier: Modifier) {
        val engine = rememberEngine()
        val materialLoader = rememberMaterialLoader(engine)
        val stickerMaterials =
            remember(materialLoader) {
                Color.entries.associateWith { materialLoader.createColorInstance(ComposeColor(StickerAppearance.of(it).fill)) }
            }
        val bodyMaterial = remember(materialLoader) { materialLoader.createColorInstance(ComposeColor(StickerAppearance.CUBIE_BODY)) }
        val cameraNode =
            rememberCameraNode(engine) {
                position = Position(EYE_X, EYE_Y, EYE_Z)
                lookAt(Position(0f, 0f, 0f))
            }
        var colorVersion by remember { mutableIntStateOf(-1) }
        val colors = remember(colorVersion) { driver.withModel { model -> model.cubies.map { c -> c.stickers.map { it.color } } } }

        SceneView(
            modifier = modifier,
            // The default SurfaceType.Surface blanked the sibling Compose controls on the API 29
            // emulator (window shown black, buttons present but not drawn); TextureView draws inline.
            surfaceType = SurfaceType.TextureSurface,
            engine = engine,
            materialLoader = materialLoader,
            cameraNode = cameraNode,
            cameraManipulator = null,
            autoCenterContent = false,
            frameRatePolicy = FrameRatePolicy.Continuous(),
            // Wall clock, as in the GLES prototype: the Choreographer frameTimeNanos is vsync-quantized.
            onFrame = { _ ->
                driver.onFrame(System.nanoTime())
                val version =
                    driver.withModel { model ->
                        for (i in 0 until SpikeCubeModel.CUBIE_COUNT) model.modelMatrix(i, matrix, i * MAT4)
                        model.colorVersion
                    }
                nodes.forEachIndexed { i, node -> node?.transform = matrix.toMat4(i * MAT4) }
                if (version != colorVersion) colorVersion = version
            },
        ) {
            val stickers = driver.withModel { model -> model.cubies.map { c -> c.stickers.map { Triple(it.nx, it.ny, it.nz) } } }
            for (i in 0 until SpikeCubeModel.CUBIE_COUNT) {
                if (stickers[i].isEmpty()) continue
                Node(apply = { nodes[i] = this }) {
                    CubeNode(size = Size(BODY), materialInstance = bodyMaterial)
                    stickers[i].forEachIndexed { s, (nx, ny, nz) ->
                        CubeNode(
                            size = Size(thickness(nx), thickness(ny), thickness(nz)),
                            center = Position(nx * LIFT, ny * LIFT, nz * LIFT),
                            materialInstance = stickerMaterials.getValue(colors.getOrNull(i)?.getOrNull(s) ?: Color.WHITE),
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val MAT4 = 16
        const val BODY = 0.94f
        const val STICKER = 0.80f
        const val STICKER_DEPTH = 0.02f
        const val LIFT = 0.475f
        const val EYE_X = 4.2f
        const val EYE_Y = 4.6f
        const val EYE_Z = 6.4f

        fun thickness(normal: Int) = if (normal != 0) STICKER_DEPTH else STICKER

        fun FloatArray.toMat4(offset: Int) =
            Mat4(
                Float4(this[offset], this[offset + 1], this[offset + 2], this[offset + 3]),
                Float4(this[offset + 4], this[offset + 5], this[offset + 6], this[offset + 7]),
                Float4(this[offset + 8], this[offset + 9], this[offset + 10], this[offset + 11]),
                Float4(this[offset + 12], this[offset + 13], this[offset + 14], this[offset + 15]),
            )
    }
}
