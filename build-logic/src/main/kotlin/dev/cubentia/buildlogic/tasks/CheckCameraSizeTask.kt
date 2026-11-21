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

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val cameraAssets: ConfigurableFileCollection

    @get:Input
    abstract val runtimeArtifacts: ListProperty<String>

    @get:Input
    abstract val allowLiteRt: org.gradle.api.provider.Property<Boolean>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val release =
            releaseReport.get().asFile.useLines { lines ->
                lines.single { it.startsWith("Installed estimate ") }.substringAfterLast(": ").toLong()
            }
        val classBytes = cameraClasses.sumOf { it.length() }
        val assetBytes = cameraAssets.files.filter { it.isFile }.sumOf { it.length() }
        val runtimeBytes = cameraRuntime.sumOf(::artifactRuntimeBytes)
        val verdict =
            CameraSizePolicy.evaluate(
                releaseEstimate = release,
                classBytes = classBytes,
                runtimeBytes = runtimeBytes,
                artifacts = runtimeArtifacts.get(),
                allowLiteRt = allowLiteRt.getOrElse(false),
                assetBytes = assetBytes,
            )
        val message =
            """
            Cubentia camera installed-size estimate (bytes)
            Release estimate: $release
            Debug camera classes excluding recorder: $classBytes
            Additional runtime jars and single-ABI native libraries: $runtimeBytes
            Camera model/runtime assets: $assetBytes
            Projected camera size: ${verdict.estimatedBytes}
            Budget: ${AppSizePolicy.BUDGET}
            LiteRT allowed: ${allowLiteRt.getOrElse(false)}
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
        private val cameraRuntimeGroups = setOf("androidx.camera", "com.google.ai.edge.litert")

        /**
         * R8 retention factor for unminified third-party debug `.jar` bytes (CameraX, Guava, LiteRT API) on
         * `debugRuntimeClasspath` (SCP 2026-11-20 §4.4), pinned to a measured minified build. Native `.so` libraries
         * (single Play ABI `arm64-v8a`) and `.tflite` assets are always counted at 100% uncompressed size.
         *
         * Measured 2026-11-21 (Story 10.18, after the ai-delivery exclusion; docs/evidence/10-18/litert-model-evaluation.md):
         * `cornerR8` `classes.dex` 8,832,716 B minus `release` `classes.dex` 2,977,180 B = 5,855,536 B of camera dex;
         * minus the 783,918 B of camera classes counted at 100% leaves 5,071,618 B retained from ~10,002,505 B of camera
         * jars = 50.7%. That is an upper bound (`cornerR8` also keeps all of `dev.cubentia.**` and the Kotlin runtime for
         * its test APK); 55% rounds it up. `CheckCameraSizeTaskTest` pins the factor at or above the measurement.
         */
        internal const val UNMINIFIED_JAR_R8_NUMERATOR = 55L
        internal const val UNMINIFIED_JAR_R8_DENOMINATOR = 100L

        /**
         * Measures one artifact's installed runtime cost: R8-estimated `.jar` bytecode plus the Play-delivered
         * `arm64-v8a` native `.so` directory under `jni/` (falling back to the largest ABI when `arm64-v8a` is
         * absent), matching single-ABI Play AAB delivery rather than summing all ABIs or emulator `x86_64`.
         */
        internal fun artifactRuntimeBytes(file: java.io.File): Long =
            when (file.extension) {
                "aar" -> {
                    ZipFile(file).use { zip ->
                        var jarBytes = 0L
                        val abiBytes = mutableMapOf<String, Long>()
                        zip.entries().asSequence().forEach { entry ->
                            val size = entry.size.coerceAtLeast(0L)
                            when {
                                entry.name.endsWith(".jar") -> {
                                    jarBytes += size
                                }

                                entry.name.endsWith(".so") -> {
                                    val abi = entry.name.removePrefix("jni/").substringBefore('/', missingDelimiterValue = "default")
                                    abiBytes[abi] = (abiBytes[abi] ?: 0L) + size
                                }
                            }
                        }
                        val nativeBytes = abiBytes["arm64-v8a"] ?: abiBytes.values.maxOrNull() ?: 0L
                        (jarBytes * UNMINIFIED_JAR_R8_NUMERATOR / UNMINIFIED_JAR_R8_DENOMINATOR) + nativeBytes
                    }
                }

                "jar" -> {
                    file.length() * UNMINIFIED_JAR_R8_NUMERATOR / UNMINIFIED_JAR_R8_DENOMINATOR
                }

                "so" -> {
                    file.length()
                }

                else -> {
                    0L
                }
            }

        /** CameraX / LiteRT and their runtime dependencies that are absent from the release estimate. */
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
                .filter { (it.selected.id as? ModuleComponentIdentifier)?.group in cameraRuntimeGroups }
                .forEach { collect(it.selected) }
            return seen
                .filterIsInstance<ModuleComponentIdentifier>()
                .filter { "${it.group}:${it.module}" !in releaseModules }
                .toSet()
        }
    }
}
