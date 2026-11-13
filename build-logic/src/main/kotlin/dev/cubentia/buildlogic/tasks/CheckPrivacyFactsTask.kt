package dev.cubentia.buildlogic.tasks

import dev.cubentia.buildlogic.policy.PrivacyFactsPolicy
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class CheckPrivacyFactsTask : DefaultTask() {
    @get:Input
    abstract val applicationId: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val runtimeCoordinates: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val factsFile: RegularFileProperty

    @get:Input
    abstract val writeTaskPath: Property<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun execute() {
        val expected =
            PrivacyFactsPolicy.derive(applicationId.get(), mergedManifest.get().asFile.readText(), runtimeCoordinates.get()).toJson()
        val file = factsFile.get().asFile
        val committed = if (file.isFile) file.readText() else null
        val message =
            if (committed == expected) {
                "Privacy facts match.\n"
            } else {
                "Privacy facts are stale or missing. Run ${writeTaskPath.get()} and update " +
                    "${PrivacyFactsPolicy.SITE_URL} (repo hebelmx/cubentia-site).\n" +
                    "Expected:\n$expected\nCommitted:\n${committed ?: "File is missing."}"
            }
        report
            .get()
            .asFile
            .apply { parentFile.mkdirs() }
            .writeText(message)
        if (committed != expected) throw GradleException(message)
    }
}
