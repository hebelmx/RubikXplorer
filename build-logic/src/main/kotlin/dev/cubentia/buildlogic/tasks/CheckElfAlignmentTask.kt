package dev.cubentia.buildlogic.tasks

import dev.cubentia.buildlogic.policy.ElfAlignmentPolicy
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

/** Verifies every 64-bit native `.so` on a variant's classpath/APK meets 16 KB ELF `PT_LOAD` page alignment (`targetSdk 36`). */
abstract class CheckElfAlignmentTask : DefaultTask() {
    @get:Input
    abstract val variantName: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val archives: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val files = archives.files.sortedBy { it.name }
        val checks = files.flatMap { file -> ElfAlignmentPolicy.checkArchive(file) }
        val failures = checks.filterNot { it.passes }
        val uninspected = ElfAlignmentPolicy.uninspectedLiteRt(files, checks)
        val summary =
            buildString {
                appendLine("Cubentia 16 KB ELF alignment check (${variantName.get()})")
                appendLine("Checked 64-bit native libraries: ${checks.size}")
                checks.forEach { entry ->
                    val alignText = entry.minLoadAlign?.let { "0x${it.toString(16)}" } ?: "n/a"
                    val status = if (entry.passes) "PASS" else "FAIL (${entry.error})"
                    appendLine("  ${entry.source} [${entry.abi}/${entry.library}] min_p_align=$alignText -> $status")
                }
                if (uninspected.isNotEmpty()) appendLine("FAIL: no 64-bit libLiteRt*.so inspected in ${uninspected.joinToString()}")
            }
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(summary)
        }
        if (uninspected.isNotEmpty()) {
            throw GradleException(
                "16 KB ELF alignment check failed for ${variantName.get()}: LiteRT is on the classpath " +
                    "(${uninspected.joinToString()}) but no 64-bit libLiteRt*.so was inspected",
            )
        }
        if (failures.isNotEmpty()) {
            val details = failures.joinToString("\n") { " - ${it.source} [${it.abi}/${it.library}]: ${it.error}" }
            throw GradleException("16 KB ELF alignment check failed for ${variantName.get()}:\n$details")
        }
    }
}
