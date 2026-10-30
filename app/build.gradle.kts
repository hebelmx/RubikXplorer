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
