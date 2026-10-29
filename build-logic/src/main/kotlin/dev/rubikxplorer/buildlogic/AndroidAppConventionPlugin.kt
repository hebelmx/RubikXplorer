package dev.rubikxplorer.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import dev.rubikxplorer.buildlogic.tasks.CheckModuleBoundariesTask
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
            Unit
        }
}
