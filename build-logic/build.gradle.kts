plugins {
    `kotlin-dsl`
}

group = "dev.rubikxplorer.buildlogic"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.jdk.get()))
    }
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.ksp.gradle.plugin)
    compileOnly(libs.hilt.gradle.plugin)
    compileOnly(libs.kover.gradle.plugin)
    compileOnly(libs.detekt.gradle.plugin)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        register("jvmCore") {
            id =
                libs.plugins.rubikxplorer.jvm.core
                    .get()
                    .pluginId
            implementationClass = "dev.rubikxplorer.buildlogic.JvmCoreConventionPlugin"
        }
        register("androidApp") {
            id =
                libs.plugins.rubikxplorer.android.app
                    .get()
                    .pluginId
            implementationClass = "dev.rubikxplorer.buildlogic.AndroidAppConventionPlugin"
        }
        register("quality") {
            id =
                libs.plugins.rubikxplorer.quality
                    .get()
                    .pluginId
            implementationClass = "dev.rubikxplorer.buildlogic.QualityConventionPlugin"
        }
        register("release") {
            id =
                libs.plugins.rubikxplorer.release
                    .get()
                    .pluginId
            implementationClass = "dev.rubikxplorer.buildlogic.ReleaseConventionPlugin"
        }
        register("androidMacrobenchmark") {
            id =
                libs.plugins.rubikxplorer.android.macrobenchmark
                    .get()
                    .pluginId
            implementationClass = "dev.rubikxplorer.buildlogic.AndroidMacrobenchmarkConventionPlugin"
        }
    }
}
