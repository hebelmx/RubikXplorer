package dev.rubikxplorer.buildlogic

import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.language.base.plugins.LifecycleBasePlugin

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String = findVersion(alias).get().requiredVersion

internal fun VersionCatalog.library(alias: String) = findLibrary(alias).get()

internal val Project.jdkVersion: Int
    get() = libs.version("jdk").toInt()

/** JUnit Jupiter on the JUnit Platform for the given test configuration prefix (`test`). */
internal fun Project.addJUnitDependencies() {
    dependencies {
        "testImplementation"(platform(libs.library("junit-bom")))
        "testImplementation"(libs.library("junit-jupiter"))
        "testRuntimeOnly"(libs.library("junit-platform-launcher"))
    }
}

/**
 * Applies detekt as an advisory tool (AD-14): it never fails a run and is never part of `check`,
 * so `./gradlew build` cannot depend on it until detekt has a stable release for Kotlin 2.4.
 */
internal fun Project.applyAdvisoryDetekt() {
    pluginManager.apply("dev.detekt")
    extensions.configure<DetektExtension> {
        buildUponDefaultConfig.set(true)
        ignoreFailures.set(true)
        parallel.set(true)
    }
    // The detekt plugin adds `check.dependsOn(detekt)`; undo it. Registered after the plugin's own
    // action, so it runs after it.
    tasks.matching { it.name == LifecycleBasePlugin.CHECK_TASK_NAME }.configureEach {
        setDependsOn(
            dependsOn.filterNot { dependency ->
                val name =
                    when (dependency) {
                        is TaskProvider<*> -> dependency.name
                        is org.gradle.api.Task -> dependency.name
                        is String -> dependency
                        else -> null
                    }
                name != null && name.startsWith("detekt")
            },
        )
    }
}
