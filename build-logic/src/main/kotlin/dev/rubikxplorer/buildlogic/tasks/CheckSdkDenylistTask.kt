package dev.rubikxplorer.buildlogic.tasks

import dev.rubikxplorer.buildlogic.policy.SdkDenylistPolicy
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/** Fails the build when a variant's runtime classpath contains a denylisted SDK (see [SdkDenylistPolicy]). */
@CacheableTask
abstract class CheckSdkDenylistTask : DefaultTask() {
    @get:Input
    abstract val variantName: Property<String>

    /** Resolved `group:name:version` of every external module on the runtime classpath. */
    @get:Input
    abstract val runtimeCoordinates: ListProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val violations = SdkDenylistPolicy.check(runtimeCoordinates.get())
        report.get().asFile.writeText(violations.joinToString("\n"))
        if (violations.isNotEmpty()) {
            throw GradleException(
                "AD-13 SDK denylist check failed for variant ${variantName.get()}:\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
    }

    companion object {
        /** Every external module reachable from [root], as sorted `group:name:version`. */
        fun coordinates(root: ResolvedComponentResult): List<String> {
            val seen = mutableSetOf<ResolvedComponentResult>()
            val pending = ArrayDeque(listOf(root))
            while (pending.isNotEmpty()) {
                val component = pending.removeFirst()
                if (!seen.add(component)) continue
                component.dependencies
                    .filterIsInstance<ResolvedDependencyResult>()
                    .forEach { pending.addLast(it.selected) }
            }
            return seen
                .mapNotNull { it.id as? ModuleComponentIdentifier }
                .map { "${it.group}:${it.module}:${it.version}" }
                .sorted()
        }
    }
}
