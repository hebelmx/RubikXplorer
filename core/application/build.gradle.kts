plugins {
    alias(libs.plugins.rubikxplorer.jvm.core)
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.kotlinx.coroutines.core)
}
