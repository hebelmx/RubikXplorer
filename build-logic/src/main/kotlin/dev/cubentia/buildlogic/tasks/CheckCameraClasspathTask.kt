package dev.cubentia.buildlogic.tasks

import dev.cubentia.buildlogic.policy.CameraClasspathPolicy
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/** Fails the build when a variant's runtime classpath contains camera dependencies while camera flags are OFF (see [CameraClasspathPolicy]). */
@CacheableTask
abstract class CheckCameraClasspathTask : DefaultTask() {
    @get:Input
    abstract val variantName: Property<String>

    /** Resolved `group:name:version` of every external module on the runtime classpath. */
    @get:Input
    abstract val runtimeCoordinates: ListProperty<String>

    @get:Input
    abstract val cameraEnabled: Property<Boolean>

    @get:Input
    abstract val liteRtEnabled: Property<Boolean>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val violations =
            CameraClasspathPolicy.check(
                runtimeCoordinates.get(),
                cameraEnabled.get(),
                liteRtEnabled.getOrElse(cameraEnabled.get()),
            )
        report.get().asFile.writeText(violations.joinToString("\n"))
        if (violations.isNotEmpty()) {
            throw GradleException(
                "AD-13 camera classpath check failed for variant ${variantName.get()}:\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
    }
}
