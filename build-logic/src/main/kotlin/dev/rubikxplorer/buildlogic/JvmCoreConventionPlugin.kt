package dev.rubikxplorer.buildlogic

import dev.rubikxplorer.buildlogic.tasks.CheckLearnerTextTask
import dev.rubikxplorer.buildlogic.tasks.CheckModuleBoundariesTask
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * `:core:*` modules: pure Kotlin/JVM, JUnit Platform, Kover ≥ 80% line coverage, AD-1 boundary check
 * and the Learner-text literal gate.
 */
class JvmCoreConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            pluginManager.apply("org.jetbrains.kotlinx.kover")

            extensions.configure<KotlinJvmProjectExtension> {
                jvmToolchain(jdkVersion)
            }

            addJUnitDependencies()
            tasks.withType<Test>().configureEach { useJUnitPlatform() }

            extensions.configure<KoverProjectExtension> {
                // On-demand benchmark harnesses (Story 1.10) never ship, so they stay out of the gate.
                currentProject {
                    sources { excludedSourceSets.add(BENCHMARK_SOURCE_SET) }
                }
                reports {
                    verify {
                        rule {
                            minBound(MIN_LINE_COVERAGE_PERCENT)
                        }
                    }
                }
            }

            if (path == CubeCoverageGate.MODULE_PATH) applyCubeCoverageGate()

            val boundaryCheck = CheckModuleBoundariesTask.register(this)
            CheckLearnerTextTask.register(this)
            tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) {
                dependsOn("koverVerify")
            }
            // Report the boundary violation before compilation trips over an Android artifact.
            tasks.withType<KotlinCompile>().configureEach { dependsOn(boundaryCheck) }

            applyAdvisoryDetekt()
        }

    private companion object {
        const val MIN_LINE_COVERAGE_PERCENT = 80
        const val BENCHMARK_SOURCE_SET = "benchmark"
    }
}
