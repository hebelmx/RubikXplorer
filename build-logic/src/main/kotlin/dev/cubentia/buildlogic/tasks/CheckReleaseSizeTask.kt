package dev.cubentia.buildlogic.tasks

import dev.cubentia.buildlogic.policy.AppSizePolicy
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.zip.ZipFile

abstract class CheckReleaseSizeTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apk: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bundle: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val apkFile = apk.get().asFile
        var dexBytes = 0L
        val abiCompressedBytes = mutableMapOf<String, Long>()
        ZipFile(apkFile).use { zip ->
            val dex =
                zip
                    .entries()
                    .asSequence()
                    .onEach { entry ->
                        if (entry.name.startsWith("lib/") && entry.name.endsWith(".so")) {
                            val abi = entry.name.removePrefix("lib/").substringBefore('/')
                            val size = entry.compressedSize.takeIf { it >= 0L } ?: entry.size.coerceAtLeast(0L)
                            abiCompressedBytes[abi] = (abiCompressedBytes[abi] ?: 0L) + size
                        }
                    }.filter { it.name.matches(Regex("classes([0-9]+)?\\.dex")) }
                    .toList()
            require(dex.isNotEmpty() && dex.all { it.size >= 0 }) { "Universal APK has no readable dex entries" }
            dexBytes = dex.sumOf { it.size }
        }
        // Play AAB installs one ABI per device; when a universal APK packs multiple ABIs, exclude the extra ABIs.
        val multiAbiExcess =
            if (abiCompressedBytes.size > 1) {
                abiCompressedBytes.values.sum() - (abiCompressedBytes.values.maxOrNull() ?: 0L)
            } else {
                0L
            }
        val singleAbiApkBytes = (apkFile.length() - multiAbiExcess).coerceAtLeast(0L)
        val verdict = AppSizePolicy.evaluate(singleAbiApkBytes, dexBytes, bundle.get().asFile.length())
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(verdict.report)
        }
        logger.lifecycle(verdict.report)
        if (!verdict.withinBudget) throw GradleException("Release installed-size estimate is not below ${AppSizePolicy.BUDGET} bytes")
    }
}
