package dev.cubentia.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import dev.cubentia.buildlogic.policy.AccessibilityLint
import dev.cubentia.buildlogic.policy.ConnectedTestResults
import dev.cubentia.buildlogic.tasks.CheckLearnerTextTask
import dev.cubentia.buildlogic.tasks.CheckModuleBoundariesTask
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/** The `:app` shell: AGP 9 application with built-in Kotlin (no kotlin-android, no kapt). */
class AndroidAppConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("com.android.application")

            extensions.configure<ApplicationExtension> {
                compileSdk = libs.version("compile-sdk").toInt()
                defaultConfig {
                    minSdk = libs.version("min-sdk").toInt()
                    targetSdk = libs.version("target-sdk").toInt()
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.toVersion(jdkVersion)
                    targetCompatibility = JavaVersion.toVersion(jdkVersion)
                }
                buildFeatures {
                    buildConfig = true
                }
                lint {
                    abortOnError = true
                    error += AccessibilityLint.ERROR_ISSUES
                }
                testOptions {
                    unitTests.all { it.useJUnitPlatform() }
                }
            }

            extensions.configure<KotlinAndroidProjectExtension> {
                jvmToolchain(jdkVersion)
            }

            addJUnitDependencies()

            // AD-1 edges for :app (Android dependencies are allowed here).
            CheckModuleBoundariesTask.register(this)
            // NFR-10: no Learner-facing literals in :app main sources.
            CheckLearnerTextTask.register(this)
            // AGP exits a connected test task with success when the instrumentation process crashes before any test
            // runs (its JUnit XML holds only a synthetic testRunFailed failure); fail on any failure in the XML.
            tasks.matching { CONNECTED_TEST.matches(it.name) }.configureEach {
                val variant = CONNECTED_TEST.matchEntire(name)!!.groupValues[1].replaceFirstChar { it.lowercase() }
                val results = layout.buildDirectory.dir("outputs/androidTest-results/connected/$variant")
                doLast {
                    val failures = ConnectedTestResults.failuresIn(results.get().asFile)
                    if (failures.isNotEmpty()) {
                        throw GradleException("Connected tests failed:\n" + failures.joinToString("\n"))
                    }
                }
            }
            Unit
        }

    private companion object {
        /** `connectedDebugAndroidTest`, `connectedCornerR8AndroidTest`; not the `connectedAndroidTest` lifecycle task. */
        val CONNECTED_TEST = Regex("connected(\\w+)AndroidTest")
    }
}
