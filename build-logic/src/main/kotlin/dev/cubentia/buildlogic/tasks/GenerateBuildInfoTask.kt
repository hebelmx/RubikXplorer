package dev.cubentia.buildlogic.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Story 10.34: writes `cubentia-build-info.properties` (`commit` = full `git rev-parse HEAD` or `unknown`, `litert` = the
 * catalog LiteRT version) into a generated asset directory of debuggable camera variants only, for the debug Quick Scan session recording's
 * continuous-session manifest. Never in a non-debuggable variant and never through `BuildConfig` (AD-18).
 */
@CacheableTask
abstract class GenerateBuildInfoTask : DefaultTask() {
    @get:Input
    abstract val commit: Property<String>

    @get:Input
    abstract val litertVersion: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun write() {
        val hash = commit.get().trim().takeIf { COMMIT.matches(it) } ?: "unknown"
        val dir = outputDirectory.get().asFile.apply { mkdirs() }
        File(dir, FILE_NAME).writeText("commit=$hash\nlitert=${litertVersion.get()}\n")
    }

    companion object {
        const val FILE_NAME = "cubentia-build-info.properties"
        private val COMMIT = Regex("[0-9a-f]{40}")
    }
}
