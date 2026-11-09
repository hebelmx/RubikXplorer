package dev.cubentia.buildlogic.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/** Writes the build-logic-owned R8 keep rules so the release variant can consume them as a file. */
@CacheableTask
abstract class GenerateKeepRulesTask : DefaultTask() {
    @get:Input
    abstract val rules: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        outputFile.get().asFile.writeText(rules.get())
    }

    companion object {
        const val RESOURCE = "/cubentia-keep-rules.pro"

        fun bundledRules(): String =
            requireNotNull(GenerateKeepRulesTask::class.java.getResource(RESOURCE)) { "Missing $RESOURCE in build-logic" }
                .readText()
    }
}
