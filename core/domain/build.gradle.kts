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

// Offline evaluation tooling never enters the shipped main jar or Kover denominator.
val evaluation: SourceSet by sourceSets.creating
evaluation.compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
evaluation.runtimeClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
dependencies { testImplementation(evaluation.output) }
tasks.named("assemble") { dependsOn(evaluation.classesTaskName) }
tasks.register<JavaExec>("evaluateLiveBaseline") {
    group = "verification"
    description = "Scores recorded live fixtures and updates the live baseline section."
    classpath = evaluation.runtimeClasspath
    mainClass.set("dev.cubentia.domain.recognition.evaluation.LiveBaselineMainKt")
    args(rootProject.file("Fixtures/live").path, rootProject.file("docs/spikes/oq-4-guided-scan.md").path)
}

// Story 10.1: the decision K pipeline (all-Kotlin reader, self-calibration, assembly) on the live fixtures. On demand only.
tasks.register<JavaExec>("evaluateScanAccuracy") {
    group = "verification"
    description = "Scores the Kotlin scan pipeline on the live fixtures and updates docs/scan-accuracy.md."
    classpath = evaluation.runtimeClasspath
    mainClass.set("dev.cubentia.domain.recognition.evaluation.scan.ScanAccuracyMainKt")
    args(rootProject.file("Fixtures/live").path, rootProject.file("docs/scan-accuracy.md").path)
}

// Story 10.13 (OQ-7): OpenCV runs only in this on-demand spike source set. Nothing wires it into assemble, build,
// check or test, so the ~110 MB JVM OpenCV jar is never downloaded or compiled by CI.
val recognitionSpike: SourceSet by sourceSets.creating
recognitionSpike.compileClasspath += evaluation.output + evaluation.compileClasspath
recognitionSpike.runtimeClasspath += evaluation.output + evaluation.runtimeClasspath
dependencies { "recognitionSpikeImplementation"(libs.opencv.openpnp) }
kover { currentProject { sources { excludedSourceSets.add(recognitionSpike.name) } } }

// The Android runtimes OQ-7 would ship, resolved only to measure their size (never put on a classpath).
val recognitionRuntimeAars: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies {
    recognitionRuntimeAars(variantOf(libs.opencv.android) { artifactType("aar") })
    recognitionRuntimeAars(variantOf(libs.litert) { artifactType("aar") })
}
tasks.register<JavaExec>("evaluateRecognitionApproaches") {
    group = "verification"
    description = "Compares the OQ-7 recognition approaches on the live fixtures and updates the OQ-7 spike results."
    classpath = recognitionSpike.runtimeClasspath
    mainClass.set("dev.cubentia.domain.recognition.evaluation.oq7.RecognitionApproachMainKt")
    val aars = recognitionRuntimeAars
    inputs.files(aars)
    argumentProviders.add {
        val files = aars.files.sortedBy { it.name }

        fun aar(prefix: String) = files.singleOrNull { it.name.startsWith(prefix) }?.path ?: error("Missing $prefix AAR in $files")
        listOf(
            rootProject.file("Fixtures/live").path,
            rootProject.file("docs/spikes/oq-7-recognition-approach.md").path,
            aar("opencv-"),
            aar("litert-"),
        )
    }
}

tasks.register<JavaExec>("evaluateQuickScan") {
    group = "verification"
    description = "Tallies device-session Quick Scan runs from a CubentiaScanDiag log."
    classpath = evaluation.runtimeClasspath
    mainClass.set("dev.cubentia.domain.recognition.evaluation.quickscan.QuickScanMeasurementKt")
    val report = layout.buildDirectory.file("reports/quick-scan-measurement.md")
    argumentProviders.add { listOf(report.get().asFile.absolutePath) }
}

tasks.register<JavaExec>("evaluateMatrix") {
    group = "verification"
    description = "Scores recognition matrix runs and updates the OQ-8 report."
    classpath = evaluation.runtimeClasspath
    mainClass.set("dev.cubentia.domain.recognition.evaluation.matrix.MatrixMainKt")
    workingDir = rootDir
    args("Fixtures/matrix", "Fixtures/matrix/truth.csv", "docs/spikes/oq-8-recognition-matrix.md")
}
