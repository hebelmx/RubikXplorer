package dev.cubentia.app.spike3d

/** OQ-3 spike, throwaway: the engines in this spike build (OpenGL ES only; the SceneView prototype was removed in Story 1.11). */
object SpikeEngines {
    val names: List<String> = listOf("gles")

    @Suppress("UNUSED_PARAMETER")
    fun create(name: String): SpikeRenderer = GlesCubeRenderer()
}
