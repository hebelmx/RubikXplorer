package dev.cubentia.buildlogic.tasks

import dev.cubentia.buildlogic.policy.PrivacyFactsPolicy
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class WritePrivacyFactsTask : DefaultTask() {
    @get:Input
    abstract val applicationId: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val runtimeCoordinates: ListProperty<String>

    @get:OutputFile
    abstract val factsFile: RegularFileProperty

    @TaskAction
    fun execute() {
        val expected =
            PrivacyFactsPolicy.derive(applicationId.get(), mergedManifest.get().asFile.readText(), runtimeCoordinates.get()).toJson()
        factsFile
            .get()
            .asFile
            .apply { parentFile.mkdirs() }
            .writeText(expected)
    }
}
