plugins {
    alias(libs.plugins.rubikxplorer.android.app)
    alias(libs.plugins.rubikxplorer.quality)
    alias(libs.plugins.rubikxplorer.release)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.rubikxplorer.app"

    defaultConfig {
        applicationId = "dev.rubikxplorer"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    // Robolectric Compose tests read the merged resources (fonts, strings, drawables).
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:application"))
    implementation(project(":core:solver"))
    implementation(project(":core:domain"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Compose UI tests on the JVM: Robolectric runs JUnit4 tests under JUnit Platform via vintage.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testRuntimeOnly(libs.junit.vintage.engine)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// OQ-3 spike (Story 1.8), throwaway: compiled only with -Prubikxplorer.spike3d=true. Without the
// property nothing below adds a source, dependency, manifest entry or task to the default build.
// -Prubikxplorer.spike3d.sceneview=false leaves SceneView out, to measure the OpenGL ES APK delta alone.
if (providers.gradleProperty("rubikxplorer.spike3d").orNull == "true") {
    val withSceneView = providers.gradleProperty("rubikxplorer.spike3d.sceneview").orNull != "false"
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
if (providers.gradleProperty("rubikxplorer.spikeScan").orNull == "true") {
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
