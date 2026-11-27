package dev.cubentia.buildlogic.tasks

import dev.cubentia.buildlogic.policy.FixtureWriterPolicy
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.zip.ZipFile

/**
 * Story 10.34: fails any non-debuggable variant whose compiled classes hold a continuous-session fixture-writer class
 * (`dev/cubentia/app/camera/session/`), naming each one (see [FixtureWriterPolicy]).
 */
@CacheableTask
abstract class CheckFixtureWriterTask : DefaultTask() {
    @get:Input
    abstract val variantName: Property<String>

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
        val classes =
            classDirectories.get().flatMap { dir ->
                val root = dir.asFile
                root
                    .walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".class") }
                    .map { it.relativeTo(root).invariantSeparatorsPath }
                    .toList()
            } +
                classJars
                    .get()
                    .map { it.asFile }
                    .filter { it.isFile }
                    .flatMap { jar ->
                        ZipFile(jar).use { zip ->
                            zip
                                .entries()
                                .asSequence()
                                .map { it.name }
                                .filter { it.endsWith(".class") }
                                .toList()
                        }
                    }
        val violations = FixtureWriterPolicy.violations(classes)
        report
            .get()
            .asFile
            .apply { parentFile.mkdirs() }
            .writeText(violations.joinToString("\n"))
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Story 10.34 fixture-writer guard failed for variant ${variantName.get()} (the session writer is debug-only):\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
    }
}
