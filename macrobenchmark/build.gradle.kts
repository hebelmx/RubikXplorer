plugins {
    alias(libs.plugins.rubikxplorer.android.macrobenchmark)
}

android {
    namespace = "dev.rubikxplorer.macrobenchmark"
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}
