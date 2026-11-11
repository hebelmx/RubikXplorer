plugins {
    alias(libs.plugins.cubentia.android.app)
    alias(libs.plugins.cubentia.quality)
    alias(libs.plugins.cubentia.release)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.kotlin.serialization)
}

room3 { schemaDirectory("$projectDir/schemas") }

val pruningTableAssets = project(":core:solver").layout.buildDirectory.dir("generated/pruningTableAsset")
tasks.named("preBuild") { dependsOn(":core:solver:writePruningTableAsset") }

android {
    namespace = "dev.cubentia.app"

    defaultConfig {
        applicationId = "com.exxerpro.cubentia"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    sourceSets.getByName("test").kotlin.directories += "src/sharedTest/kotlin"
    sourceSets.getByName("androidTest").kotlin.directories += "src/sharedTest/kotlin"

    // Story 3.8 bundled delivery (AD-8, NFR-11): the provider-format pruning table file, generated at build
    // time by :core:solver:writePruningTableAsset, ships as assets/solver/pruning-v1.bin in every variant.
    sourceSets.getByName("main").assets.directories += pruningTableAssets.get().asFile.path

    // Story 3.8 device corpus runner (debug only): the same Story 3.3 corpus code as the JVM harness.
    sourceSets.getByName("debug").kotlin.directories += "../core/solver/src/benchmarkShared/kotlin"

    // Robolectric reads debug assets; Android unit-test source-set assets are not merged by AGP.
    sourceSets.getByName("debug").assets.directories += "schemas"
    sourceSets.getByName("androidTest").assets.directories += "schemas"

    // Robolectric Compose tests read the merged resources (fonts, strings, drawables).
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.sqlite.framework)
    implementation(libs.kotlinx.serialization.json)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.room.testing)
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":core:application"))
    implementation(project(":core:solver"))
    implementation(project(":core:domain"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.window.core)
    implementation(libs.androidx.activity.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)

    // Compose UI tests on the JVM: Robolectric runs JUnit4 tests under JUnit Platform via vintage.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testRuntimeOnly(libs.junit.vintage.engine)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// OQ-3 spike (Story 1.8), throwaway: compiled only with -Pcubentia.spike3d=true. Without the
// property nothing below adds a source, dependency, manifest entry or task to the default build.
// -Pcubentia.spike3d.sceneview=false leaves SceneView out, to measure the OpenGL ES APK delta alone.
if (providers.gradleProperty("cubentia.spike3d").orNull == "true") {
    val withSceneView = providers.gradleProperty("cubentia.spike3d.sceneview").orNull != "false"
    android {
        sourceSets.getByName("main").kotlin.directories +=
            listOf("src/spike3d/kotlin", if (withSceneView) "src/spike3dSceneView/kotlin" else "src/spike3dNoSceneView/kotlin")
        sourceSets.getByName("test").kotlin.directories += "src/spike3dTest/kotlin"
        // The debug/release source sets have no manifest of their own, so the overlay merges into both.
        sourceSets.getByName("debug").manifest.srcFile("src/spike3d/AndroidManifest.xml")
        sourceSets.getByName("release").manifest.srcFile("src/spike3d/AndroidManifest.xml")
    }
    dependencies {
        if (withSceneView) implementation(libs.sceneview)
    }
}

// Story 1.9 guided camera scan prototype. Opt-in debug source and manifest only; release stays clean.
if (providers.gradleProperty("cubentia.spikeScan").orNull == "true") {
    android {
        sourceSets.getByName("debug").kotlin.directories += "src/spikeScan/kotlin"
        sourceSets.getByName("debug").manifest.srcFile("src/spikeScan/AndroidManifest.xml")
    }
    dependencies {
        debugImplementation(libs.androidx.camera.camera2)
        debugImplementation(libs.androidx.camera.lifecycle)
        debugImplementation(libs.androidx.camera.view)
        debugImplementation(libs.kotlinx.serialization.json)
    }
}
