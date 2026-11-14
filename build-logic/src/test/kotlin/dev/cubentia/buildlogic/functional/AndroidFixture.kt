package dev.cubentia.buildlogic.functional

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.util.Properties
import java.util.zip.ZipOutputStream

/**
 * A throwaway Android application project for Gradle TestKit tests of the convention plugins. It
 * applies the real `com.android.application` plugin plus [plugins], and runs real tasks.
 *
 * Each [name] gets its own directory under `build/`, wiped on creation. A missing Android SDK
 * throws, so a TestKit test fails loudly instead of skipping.
 */
internal class AndroidFixture(
    name: String,
    permissions: List<String> = emptyList(),
    buildTypes: String = "",
    plugins: List<String> = listOf("cubentia.quality"),
    defaultConfig: String = "",
) {
    val projectDir: File = File("build/functional-fixtures/$name").absoluteFile

    init {
        projectDir.deleteRecursively()
        projectDir.mkdirs()
        write("settings.gradle.kts", settingsScript(name))
        write(
            "gradle/libs.versions.toml",
            listOf("camera2", "lifecycle", "view").joinToString("\n", "[libraries]\n") {
                "androidx-camera-$it = { module = \"androidx.camera:camera-$it\", version = \"1.0\" }"
            },
        )
        listOf("camera2", "lifecycle", "view").forEach { publishModule("androidx.camera", "camera-$it") }
        write("gradle.properties", GRADLE_PROPERTIES)
        write("build.gradle.kts", buildScript(plugins, buildTypes, defaultConfig))
        write("src/main/AndroidManifest.xml", manifest(permissions))
        write(
            "src/camera/AndroidManifest.xml",
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
<uses-permission android:name="android.permission.CAMERA"/>
<uses-feature android:name="android.hardware.camera.any" android:required="false"/>
</manifest>""",
        )
        projectDir.resolve("local.properties").outputStream().use { output ->
            Properties().apply { setProperty("sdk.dir", androidSdkDir().path) }.store(output, null)
        }
    }

    /** Writes [content] to [path], relative to the fixture root, creating parent directories. */
    fun write(
        path: String,
        content: String,
    ) {
        projectDir.resolve(path).apply { parentFile.mkdirs() }.writeText(content)
    }

    fun publishModule(
        group: String,
        module: String,
    ) {
        val dir = "local-repo/${group.replace('.', '/')}/$module/1.0"
        write(
            "$dir/$module-1.0.pom",
            """<project><modelVersion>4.0.0</modelVersion>
<groupId>$group</groupId><artifactId>$module</artifactId><version>1.0</version></project>""",
        )
        ZipOutputStream(projectDir.resolve("$dir/$module-1.0.jar").outputStream()).use { it.finish() }
    }

    fun build(vararg tasks: String): BuildResult = runner(*tasks).build()

    fun buildAndFail(vararg tasks: String): BuildResult = runner(*tasks).buildAndFail()

    private fun runner(vararg tasks: String): GradleRunner =
        GradleRunner
            .create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*tasks, "--stacktrace")

    private companion object {
        const val GRADLE_PROPERTIES = "org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8\nandroid.useAndroidX=true\n"

        /** `ANDROID_HOME`, else `sdk.dir` from the repository's `local.properties`; tests run from `build-logic/`. */
        fun androidSdkDir(): File {
            val path =
                System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }
                    ?: File("../local.properties")
                        .takeIf { it.isFile }
                        ?.inputStream()
                        ?.use { input -> Properties().apply { load(input) } }
                        ?.getProperty("sdk.dir")
                    ?: error("No Android SDK: set ANDROID_HOME or sdk.dir in local.properties.")
            return File(path).also { check(it.isDirectory) { "Android SDK directory does not exist: $it" } }
        }

        fun requiredSystemProperty(name: String): String =
            checkNotNull(System.getProperty(name)) { "System property $name is not set; run through Gradle." }

        fun settingsScript(name: String) =
            """
            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }

            dependencyResolutionManagement {
                repositories {
                    maven { url = uri("local-repo") }
                    google()
                    mavenCentral()
                }
            }

            rootProject.name = "$name"
            """.trimIndent()

        fun buildScript(
            plugins: List<String>,
            buildTypes: String,
            defaultConfig: String,
        ): String =
            buildString {
                appendLine("plugins {")
                appendLine("""    id("com.android.application")""")
                plugins.forEach { appendLine("""    id("$it")""") }
                appendLine("}")
                appendLine()
                appendLine("android {")
                appendLine("""    namespace = "dev.cubentia.fixture"""")
                appendLine("    compileSdk = ${requiredSystemProperty("cubentia.compileSdk")}")
                appendLine("    defaultConfig {")
                defaultConfig.lines().filter { it.isNotBlank() }.forEach { appendLine("        ${it.trim()}") }
                appendLine("        minSdk = ${requiredSystemProperty("cubentia.minSdk")}")
                appendLine("        targetSdk = ${requiredSystemProperty("cubentia.targetSdk")}")
                appendLine("    }")
                appendLine("    buildFeatures {")
                appendLine("        buildConfig = true")
                appendLine("    }")
                appendLine("    buildTypes {")
                buildTypes.lines().filter { it.isNotBlank() }.forEach { appendLine("        ${it.trim()}") }
                appendLine("    }")
                appendLine("}")
            }

        fun manifest(permissions: List<String>): String =
            buildString {
                appendLine("""<manifest xmlns:android="http://schemas.android.com/apk/res/android">""")
                permissions.forEach { appendLine("""    <uses-permission android:name="$it" />""") }
                appendLine("""    <application android:allowBackup="false" />""")
                appendLine("</manifest>")
            }
    }
}
