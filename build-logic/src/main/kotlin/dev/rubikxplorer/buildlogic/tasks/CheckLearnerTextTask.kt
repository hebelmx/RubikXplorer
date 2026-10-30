package dev.rubikxplorer.buildlogic.tasks

import dev.rubikxplorer.buildlogic.policy.LearnerTextPolicy
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.language.base.plugins.LifecycleBasePlugin

/**
 * Fails the build on a Learner-facing string literal in the shipped Kotlin sources — `src/main/kotlin`
 * plus `src/debug/kotlin` and `src/release/kotlin` where they exist (see [LearnerTextPolicy]).
 */
@CacheableTask
abstract class CheckLearnerTextTask : DefaultTask() {
    @get:InputFiles
    @get:SkipWhenEmpty
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** Violations are reported relative to this directory. */
    @get:Internal
    abstract val projectDirectory: DirectoryProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val root = projectDirectory.get().asFile
        val violations =
            sources.files
                .sortedBy { it.path }
                .flatMap { LearnerTextPolicy.check(it.relativeTo(root).invariantSeparatorsPath, it.readText()) }
                .map { it.toString() }
        report.get().asFile.writeText(violations.joinToString("\n"))
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Learner-facing string literals found (NFR-10, AD-12); use Text(key) or a string resource:\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
    }

    companion object {
        const val NAME = "checkLearnerText"

        /** The shipped source sets scanned; spike and test source sets are not. */
        val SOURCE_DIRS = listOf("src/main/kotlin", "src/debug/kotlin", "src/release/kotlin")

        /** Registers the literal gate over [SOURCE_DIRS] of [project] and wires it into `check`. */
        fun register(project: Project): TaskProvider<CheckLearnerTextTask> {
            val task =
                project.tasks.register(NAME, CheckLearnerTextTask::class.java) {
                    group = LifecycleBasePlugin.VERIFICATION_GROUP
                    description = "Fails on a Learner-facing string literal in main, debug or release Kotlin sources."
                    SOURCE_DIRS.forEach { dir -> sources.from(project.fileTree(dir) { include("**/*.kt") }) }
                    projectDirectory.set(project.layout.projectDirectory)
                    report.set(project.layout.buildDirectory.file("reports/rubikxplorer/learner-text.txt"))
                }
            project.pluginManager.apply(LifecycleBasePlugin::class.java)
            project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(task) }
            return task
        }
    }
}
