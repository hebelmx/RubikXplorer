package dev.cubentia.domain.recognition.evaluation.oq7

import java.io.File
import java.util.zip.ZipFile

/** What one runtime AAR would add to an arm64 release: its arm64-v8a native libraries and its classes.jar. */
data class AarSize(
    val name: String,
    val nativeLibraries: List<String>,
    val nativeUncompressed: Long,
    val nativeCompressed: Long,
    val classesJar: Long,
) {
    /** Projected install-size cost: arm64 `.so` uncompressed plus 3 × classes.jar as a dex proxy. */
    val projectedAdded: Long get() = nativeUncompressed + DEX_PROXY_FACTOR * classesJar

    companion object {
        const val DEX_PROXY_FACTOR = 3
        private const val ARM64_PREFIX = "jni/arm64-v8a/"

        fun read(file: File): AarSize {
            require(file.isFile) { "Missing AAR ${file.path}" }
            return ZipFile(file).use { zip ->
                val natives =
                    zip
                        .entries()
                        .asSequence()
                        .filter { it.name.startsWith(ARM64_PREFIX) && it.name.endsWith(".so") }
                        .sortedBy { it.name }
                        .toList()
                val classes = zip.getEntry("classes.jar")
                AarSize(
                    name = file.name,
                    nativeLibraries = natives.map { it.name.removePrefix(ARM64_PREFIX) },
                    nativeUncompressed = natives.sumOf { it.size },
                    nativeCompressed = natives.sumOf { it.compressedSize },
                    classesJar = classes?.size ?: 0L,
                )
            }
        }
    }
}
