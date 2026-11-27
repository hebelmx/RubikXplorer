package dev.cubentia.buildlogic

import dev.cubentia.buildlogic.tasks.CheckFixtureSizeTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register
import org.gradle.language.base.plugins.LifecycleBasePlugin

/**
 * Root-project fixture gates (Story 10.34): applies `base` so the root has `check`/`build`, and wires
 * `:checkFixtureSize` (15,000,000 B per continuous session, 60,000,000 B for `Fixtures/continuous/`, no LFS) into `check`.
 */
class FixtureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("base")
            val root = layout.projectDirectory.dir("Fixtures/continuous")
            val sizeCheck =
                tasks.register<CheckFixtureSizeTask>("checkFixtureSize") {
                    group = LifecycleBasePlugin.VERIFICATION_GROUP
                    description = "Fails when a checked-in continuous session or Fixtures/continuous/ is over its size limit."
                    rootPath.set(root.asFile.absolutePath)
                    fixtureFiles.from(root.asFileTree)
                    report.set(layout.buildDirectory.file("reports/cubentia/fixture-size.txt"))
                }
            tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(sizeCheck) }
            Unit
        }
}
