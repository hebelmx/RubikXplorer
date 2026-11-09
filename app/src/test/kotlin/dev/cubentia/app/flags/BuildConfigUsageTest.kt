package dev.cubentia.app.flags

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** AD-18: no production code in `:app` (any source set but tests) reads `BuildConfig` except the flags adapter. */
class BuildConfigUsageTest {
    private val sourceRoot = File("src")
    private val adapter = File(sourceRoot, "main/kotlin/dev/cubentia/app/flags/BuildConfigFeatureFlags.kt")
    private val buildConfigReference = Regex("""\bBuildConfig\b""")

    private fun isTestSourceSet(name: String) = name.startsWith("test") || name.startsWith("androidTest")

    @Test
    fun `only the flags adapter references BuildConfig`() {
        assertTrue(adapter.isFile, "Run from the :app module directory; missing ${adapter.absolutePath}")
        val sourceSets = sourceRoot.listFiles().orEmpty().filter { it.isDirectory && !isTestSourceSet(it.name) }
        assertTrue(sourceSets.any { it.name == "main" }, "No main source set under ${sourceRoot.absolutePath}")
        val offenders =
            sourceSets
                .flatMap { it.walkTopDown().toList() }
                .filter { it.isFile && it.extension in setOf("kt", "java") }
                .filter { it.canonicalFile != adapter.canonicalFile }
                .filter { buildConfigReference.containsMatchIn(it.readText()) }
                .map { it.relativeTo(sourceRoot).path }
        assertEquals(emptyList<String>(), offenders, "BuildConfig is read outside the flags adapter")
    }
}
