package dev.cubentia.buildlogic.policy

/**
 * Story 10.23 (AD-9, AD-13, AD-18): the shadow sticker reader, its toggle receiver and `sticker_reader.tflite` live only in
 * `src/camera/debug/sticker/{kotlin,assets,AndroidManifest.xml}`, composed only into a build type that is debuggable and
 * has a corner-model flag above OFF (the reader needs LiteRT). A non-debuggable variant (release, benchmark, `cornerR8`)
 * must carry neither the model asset nor any reader class.
 */
object ShadowReaderPolicy {
    const val SOURCE_DIR = "src/camera/debug/sticker"
    const val CLASS_PACKAGE = "dev/cubentia/app/camera/sticker/"
    private const val ASSET_PREFIX = "sticker_reader"
    private const val ASSET_SUFFIX = ".tflite"

    fun composes(
        debuggable: Boolean,
        cornerModelEnabled: Boolean,
    ): Boolean = debuggable && cornerModelEnabled

    fun isReaderAsset(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name.startsWith(ASSET_PREFIX) && name.endsWith(ASSET_SUFFIX)
    }

    fun isReaderClass(path: String): Boolean = path.replace('\\', '/').let { it.startsWith(CLASS_PACKAGE) || "/$CLASS_PACKAGE" in it }

    /** One violation per offending merged asset ([assets], paths relative to the merged assets) or class ([classes]). */
    fun violations(
        assets: Collection<String>,
        classes: Collection<String>,
    ): List<String> =
        assets.filter(::isReaderAsset).sorted().map { "Non-debuggable variant contains sticker model asset $it" } +
            classes.filter(::isReaderClass).sorted().map { "Non-debuggable variant contains shadow-reader class $it" }
}
