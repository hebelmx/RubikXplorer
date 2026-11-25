package dev.cubentia.buildlogic.policy

/**
 * Story 10.23 / Story 10.31 (AD-9, AD-13, AD-18): the Story 10.23 shadow sticker reader is retired and no longer
 * composed into any variant, including debug. This policy remains as the non-debuggable release guard ensuring
 * release, benchmark, `cornerR8`, and `release011` carry neither a `sticker_reader*.tflite` asset nor any
 * `dev/cubentia/app/camera/sticker/` class.
 */
object ShadowReaderPolicy {
    const val CLASS_PACKAGE = "dev/cubentia/app/camera/sticker/"
    private const val ASSET_PREFIX = "sticker_reader"
    private const val ASSET_SUFFIX = ".tflite"

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
