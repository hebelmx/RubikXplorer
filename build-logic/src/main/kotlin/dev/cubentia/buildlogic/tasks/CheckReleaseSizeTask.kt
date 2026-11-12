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
        val dexBytes =
            ZipFile(apkFile).use { zip ->
                val dex =
                    zip
                        .entries()
                        .asSequence()
                        .filter { it.name.matches(Regex("classes([0-9]+)?\\.dex")) }
                        .toList()
                require(dex.isNotEmpty() && dex.all { it.size >= 0 }) { "Universal APK has no readable dex entries" }
                dex.sumOf { it.size }
            }
        val verdict = AppSizePolicy.evaluate(apkFile.length(), dexBytes, bundle.get().asFile.length())
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(verdict.report)
        }
        logger.lifecycle(verdict.report)
        if (!verdict.withinBudget) throw GradleException("Release installed-size estimate exceeds ${AppSizePolicy.BUDGET} bytes")
    }
}
