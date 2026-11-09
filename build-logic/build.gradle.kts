import org.gradle.api.attributes.plugin.GradlePluginApiVersion
import org.gradle.util.GradleVersion

plugins {
    `kotlin-dsl`
}

group = "dev.cubentia.buildlogic"

// TestKit needs the Android, Kotlin and detekt plugins to load `cubentia.quality` in a fixture. They
// stay compileOnly for the included build, so this classpath feeds only `pluginUnderTestMetadata`.
val functionalTestPlugins = configurations.dependencyScope("functionalTestPlugins")
val functionalTestPluginsClasspath =
    configurations.resolvable("functionalTestPluginsClasspath") {
        extendsFrom(functionalTestPlugins.get())
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
            attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
            attribute(
                GradlePluginApiVersion.GRADLE_PLUGIN_API_VERSION_ATTRIBUTE,
                objects.named(GradleVersion.current().version),
            )
        }
    }

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

    add(functionalTestPlugins.name, libs.android.gradle.plugin)
    add(functionalTestPlugins.name, libs.kotlin.gradle.plugin)
    add(functionalTestPlugins.name, libs.detekt.gradle.plugin)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.pluginUnderTestMetadata {
    pluginClasspath.from(functionalTestPluginsClasspath)
}

tasks.test {
    useJUnitPlatform()
    systemProperty(
        "cubentia.compileSdk",
        libs.versions.compile.sdk
            .get(),
    )
    systemProperty(
        "cubentia.minSdk",
        libs.versions.min.sdk
            .get(),
    )
    systemProperty(
        "cubentia.targetSdk",
        libs.versions.target.sdk
            .get(),
    )
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
