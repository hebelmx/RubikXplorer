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
}
