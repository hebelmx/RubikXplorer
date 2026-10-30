package dev.rubikxplorer.app.spike3d

/** OQ-3 spike, throwaway: the engines in this spike build (OpenGL ES only, to measure its APK delta alone). */
object SpikeEngines {
    val names: List<String> = listOf("gles")

    @Suppress("UNUSED_PARAMETER")
    fun create(name: String): SpikeRenderer = GlesCubeRenderer()
}
