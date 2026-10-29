plugins {
    alias(libs.plugins.rubikxplorer.jvm.core)
}

dependencies {
    implementation(project(":core:application"))
    implementation(project(":core:domain"))
}
