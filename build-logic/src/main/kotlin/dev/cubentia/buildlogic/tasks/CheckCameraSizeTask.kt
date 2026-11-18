package dev.cubentia.buildlogic.tasks

import dev.cubentia.buildlogic.policy.AppSizePolicy
import dev.cubentia.buildlogic.policy.CameraSizePolicy
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.zip.ZipFile

abstract class CheckCameraSizeTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val releaseReport: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val cameraClasses: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val cameraRuntime: ConfigurableFileCollection

    @get:Input
    abstract val runtimeArtifacts: ListProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val release =
            releaseReport.get().asFile.useLines { lines ->
                lines.single { it.startsWith("Installed estimate ") }.substringAfterLast(": ").toLong()
            }
        val classBytes = cameraClasses.sumOf { it.length() }
        val runtimeBytes =
            cameraRuntime.sumOf { file ->
                when (file.extension) {
                    "aar" -> {
                        ZipFile(file).use { zip ->
                            zip
                                .entries()
                                .asSequence()
                                .filter { it.name.endsWith(".jar") || it.name.endsWith(".so") }
                                .sumOf { it.size }
                        }
                    }

                    "jar", "so" -> {
                        file.length()
                    }

                    else -> {
                        0
                    }
                }
            }
        val verdict = CameraSizePolicy.evaluate(release, classBytes, runtimeBytes, runtimeArtifacts.get())
        val message =
            """
            Cubentia camera installed-size estimate (bytes)
            Release estimate: $release
            Debug camera classes excluding recorder: $classBytes
            Additional runtime jars and native libraries: $runtimeBytes
            Projected camera size: ${verdict.estimatedBytes}
            Budget: ${AppSizePolicy.BUDGET}
            Forbidden artifacts: ${verdict.forbidden.joinToString().ifEmpty { "none" }}
            """.trimIndent() + "\n"
        report
            .get()
            .asFile
            .apply { parentFile.mkdirs() }
            .writeText(message)
        logger.lifecycle(message)
        if (!verdict.passes) throw GradleException("Camera size or runtime policy failed")
    }

    companion object {
        /** CameraX and its runtime dependencies that are absent from the release estimate. */
        fun additionalCameraComponents(
            debug: ResolvedComponentResult,
            release: ResolvedComponentResult,
        ): Set<ComponentIdentifier> {
            val releaseModules = CheckSdkDenylistTask.coordinates(release).map { it.substringBeforeLast(':') }.toSet()
            val seen = mutableSetOf<ComponentIdentifier>()

            fun collect(component: ResolvedComponentResult) {
                if (!seen.add(component.id)) return
                component.dependencies
                    .filterIsInstance<ResolvedDependencyResult>()
                    .filterNot { it.isConstraint }
                    .forEach { collect(it.selected) }
            }

            debug.dependencies
                .filterIsInstance<ResolvedDependencyResult>()
                .filter { (it.selected.id as? ModuleComponentIdentifier)?.group == "androidx.camera" }
                .forEach { collect(it.selected) }
            return seen
                .filterIsInstance<ModuleComponentIdentifier>()
                .filter { "${it.group}:${it.module}" !in releaseModules }
                .toSet()
        }
    }
}
