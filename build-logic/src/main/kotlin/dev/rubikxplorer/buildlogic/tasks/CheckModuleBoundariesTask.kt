package dev.rubikxplorer.buildlogic.tasks

import dev.rubikxplorer.buildlogic.policy.DeclaredDependency
import dev.rubikxplorer.buildlogic.policy.DeclaredDependencyCodec
import dev.rubikxplorer.buildlogic.policy.ModuleBoundaryPolicy
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.language.base.plugins.LifecycleBasePlugin

/** Fails the build when a module breaks AD-1 (see [ModuleBoundaryPolicy]). */
@CacheableTask
abstract class CheckModuleBoundariesTask : DefaultTask() {
    @get:Input
    abstract val modulePath: Property<String>

    /** Encoded with [DeclaredDependencyCodec]. */
    @get:Input
    abstract val declaredDependencies: ListProperty<String>

    @get:Input
    abstract val appliedPluginIds: ListProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val dependencies = declaredDependencies.get().map(DeclaredDependencyCodec::decode)
        val violations = ModuleBoundaryPolicy.check(modulePath.get(), dependencies, appliedPluginIds.get())
        report.get().asFile.writeText(violations.joinToString("\n"))
        if (violations.isNotEmpty()) {
            throw GradleException("AD-1 module boundary check failed:\n" + violations.joinToString("\n") { "  - $it" })
        }
    }

    companion object {
        const val NAME = "checkModuleBoundaries"

        /** Registers the AD-1 boundary check for [project] and wires it into `check`. */
        fun register(project: Project): TaskProvider<CheckModuleBoundariesTask> {
            val projectPath = project.path
            val task =
                project.tasks.register(NAME, CheckModuleBoundariesTask::class.java) {
                    group = LifecycleBasePlugin.VERIFICATION_GROUP
                    description = "Fails when this module breaks the AD-1 module boundaries."
                    modulePath.set(projectPath)
                    report.set(project.layout.buildDirectory.file("reports/rubikxplorer/module-boundaries.txt"))
                }
            project.afterEvaluate {
                val (dependencies, plugins) = collect(this)
                task.configure {
                    declaredDependencies.set(dependencies)
                    appliedPluginIds.set(plugins)
                }
            }
            project.pluginManager.apply(LifecycleBasePlugin::class.java)
            project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(task) }
            return task
        }

        /** Collects what [project] declares; call once the build script has been evaluated. */
        fun collect(project: Project): Pair<List<String>, List<String>> {
            val dependencies =
                project.configurations.flatMap { configuration ->
                    configuration.dependencies.mapNotNull { dependency ->
                        when (dependency) {
                            is ProjectDependency -> {
                                DeclaredDependency.OnProject(configuration.name, dependency.path)
                            }

                            is ExternalDependency -> {
                                DeclaredDependency.External(
                                    configuration.name,
                                    dependency.group.orEmpty(),
                                    dependency.name,
                                    dependency.version,
                                )
                            }

                            else -> {
                                null
                            }
                        }?.let(DeclaredDependencyCodec::encode)
                    }
                }
            val plugins = ModuleBoundaryPolicy.forbiddenPluginIds.filter { project.pluginManager.hasPlugin(it) }
            return dependencies.distinct() to plugins
        }
    }
}
