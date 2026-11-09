plugins {
    `kotlin-dsl`
}

group = "dev.cubentia.buildlogic"

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
                libs.plugins.cubentia.jvm.core
                    .get()
                    .pluginId
            implementationClass = "dev.cubentia.buildlogic.JvmCoreConventionPlugin"
        }
        register("androidApp") {
            id =
                libs.plugins.cubentia.android.app
                    .get()
                    .pluginId
            implementationClass = "dev.cubentia.buildlogic.AndroidAppConventionPlugin"
        }
        register("quality") {
            id =
                libs.plugins.cubentia.quality
                    .get()
                    .pluginId
            implementationClass = "dev.cubentia.buildlogic.QualityConventionPlugin"
        }
        register("release") {
            id =
                libs.plugins.cubentia.release
                    .get()
                    .pluginId
            implementationClass = "dev.cubentia.buildlogic.ReleaseConventionPlugin"
        }
        register("androidMacrobenchmark") {
            id =
                libs.plugins.cubentia.android.macrobenchmark
                    .get()
                    .pluginId
            implementationClass = "dev.cubentia.buildlogic.AndroidMacrobenchmarkConventionPlugin"
        }
    }
}
