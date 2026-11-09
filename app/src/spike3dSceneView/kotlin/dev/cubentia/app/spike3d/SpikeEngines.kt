package dev.cubentia.app.spike3d

/** OQ-3 spike, throwaway: the engines in this spike build (SceneView included). */
object SpikeEngines {
    val names: List<String> = listOf("gles", "sceneview")

    fun create(name: String): SpikeRenderer = if (name == "sceneview") SceneViewCubeRenderer() else GlesCubeRenderer()
}
