package dev.rubikxplorer.buildlogic

import com.android.build.api.dsl.TestExtension
import com.android.build.api.variant.TestAndroidComponentsExtension
import dev.rubikxplorer.buildlogic.tasks.CheckModuleBoundariesTask
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * `:macrobenchmark`: an AGP `com.android.test` module that measures the `:app` `benchmark` build
 * type (NFR-2, 2D net frame timing). Only the `benchmark` variant exists, so `assemble` builds the
 * test APK while the benchmark itself runs only on demand through `connectedBenchmarkAndroidTest`.
 */
class AndroidMacrobenchmarkConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("com.android.test")

            extensions.configure<TestExtension> {
                compileSdk = libs.version("compile-sdk").toInt()
                defaultConfig {
                    minSdk = libs.version("min-sdk").toInt()
                    targetSdk = libs.version("target-sdk").toInt()
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.toVersion(jdkVersion)
                    targetCompatibility = JavaVersion.toVersion(jdkVersion)
                }
                buildTypes.create(BENCHMARK) {
                    isDebuggable = true
                    signingConfig = signingConfigs.getByName("debug")
                    matchingFallbacks += "release"
                }
                targetProjectPath = ":app"
                experimentalProperties["android.experimental.self-instrumenting"] = true
            }

            extensions.configure<KotlinAndroidProjectExtension> {
                jvmToolchain(jdkVersion)
            }

            val androidComponents = extensions.getByType<TestAndroidComponentsExtension>()
            androidComponents.beforeVariants { variant ->
                variant.enable = variant.buildType == BENCHMARK
            }

            // AD-1: no project edges; the target app is reached through targetProjectPath only.
            CheckModuleBoundariesTask.register(this)
            Unit
        }

    private companion object {
        const val BENCHMARK = "benchmark"
    }
}
