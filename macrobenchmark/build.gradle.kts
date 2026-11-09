plugins {
    alias(libs.plugins.cubentia.android.macrobenchmark)
}

android {
    namespace = "dev.cubentia.macrobenchmark"
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}
