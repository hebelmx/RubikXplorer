plugins {
    alias(libs.plugins.cubentia.jvm.core)
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.kotlinx.coroutines.core)
}
