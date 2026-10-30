plugins {
    alias(libs.plugins.rubikxplorer.jvm.core)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotest.property)
}
