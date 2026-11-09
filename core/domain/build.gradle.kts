plugins {
    alias(libs.plugins.cubentia.jvm.core)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotest.property)
}

// ShippedContentTest scans every Text(...) call in :app and :core:* main sources, and the :app strings.xml files,
// by file path, so those sources must be test inputs; otherwise a change outside this module leaves the test
// UP-TO-DATE / FROM-CACHE.
// (en.json is a main resource of this module and is already on the test runtime classpath.)
tasks.test {
    val repo = rootProject.layout.projectDirectory
    inputs
        .files(
            repo.dir("app/src/main/kotlin").asFileTree.matching { include("**/*.kt") },
            repo.dir("core").asFileTree.matching { include("*/src/main/kotlin/**/*.kt") },
            repo.dir("app/src/main/res").asFileTree.matching { include("values*/strings.xml") },
        ).withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("textScanSources")
}
