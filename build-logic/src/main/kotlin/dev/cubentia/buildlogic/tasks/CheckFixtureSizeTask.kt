package dev.cubentia.buildlogic.tasks

import dev.cubentia.buildlogic.policy.FixtureSizePolicy
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Story 10.34: fails when a checked-in continuous session directory exceeds 15,000,000 B or `Fixtures/continuous/`
 * exceeds 60,000,000 B, naming the directory and its bytes (see [FixtureSizePolicy]). A missing root passes.
 */
abstract class CheckFixtureSizeTask : DefaultTask() {
    @get:Input
    abstract val rootPath: Property<String>

    /** Every file under the root, so the check reruns when a fixture changes. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fixtureFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val root = File(rootPath.get())
        val sessions =
            root
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory }
                .associate { dir -> dir.name to dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        val rootBytes = if (root.isDirectory) root.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
        val violations = FixtureSizePolicy.violations(sessions, rootBytes)
        report
            .get()
            .asFile
            .apply { parentFile.mkdirs() }
            .writeText((sessions.map { (name, bytes) -> "$name $bytes" } + "total $rootBytes" + violations).joinToString("\n"))
        if (violations.isNotEmpty()) {
            throw GradleException("Story 10.34 fixture size check failed:\n" + violations.joinToString("\n") { "  - $it" })
        }
    }
}
