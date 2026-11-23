package dev.cubentia.buildlogic.tasks

import dev.cubentia.buildlogic.policy.ShadowReaderPolicy
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.zip.ZipFile

/**
 * Story 10.23: fails a non-debuggable variant (release, benchmark, `cornerR8`) whose merged assets hold a
 * `sticker_reader*.tflite` or whose compiled classes hold any `dev/cubentia/app/camera/sticker/` class, naming each
 * offending entry (see [ShadowReaderPolicy]).
 */
@CacheableTask
abstract class CheckShadowReaderTask : DefaultTask() {
    @get:Input
    abstract val variantName: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mergedAssets: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classJars: ListProperty<RegularFile>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirectories: ListProperty<Directory>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val assets = filesUnder(mergedAssets.get().asFile)
        val classes =
            classDirectories.get().flatMap { filesUnder(it.asFile).filter { path -> path.endsWith(".class") } } +
                classJars
                    .get()
                    .map { it.asFile }
                    .filter { it.isFile }
                    .flatMap(::classEntries)
        val violations = ShadowReaderPolicy.violations(assets, classes)
        report
            .get()
            .asFile
            .apply { parentFile.mkdirs() }
            .writeText(violations.joinToString("\n"))
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Story 10.23 shadow-reader check failed for variant ${variantName.get()} (the reader is debug-only):\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
    }

    private fun filesUnder(root: java.io.File): List<String> =
        root
            .walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toList()

    private fun classEntries(jar: java.io.File): List<String> =
        ZipFile(jar).use { zip ->
            zip
                .entries()
                .asSequence()
                .map { it.name }
                .filter { it.endsWith(".class") }
                .toList()
        }
}
