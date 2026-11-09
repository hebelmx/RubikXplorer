package dev.cubentia.buildlogic.tasks

import dev.cubentia.buildlogic.policy.ManifestPolicy
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Fails the build when a variant's merged manifest breaks AD-13 (see [ManifestPolicy]). */
@CacheableTask
abstract class CheckMergedManifestTask : DefaultTask() {
    @get:Input
    abstract val variantName: Property<String>

    /**
     * Whether the variant is debuggable; decides the allowed runtime permissions (see
     * [ManifestPolicy.allowedRuntimePermissions]). Required, with no default: an unset value fails the task.
     */
    @get:Input
    abstract val debuggable: Property<Boolean>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val violations =
            ManifestPolicy.check(
                mergedManifest.get().asFile.readText(),
                ManifestPolicy.allowedRuntimePermissions(debuggable.get()),
            )
        report.get().asFile.writeText(violations.joinToString("\n"))
        if (violations.isNotEmpty()) {
            throw GradleException(
                "AD-13 merged-manifest check failed for variant ${variantName.get()}:\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
    }
}
